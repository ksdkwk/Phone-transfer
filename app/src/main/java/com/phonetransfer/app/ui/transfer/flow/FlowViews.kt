package com.phonetransfer.app.ui.transfer.flow

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

internal fun Context.textView(text: String, sizeSp: Float, @ColorRes colorRes: Int, bold: Boolean = false): TextView {
    val view = TextView(this)
    view.text = text
    view.textSize = sizeSp
    view.setTextColor(ContextCompat.getColor(this, colorRes))
    if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
    return view
}

internal fun Context.horizontalRow(): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(0, dp(10), 0, dp(10))
    row.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    return row
}

internal fun Context.divider(): View {
    val view = View(this)
    view.setBackgroundColor(ContextCompat.getColor(this, R.color.pt_divider))
    view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    return view
}

/** 每个流程页顶部都显示当前换机方式。 */
internal fun Fragment.bindMode(root: View) {
    root.findViewById<TextView>(R.id.flow_mode).text =
        getString(R.string.flow_mode_label, getString(TransferFlowSession.mode.titleRes))
}
