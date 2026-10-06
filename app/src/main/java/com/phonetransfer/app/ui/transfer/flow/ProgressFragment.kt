package com.phonetransfer.app.ui.transfer.flow

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.core.session.SessionState
import com.phonetransfer.app.p2p.P2pTransfer
import com.phonetransfer.app.ui.common.labelRes

/**
 * 第 6 步：迁移与校验。
 *
 * · 真实链路（Wi-Fi Direct）：进度、条目数、字节数都来自会话回调（[TransferFlowSession.realBytesDone]…），
 *   界面只做渲染；
 * · 其它通道尚未接入链路：进度为本地模拟，界面上有明确说明。
 */
class ProgressFragment : Fragment(R.layout.fragment_flow_progress) {

    private val handler = Handler(Looper.getMainLooper())
    private var progress = 0
    private var running = false

    private lateinit var bar: ProgressBar
    private lateinit var phaseView: TextView
    private lateinit var channelView: TextView
    private lateinit var currentView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var pauseButton: Button

    private val tick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            if (TransferFlowSession.realSession) {
                render()
                handler.postDelayed(this, TICK_MS)
                return
            }
            if (TransferFlowSession.realResults.isNotEmpty() || TransferFlowSession.realError.isNotEmpty()) {
                handler.removeCallbacks(this)
                openFlowStep(ReportFragment())
                return
            }
            if (!running) return
            progress = (progress + 2).coerceAtMost(100)
            render()
            if (progress >= 100) {
                running = false
                complete()
            } else {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindMode(view)
        bar = view.findViewById(R.id.transfer_bar)
        bar.max = 100
        phaseView = view.findViewById(R.id.transfer_phase)
        channelView = view.findViewById(R.id.transfer_channel)
        currentView = view.findViewById(R.id.transfer_current)
        elapsedView = view.findViewById(R.id.transfer_elapsed)
        pauseButton = view.findViewById(R.id.transfer_pause)

        pauseButton.setOnClickListener { togglePause() }
        view.findViewById<View>(R.id.transfer_cancel).setOnClickListener {
            handler.removeCallbacks(tick)
            running = false
            if (TransferFlowSession.realSession) P2pTransfer.stop(requireContext())
            TransferFlowSession.cancelTransfer()
            openFlowStep(ReportFragment())
        }

        if (TransferFlowSession.realSession) {
            running = true
            render()
            handler.postDelayed(tick, TICK_MS)
            return
        }
        if (TransferFlowSession.isFinished) {
            progress = 100
            render()
        } else {
            running = true
            handler.postDelayed(tick, TICK_MS)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(tick)
        running = false
        super.onDestroyView()
    }

    private fun togglePause() {
        if (TransferFlowSession.realSession) return // 真实链路的暂停待接入（协议 §12.2 已留 CONTROL 消息）
        if (running) {
            running = false
            handler.removeCallbacks(tick)
            TransferFlowSession.machine.tryTransition(SessionState.PAUSED)
        } else {
            TransferFlowSession.machine.tryTransition(SessionState.TRANSFERRING)
            running = true
            handler.postDelayed(tick, TICK_MS)
        }
        render()
    }

    private fun complete() {
        TransferFlowSession.finishTransfer()
        openFlowStep(ReportFragment())
    }

    private fun render() {
        val real = TransferFlowSession.realSession
        phaseView.setText(TransferFlowSession.machine.state.labelRes())
        channelView.text =
            getString(R.string.flow_transfer_channel, getString(TransferFlowSession.channel.titleRes))

        if (real) {
            val done = TransferFlowSession.realBytesDone
            val total = TransferFlowSession.realBytesTotal
            bar.progress = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            currentView.text = getString(
                R.string.flow_transfer_current,
                TransferFlowSession.realItemsDone.toString() + "/" +
                    TransferFlowSession.realItemCount + " · " + done + " B",
            )
            pauseButton.setText(R.string.flow_transfer_pause)
            pauseButton.isEnabled = false
        } else {
            bar.progress = progress
            val items = TransferFlowSession.selectedItems.toList()
            if (items.isEmpty()) {
                currentView.text = ""
            } else {
                val index = (progress * items.size / 101).coerceIn(0, items.size - 1)
                currentView.text =
                    getString(R.string.flow_transfer_current, getString(items[index].labelRes()))
            }
            pauseButton.setText(if (running) R.string.flow_transfer_pause else R.string.flow_transfer_resume)
            pauseButton.isEnabled = !TransferFlowSession.isFinished
        }

        val end = if (TransferFlowSession.finishedAtMs > 0) {
            TransferFlowSession.finishedAtMs
        } else {
            System.currentTimeMillis()
        }
        elapsedView.text =
            getString(R.string.flow_transfer_elapsed, formatElapsed(end - TransferFlowSession.startedAtMs))
    }

    private companion object {
        const val TICK_MS = 120L
    }
}
