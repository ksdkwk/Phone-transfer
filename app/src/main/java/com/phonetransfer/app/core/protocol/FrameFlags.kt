package com.phonetransfer.app.core.protocol

/** 帧头 flags 位定义与判定工具（规范 §5）。 */
object FrameFlags {

    /** 无任何标志。 */
    const val NONE: Int = 0

    /** bit0：payload 已加密（握手完成后所有消息置 1）。 */
    const val ENCRYPTED: Int = 0x01

    /** bit1：payload 明文经 zstd 压缩（CHUNK 默认不压缩）。 */
    const val COMPRESSED: Int = 0x02

    /** bit2：要求对端显式 ACK。 */
    const val REQUIRES_ACK: Int = 0x04

    /** bit3：分片最后一帧（本期保留未使用）。 */
    const val LAST_FRAGMENT: Int = 0x08

    /** bit4-7 保留，必须为 0。 */
    const val RESERVED_MASK: Int = 0xF0

    /** 是否置了指定标志位。 */
    fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0

    fun isEncrypted(flags: Int): Boolean = has(flags, ENCRYPTED)

    fun isCompressed(flags: Int): Boolean = has(flags, COMPRESSED)

    fun requiresAck(flags: Int): Boolean = has(flags, REQUIRES_ACK)

    fun isLastFragment(flags: Int): Boolean = has(flags, LAST_FRAGMENT)

    /** 置位若干标志。 */
    fun with(flags: Int, vararg add: Int): Int {
        var result = flags
        for (flag in add) result = result or flag
        return result
    }

    /** 清位若干标志。 */
    fun without(flags: Int, vararg remove: Int): Int {
        var result = flags
        for (flag in remove) result = result and flag.inv()
        return result
    }

    /** 保留位是否全 0。 */
    fun isValid(flags: Int): Boolean = (flags and RESERVED_MASK) == 0

    /** 便于日志的文本表示。 */
    fun describe(flags: Int): String {
        val parts = ArrayList<String>(4)
        if (isEncrypted(flags)) parts.add("ENCRYPTED")
        if (isCompressed(flags)) parts.add("COMPRESSED")
        if (requiresAck(flags)) parts.add("REQUIRES_ACK")
        if (isLastFragment(flags)) parts.add("LAST_FRAGMENT")
        if (parts.isEmpty()) parts.add("NONE")
        return parts.joinToString("|")
    }
}
