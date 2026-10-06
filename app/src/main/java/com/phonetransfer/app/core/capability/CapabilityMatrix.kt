package com.phonetransfer.app.core.capability

import com.phonetransfer.app.core.protocol.ItemSupport
import com.phonetransfer.app.core.protocol.ItemType

/** 换机方向组合：旧机平台 → 新机平台。 */
enum class Scenario {
    /** Android → Android，V1.0 MVP 的唯一承诺范围。 */
    A_TO_A,

    /** Android → iOS。 */
    A_TO_IOS,

    /** iOS → Android。 */
    IOS_TO_A,

    /** iOS → iOS。 */
    IOS_TO_IOS,
}

/**
 * 单个数据项在四种方向组合下的支持度。
 *
 * 数值来源：《Phone-transfer 可行性设计文档》第 5 节「数据项可行性矩阵」。
 * 这是**设计基线**，实际迁移前必须以两端能力探测（协议 §11 CAPABILITY_QUERY/RESPONSE）的实测结果为准。
 */
data class ItemCapability(
    val itemType: ItemType,
    val androidToAndroid: ItemSupport,
    val androidToIos: ItemSupport,
    val iosToAndroid: ItemSupport,
    val iosToIos: ItemSupport,
) {
    fun supportOf(scenario: Scenario): ItemSupport = when (scenario) {
        Scenario.A_TO_A -> androidToAndroid
        Scenario.A_TO_IOS -> androidToIos
        Scenario.IOS_TO_A -> iosToAndroid
        Scenario.IOS_TO_IOS -> iosToIos
    }
}

private val OK = ItemSupport.SUPPORTED
private val PART = ItemSupport.PARTIAL
private val GRANT = ItemSupport.NEEDS_GRANT
private val NO = ItemSupport.UNSUPPORTED

/**
 * 「能力地图」的数据源。
 *
 * 产品化建议（可行性文档 §5 末）：把不可迁移项渲染成替代引导卡片，
 * 而不是简单隐藏，这既降低客诉也是与夸大宣传的第三方工具形成差异的关键。
 */
object CapabilityMatrix {

    /** 设计基线矩阵，顺序即界面展示顺序。 */
    val baseline: List<ItemCapability> = listOf(
        ItemCapability(ItemType.CONTACT, OK, OK, OK, OK),
        ItemCapability(ItemType.SMS, PART, NO, NO, NO),
        ItemCapability(ItemType.MMS, PART, NO, NO, NO),
        ItemCapability(ItemType.CALL_LOG, PART, NO, NO, NO),
        ItemCapability(ItemType.PHOTO, OK, OK, OK, OK),
        ItemCapability(ItemType.VIDEO, OK, OK, OK, OK),
        ItemCapability(ItemType.AUDIO, OK, PART, PART, PART),
        ItemCapability(ItemType.DOCUMENT, OK, PART, PART, PART),
        ItemCapability(ItemType.CALENDAR, OK, OK, OK, OK),
        ItemCapability(ItemType.REMINDER, PART, OK, OK, OK),
        ItemCapability(ItemType.NOTE, NO, NO, NO, NO),
        ItemCapability(ItemType.APP_LIST, OK, PART, PART, PART),
        ItemCapability(ItemType.APK, OK, NO, NO, NO),
        ItemCapability(ItemType.APP_DATA, NO, NO, NO, NO),
        ItemCapability(ItemType.WALLPAPER, GRANT, NO, NO, NO),
        ItemCapability(ItemType.ALARM, GRANT, NO, NO, NO),
        ItemCapability(ItemType.WIFI_CONFIG, NO, NO, NO, NO),
        ItemCapability(ItemType.BROWSER_BOOKMARK, NO, NO, NO, NO),
        ItemCapability(ItemType.OTHER, GRANT, NO, NO, NO),
    )

    private val BY_TYPE: Map<ItemType, ItemCapability> = baseline.associateBy { it.itemType }

    /** 未知数据项按「不支持」处理，避免能力被高估。 */
    fun supportOf(itemType: ItemType, scenario: Scenario): ItemSupport =
        BY_TYPE[itemType]?.supportOf(scenario) ?: NO

    /** 用于「我的 → 能力地图」按方向组合渲染。 */
    fun byScenario(scenario: Scenario): List<Pair<ItemType, ItemSupport>> =
        baseline.map { it.itemType to it.supportOf(scenario) }
}
