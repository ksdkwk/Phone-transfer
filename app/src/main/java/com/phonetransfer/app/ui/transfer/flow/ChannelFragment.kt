package com.phonetransfer.app.ui.transfer.flow

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R

/** 第 2 步（仅旧机）：选择换机技术。5 种技术全部展示，不可行的置灰并写明原因。 */
class ChannelFragment : Fragment(R.layout.fragment_flow_channel) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindMode(view)
        val container = view.findViewById<LinearLayout>(R.id.channel_container)
        container.removeAllViews()
        TransferChannel.entries.forEach { container.addView(buildRow(it)) }
    }

    private fun buildRow(channel: TransferChannel): View {
        val context = requireContext()
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = context.dp(12) }
        card.setBackgroundResource(R.drawable.bg_card)
        card.setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))

        card.addView(context.textView(getString(channel.titleRes), 16f, R.color.pt_text_primary, bold = true))
        card.addView(context.textView(getString(channel.detailRes), 12f, R.color.pt_text_secondary))
        card.addView(context.textView(getString(channel.descRes), 13f, R.color.pt_text_secondary))

        if (channel.selectable) {
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener {
                TransferFlowSession.channel = channel
                openFlowStep(PairingFragment())
            }
        } else {
            card.alpha = 0.5f
        }
        return card
    }
}
