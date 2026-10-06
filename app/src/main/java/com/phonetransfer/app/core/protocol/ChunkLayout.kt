package com.phonetransfer.app.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays

/**
 * CHUNK 明文固定头，共 60 字节（规范 §7.9）。
 *
 * 布局（规范 §7.9 偏移表）：itemId u64 @0 / chunkSeq u32 @8 / **保留 4B @12** / offset u64 @16 /
 * dataLen u32 @24 / chunkSha256 32B @28，共 60 字节。
 * CHUNK 不使用 TLV，避免大块内存拷贝。
 */
data class ChunkHeader(
    val itemId: Long,
    val chunkSeq: Int,
    val offset: Long,
    val dataLen: Int,
    val chunkSha256: ByteArray,
) {

    /** 编码为 60 字节大端固定头。 */
    fun encode(): ByteArray {
        require(chunkSha256.size == ProtocolConstants.SHA256_SIZE) {
            "chunkSha256 必须为 32 字节, 实际 " + chunkSha256.size
        }
        require(dataLen >= 0) { "dataLen 非法: $dataLen" }
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(itemId)      // 0..7
        buffer.putInt(chunkSeq)     // 8..11
        buffer.putInt(RESERVED)     // 12..15 保留字节，规范 §7.9 的偏移表从 16 开始
        buffer.putLong(offset)      // 16..23
        buffer.putInt(dataLen)      // 24..27
        buffer.put(chunkSha256)     // 28..59
        return buffer.array()
    }

    /** 该块的帧 payloadLen = 60 + dataLen + 16。 */
    fun payloadLength(): Int = ChunkLayout.payloadLength(dataLen)

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is ChunkHeader &&
                itemId == other.itemId &&
                chunkSeq == other.chunkSeq &&
                offset == other.offset &&
                dataLen == other.dataLen &&
                chunkSha256.contentEquals(other.chunkSha256)
            )

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + chunkSeq
        result = 31 * result + offset.hashCode()
        result = 31 * result + dataLen
        result = 31 * result + chunkSha256.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ChunkHeader(itemId=" + itemId + ", chunkSeq=" + chunkSeq + ", offset=" + offset + ", dataLen=" + dataLen + ")"

    companion object {
        /** 固定头长度。 */
        const val SIZE: Int = ProtocolConstants.CHUNK_FIXED_HEADER_SIZE

        /** 保留字节（12..15）的取值，规范未定义，实现置 0。 */
        private const val RESERVED: Int = 0

        /** 从字节数组解析固定头，长度不足抛 MALFORMED_TLV。 */
        fun decode(bytes: ByteArray, byteOffset: Int = 0): ChunkHeader {
            if (bytes.size - byteOffset < SIZE) {
                throw ProtocolException(ErrorCode.MALFORMED_TLV, "CHUNK 固定头长度不足: " + (bytes.size - byteOffset))
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buffer.position(byteOffset)
            val itemId = buffer.getLong()   // 0..7
            val chunkSeq = buffer.getInt()  // 8..11
            buffer.getInt()                 // 12..15 保留字节，必须忽略
            val offset = buffer.getLong()   // 16..23
            val dataLen = buffer.getInt()   // 24..27
            val sha = ByteArray(ProtocolConstants.SHA256_SIZE)
            buffer.get(sha)                 // 28..59
            if (dataLen < 0) {
                throw ProtocolException(ErrorCode.MALFORMED_TLV, "CHUNK dataLen 非法: $dataLen")
            }
            return ChunkHeader(itemId, chunkSeq, offset, dataLen, sha)
        }
    }
}

/** CHUNK 明文：60 字节固定头 + data。 */
data class Chunk(val header: ChunkHeader, val data: ByteArray) {

    val itemId: Long get() = header.itemId

    val chunkSeq: Int get() = header.chunkSeq

    val offset: Long get() = header.offset

    /** 实际数据长度。 */
    val dataLen: Int get() = data.size

    /** 编码为明文（固定头 + 数据）。 */
    fun encode(): ByteArray {
        require(header.dataLen == data.size) {
            "dataLen=" + header.dataLen + " 与数据长度 " + data.size + " 不一致"
        }
        val buffer = ByteBuffer.allocate(ChunkHeader.SIZE + data.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header.encode())
        buffer.put(data)
        return buffer.array()
    }

    /** 重新计算数据摘要并与固定头中的 chunkSha256 比对。 */
    fun sha256Matches(): Boolean = header.chunkSha256.contentEquals(ChunkLayout.sha256Of(data))

    /** 数据块的实际 SHA-256。 */
    fun actualSha256(): ByteArray = ChunkLayout.sha256Of(data)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Chunk && header == other.header && data.contentEquals(other.data))

    override fun hashCode(): Int = 31 * header.hashCode() + data.contentHashCode()

    override fun toString(): String = "Chunk(" + header + ", dataLen=" + data.size + ")"

    companion object {
        /** 由数据构造：自动计算 sha256 并填充固定头。 */
        fun of(itemId: Long, chunkSeq: Int, offset: Long, data: ByteArray): Chunk =
            Chunk(ChunkHeader(itemId, chunkSeq, offset, data.size, ChunkLayout.sha256Of(data)), data)

        /** 解析明文，长度必须与 dataLen 严格自洽。 */
        fun decode(plain: ByteArray): Chunk {
            val header = ChunkHeader.decode(plain)
            val actualDataLen = plain.size - ChunkHeader.SIZE
            if (actualDataLen != header.dataLen) {
                throw ProtocolException(
                    ErrorCode.MALFORMED_TLV,
                    "CHUNK 明文长度与 dataLen 不一致: 实际 " + actualDataLen + ", dataLen=" + header.dataLen,
                )
            }
            return Chunk(header, Arrays.copyOfRange(plain, ChunkHeader.SIZE, plain.size))
        }
    }
}

/** CHUNK 布局常量与摘要工具。 */
object ChunkLayout {

    /** 固定头长度 60 字节。 */
    const val FIXED_HEADER_SIZE: Int = ProtocolConstants.CHUNK_FIXED_HEADER_SIZE

    private const val ALGORITHM = "SHA-256"

    /** 给定 payloadLen 上限下允许的最大 dataLen。 */
    fun maxDataLen(maxPayloadLen: Int = ProtocolConstants.MAX_FRAME_PAYLOAD): Int =
        maxPayloadLen - FIXED_HEADER_SIZE - ProtocolConstants.GCM_TAG_SIZE

    /** 帧内 payloadLen = 60 + dataLen + 16；超上限抛 FRAME_TOO_LARGE。 */
    fun payloadLength(dataLen: Int): Int {
        require(dataLen >= 0) { "dataLen 非法: $dataLen" }
        val payloadLen = FIXED_HEADER_SIZE + dataLen + ProtocolConstants.GCM_TAG_SIZE
        if (payloadLen > ProtocolConstants.MAX_FRAME_PAYLOAD) throw ProtocolException.frameTooLarge(payloadLen)
        return payloadLen
    }

    /** 与 [payloadLength] 等价，语义上强调含 GCM Tag。 */
    fun encryptedPayloadLength(dataLen: Int, tagSize: Int = ProtocolConstants.GCM_TAG_SIZE): Int =
        FIXED_HEADER_SIZE + dataLen + tagSize

    /** 计算 SHA-256 摘要。 */
    fun sha256Of(bytes: ByteArray): ByteArray = MessageDigest.getInstance(ALGORITHM).digest(bytes)
}
