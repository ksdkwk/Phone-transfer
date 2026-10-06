package com.phonetransfer.app.core.protocol

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * 单个 TLV 字段：tag(u16) + len(u32) + value(len bytes)，全大端。
 *
 * value 为原样字节，不附加尾零；字符串使用 UTF-8；bool 使用 u8。
 */
data class Tlv(val tag: Int, val value: ByteArray) {

    /** value 字节数。 */
    val length: Int get() = value.size

    /** value 是否为空。 */
    fun isEmpty(): Boolean = value.isEmpty()

    /** 按 u8 读取，长度必须为 1。 */
    fun asU8(): Int {
        checkSize(1)
        return value[0].toInt() and 0xFF
    }

    /** 按 u16 大端读取，长度必须为 2。 */
    fun asU16(): Int {
        checkSize(2)
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getShort().toInt() and 0xFFFF
    }

    /** 按 u32 大端读取为无符号 Long，长度必须为 4。 */
    fun asU32(): Long {
        checkSize(4)
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
    }

    /** 按 u64 大端读取（超过 Long.MAX_VALUE 时为负的位模式），长度必须为 8。 */
    fun asU64(): Long {
        checkSize(8)
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong()
    }

    /** 按 UTF-8 读取字符串。 */
    fun asString(): String = String(value, Charsets.UTF_8)

    /** bool 使用 u8，非 0 即 true。 */
    fun asBool(): Boolean = asU8() != 0

    /** 返回原始字节，不复制。 */
    fun asBytes(): ByteArray = value

    private fun checkSize(expected: Int) {
        if (value.size != expected) {
            throw ProtocolException(
                ErrorCode.MALFORMED_TLV,
                "tag 0x" + tag.toString(16) + " 期望 " + expected + " 字节, 实际 " + value.size,
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Tlv && tag == other.tag && value.contentEquals(other.value))

    override fun hashCode(): Int = 31 * tag + value.contentHashCode()

    override fun toString(): String = "Tlv(tag=0x" + tag.toString(16) + ", len=" + value.size + ")"

    companion object {
        /** TLV 头长度：tag(2) + len(4)。 */
        const val HEADER_SIZE: Int = 6

        fun of(tag: Int, value: ByteArray): Tlv = Tlv(tag, value)
    }
}

/** 顺序写入 TLV 字段的构建器，内部全部大端编码。 */
class TlvWriter(initialCapacity: Int = 256) {

    private var buffer = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
    private var count = 0

    /** 已写入字节数。 */
    val size: Int get() = count

    /** 写入长度为 0 的 tag，用作标记类字段。 */
    fun putEmpty(tag: Int): TlvWriter {
        writeHeader(tag, 0)
        return this
    }

    /** 写入 u8。 */
    fun putU8(tag: Int, value: Int): TlvWriter {
        require(value in 0..0xFF) { "u8 越界: $value" }
        writeHeader(tag, 1)
        buffer[count] = value.toByte()
        count += 1
        return this
    }

    /** 写入 bool（u8，0 或 1）。 */
    fun putBool(tag: Int, value: Boolean): TlvWriter = putU8(tag, if (value) 1 else 0)

    /** 写入 u16。 */
    fun putU16(tag: Int, value: Int): TlvWriter {
        require(value in 0..0xFFFF) { "u16 越界: $value" }
        writeHeader(tag, 2)
        buffer[count] = (value ushr 8).toByte()
        buffer[count + 1] = value.toByte()
        count += 2
        return this
    }

    /** 写入 u32，取值 0 - 4294967295。 */
    fun putU32(tag: Int, value: Long): TlvWriter {
        require(value in 0L..0xFFFFFFFFL) { "u32 越界: $value" }
        writeHeader(tag, 4)
        putIntAt(count, (value and 0xFFFFFFFFL).toInt())
        count += 4
        return this
    }

    /** 写入 u32，参数按无符号位模式解释。 */
    fun putU32(tag: Int, value: Int): TlvWriter = putU32(tag, value.toLong() and 0xFFFFFFFFL)

    /** 写入 u64，参数按无符号位模式解释。 */
    fun putU64(tag: Int, value: Long): TlvWriter {
        writeHeader(tag, 8)
        putLongAt(count, value)
        count += 8
        return this
    }

    /** 写入原始字节。 */
    fun putBytes(tag: Int, value: ByteArray): TlvWriter {
        writeHeader(tag, value.size)
        System.arraycopy(value, 0, buffer, count, value.size)
        count += value.size
        return this
    }

    /** 写入 UTF-8 字符串，不含尾零。 */
    fun putString(tag: Int, value: String): TlvWriter = putBytes(tag, value.toByteArray(Charsets.UTF_8))

    /** 原样写入一个已构造的 TLV。 */
    fun putTlv(tlv: Tlv): TlvWriter = putBytes(tlv.tag, tlv.value)

    /** 返回已写入的字节副本。 */
    fun toByteArray(): ByteArray = Arrays.copyOf(buffer, count)

    /** 写入外部流。 */
    fun writeTo(out: OutputStream) {
        out.write(buffer, 0, count)
    }

    /** 清空内容，复用缓冲区。 */
    fun reset() {
        count = 0
    }

    private fun writeHeader(tag: Int, len: Int) {
        require(tag in 0..0xFFFF) { "tag 越界: $tag" }
        require(len >= 0) { "TLV 长度非法: $len" }
        ensure(Tlv.HEADER_SIZE + len)
        buffer[count] = (tag ushr 8).toByte()
        buffer[count + 1] = tag.toByte()
        putIntAt(count + 2, len)
        count += Tlv.HEADER_SIZE
    }

    private fun putIntAt(index: Int, value: Int) {
        buffer[index] = (value ushr 24).toByte()
        buffer[index + 1] = (value ushr 16).toByte()
        buffer[index + 2] = (value ushr 8).toByte()
        buffer[index + 3] = value.toByte()
    }

    private fun putLongAt(index: Int, value: Long) {
        buffer[index] = (value ushr 56).toByte()
        buffer[index + 1] = (value ushr 48).toByte()
        buffer[index + 2] = (value ushr 40).toByte()
        buffer[index + 3] = (value ushr 32).toByte()
        buffer[index + 4] = (value ushr 24).toByte()
        buffer[index + 5] = (value ushr 16).toByte()
        buffer[index + 6] = (value ushr 8).toByte()
        buffer[index + 7] = value.toByte()
    }

    private fun ensure(extra: Int) {
        val required = count + extra
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity = capacity shl 1
        buffer = Arrays.copyOf(buffer, capacity)
    }
}

/**
 * TLV 读取器。
 *
 * 顺序遍历使用 [hasNext] / [next]，未知 tag 按 len 自动跳过；按 tag 读取使用 [find] / [requireU32]
 * 等便捷方法，它们基于整段缓冲建立的索引，与顺序游标互不影响。
 */
class TlvReader(
    private val data: ByteArray,
    private val start: Int = 0,
    private val end: Int = data.size,
) {

    private var position = start
    private var cachedFields: Map<Int, Tlv>? = null

    init {
        require(start >= 0 && end >= start && end <= data.size) { "非法读取区间: $start - $end, 缓冲 " + data.size }
    }

    /** 顺序游标之后尚余字节数。 */
    val remaining: Int get() = end - position

    /** 顺序游标复位到起始位置。 */
    fun reset() {
        position = start
    }

    /** 是否还有 TLV 可读。 */
    fun hasNext(): Boolean = position < end

    /** 读取下一个 TLV，头或长度越界抛 MALFORMED_TLV。 */
    fun next(): Tlv {
        if (position + Tlv.HEADER_SIZE > end) {
            throw ProtocolException(ErrorCode.MALFORMED_TLV, "TLV 头越界, 剩余 " + (end - position) + " 字节")
        }
        val tag = readU16At(position)
        val len = readU32At(position + 2)
        if (len > (end - position - Tlv.HEADER_SIZE).toLong()) {
            throw ProtocolException(
                ErrorCode.MALFORMED_TLV,
                "tag 0x" + tag.toString(16) + " 声明长度 " + len + " 超出剩余 " + (end - position - Tlv.HEADER_SIZE),
            )
        }
        val valueStart = position + Tlv.HEADER_SIZE
        val value = Arrays.copyOfRange(data, valueStart, valueStart + len.toInt())
        position = valueStart + len.toInt()
        return Tlv(tag, value)
    }

    /** 读取下一个 TLV，没有则返回 null。 */
    fun nextOrNull(): Tlv? = if (hasNext()) next() else null

    /** 顺序读取剩余全部 TLV。 */
    fun readAll(): List<Tlv> {
        val result = ArrayList<Tlv>()
        while (hasNext()) result.add(next())
        return result
    }

    /** 按 tag 建索引，重复 tag 保留最后一个；结果缓存。 */
    fun fields(): Map<Int, Tlv> {
        val cached = cachedFields
        if (cached != null) return cached
        val map = LinkedHashMap<Int, Tlv>()
        var cursor = start
        while (cursor < end) {
            if (cursor + Tlv.HEADER_SIZE > end) {
                throw ProtocolException(ErrorCode.MALFORMED_TLV, "TLV 头越界, 剩余 " + (end - cursor) + " 字节")
            }
            val tag = readU16At(cursor)
            val len = readU32At(cursor + 2)
            if (len > (end - cursor - Tlv.HEADER_SIZE).toLong()) {
                throw ProtocolException(ErrorCode.MALFORMED_TLV, "tag 0x" + tag.toString(16) + " 声明长度 " + len + " 越界")
            }
            val valueStart = cursor + Tlv.HEADER_SIZE
            map[tag] = Tlv(tag, Arrays.copyOfRange(data, valueStart, valueStart + len.toInt()))
            cursor = valueStart + len.toInt()
        }
        cachedFields = map
        return map
    }

    /** 查找 tag，不存在返回 null。 */
    fun find(tag: Int): Tlv? = fields()[tag]

    /** 查找并解析为 u8。 */
    fun findU8(tag: Int): Int? = find(tag)?.asU8()

    /** 查找并解析为 u16。 */
    fun findU16(tag: Int): Int? = find(tag)?.asU16()

    /** 查找并解析为无符号 u32。 */
    fun findU32(tag: Int): Long? = find(tag)?.asU32()

    /** 查找并解析为 u64。 */
    fun findU64(tag: Int): Long? = find(tag)?.asU64()

    /** 查找原始字节。 */
    fun findBytes(tag: Int): ByteArray? = find(tag)?.value

    /** 查找并解析为 UTF-8 字符串。 */
    fun findString(tag: Int): String? = find(tag)?.asString()

    /** 查找并解析为 bool。 */
    fun findBool(tag: Int): Boolean? = find(tag)?.asBool()

    /** 必备字段，缺失抛 MISSING_FIELD。 */
    fun requireTlv(tag: Int): Tlv = find(tag) ?: throw ProtocolException.missingField(nameOf(tag))

    /** 必备 u8 字段。 */
    fun requireU8(tag: Int): Int = requireTlv(tag).asU8()

    /** 必备 u16 字段。 */
    fun requireU16(tag: Int): Int = requireTlv(tag).asU16()

    /** 必备 u32 字段，返回无符号 Long。 */
    fun requireU32(tag: Int): Long = requireTlv(tag).asU32()

    /** 必备 u64 字段。 */
    fun requireU64(tag: Int): Long = requireTlv(tag).asU64()

    /** 必备 bytes 字段。 */
    fun requireBytes(tag: Int): ByteArray = requireTlv(tag).value

    /** 必备字符串字段。 */
    fun requireString(tag: Int): String = requireTlv(tag).asString()

    /** 必备 bool 字段。 */
    fun requireBool(tag: Int): Boolean = requireTlv(tag).asBool()

    /** 可选 u8 字段，缺失返回兜底值。 */
    fun u8(tag: Int, fallback: Int): Int = findU8(tag) ?: fallback

    /** 可选 u16 字段，缺失返回兜底值。 */
    fun u16(tag: Int, fallback: Int): Int = findU16(tag) ?: fallback

    /** 可选 u32 字段，缺失返回兜底值。 */
    fun u32(tag: Int, fallback: Long): Long = findU32(tag) ?: fallback

    /** 可选 u64 字段，缺失返回兜底值。 */
    fun u64(tag: Int, fallback: Long): Long = findU64(tag) ?: fallback

    /** 可选字符串字段，缺失返回兜底值。 */
    fun string(tag: Int, fallback: String): String = findString(tag) ?: fallback

    /** 可选 bool 字段，缺失返回兜底值。 */
    fun bool(tag: Int, fallback: Boolean): Boolean = findBool(tag) ?: fallback

    private fun readU16At(index: Int): Int =
        ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)

    private fun readU32At(index: Int): Long =
        ((data[index].toLong() and 0xFF) shl 24) or
            ((data[index + 1].toLong() and 0xFF) shl 16) or
            ((data[index + 2].toLong() and 0xFF) shl 8) or
            (data[index + 3].toLong() and 0xFF)

    companion object {
        /** 便于日志的 tag 展示。 */
        fun nameOf(tag: Int): String = "0x" + tag.toString(16).uppercase().padStart(4, '0')
    }
}

/** TLV 列表与字节数组互转的便捷入口。 */
object TlvCodec {

    /** 编码若干个 TLV。 */
    fun encode(vararg tlvs: Tlv): ByteArray {
        val writer = TlvWriter()
        for (tlv in tlvs) writer.putTlv(tlv)
        return writer.toByteArray()
    }

    /** 编码 TLV 集合。 */
    fun encode(tlvs: Iterable<Tlv>): ByteArray {
        val writer = TlvWriter()
        for (tlv in tlvs) writer.putTlv(tlv)
        return writer.toByteArray()
    }

    /** 解码整段 payload 为 TLV 列表。 */
    fun decode(bytes: ByteArray): List<Tlv> = TlvReader(bytes).readAll()

    /** 解码整段 payload 为 tag 索引。 */
    fun decodeToMap(bytes: ByteArray): Map<Int, Tlv> = TlvReader(bytes).fields()
}
