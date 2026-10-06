package com.phonetransfer.app.ui.transfer.flow

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.ui.common.labelRes

/** 迁移报告：成功/取消明细、校验结论与清理确认。 */
class ReportFragment : Fragment(R.layout.fragment_flow_report) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val session = TransferFlowSession
        val elapsed = if (session.finishedAtMs > 0) session.finishedAtMs - session.startedAtMs else 0L

        view.findViewById<TextView>(R.id.report_summary).text = listOf(
            getString(R.string.flow_report_mode, getString(session.mode.titleRes)),
            getString(R.string.flow_report_role, getString(session.role.labelRes)),
            getString(R.string.flow_report_channel, getString(session.channel.titleRes)),
            getString(R.string.flow_report_elapsed, formatElapsed(elapsed)),
        ).joinToString("\n")

        view.findViewById<TextView>(R.id.report_verify).setText(
            if (session.cancelled) R.string.flow_report_verify_skipped else R.string.flow_report_verify_ok
        )

        val container = view.findViewById<LinearLayout>(R.id.report_container)
        container.removeAllViews()
        val real = session.realResults
        if (real.isNotEmpty()) {
            real.forEachIndexed { index, result ->
                if (index > 0) container.addView(requireContext().divider())
                val row = requireContext().horizontalRow()
                val name = requireContext().textView(
                    getString(
                        R.string.flow_real_item,
                        getString(result.itemType.labelRes()),
                        result.byteCount,
                        getString(
                            if (result.verified) R.string.flow_real_verified else R.string.flow_real_failed
                        ),
                    ),
                    14f, R.color.pt_text_primary,
                )
                name.layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(name)
                container.addView(row)
            }
        } else if (session.realError.isNotEmpty()) {
            container.addView(requireContext().horizontalRow().apply {
                addView(requireContext().textView(session.realError, 14f, R.color.pt_danger))
            })
        } else if (session.outcomes.isEmpty()) {
            container.addView(requireContext().horizontalRow().apply {
                addView(requireContext().textView("—", 14f, R.color.pt_text_secondary))
            })
        } else {
            session.outcomes.forEachIndexed { index, outcome ->
                if (index > 0) container.addView(requireContext().divider())
                val row = requireContext().horizontalRow()
                val name = requireContext()
                    .textView(getString(outcome.itemType.labelRes()), 16f, R.color.pt_text_primary)
                name.layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(name)
                row.addView(
                    requireContext().textView(
                        getString(outcome.statusRes), 12f, R.color.pt_text_secondary
                    )
                )
                container.addView(row)
            }
        }

        view.findViewById<View>(R.id.report_done).setOnClickListener {
            TransferFlowSession.clear()
            closeFlow()
        }
    }
}
