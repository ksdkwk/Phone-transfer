package com.phonetransfer.app.p2p

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.transport.PtItemResult
import com.phonetransfer.app.core.transport.PtPayload
import com.phonetransfer.app.core.transport.PtSession
import com.phonetransfer.app.core.transport.PtSessionListener
import com.phonetransfer.app.core.transport.SessionRole
import com.phonetransfer.app.ui.transfer.flow.TransferFlowSession
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 需要界面展示的 P2P 事件（均回调在主线程）。 */
interface P2pEvents {
    fun onStatus(text: String)
    fun onPeers(devices: List<WifiP2pDevice>)
    fun onSas(sas: Int)
    fun onFinished(ok: Boolean, message: String)
}

/**
 * Wi-Fi Direct 建链 + 真实协议会话的编排。
 *
 * 组网：新机（接收端）createGroup 成为 Group Owner，旧机（发送端）发现并 connect 加入；
 * 之后由 Group Owner 监听 45717，对端连上来，在 TCP 上跑 [PtSession]（真实 ECDH/HKDF/AES-GCM + 分块校验）。
 *
 * 注意：Wi-Fi Direct 只能在两台真机上验证，模拟器不支持 P2P。
 */
object P2pTransfer {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pt-p2p") }
    private val main = Handler(Looper.getMainLooper())

    private var controller: WifiDirectController? = null
    private var events: P2pEvents? = null
    private var role: SessionRole = SessionRole.SENDER
    private var pairingCode: String = "000000"
    private var payloads: List<PtPayload> = emptyList()
    private var wanted: Set<ItemType> = emptySet()
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private val running = AtomicBoolean(false)
    private val sasLatch = CountDownLatch(1)
    private val itemsLatch = CountDownLatch(1)

    @Volatile private var suppliedPayloads: List<PtPayload>? = null
    @Volatile private var suppliedSelection: Set<ItemType>? = null

    @Volatile private var sasDecision: Boolean? = null

    val isRunning: Boolean get() = running.get()

    fun attach(listener: P2pEvents) {
        events = listener
    }

    /** 新机（接收端）：建组成为 GO，等待旧机连上来。 */
    fun startReceiver(context: Context, code: String, wantedTypes: Set<ItemType>) {
        role = SessionRole.RECEIVER
        pairingCode = code
        wanted = wantedTypes
        payloads = emptyList()
        start(context, asGroupOwner = true)
    }

    /** 旧机（发送端）：搜索并连接新机的直连组，然后跑发送端会话。 */
    fun startSender(context: Context, code: String, items: List<PtPayload>) {
        role = SessionRole.SENDER
        pairingCode = code
        payloads = items
        wanted = emptySet()
        start(context, asGroupOwner = false)
    }

    private fun start(context: Context, asGroupOwner: Boolean) {
        stop(context, notify = false)
        TransferFlowSession.resetReal()
        TransferFlowSession.realSession = true
        sasDecision = null
        suppliedPayloads = null
        suppliedSelection = null
        running.set(true)

        val c = WifiDirectController(context.applicationContext, object : WifiDirectListener {
            override fun onAvailabilityChanged(available: Boolean) = status(
                if (available) "Wi-Fi Direct 可用" else "Wi-Fi Direct 不可用，请检查系统 Wi-Fi 开关"
            )

            override fun onPeersChanged(peers: List<WifiP2pDevice>) {
                post { events?.onPeers(peers) }
            }

            override fun onConnectionChanged(info: WifiP2pInfo?) {
                if (info == null || !running.get()) return
                status("已建立直连：" + (info.groupOwnerAddress?.hostAddress ?: "?"))
                executor.execute { if (info.isGroupOwner) serveAsGroupOwner() else connectToGroupOwner(info) }
            }

            override fun onError(operation: String, reason: Int) {
                if (reason == WifiDirectController.ERROR_NO_PERMISSION) {
                    finish(false, "缺少「附近的设备」权限，无法使用 Wi-Fi Direct")
                } else {
                    status(operation + " 失败（原因码 " + reason + "）")
                }
            }

            override fun onThisDeviceChanged(device: WifiP2pDevice?) {}
        })
        controller = c
        if (!c.isSupported) {
            finish(false, "本机不支持 Wi-Fi Direct")
            return
        }
        if (!c.hasPermission()) {
            finish(false, "需要授予「附近的设备」权限")
            return
        }
        if (!c.start()) {
            finish(false, "Wi-Fi Direct 初始化失败")
            return
        }
        if (asGroupOwner) {
            status("正在创建直连组…")
            c.createGroup()
            // 建组成功后系统会发 CONNECTION_CHANGED，由 onConnectionChanged 接管
        } else {
            status("正在搜索附近的设备…")
            c.discoverPeers()
        }
    }

    /** Group Owner 侧：监听端口，等对端 TCP 连入。 */
    private fun serveAsGroupOwner() {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(WifiDirectController.DEFAULT_PORT))
            serverSocket = server
            status("等待旧机接入（端口 " + WifiDirectController.DEFAULT_PORT + "）…")
            server.soTimeout = ACCEPT_TIMEOUT_MS
            val accepted = server.accept()
            socket = accepted
            status("对端已接入，开始加密握手…")
            runSession(accepted, SessionRole.RECEIVER)
        } catch (t: Throwable) {
            finish(false, "等待连接失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** 客户端侧：连到 Group Owner 的 IP。 */
    private fun connectToGroupOwner(info: WifiP2pInfo) {
        try {
            val host = info.groupOwnerAddress?.hostAddress ?: run {
                finish(false, "无法获取对端地址")
                return
            }
            status("正在连接 " + host + " …")
            val s = Socket()
            s.connect(InetSocketAddress(host, WifiDirectController.DEFAULT_PORT), CONNECT_TIMEOUT_MS)
            socket = s
            status("已连接，开始加密握手…")
            runSession(s, SessionRole.SENDER)
        } catch (t: Throwable) {
            finish(false, "连接失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun runSession(s: Socket, sessionRole: SessionRole) {
        val session = PtSession(
            role = sessionRole,
            pairingCode = pairingCode,
            payloads = payloads,
            wantedTypes = wanted,
            deviceModel = android.os.Build.MODEL ?: "",
            listener = object : PtSessionListener {
                override fun onSas(sas: Int) {
                    TransferFlowSession.sas = sas
                    post { events?.onSas(sas) }
                }

                override fun onProgress(itemsDone: Int, itemCount: Int, bytesDone: Long, bytesTotal: Long) {
                    TransferFlowSession.realItemsDone = itemsDone
                    TransferFlowSession.realItemCount = itemCount
                    TransferFlowSession.realBytesDone = bytesDone
                    TransferFlowSession.realBytesTotal = bytesTotal
                }

                override fun onResult(results: List<PtItemResult>) {
                    TransferFlowSession.realResults.clear()
                    TransferFlowSession.realResults.addAll(results)
                }

                override fun onPeer(model: String, osName: Int) {
                    status("对端：" + model)
                }
            },
            payloadProvider = {
                status("请在界面上选择要迁移的数据项…")
                itemsLatch.await(ITEMS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                suppliedPayloads ?: emptyList()
            },
            selectionProvider = {
                status("请在界面上选择要迁移的数据项…")
                itemsLatch.await(ITEMS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                suppliedSelection ?: emptySet()
            },
            sasConfirmer = { sas ->
                status("请在两台手机上核对 4 位数字：" + sas.toString().padStart(4, '0'))
                val decided = sasLatch.await(SAS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                decided && sasDecision == true
            },
        )
        session.run(s)
        finish(true, "迁移完成")
    }

    /** 界面在数据项页解锁会话：发送端交载荷，接收端交勾选结果。 */
    fun supplyPayloads(items: List<PtPayload>) {
        suppliedPayloads = items
        openItemsGate()
    }

    fun supplySelection(types: Set<ItemType>) {
        suppliedSelection = types
        openItemsGate()
    }

    private fun openItemsGate() {
        while (itemsLatch.count > 0) itemsLatch.countDown()
    }

    fun detach() {
        events = null
    }

    /** 旧机：连接用户选中的对端设备。 */
    fun connectTo(device: WifiP2pDevice) {
        status("正在连接 " + (device.deviceName ?: device.deviceAddress) + " …")
        controller?.connect(device)
    }

    /** 用户在两台手机核对 SAS 后给出结论。 */
    fun confirmSas(agreed: Boolean) {
        sasDecision = agreed
        while (sasLatch.count > 0) sasLatch.countDown()
    }

    fun retrySasGate() {
        while (sasLatch.count > 0) sasLatch.countDown()
    }

    fun stop(context: Context, notify: Boolean = true) {
        running.set(false)
        openItemsGate()
        while (sasLatch.count > 0) sasLatch.countDown()
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null
        serverSocket = null
        controller?.let { c ->
            runCatching { c.stopPeerDiscovery() }
            runCatching { c.removeGroup() }
            c.stop()
        }
        controller = null
        if (notify) TransferFlowSession.realSession = false
    }

    private fun status(text: String) = post { events?.onStatus(text) }

    private fun finish(ok: Boolean, message: String) {
        if (!running.getAndSet(false) && !ok) return
        TransferFlowSession.realSession = false
        if (!ok) TransferFlowSession.realError = message
        post { events?.onFinished(ok, message) }
    }

    private fun post(block: () -> Unit) {
        main.post { runCatching { block() } }
    }

    private const val ACCEPT_TIMEOUT_MS = 180_000
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val SAS_TIMEOUT_MS = 120_000L
    private const val ITEMS_TIMEOUT_MS = 300_000L
}
