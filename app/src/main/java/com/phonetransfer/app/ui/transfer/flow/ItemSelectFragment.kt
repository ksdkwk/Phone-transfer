package com.phonetransfer.app.ui.transfer.flow

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.core.capability.CapabilityMatrix
import com.phonetransfer.app.core.protocol.ItemSupport
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.settings.TransferSettingsStore
import com.phonetransfer.app.core.transport.PayloadBuilder
import com.phonetransfer.app.p2p.P2pTransfer
import com.phonetransfer.app.ui.common.labelRes

/**
 * 第 4 步：数据项选择。
 *
 * 合规要点（协议 §11、可行性文档 §7.3）：
 *  · 未勾选的类型不申请任何权限；
 *  · L4 极高敏感项（短信/彩信/通话记录/应用数据）必须单独同意；
 *  · 不支持的类型灰显并给出替代引导，而不是隐藏。
 */
class ItemSelectFragment : Fragment(R.layout.fragment_flow_items) {

    private val selected get() = TransferFlowSession.selectedItems
    private val consented = mutableSetOf<ItemType>()
    private lateinit var countView: TextView
    private lateinit var startButton: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindMode(view)
        countView = view.findViewById(R.id.items_count)
        startButton = view.findViewById(R.id.items_start)

        val settings = TransferSettingsStore(requireContext())
        view.findViewById<TextView>(R.id.items_policy).text =
            getString(R.string.flow_items_policy, getString(settings.conflictPolicy.labelRes()))

        val container = view.findViewById<LinearLayout>(R.id.items_container)
        container.removeAllViews()
        CapabilityMatrix.byScenario(TransferFlowSession.scenario).forEach { (itemType, support) ->
            container.addView(buildRow(itemType, support))
            container.addView(requireContext().divider())
        }

        startButton.setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), R.string.flow_items_need_one, Toast.LENGTH_SHORT).show()
            } else {
                TransferFlowSession.beginTransfer(settings.conflictPolicy, settings.includeExifLocation)
                if (TransferFlowSession.realSession) {
                    if (TransferFlowSession.role == DeviceRole.OLD_PHONE) {
                        P2pTransfer.supplyPayloads(
                            PayloadBuilder.build(
                                selected.toList(),
                                requireContext().applicationInfo?.sourceDir,
                                getString(R.string.app_name),
                            )
                        )
                    } else {
                        P2pTransfer.supplySelection(selected.toSet())
                    }
                }
                openFlowStep(ProgressFragment())
            }
        }
        refresh()
    }

    private fun buildRow(itemType: ItemType, support: ItemSupport): View {
        val context = requireContext()
        val row = context.horizontalRow()
        val box = CheckBox(context)
        box.isEnabled = support != ItemSupport.UNSUPPORTED
        box.isChecked = selected.contains(itemType)
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        column.addView(context.textView(getString(itemType.labelRes()), 16f, R.color.pt_text_primary))
        val meta = if (support == ItemSupport.UNSUPPORTED) {
            getString(R.string.flow_items_unsupported) + " · " +
                getString(R.string.flow_items_unsupported_note)
        } else {
            getString(support.labelRes()) + " · " + getString(itemType.sensitivity.labelRes())
        }
        column.addView(context.textView(meta, 12f, R.color.pt_text_secondary))

        row.addView(box)
        row.addView(column)
        if (support == ItemSupport.UNSUPPORTED) {
            row.alpha = 0.5f
        } else {
            // 整行可点：点标题也能勾选，避免只点中小复选框
            row.setOnClickListener { box.toggle() }
        }

        box.setOnCheckedChangeListener { _, checked ->
            when {
                !checked -> {
                    selected.remove(itemType)
                    refresh()
                }

                itemType.sensitivity.requiresSeparateConsent && !consented.contains(itemType) -> {
                    askConsent(itemType, box)
                }

                else -> {
                    selected.add(itemType)
                    refresh()
                }
            }
        }
        return row
    }

    private fun askConsent(itemType: ItemType, box: CheckBox) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.flow_items_consent_title)
            .setMessage(
                getString(R.string.flow_items_consent_message, getString(itemType.labelRes()))
            )
            .setCancelable(false)
            .setPositiveButton(R.string.flow_items_consent_ok) { _, _ ->
                consented.add(itemType)
                selected.add(itemType)
                box.isChecked = true
                refresh()
            }
            .setNegativeButton(R.string.flow_items_consent_cancel) { _, _ ->
                box.isChecked = false
            }
            .show()
    }

    private fun refresh() {
        countView.text = getString(R.string.flow_items_selected, selected.size)
        startButton.alpha = if (selected.isEmpty()) 0.5f else 1f
    }
}
