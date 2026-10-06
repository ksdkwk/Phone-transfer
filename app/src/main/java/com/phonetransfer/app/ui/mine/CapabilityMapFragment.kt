package com.phonetransfer.app.ui.mine

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.core.capability.CapabilityMatrix
import com.phonetransfer.app.core.capability.Scenario
import com.phonetransfer.app.core.protocol.ItemSupport
import com.phonetransfer.app.ui.common.colorRes
import com.phonetransfer.app.ui.common.labelRes

/**
 * 能力地图（可行性文档 §5）：按「旧机 → 新机」四种组合展示每个数据项的可迁移程度。
 *
 * 表格在代码中动态生成，数据全部来自 [CapabilityMatrix]，
 * 后续接入能力探测后只需要把数据源换成 CAPABILITY_RESPONSE 的实测结果即可，界面无需改动。
 */
class CapabilityMapFragment : Fragment(R.layout.fragment_capability_map) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.capability_container)
        val scenarios = Scenario.entries

        container.addView(headerRow(scenarios))
        container.addView(divider())

        CapabilityMatrix.baseline.forEach { capability ->
            container.addView(
                itemRow(
                    title = getString(capability.itemType.labelRes()),
                    supports = scenarios.map { capability.supportOf(it) },
                )
            )
            container.addView(divider())
        }

        container.addView(legend())
    }

    private fun headerRow(scenarios: List<Scenario>): LinearLayout {
        val row = baseRow()
        row.addView(
            cell(
                text = getString(R.string.capability_col_scenario),
                weight = NAME_WEIGHT,
                sizeSp = CELL_SIZE,
                colorRes = R.color.pt_text_secondary,
                gravity = Gravity.START,
                bold = true,
            )
        )
        scenarios.forEach { scenario ->
            row.addView(
                cell(
                    text = getString(scenario.labelRes()),
                    weight = 1f,
                    sizeSp = CELL_SIZE,
                    colorRes = R.color.pt_text_secondary,
                    gravity = Gravity.CENTER,
                    bold = true,
                )
            )
        }
        return row
    }

    private fun itemRow(title: String, supports: List<ItemSupport>): LinearLayout {
        val row = baseRow()
        row.addView(
            cell(
                text = title,
                weight = NAME_WEIGHT,
                sizeSp = BODY_SIZE,
                colorRes = R.color.pt_text_primary,
                gravity = Gravity.START,
                bold = false,
            )
        )
        supports.forEach { support ->
            row.addView(
                cell(
                    text = getString(support.labelRes()),
                    weight = 1f,
                    sizeSp = CELL_SIZE,
                    colorRes = support.colorRes(),
                    gravity = Gravity.CENTER,
                    bold = false,
                )
            )
        }
        return row
    }

    private fun legend(): LinearLayout {
        val block = LinearLayout(requireContext())
        block.orientation = LinearLayout.VERTICAL
        block.setPadding(0, dp(16), 0, 0)
        block.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val title = TextView(requireContext())
        title.text = getString(R.string.capability_legend)
        title.textSize = BODY_SIZE
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.pt_text_secondary))
        block.addView(title)

        ItemSupport.entries.forEach { support ->
            val item = TextView(requireContext())
            item.text = "· " + getString(support.labelRes())
            item.textSize = BODY_SIZE
            item.setTextColor(ContextCompat.getColor(requireContext(), support.colorRes()))
            item.setPadding(0, dp(4), 0, 0)
            block.addView(item)
        }
        return block
    }

    private fun baseRow(): LinearLayout {
        val row = LinearLayout(requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return row
    }

    private fun cell(
        text: String,
        weight: Float,
        sizeSp: Float,
        colorRes: Int,
        gravity: Int,
        bold: Boolean,
    ): TextView {
        val view = TextView(requireContext())
        view.text = text
        view.textSize = sizeSp
        view.gravity = gravity
        view.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        if (bold) {
            view.setTypeface(view.typeface, Typeface.BOLD)
        }
        view.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            weight,
        )
        return view
    }

    private fun divider(): View {
        val view = View(requireContext())
        view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.pt_divider))
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        )
        return view
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private companion object {
        const val NAME_WEIGHT = 1.7f
        const val CELL_SIZE = 11f
        const val BODY_SIZE = 14f
    }
}
