package com.phonetransfer.app.ui.transfer.flow

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R

/** 第 1 步：选择本机角色（旧机发送 / 新机接收）。 */
class RoleFragment : Fragment(R.layout.fragment_flow_role) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindMode(view)
        view.findViewById<View>(R.id.role_old).setOnClickListener { choose(DeviceRole.OLD_PHONE) }
        view.findViewById<View>(R.id.role_new).setOnClickListener { choose(DeviceRole.NEW_PHONE) }
    }

    private fun choose(role: DeviceRole) {
        TransferFlowSession.role = role
        // 旧机负责建网或建链，所以由旧机选择换机技术；新机只需加入。
        if (role == DeviceRole.OLD_PHONE) {
            openFlowStep(ChannelFragment())
        } else {
            openFlowStep(PairingFragment())
        }
    }
}
