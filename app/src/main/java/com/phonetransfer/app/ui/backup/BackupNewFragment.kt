package com.phonetransfer.app.ui.backup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
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
 * 文件备份（SoftAP + TCP）· 新机页面（接收端）。
 *
 * 流程：接入旧机热点（系统 Wi-Fi 设置）→ 选择备份目录（SAF）→ 输入 6 位配对码
 * →（可选）旧机 IP → 开始 → 后台服务走协议 → SAS 核对 → 进度 → 报告。
 */
class BackupNewFragment : Fragment(R.layout.fragment_backup_new) {

    private var tree: Uri? = null
    private lateinit var observer: (BackupSnapshot) -> Unit

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                tree = uri
                // SAF 持久授权：备份过程可能跨越屏幕旋转与后台切换。
                // 个别 ROM 会对持久化数量设限，失败不阻断本次备份。
                runCatching {
                    requireContext().contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            renderTree()
        }

    // Android 16+ 的局域网权限：局域网 TCP 连接需要 ACCESS_LOCAL_NETWORK（运行时）。
    // 用字面量而不是 Manifest 常量，避免依赖 compileSdk 里是否收录该常量。
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startService()
            else view?.findViewById<TextView>(R.id.backup_new_status)?.text =
                getString(R.string.backup_need_local_network)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.backup_new_tree).setOnClickListener { pickTree.launch(null) }
        view.findViewById<Button>(R.id.backup_new_start).setOnClickListener { startOrPermissions() }
        view.findViewById<View>(R.id.backup_new_cancel).setOnClickListener { cancel() }
        view.findViewById<Button>(R.id.backup_new_sas_ok).setOnClickListener { confirmSas(true) }
        view.findViewById<Button>(R.id.backup_new_sas_no).setOnClickListener { confirmSas(false) }
        observer = { snapshot -> if (isAdded) render(snapshot) }
        BackupState.observe(observer)
    }

    override fun onDestroyView() {
        if (::observer.isInitialized) BackupState.remove(observer)
        super.onDestroyView()
    }

    private fun renderTree() {
        val status = requireView().findViewById<TextView>(R.id.backup_new_tree_status)
        status.text = if (tree == null) getString(R.string.backup_pick_tree_desc)
            else getString(R.string.backup_tree_chosen)
    }

    private fun startOrPermissions() {
        val codeView = requireView().findViewById<EditText>(R.id.backup_new_code)
        val code = codeView.text.toString().trim()
        if (tree == null) {
            requireView().findViewById<TextView>(R.id.backup_new_status).text =
                getString(R.string.backup_pick_tree)
            return
        }
        if (!code.matches(Regex("[0-9]{6}"))) {
            requireView().findViewById<TextView>(R.id.backup_new_status).text =
                getString(R.string.backup_code_label)
            return
        }
        // API 33+ 需要常驻通知权限；targetSdk 36+ 的局域网 TCP 需要运行时局域网权限。
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permissions.launch(missing.toTypedArray())
    }

    private fun startService() {
        val view = requireView()
        view.findViewById<Button>(R.id.backup_new_start).isEnabled = false
        view.findViewById<View>(R.id.backup_new_cancel).visibility = View.VISIBLE
        view.findViewById<View>(R.id.backup_new_session).visibility = View.VISIBLE
        val intent = Intent(requireContext(), SoftApBackupService::class.java)
            .setAction(SoftApBackupService.START)
            .putExtra("sender", false)
            .putExtra("tree", tree.toString())
            .putExtra("code", requireView().findViewById<EditText>(R.id.backup_new_code).text.toString().trim())
            .putExtra("host", requireView().findViewById<EditText>(R.id.backup_new_host).text.toString().trim())
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
        view.findViewById<ProgressBar>(R.id.backup_new_bar).progress =
            if (snapshot.total > 0) (snapshot.done * 100 / snapshot.total).toInt() else 0
        view.findViewById<TextView>(R.id.backup_new_status).text = snapshot.status
        val sasView = view.findViewById<TextView>(R.id.backup_new_sas)
        val sasButtons = view.findViewById<View>(R.id.backup_new_sas_buttons)
        if (snapshot.sas != null && snapshot.active) {
            sasView.visibility = View.VISIBLE
            sasView.text = String.format(Locale.US, "%06d", snapshot.sas)
            sasButtons.visibility = View.VISIBLE
        } else {
            sasView.visibility = View.GONE
            sasButtons.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.backup_new_report).text = snapshot.report
        if (!snapshot.active) {
            view.findViewById<Button>(R.id.backup_new_start).isEnabled = true
            view.findViewById<View>(R.id.backup_new_cancel).visibility = View.GONE
        }
    }
}