package com.phonetransfer.app.ui.backup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.softap.BackupSnapshot
import com.phonetransfer.app.softap.BackupState
import com.phonetransfer.app.softap.SoftApBackupService
import java.util.Locale

/**
 * 文件备份（SoftAP + TCP）· 旧机页面（发送端）。
 *
 * 流程：选择文件（SAF 多选）→ 选择热点方式（自动 / 手动）→ 开始 →
 * 后台 [SoftApBackupService] 开热点并跑协议 → SAS 核对 → 进度 → 报告。
 * 界面只订阅 [BackupState] 快照渲染，不持有任何会话对象，旋转后自动恢复。
 */
class BackupOldFragment : Fragment(R.layout.fragment_backup_old) {

    private var files: List<Uri> = emptyList()
    private lateinit var observer: (BackupSnapshot) -> Unit

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) files = uris
            renderFiles()
        }

    // NEARBY_WIFI_DEVICES（API 33+）/ ACCESS_FINE_LOCATION（以下）是 startLocalOnlyHotspot 的前置条件；
    // POST_NOTIFICATIONS（API 33+）用于传输过程中的常驻通知，与换机主流程的权限节奏一致。
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startService()
            else view?.findViewById<TextView>(R.id.backup_old_status)?.text =
                getString(R.string.backup_need_hotspot_permission)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.backup_old_files).setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        val mode = view.findViewById<RadioGroup>(R.id.backup_old_hotspot_mode)
        val modeDesc = view.findViewById<TextView>(R.id.backup_old_mode_desc)
        fun syncModeDesc() {
            modeDesc.text = getString(
                if (mode.checkedRadioButtonId == R.id.backup_old_mode_manual) R.string.backup_hotspot_manual_desc
                else R.string.backup_hotspot_auto_desc,
            )
        }
        mode.setOnCheckedChangeListener { _, _ -> syncModeDesc() }
        syncModeDesc()
        view.findViewById<Button>(R.id.backup_old_start).setOnClickListener { ensurePermissionsThenStart() }
        view.findViewById<View>(R.id.backup_old_cancel).setOnClickListener { cancel() }
        view.findViewById<Button>(R.id.backup_old_sas_ok).setOnClickListener { confirmSas(true) }
        view.findViewById<Button>(R.id.backup_old_sas_no).setOnClickListener { confirmSas(false) }
        observer = { snapshot -> if (isAdded) render(snapshot) }
        BackupState.observe(observer)
    }

    override fun onDestroyView() {
        if (::observer.isInitialized) BackupState.remove(observer)
        super.onDestroyView()
    }

    private fun renderFiles() {
        val status = requireView().findViewById<TextView>(R.id.backup_old_files_status)
        status.text = if (files.isEmpty()) getString(R.string.backup_pick_files_desc)
            else getString(R.string.backup_files_chosen, files.size)
    }

    private fun ensurePermissionsThenStart() {
        if (files.isEmpty()) {
            requireView().findViewById<TextView>(R.id.backup_old_status).text = getString(R.string.backup_pick_files)
            return
        }
        val notification = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.POST_NOTIFICATIONS else null
        val hotspot = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        // targetSdk 36+：局域网 TCP（本机做热点并监听）需要运行时局域网权限。
        val localNetwork = if (Build.VERSION.SDK_INT >= 36) "android.permission.ACCESS_LOCAL_NETWORK" else null
        val missing = listOfNotNull(notification, hotspot, localNetwork).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permissions.launch(missing.toTypedArray())
    }

    private fun startService() {
        val view = requireView()
        view.findViewById<Button>(R.id.backup_old_start).isEnabled = false
        view.findViewById<View>(R.id.backup_old_cancel).visibility = View.VISIBLE
        view.findViewById<View>(R.id.backup_old_session).visibility = View.VISIBLE
        val manual = view.findViewById<RadioGroup>(R.id.backup_old_hotspot_mode)
            .checkedRadioButtonId == R.id.backup_old_mode_manual
        val intent = Intent(requireContext(), SoftApBackupService::class.java)
            .setAction(SoftApBackupService.START)
            .putExtra("sender", true)
            .putExtra("manual", manual)
            .putStringArrayListExtra("files", ArrayList(files.map { it.toString() }))
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun cancel() {
        requireContext().startService(
            Intent(requireContext(), SoftApBackupService::class.java).setAction(SoftApBackupService.CANCEL))
    }

    private fun confirmSas(agreed: Boolean) {
        requireContext().startService(
            Intent(requireContext(), SoftApBackupService::class.java).setAction(SoftApBackupService.CONFIRM)
                .putExtra("agreed", agreed))
    }

    private fun render(snapshot: BackupSnapshot) {
        val view = view ?: return
        view.findViewById<ProgressBar>(R.id.backup_old_bar).progress =
            if (snapshot.total > 0) (snapshot.done * 100 / snapshot.total).toInt() else 0
        view.findViewById<TextView>(R.id.backup_old_status).text = snapshot.status
        view.findViewById<TextView>(R.id.backup_old_credentials).text = snapshot.credentials
        view.findViewById<TextView>(R.id.backup_old_credentials).visibility =
            if (snapshot.credentials.isEmpty()) View.GONE else View.VISIBLE
        val sasView = view.findViewById<TextView>(R.id.backup_old_sas)
        val sasButtons = view.findViewById<View>(R.id.backup_old_sas_buttons)
        if (snapshot.sas != null && snapshot.active) {
            sasView.visibility = View.VISIBLE
            sasView.text = String.format(Locale.US, "%06d", snapshot.sas)
            sasButtons.visibility = View.VISIBLE
        } else {
            sasView.visibility = View.GONE
            sasButtons.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.backup_old_report).text = snapshot.report
        if (!snapshot.active) {
            view.findViewById<Button>(R.id.backup_old_start).isEnabled = true
            view.findViewById<View>(R.id.backup_old_cancel).visibility = View.GONE
        }
    }
}