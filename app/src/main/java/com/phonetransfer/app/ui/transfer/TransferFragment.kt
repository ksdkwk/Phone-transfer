package com.phonetransfer.app.ui.transfer

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.ui.transfer.flow.RoleFragment
import com.phonetransfer.app.ui.backup.BackupRoleFragment
import com.phonetransfer.app.ui.transfer.flow.TransferFlowSession
import com.phonetransfer.app.ui.transfer.flow.TransferMode
import com.phonetransfer.app.ui.transfer.flow.openFlowStep

/**
 * 「换机」功能区。
 *
 * 上方大片区域一分为二（见 res/layout/fragment_transfer.xml）：
 *  · 左半部分：同品牌换机
 *  · 右半部分：跨 Android 品牌换机
 *
 * 点击后进入换机主流程，步骤与《传输协议规范 v1》§9~§13 对齐：
 * 角色选择 → 配对（配对码）→ SAS 人工比对 → 数据项选择 → 传输与校验 → 迁移报告。
 * 主流程中 Wi-Fi Direct 已接入真实链路，其余通道仍为本地演练（界面有明确提示）。
 *
 * 下方整行卡片是「文件备份」：SoftAP + 加密 TCP，不依赖 Wi-Fi Direct P2P 硬件，
 * 任何能开热点的 Android 手机都可互传（BackupRoleFragment → BackupOld/NewFragment）。
 */
class TransferFragment : Fragment(R.layout.fragment_transfer) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.transfer_half_same_brand).setOnClickListener {
            begin(TransferMode.SAME_BRAND)
        }
        view.findViewById<View>(R.id.transfer_half_cross_brand).setOnClickListener {
            begin(TransferMode.CROSS_BRAND)
        }
        // 文件备份：SoftAP + TCP 通道，不依赖 Wi-Fi Direct P2P 硬件（兼容性更好）。
        view.findViewById<View>(R.id.transfer_backup).setOnClickListener {
            openFlowStep(BackupRoleFragment())
        }
    }

    private fun begin(mode: TransferMode) {
        TransferFlowSession.start(mode)
        openFlowStep(RoleFragment())
    }
}