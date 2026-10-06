package com.phonetransfer.app.core.protocol

/**
 * 协议规范 §7.7 ITEM_TYPE：会话中可迁移的数据项类型。
 *
 * 本枚举刻意保持为**纯 JVM 类型**（不引用 Android 资源），
 * 界面文案由 ui 层的 [com.phonetransfer.app.ui.common.labelRes] 映射，
 * 这样协议层可以在 JVM 单元测试中直接使用。
 *
 * @property code 协议中的数值（TLV T_ITEM_TYPE 的取值）
 * @property sensitivity 数据分级，见可行性设计文档 §7.2
 */
enum class ItemType(val code: Int, val sensitivity: Sensitivity) {
    CONTACT(1, Sensitivity.L3),
    SMS(2, Sensitivity.L4),
    MMS(3, Sensitivity.L4),
    CALL_LOG(4, Sensitivity.L4),
    PHOTO(5, Sensitivity.L2),
    VIDEO(6, Sensitivity.L2),
    AUDIO(7, Sensitivity.L2),
    DOCUMENT(8, Sensitivity.L2),
    CALENDAR(9, Sensitivity.L3),
    REMINDER(10, Sensitivity.L3),
    NOTE(11, Sensitivity.L3),
    APP_LIST(12, Sensitivity.L1),
    APK(13, Sensitivity.L1),
    APP_DATA(14, Sensitivity.L4),
    WALLPAPER(15, Sensitivity.L2),
    ALARM(16, Sensitivity.L2),
    WIFI_CONFIG(17, Sensitivity.L4),
    BROWSER_BOOKMARK(18, Sensitivity.L3),
    OTHER(19, Sensitivity.L2);

    companion object {
        private val BY_CODE: Map<Int, ItemType> = entries.associateBy { it.code }

        /** 未知类型返回 null（协议 §7 前向兼容：未知取值不得导致崩溃）。 */
        fun fromCode(code: Int): ItemType? = BY_CODE[code]
    }
}

/**
 * 数据敏感度分级（可行性设计文档 §7.2）。
 * L4 需要**单独同意**，且必须逐项开关。
 */
enum class Sensitivity(val level: Int) {
    L1(1),
    L2(2),
    L3(3),
    L4(4);

    /** 是否为需要单独同意的极高敏感级别。 */
    val requiresSeparateConsent: Boolean get() = this == L4
}

/**
 * 协议规范 §7.6 的 T_ITEM_SUPPORT：某个数据项在**对端实测**下的支持度。
 * 0 不支持 / 1 支持 / 2 部分支持 / 3 需额外授权。
 */
enum class ItemSupport(val code: Int) {
    UNSUPPORTED(0),
    SUPPORTED(1),
    PARTIAL(2),
    NEEDS_GRANT(3);

    companion object {
        fun fromCode(code: Int): ItemSupport? = entries.firstOrNull { it.code == code }
    }
}
