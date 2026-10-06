package com.phonetransfer.app.ui.common

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.phonetransfer.app.R
import com.phonetransfer.app.core.capability.Scenario
import com.phonetransfer.app.core.protocol.ItemSupport
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.protocol.Sensitivity
import com.phonetransfer.app.core.settings.ConflictPolicy

/**
 * 核心层（core 包）刻意不引用 Android 资源，界面文案统一在这里映射。
 * 好处：协议层与能力矩阵可以直接在 JVM 单元测试里使用。
 */

@StringRes
fun ItemType.labelRes(): Int = when (this) {
    ItemType.CONTACT -> R.string.item_1
    ItemType.SMS -> R.string.item_2
    ItemType.MMS -> R.string.item_3
    ItemType.CALL_LOG -> R.string.item_4
    ItemType.PHOTO -> R.string.item_5
    ItemType.VIDEO -> R.string.item_6
    ItemType.AUDIO -> R.string.item_7
    ItemType.DOCUMENT -> R.string.item_8
    ItemType.CALENDAR -> R.string.item_9
    ItemType.REMINDER -> R.string.item_10
    ItemType.NOTE -> R.string.item_11
    ItemType.APP_LIST -> R.string.item_12
    ItemType.APK -> R.string.item_13
    ItemType.APP_DATA -> R.string.item_14
    ItemType.WALLPAPER -> R.string.item_15
    ItemType.ALARM -> R.string.item_16
    ItemType.WIFI_CONFIG -> R.string.item_17
    ItemType.BROWSER_BOOKMARK -> R.string.item_18
    ItemType.OTHER -> R.string.item_19
}

@StringRes
fun ItemSupport.labelRes(): Int = when (this) {
    ItemSupport.UNSUPPORTED -> R.string.support_0
    ItemSupport.SUPPORTED -> R.string.support_1
    ItemSupport.PARTIAL -> R.string.support_2
    ItemSupport.NEEDS_GRANT -> R.string.support_3
}

@ColorRes
fun ItemSupport.colorRes(): Int = when (this) {
    ItemSupport.UNSUPPORTED -> R.color.pt_danger
    ItemSupport.SUPPORTED -> R.color.pt_success
    ItemSupport.PARTIAL -> R.color.pt_warning
    ItemSupport.NEEDS_GRANT -> R.color.pt_warning
}

@StringRes
fun Sensitivity.labelRes(): Int = when (this) {
    Sensitivity.L1 -> R.string.sensitivity_1
    Sensitivity.L2 -> R.string.sensitivity_2
    Sensitivity.L3 -> R.string.sensitivity_3
    Sensitivity.L4 -> R.string.sensitivity_4
}

@StringRes
fun Scenario.labelRes(): Int = when (this) {
    Scenario.A_TO_A -> R.string.capability_col_aa
    Scenario.A_TO_IOS -> R.string.capability_col_a2ios
    Scenario.IOS_TO_A -> R.string.capability_col_ios2a
    Scenario.IOS_TO_IOS -> R.string.capability_col_ios2ios
}

@StringRes
fun ConflictPolicy.labelRes(): Int = when (this) {
    ConflictPolicy.RENAME -> R.string.settings_conflict_rename
    ConflictPolicy.SKIP -> R.string.settings_conflict_skip
    ConflictPolicy.OVERWRITE -> R.string.settings_conflict_overwrite
    ConflictPolicy.MERGE -> R.string.settings_conflict_merge
}
