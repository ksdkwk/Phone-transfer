package com.phonetransfer.app.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat

/** Wi-Fi Direct 事件回调。 */
interface WifiDirectListener {
    fun onAvailabilityChanged(available: Boolean)
    fun onPeersChanged(peers: List<WifiP2pDevice>)
    fun onConnectionChanged(info: WifiP2pInfo?)
    fun onError(operation: String, reason: Int)
    fun onThisDeviceChanged(device: WifiP2pDevice?)
}

/**
 * Wi-Fi Direct (P2P) 控制层（可行性文档 §4.3：Android↔Android 首选通道）。
 *
 * 只做组网：发现对端 / 连接 / 建组（旧机为 Group Owner 时由新机加入，反之亦然）。
 * 组网成功后的 TCP 连接与协议会话由 [P2pCoordinator] 负责。
 */
class WifiDirectController(
    private val context: Context,
    private val listener: WifiDirectListener,
) {

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var registered = false

    val isSupported: Boolean get() = manager != null

    /** Android 13 起用 NEARBY_WIFI_DEVICES，之前用定位权限。 */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        val m = manager ?: return false
        if (channel == null) channel = m.initialize(context, Looper.getMainLooper(), null)
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                            listener.onAvailabilityChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        }
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                        WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                            @Suppress("DEPRECATION")
                            listener.onThisDeviceChanged(intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE))
                        }
                    }
                }
            }
            receiver = r
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(r, filter)
            }
            registered = true
        }
        return true
    }

    fun stop() {
        if (registered) {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            registered = false
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        val m = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) {
            listener.onError("discoverPeers", ERROR_NO_PERMISSION)
            return
        }
        m.discoverPeers(c, actionListener("discoverPeers"))
    }

    @SuppressLint("MissingPermission")
    fun stopPeerDiscovery() {
        val m = manager ?: return
        val c = channel ?: return
        m.stopPeerDiscovery(c, actionListener("stopPeerDiscovery"))
    }

    /** 对端（新机）已建组：本机作为客户端连接过去。 */
    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val m = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) {
            listener.onError("connect", ERROR_NO_PERMISSION)
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
        }
        m.connect(c, config, actionListener("connect"))
    }

    /** 本机（新机）建组并成为 Group Owner，等待旧机加入。 */
    @SuppressLint("MissingPermission")
    fun createGroup() {
        val m = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) {
            listener.onError("createGroup", ERROR_NO_PERMISSION)
            return
        }
        m.createGroup(c, actionListener("createGroup"))
    }

    @SuppressLint("MissingPermission")
    fun removeGroup() {
        val m = manager ?: return
        val c = channel ?: return
        m.removeGroup(c, actionListener("removeGroup"))
    }

    @SuppressLint("MissingPermission")
    fun requestPeers() {
        val m = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) return
        m.requestPeers(c) { peers -> listener.onPeersChanged(peers.deviceList.toList()) }
    }

    @SuppressLint("MissingPermission")
    fun requestConnectionInfo() {
        val m = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) return
        m.requestConnectionInfo(c) { info -> listener.onConnectionChanged(if (info.groupFormed) info else null) }
    }

    /** ActionListener 有两个抽象方法，不能用 lambda。 */
    private fun actionListener(operation: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            // 成功路径由广播回调驱动（PEERS_CHANGED / CONNECTION_CHANGED）
        }

        override fun onFailure(reason: Int) {
            listener.onError(operation, reason)
        }
    }

    companion object {
        const val ERROR_NO_PERMISSION = -100
        const val DEFAULT_PORT = 45717
    }
}
