package com.phonetransfer.app.softap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.phonetransfer.app.R
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.protocol.ProtocolConstants
import com.phonetransfer.app.core.transport.*
import com.phonetransfer.app.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import android.provider.DocumentsContract
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Old phone hosts SoftAP/TCP but runs protocol SENDER; new phone joins and runs RECEIVER. */
class SoftApBackupService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val sasGate = CountDownLatch(1)
    @Volatile private var sasAccepted = false
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false
    private val deadline = Runnable { finish(false, getString(R.string.backup_timeout)) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CANCEL -> { finish(false, getString(R.string.backup_cancelled)); return START_NOT_STICKY }
            CONFIRM -> {
                sasAccepted = intent.getBooleanExtra("agreed", false)
                sasGate.countDown()
                return START_NOT_STICKY
            }
        }
        if (started || intent == null) { if (!started) stopSelf(); return START_NOT_STICKY }
        started = true
        startForeground(NOTIFICATION, notification(getString(R.string.backup_preparing)))
        BackupState.update { BackupSnapshot(active = true, phase = "preparing", status = getString(R.string.backup_preparing)) }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneTransfer:backup").also { it.acquire(30 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneTransfer:backup").also { it.acquire() }
        main.postDelayed(deadline, 30 * 60 * 1000L)
        if (intent.getBooleanExtra("sender", false)) {
            @Suppress("DEPRECATION")
            val uris = intent.getStringArrayListExtra("files").orEmpty().map { Uri.parse(it) }
            val code = (0..5).joinToString("") { SecureRandom().nextInt(10).toString() }
            if (intent.getBooleanExtra("manual", false)) {
                BackupState.update { it.copy(credentials = getString(R.string.backup_manual_credentials, code)) }
                serve(uris, code)
            } else startHotspot(uris, code)
        } else {
            val tree = intent.getStringExtra("tree")?.let(Uri::parse)
            val code = intent.getStringExtra("code").orEmpty()
            val host = intent.getStringExtra("host").orEmpty()
            if (tree == null || !code.matches(Regex("[0-9]{6}"))) {
                finish(false, getString(R.string.backup_invalid_input))
            } else receive(tree, code, host)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun startHotspot(files: List<Uri>, code: String) {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    if (cancelled.get()) { value.close(); return }
                    reservation = value
                    val ssid: String
                    val password: String
                    if (Build.VERSION.SDK_INT >= 30) {
                        ssid = value.softApConfiguration.ssid.orEmpty()
                        password = value.softApConfiguration.passphrase.orEmpty()
                    } else {
                        ssid = value.wifiConfiguration?.SSID.orEmpty()
                        password = value.wifiConfiguration?.preSharedKey.orEmpty()
                    }
                    BackupState.update { it.copy(credentials = getString(R.string.backup_credentials, ssid, password, code)) }
                    serve(files, code)
                }
                override fun onFailed(reason: Int) {
                    finish(false, getString(R.string.backup_hotspot_failed, reason))
                }
                override fun onStopped() {
                    if (!cancelled.get()) finish(false, getString(R.string.backup_hotspot_stopped))
                }
            }, main)
        } catch (e: Exception) { finish(false, getString(R.string.backup_hotspot_exception, e.message.orEmpty())) }
    }

    private fun serve(files: List<Uri>, code: String) = worker.execute {
        try {
            // 先监听再算摘要：新机的 TCP 连接可以立刻完成握手进入 backlog，
            // 不会在旧机还在读取大文件时被「连接拒绝」打断。
            val listener = ServerSocket()
            server = listener
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(ProtocolConstants.DEFAULT_PORT))
            listener.soTimeout = 300000
            val docs = BackupDocuments(applicationContext) { cancelled.get() }
            val payloads = docs.prepare(files) { n ->
                status(getString(R.string.backup_hashing, n, files.size))
            }
            check(!cancelled.get())
            val addresses = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { iface ->
                Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
            }.distinct().joinToString(" / ")
            BackupState.update { it.copy(phase = "waiting", status = getString(R.string.backup_waiting, addresses)) }
            val peer = listener.accept()
            socket = peer
            listener.close()
            peer.use { runSession(it, SessionRole.SENDER, code, payloads, null) }
            finish(true, getString(R.string.backup_success))
        } catch (e: Exception) { if (!cancelled.get()) finish(false, e.message ?: e.javaClass.simpleName) }
    }

    private fun receive(tree: Uri, code: String, typedHost: String) = worker.execute {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            // Do not require INTERNET/VALIDATED: a local-only hotspot has neither.
            val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                ?: error(getString(R.string.backup_need_wifi))
            val host = typedHost.ifBlank {
                cm.getLinkProperties(wifi)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway?.hostAddress ?: error(getString(R.string.backup_need_address))
            }
            require(host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) { getString(R.string.backup_need_address) }
            require(host.split('.').all { it.toInt() in 0..255 })
            status(getString(R.string.backup_connecting))
            val peer = wifi.socketFactory.createSocket()
            socket = peer
            peer.connect(InetSocketAddress(host, ProtocolConstants.DEFAULT_PORT), 30000)
            peer.use { runSession(it, SessionRole.RECEIVER, code, emptyList(), tree) }
            finish(true, getString(R.string.backup_success))
        } catch (e: Exception) { if (!cancelled.get()) finish(false, e.message ?: e.javaClass.simpleName) }
    }

    private fun runSession(peer: Socket, role: SessionRole, code: String, payloads: List<PtPayload>, tree: Uri?) {
        peer.soTimeout = 30 * 60 * 1000
        val docs = BackupDocuments(applicationContext) { cancelled.get() }
        var directory: Uri? = null
        val session = PtSession(role = role, pairingCode = code, payloads = payloads,
            wantedTypes = ItemType.entries.toSet(), deviceModel = Build.MANUFACTURER + " " + Build.MODEL,
            listener = object : PtSessionListener {
                override fun onSas(sas: Int) {
                    BackupState.update { it.copy(phase = "sas", sas = sas, status = getString(R.string.backup_compare_sas)) }
                }
                override fun onManifest(entries: List<PtManifestEntry>) {
                    if (tree != null) directory = docs.createSessionDirectory(tree, "PhoneTransfer-" + System.currentTimeMillis())
                }
                override fun onProgress(itemsDone: Int, itemCount: Int, bytesDone: Long, bytesTotal: Long) {
                    BackupState.update { it.copy(phase = "transfer", sas = null, done = bytesDone, total = bytesTotal,
                        itemsDone = itemsDone, itemCount = itemCount, status = getString(R.string.backup_transferring)) }
                }
                override fun onResult(results: List<PtItemResult>) {
                    val entries = JSONArray()
                    results.forEach { r -> entries.put(JSONObject().put("name", r.name).put("bytes", r.byteCount)
                        .put("sha256", r.sha256.joinToString("") { "%02x".format(it.toInt() and 255) })
                        .put("verified", r.verified).put("uri", r.savedLocation)) }
                    val report = JSONObject().put("transport", "SoftAP+TCP").put("protocol", 1)
                        .put("files", entries).put("completedAt", System.currentTimeMillis()).toString(2)
                    if (tree != null && directory != null) {
                        val uri = DocumentsContract.createDocument(contentResolver, directory!!, "application/json", "backup-report.json")
                            ?: error("Cannot create backup report")
                        (contentResolver.openOutputStream(uri, "w") ?: error("Cannot write backup report")).use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    }
                    BackupState.update { it.copy(report = results.joinToString("\n") { r ->
                        r.name + " · " + r.byteCount + " B · SHA-256 ✓" +
                            if (r.savedLocation.isNotEmpty()) "\n" + r.savedLocation else ""
                    }) }
                }
            }, sasConfirmer = {
                sasGate.await(120, TimeUnit.SECONDS) && sasAccepted && !cancelled.get()
            }, receiveTarget = if (tree == null) null else { entry ->
                docs.target(tree, directory ?: error("Missing backup directory"), entry)
            }, requireReceiveTarget = role == SessionRole.RECEIVER)
        session.run(peer)
    }

    private fun status(text: String) {
        if (!cancelled.get()) BackupState.update { it.copy(status = text) }
    }

    private fun finish(ok: Boolean, text: String) {
        if (!cancelled.compareAndSet(false, true)) return
        sasGate.countDown()
        runCatching { socket?.close() }
        runCatching { server?.close() }
        main.post {
            release()
            BackupState.update { it.copy(active = false, phase = if (ok) "done" else "error", sas = null,
                credentials = "", status = text) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun release() {
        main.removeCallbacks(deadline)
        runCatching { reservation?.close() }
        reservation = null
        runCatching { if (wake?.isHeld == true) wake?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    override fun onDestroy() {
        cancelled.set(true)
        sasGate.countDown()
        runCatching { socket?.close() }
        runCatching { server?.close() }
        release()
        worker.shutdownNow()
        if (BackupState.snapshot.active) BackupState.update { it.copy(active = false, phase = "error", credentials = "", sas = null,
            status = getString(R.string.backup_interrupted)) }
        super.onDestroy()
    }

    private fun notification(text: String): android.app.Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.backup_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, SoftApBackupService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tab_transfer)
            .setContentTitle(getString(R.string.backup_title)).setContentText(text).setOngoing(true)
            .setContentIntent(open).addAction(0, getString(R.string.backup_cancel), cancel).build()
    }

    companion object {
        const val START = "com.phonetransfer.BACKUP_START"
        const val CANCEL = "com.phonetransfer.BACKUP_CANCEL"
        const val CONFIRM = "com.phonetransfer.BACKUP_CONFIRM"
        private const val CHANNEL = "softap-backup"
        private const val NOTIFICATION = 45717
    }
}