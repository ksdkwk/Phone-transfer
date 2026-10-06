package com.phonetransfer.app.ui.mine

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.phonetransfer.app.R
import com.phonetransfer.app.core.settings.ConflictPolicy
import com.phonetransfer.app.core.settings.TransferSettingsStore

/**
 * 传输设置。
 *
 * 这里保存的只是**策略开关**（冲突策略、是否携带 EXIF 位置、是否允许 L4 数据项、温升限速），
 * 与会话密钥、传输内容、临时缓存无关——后者一律不落盘（协议规范 §10）。
 */
class TransferSettingsFragment : Fragment(R.layout.fragment_transfer_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val store = TransferSettingsStore(requireContext())

        bindConflictPolicy(view, store)
        bindSwitch(view, R.id.settings_exif, store.includeExifLocation) {
            store.includeExifLocation = it
        }
        bindSwitch(view, R.id.settings_l4, store.allowCriticalItems) {
            store.allowCriticalItems = it
        }
        bindSwitch(view, R.id.settings_throttle, store.thermalThrottle) {
            store.thermalThrottle = it
        }
    }

    private fun bindConflictPolicy(root: View, store: TransferSettingsStore) {
        val group = root.findViewById<RadioGroup>(R.id.settings_conflict_group)
        group.check(
            when (store.conflictPolicy) {
                ConflictPolicy.RENAME -> R.id.settings_conflict_rename
                ConflictPolicy.SKIP -> R.id.settings_conflict_skip
                ConflictPolicy.OVERWRITE -> R.id.settings_conflict_overwrite
                ConflictPolicy.MERGE -> R.id.settings_conflict_merge
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            val policy = when (checkedId) {
                R.id.settings_conflict_rename -> ConflictPolicy.RENAME
                R.id.settings_conflict_skip -> ConflictPolicy.SKIP
                R.id.settings_conflict_overwrite -> ConflictPolicy.OVERWRITE
                R.id.settings_conflict_merge -> ConflictPolicy.MERGE
                else -> null
            } ?: return@setOnCheckedChangeListener

            store.conflictPolicy = policy
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSwitch(root: View, id: Int, initial: Boolean, onChanged: (Boolean) -> Unit) {
        val switch = root.findViewById<SwitchMaterial>(id)
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }
}
