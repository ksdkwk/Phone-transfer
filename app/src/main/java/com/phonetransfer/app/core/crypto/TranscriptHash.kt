package com.phonetransfer.app.core.crypto

import com.phonetransfer.app.core.protocol.FrameHeader
import java.security.MessageDigest

/**
 * 增量转录哈希（规范 §10）。
 *
 * transcriptHash = SHA256(frameHeader_1 || payload_1 || frameHeader_2 || payload_2 || ...)，
 * 覆盖 HELLO 至 KEY_EXCHANGE 的全部帧（含帧头），用于防注入与防降级。
 */
class TranscriptHash {

    private val digest = MessageDigest.getInstance(ALGORITHM)

    /** 已喂入的字节总数。 */
    var byteCount: Long = 0L
        private set

    /** 追加任意字节。 */
    fun update(bytes: ByteArray): TranscriptHash {
        digest.update(bytes)
        byteCount += bytes.size
        return this
    }

    /** 追加「帧头 + payload」。 */
    fun update(frameHeader: ByteArray, payload: ByteArray): TranscriptHash {
        update(frameHeader)
        update(payload)
        return this
    }

    /** 追加一帧（帧头编码 + payload）。 */
    fun update(header: FrameHeader, payload: ByteArray): TranscriptHash = update(header.encode(), payload)

    /** 计算摘要；调用后哈希状态复位，可继续 update 形成新的一段转录。 */
    fun digest(): ByteArray {
        byteCount = 0L
        return digest.digest()
    }

    /** 摘要的十六进制文本。 */
    fun digestHex(): String {
        val bytes = digest()
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value < 16) builder.append('0')
            builder.append(value.toString(16))
        }
        return builder.toString()
    }

    /** 复位哈希状态。 */
    fun reset() {
        digest.reset()
        byteCount = 0L
    }

    companion object {
        /** 摘要算法。 */
        const val ALGORITHM: String = "SHA-256"

        /** 摘要长度。 */
        const val HASH_SIZE: Int = 32

        /** 一次性计算若干段的 SHA-256。 */
        fun hash(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance(ALGORITHM)
            for (part in parts) md.update(part)
            return md.digest()
        }

        /** 一次性计算「帧头 + payload」的转录哈希。 */
        fun hashFrame(header: FrameHeader, payload: ByteArray): ByteArray =
            hash(header.encode(), payload)
    }
}
