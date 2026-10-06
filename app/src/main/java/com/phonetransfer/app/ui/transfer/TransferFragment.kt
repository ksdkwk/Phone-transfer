package com.phonetransfer.app.ui.transfer

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R

/**
 * 「换机」功能区。
 *
 * 按当前需求，这里是一片大部分空白的功能区，功能待定；
 * 后续接入换机主流程时，将按《传输协议规范 v1》的顺序在此区域内挂载：
 *
 *  1. 角色选择（T_ROLE：旧机 SENDER / 新机 RECEIVER），§9
 *  2. 发现与配对：HELLO / HELLO_ACK → PAIR_REQUEST / PAIR_ACCEPT（二维码或 6 位配对码），§9
 *  3. 密钥协商与 SAS：KEY_EXCHANGE → SAS_NOTIFY → SAS_CONFIRM（双方人工比对 4 位短认证串），§10
 *  4. 能力协商与数据项选择：CAPABILITY_QUERY / CAPABILITY_RESPONSE → MANIFEST_OFFER → ITEM_SELECT，§11
 *  5. 传输与收尾：ITEM_BEGIN / CHUNK / CHUNK_ACK / ITEM_END … → TRANSFER_COMPLETE → VERIFY_RESULT → SESSION_CLOSE，§12~§13
 *
 * 注意：空白期不申请任何权限——权限必须在第 4 步选定数据项之后按需申请（协议 §11、可行性文档 §6.7）。
 */
class TransferFragment : Fragment(R.layout.fragment_transfer) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 功能区当前为占位实现，仅保证无障碍可读。
        val functionArea = view.findViewById<View>(R.id.transfer_function_area)
        functionArea.contentDescription = getString(R.string.transfer_placeholder_hint)
    }
}
