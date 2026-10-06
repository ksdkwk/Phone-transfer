package com.phonetransfer.app.core.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

/** 单帧：20B 明文帧头 + payload（加密时为密文 + 16B Tag）。 */
data class Frame(val header: FrameHeader, val payload: ByteArray) {

    /** payload 字节数，应等于 header.payloadLen。 */
    val payloadLen: Int get() = payload.size

    /** 本帧 payload 是否加密。 */
    val encrypted: Boolean get() = header.encrypted

    override fun equals(other: Any?): Boolean =
        this === other || (other is Frame && header == other.header && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()

    override fun toString(): String =
        "Frame(msgType=0x" + header.msgType.toString(16) + ", sessionId=" + header.sessionId +
            ", msgSeq=" + header.msgSeq + ", payloadLen=" + payload.size + ")"
}

/**
 * 帧读写（规范 §5）。
 *
 * 写帧时 payloadLen 必须与 payload 长度一致；读帧时校验 magic、版本与 payloadLen 上限。
 */
object FrameCodec {

    /** 用 payload 长度构造帧，payloadLen 自动填充。 */
    fun frame(msgType: Int, flags: Int, sessionId: Int, msgSeq: Int, payload: ByteArray): Frame =
        Frame(
            FrameHeader(
                msgType = msgType,
                flags = flags,
                sessionId = sessionId,
                payloadLen = payload.size,
                msgSeq = msgSeq,
            ),
            payload,
        )

    /** 编码整帧为字节数组。 */
    fun encode(frame: Frame): ByteArray {
        val out = ByteArrayOutputStream(FrameHeader.SIZE + frame.payload.size)
        writeFrame(out, frame)
        return out.toByteArray()
    }

    /** 解码整帧，要求字节数恰好等于帧头 + payloadLen。 */
    fun decode(bytes: ByteArray): Frame {
        val header = FrameHeader.decode(bytes)
        val expected = FrameHeader.SIZE + header.payloadLen
        if (bytes.size != expected) {
            throw ProtocolException(
                ErrorCode.MALFORMED_TLV,
                "帧长度与 payloadLen 不一致: 实际 " + bytes.size + ", 期望 " + expected,
            )
        }
        return Frame(header, Arrays.copyOfRange(bytes, FrameHeader.SIZE, bytes.size))
    }

    /** 写入整帧。 */
    fun writeFrame(out: OutputStream, frame: Frame) = writeFrame(out, frame.header, frame.payload)

    /** 写入帧头与 payload，二者长度不一致时抛 MALFORMED_TLV。 */
    fun writeFrame(out: OutputStream, header: FrameHeader, payload: ByteArray) {
        if (header.payloadLen != payload.size) {
            throw ProtocolException(
                ErrorCode.MALFORMED_TLV,
                "payloadLen=" + header.payloadLen + " 与 payload 长度 " + payload.size + " 不一致",
            )
        }
        header.validate()
        out.write(header.encode())
        out.write(payload)
        out.flush()
    }

    /** 读一整帧；读到干净 EOF 抛 [EOFException]。 */
    fun readFrame(input: InputStream): Frame {
        val header = FrameHeader.readFrom(input)
        return Frame(header, readFully(input, header.payloadLen))
    }

    /** 读一整帧；干净 EOF 返回 null，半截帧抛 [EOFException]。 */
    fun readFrameOrNull(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val headerBytes = ByteArray(FrameHeader.SIZE)
        headerBytes[0] = first.toByte()
        readFullyInto(input, headerBytes, 1, FrameHeader.SIZE - 1)
        val header = FrameHeader.decode(headerBytes)
        return Frame(header, readFully(input, header.payloadLen))
    }

    /** 读满指定字节数。 */
    fun readFully(input: InputStream, length: Int): ByteArray {
        if (length < 0) throw ProtocolException.frameTooLarge(length)
        val data = ByteArray(length)
        readFullyInto(input, data, 0, length)
        return data
    }

    private fun readFullyInto(input: InputStream, target: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(target, offset + read, length - read)
            if (count < 0) throw EOFException("期望读取 " + length + " 字节, 实际 " + read)
            read += count
        }
    }
}
