package com.phonetransfer.app.ui.transfer.flow

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.core.transport.PayloadBuilder
import com.phonetransfer.app.p2p.P2pEvents
import com.phonetransfer.app.p2p.P2pTransfer

/**
 * 第 3 步：配对。
 *
 * · 选择 Wi-Fi Direct 通道时走**真实链路**：新机建组当 Group Owner 并显示配对码，
 *   旧机输入配对码、搜索并连接新机；随后在 TCP 上跑真实协议会话。
 * · 其它通道尚未接入链路，仍是本地演练（界面会明确写出）。
 */
class PairingFragment : Fragment(R.layout.fragment_flow_pairing), P2pEvents {

    private lateinit var hintView: TextView
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var peersView: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var startButton: Button

    private val isReal: Boolean get() = TransferFlowSession.channel == TransferChannel.WIFI_DIRECT
    private val isSender: Boolean get() = TransferFlowSession.role == DeviceRole.OLD_PHONE

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginReal()
            } else {
                statusView.text = "缺少「附近的设备」权限，无法使用 Wi-Fi Direct"
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindMode(view)
        hintView = view.findViewById(R.id.pairing_hint)
        codeView = view.findViewById(R.id.pairing_code)
        statusView = view.findViewById(R.id.pairing_status)
        peersView = view.findViewById(R.id.pairing_peers)
        codeInput = view.findViewById(R.id.pairing_code_input)
        startButton = view.findViewById(R.id.pairing_start)
        render()
    }

    override fun onDestroyView() {
        P2pTransfer.detach()
        super.onDestroyView()
    }

    private fun render() {
        hintView.setText(
            if (isSender) R.string.flow_pairing_hint_old else R.string.flow_pairing_hint_new
        )
        codeView.text = TransferFlowSession.pairingCode.chunked(3).joinToString(" ")
        if (!isReal) {
            statusView.setText(R.string.flow_p2p_rehearsal)
            codeInput.visibility = View.GONE
            startButton.setText(R.string.flow_pairing_start)
            startButton.setOnClickListener { rehearsalHandshake() }
            return
        }
        statusView.setText(R.string.flow_p2p_real)
        if (isSender) {
            codeInput.visibility = View.VISIBLE
            startButton.setText(R.string.flow_p2p_search)
            startButton.setOnClickListener { ensurePermissionThenStart() }
        } else {
            codeInput.visibility = View.GONE
            startButton.setText(R.string.flow_p2p_host)
            startButton.setOnClickListener { ensurePermissionThenStart() }
        }
    }

    private fun rehearsalHandshake() {
        if (TransferFlowSession.performHandshake()) {
            openFlowStep(SasFragment())
        } else {
            statusView.setText(R.string.flow_pairing_failed)
        }
    }

    private fun ensurePermissionThenStart() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginReal()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun beginReal() {
        P2pTransfer.attach(this)
        peersView.removeAllViews()
        if (isSender) {
            val typed = codeInput.text?.toString()?.trim().orEmpty()
            if (typed.length != 6) {
                statusView.text = "请输入新机上显示的 6 位配对码"
                return
            }
            TransferFlowSession.setPairingCode(typed)
            P2pTransfer.startSender(
                requireContext(),
                typed,
                PayloadBuilder.build(
                    TransferFlowSession.selectedItems.toList(),
                    requireContext().applicationInfo?.sourceDir,
                    getString(R.string.app_name),
                ),
            )
        } else {
            P2pTransfer.startReceiver(
                requireContext(),
                TransferFlowSession.pairingCode,
                TransferFlowSession.selectedItems.toSet(),
            )
        }
    }

    // ---------------- P2pEvents ----------------

    override fun onStatus(text: String) {
        if (isAdded) statusView.text = text
    }

    override fun onPeers(devices: List<WifiP2pDevice>) {
        if (!isAdded) return
        peersView.removeAllViews()
        statusView.text = getString(R.string.flow_p2p_peers, devices.size)
        devices.forEach { device ->
            val row = requireContext().horizontalRow()
            row.addView(
                requireContext().textView(
                    (device.deviceName ?: device.deviceAddress) + "  (" + device.deviceAddress + ")",
                    14f,
                    R.color.pt_text_primary,
                )
            )
            row.isClickable = true
            row.setOnClickListener {
                statusView.text = "正在连接 " + (device.deviceName ?: device.deviceAddress)
                P2pTransfer.connectTo(device)
            }
            peersView.addView(row)
        }
    }

    override fun onSas(sas: Int) {
        if (isAdded) openFlowStep(SasFragment())
    }

    override fun onFinished(ok: Boolean, message: String) {
        if (isAdded && !ok) statusView.text = message
    }
}
