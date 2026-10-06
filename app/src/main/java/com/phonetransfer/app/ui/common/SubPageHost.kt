package com.phonetransfer.app.ui.common

import androidx.fragment.app.Fragment

/**
 * 由 MainActivity 实现：在同一个内容容器里打开二级页面（带返回栈）。
 * 「我的」页的子页面通过它导航，避免直接在 Fragment 里硬编码容器 id。
 */
interface SubPageHost {
    fun openSubPage(fragment: Fragment)
}
