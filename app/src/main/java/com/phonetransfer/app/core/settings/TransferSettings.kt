package com.phonetransfer.app.core.settings

import android.content.Context

/**
 * 同名文件冲突策略（协议规范 §7.6 T_CONFLICT_POLICY）。
 * 默认 1 = 重命名。
 */
enum class ConflictPolicy(val code: Int) {
    RENAME(1),
    SKIP(2),
    OVERWRITE(3),
    MERGE(4);

    companion object {
        fun fromCode(code: Int): ConflictPolicy = entries.firstOrNull { it.code == code } ?: RENAME
    }
}

/**
 * 传输设置的本地持久化。
 *
 * 注意：这里**只保存策略开关**，任何会话密钥、传输内容、临时缓存都不落盘
 * （协议规范 §10「密钥生命周期：落盘 —— 禁止」）。
 */
class TransferSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 同名文件冲突策略，默认重命名（协议 §12.5）。 */
    var conflictPolicy: ConflictPolicy
        get() = ConflictPolicy.fromCode(prefs.getInt(KEY_CONFLICT, ConflictPolicy.RENAME.code))
        set(value) = prefs.edit().putInt(KEY_CONFLICT, value.code).apply()

    /** 是否携带照片 EXIF 位置信息，默认关闭（协议 §7.6 T_INCLUDE_EXIF_LOCATION 默认 0）。 */
    var includeExifLocation: Boolean
        get() = prefs.getBoolean(KEY_EXIF, false)
        set(value) = prefs.edit().putBoolean(KEY_EXIF, value).apply()

    /**
     * 是否允许迁移 L4 极高敏感数据项，默认关闭。
     * 开启只代表「允许出现在选择列表」，每一项仍需单独同意。
     */
    var allowCriticalItems: Boolean
        get() = prefs.getBoolean(KEY_L4, false)
        set(value) = prefs.edit().putBoolean(KEY_L4, value).apply()

    /** 温升自动限速，默认开启（可行性文档 §6.6）。 */
    var thermalThrottle: Boolean
        get() = prefs.getBoolean(KEY_THROTTLE, true)
        set(value) = prefs.edit().putBoolean(KEY_THROTTLE, value).apply()

    private companion object {
        const val PREFS_NAME = "pt_transfer_settings"
        const val KEY_CONFLICT = "conflict_policy"
        const val KEY_EXIF = "include_exif_location"
        const val KEY_L4 = "allow_critical_items"
        const val KEY_THROTTLE = "thermal_throttle"
    }
}
