package com.phonetransfer.app.core.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 20 字节固定帧头（规范 §5）。
 *
 * 布局：magic(4) / version(1) / msgType(1) / flags(1) / reserved(1) / sessionId(4) / payloadLen(4) / msgSeq(4)。
 * 帧头明文传输，并在加密层作为 AES-GCM 的 AAD 参与完整性校验。
 */
data class FrameHeader(
    val magic: Int = ProtocolConstants.MAGIC,
    val version: Int = ProtocolConstants.PROTOCOL_VERSION,
    val msgType: Int,
    val flags: Int,
    val reserved: Int = 0,
    val sessionId: Int,
    val payloadLen: Int,
    val msgSeq: Int,
) {

    /** payload 是否已加密。 */
    val encrypted: Boolean get() = FrameFlags.isEncrypted(flags)

    /** payload 明文是否经 zstd 压缩。 */
    val compressed: Boolean get() = FrameFlags.isCompressed(flags)

    /** 是否要求显式 ACK。 */
    val requiresAck: Boolean get() = FrameFlags.requiresAck(flags)

    /** 是否分片最后一帧。 */
    val lastFragment: Boolean get() = FrameFlags.isLastFragment(flags)

    /** 解密后明文字节数：加密时 payloadLen 含 16B Tag。 */
    val plaintextLength: Int get() = if (encrypted) payloadLen - ProtocolConstants.GCM_TAG_SIZE else payloadLen

    /** 解析消息类型，未知返回 null。 */
    fun messageType(): MessageType? = MessageType.fromCode(msgType)

    /** 校验 magic、版本、payloadLen 上限与保留位，失败抛对应错误码的 [ProtocolException]。 */
    fun validate(expectedVersion: Int = ProtocolConstants.PROTOCOL_VERSION) {
        if (magic != ProtocolConstants.MAGIC) {
            throw ProtocolException(ErrorCode.MALFORMED_TLV, "帧 magic 非法: 0x" + magic.toString(16))
        }
        if (version != expectedVersion) {
            throw ProtocolException.versionMismatch(expectedVersion, version)
        }
        if (!ProtocolConstants.isPayloadLenAllowed(payloadLen)) {
            throw ProtocolException.frameTooLarge(payloadLen)
        }
        if (!FrameFlags.isValid(flags)) {
            throw ProtocolException(ErrorCode.MALFORMED_TLV, "flags 保留位必须为 0: 0x" + flags.toString(16))
        }
    }

    /** 编码为 20 字节大端序列。 */
    fun encode(): ByteArray {
        require(version in 0..0xFF) { "version 越界: $version" }
        require(msgType in 0..0xFF) { "msgType 越界: $msgType" }
        require(flags in 0..0xFF) { "flags 越界: $flags" }
        require(reserved in 0..0xFF) { "reserved 越界: $reserved" }
        if (!ProtocolConstants.isPayloadLenAllowed(payloadLen)) {
            throw ProtocolException.frameTooLarge(payloadLen)
        }
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(magic)
        buffer.put(version.toByte())
        buffer.put(msgType.toByte())
        buffer.put(flags.toByte())
        buffer.put(reserved.toByte())
        buffer.putInt(sessionId)
        buffer.putInt(payloadLen)
        buffer.putInt(msgSeq)
        return buffer.array()
    }

    /** 写入外部流。 */
    fun writeTo(out: OutputStream) {
        out.write(encode())
    }

    companion object {
        /** 固定帧头长度。 */
        const val SIZE: Int = ProtocolConstants.FRAME_HEADER_SIZE

        /** 从字节数组解码并校验；期望版本可显式指定。 */
        fun decode(
            bytes: ByteArray,
            offset: Int = 0,
            expectedVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
        ): FrameHeader {
            if (bytes.size - offset < SIZE) {
                throw ProtocolException(ErrorCode.MALFORMED_TLV, "帧头长度不足: " + (bytes.size - offset))
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset)
            val header = FrameHeader(
                magic = buffer.getInt(),
                version = buffer.get().toInt() and 0xFF,
                msgType = buffer.get().toInt() and 0xFF,
                flags = buffer.get().toInt() and 0xFF,
                reserved = buffer.get().toInt() and 0xFF,
                sessionId = buffer.getInt(),
                payloadLen = buffer.getInt(),
                msgSeq = buffer.getInt(),
            )
            header.validate(expectedVersion)
            return header
        }

        /** 从 ByteBuffer 当前位置解码并校验。 */
        fun decode(buffer: ByteBuffer): FrameHeader {
            if (buffer.remaining() < SIZE) {
                throw ProtocolException(ErrorCode.MALFORMED_TLV, "帧头长度不足: " + buffer.remaining())
            }
            val header = FrameHeader(
                magic = buffer.getInt(),
                version = buffer.get().toInt() and 0xFF,
                msgType = buffer.get().toInt() and 0xFF,
                flags = buffer.get().toInt() and 0xFF,
                reserved = buffer.get().toInt() and 0xFF,
                sessionId = buffer.getInt(),
                payloadLen = buffer.getInt(),
                msgSeq = buffer.getInt(),
            )
            header.validate()
            return header
        }

        /** 从流中读满 20 字节并解码。 */
        fun readFrom(input: InputStream): FrameHeader = decode(FrameCodec.readFully(input, SIZE))
    }
}
