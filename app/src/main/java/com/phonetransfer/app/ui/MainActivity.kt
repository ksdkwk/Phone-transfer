package com.phonetransfer.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.phonetransfer.app.R
import com.phonetransfer.app.ui.common.SubPageHost
import com.phonetransfer.app.ui.mine.MineFragment
import com.phonetransfer.app.ui.transfer.TransferFragment

/**
 * 唯一的 Activity：点开应用图标即进入本界面，中间没有任何过渡页。
 *
 * 界面结构（见 res/layout/activity_main.xml）：
 *  · 上方（layout_weight = 1）是功能区容器 main_content；
 *  · 下方是功能选项，只有「换机」「我的」两个入口。
 */
class MainActivity : AppCompatActivity(), SubPageHost {

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_transfer -> {
                    showTab(TAG_TRANSFER)
                    true
                }

                R.id.nav_mine -> {
                    showTab(TAG_MINE)
                    true
                }

                else -> false
            }
        }

        // 首次创建时默认停在「换机」；旋屏等重建场景交给 FragmentManager 自行恢复。
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_transfer
        }
    }

    override fun openSubPage(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.main_content, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showTab(tag: String) {
        val fm = supportFragmentManager
        // 已经停在目标选项卡且没有二级页面时，不做多余的重建。
        if (fm.backStackEntryCount == 0 && fm.findFragmentByTag(tag) != null) {
            return
        }
        // 切换底部选项卡时，先把「我的」里的二级页面全部弹出。
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val fragment: Fragment = if (tag == TAG_MINE) MineFragment() else TransferFragment()
        fm.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.main_content, fragment, tag)
            .commit()
    }

    private companion object {
        const val TAG_TRANSFER = "tab_transfer"
        const val TAG_MINE = "tab_mine"
    }
}
