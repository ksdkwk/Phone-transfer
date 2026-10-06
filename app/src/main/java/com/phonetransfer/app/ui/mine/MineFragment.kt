package com.phonetransfer.app.ui.mine

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.phonetransfer.app.BuildConfig
import com.phonetransfer.app.R
import com.phonetransfer.app.ui.common.SubPageHost

/**
 * 「我的」页：本机信息 + 功能入口。
 * 功能划分与三份规范文档一致：能力地图（§5）、迁移报告（FR-18）、
 * 传输设置（协议 §7.6 / §12.5）、权限与隐私（§7）、关于。
 */
class MineFragment : Fragment(R.layout.fragment_mine) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }
        view.findViewById<TextView>(R.id.mine_device_model).text =
            getString(R.string.mine_header_device, brand, Build.MODEL)
        view.findViewById<TextView>(R.id.mine_device_os).text =
            getString(R.string.mine_header_os, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)

        view.findViewById<View>(R.id.row_capability_map).setOnClickListener {
            openSubPage(CapabilityMapFragment())
        }
        view.findViewById<View>(R.id.row_report).setOnClickListener {
            openSubPage(
                TextPageFragment.newInstance(
                    R.string.page_report_title,
                    getString(R.string.page_report_empty),
                )
            )
        }
        view.findViewById<View>(R.id.row_settings).setOnClickListener {
            openSubPage(TransferSettingsFragment())
        }
        view.findViewById<View>(R.id.row_privacy).setOnClickListener {
            openSubPage(
                TextPageFragment.newInstance(
                    R.string.page_privacy_title,
                    getString(R.string.privacy_body),
                )
            )
        }
        view.findViewById<View>(R.id.row_about).setOnClickListener {
            openSubPage(
                TextPageFragment.newInstance(
                    R.string.page_about_title,
                    buildAboutBody(),
                )
            )
        }
    }

    private fun openSubPage(fragment: Fragment) {
        (activity as? SubPageHost)?.openSubPage(fragment)
    }

    private fun buildAboutBody(): String = buildString {
        appendLine(getString(R.string.about_version, BuildConfig.VERSION_NAME))
        appendLine(getString(R.string.about_protocol))
        appendLine(getString(R.string.about_crypto))
        appendLine(getString(R.string.about_no_cloud))
        appendLine()
        append(getString(R.string.about_spec))
    }
}
