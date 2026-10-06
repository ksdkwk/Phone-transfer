package com.phonetransfer.app.ui.backup

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.phonetransfer.app.R
import com.phonetransfer.app.ui.common.SubPageHost

/**
 * 文件备份（SoftAP + TCP）· 第 1 步：选择本机角色。
 *
 * 与换机主流程不同，这里旧机是「热点 + 协议发送端」、新机是「热点客户端 + 协议接收端」，
 * 不依赖 Wi-Fi Direct P2P 硬件，任何能开热点的 Android 手机都可用（兼容性更好）。
 */
class BackupRoleFragment : Fragment(R.layout.fragment_backup_role) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.backup_role_old).setOnClickListener {
            open(BackupOldFragment())
        }
        view.findViewById<View>(R.id.backup_role_new).setOnClickListener {
            open(BackupNewFragment())
        }
    }

    private fun open(fragment: Fragment) {
        (activity as? SubPageHost)?.openSubPage(fragment)
    }
}
