package com.phonetransfer.app.core.crypto

import com.phonetransfer.app.core.protocol.ErrorCode
import com.phonetransfer.app.core.protocol.Frame
import com.phonetransfer.app.core.protocol.FrameFlags
import com.phonetransfer.app.core.protocol.FrameHeader
import com.phonetransfer.app.core.protocol.ProtocolConstants
import com.phonetransfer.app.core.protocol.ProtocolException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密核心（规范 §6 与 §10）。
 *
 * 提供 ECDH-P256 临时密钥与压缩公钥编解码、HKDF-SHA256、AES-256-GCM 帧加解密，
 * 以及 k_c2s / k_s2c / SAS 的派生。全部基于 java.* 与 javax.crypto.*，可在 JVM 上单测。
 */
class CryptoEngine(private val random: SecureRandom = SecureRandom()) {

    // ---------- 随机数 ----------

    /** 使用平台安全随机数生成随机字节。 */
    fun randomBytes(length: Int): ByteArray {
        require(length > 0) { "长度必须为正: $length" }
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }

    // ---------- ECDH-P256 ----------

    /** 生成 P-256 临时密钥对。 */
    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE_NAME), random)
        return generator.generateKeyPair()
    }

    /** 获取 secp256r1 曲线参数。 */
    fun curveParameters(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance(EC_ALGORITHM)
        parameters.init(ECGenParameterSpec(CURVE_NAME))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    /** 编码为 33 字节压缩公钥：0x02/0x03 前缀 + 32 字节 X。 */
    fun encodePublicKey(publicKey: ECPublicKey): ByteArray {
        val point = publicKey.w
        val prefix = if (point.affineY.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix.toByte()) + toFixed(point.affineX, FIELD_SIZE)
    }

    /** 编码为 65 字节非压缩公钥：0x04 + X(32) + Y(32)。 */
    fun encodePublicKeyUncompressed(publicKey: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + toFixed(publicKey.w.affineX, FIELD_SIZE) + toFixed(publicKey.w.affineY, FIELD_SIZE)

    /** 解码 33 字节压缩或 65 字节非压缩 P-256 公钥。 */
    fun decodePublicKey(encoded: ByteArray): ECPublicKey {
        if (encoded.isEmpty()) throw ProtocolException(ErrorCode.MALFORMED_TLV, "EC 公钥为空")
        val first = encoded[0].toInt() and 0xFF
        val params = curveParameters()
        val point = when {
            encoded.size == COMPRESSED_PUBKEY_SIZE && first in 0x02..0x03 -> {
                val x = BigInteger(1, encoded.copyOfRange(1, COMPRESSED_PUBKEY_SIZE))
                ECPoint(x, decompressY(x, (first and 1) == 1, params))
            }
            encoded.size == UNCOMPRESSED_PUBKEY_SIZE && first == 0x04 -> ECPoint(
                BigInteger(1, encoded.copyOfRange(1, FIELD_SIZE + 1)),
                BigInteger(1, encoded.copyOfRange(FIELD_SIZE + 1, UNCOMPRESSED_PUBKEY_SIZE)),
            )
            else -> throw ProtocolException(
                ErrorCode.MALFORMED_TLV,
                "非法 EC 公钥编码: 长度 " + encoded.size + ", 首字节 0x" + first.toString(16),
            )
        }
        return try {
            KeyFactory.getInstance(EC_ALGORITHM).generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
        } catch (e: GeneralSecurityException) {
            throw ProtocolException(ErrorCode.MALFORMED_TLV, "无法解析 EC 公钥", e)
        }
    }

    /** 计算 ECDH 共享密钥（32 字节 P-256 x 坐标）。 */
    fun ecdh(privateKey: PrivateKey, peerPublicKey: ECPublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val secret = agreement.generateSecret()
        if (secret.size == SHARED_SECRET_SIZE) return secret
        return toFixed(BigInteger(1, secret), SHARED_SECRET_SIZE)
    }

    /** 以对端公钥编码计算共享密钥。 */
    fun ecdh(privateKey: PrivateKey, peerPublicKeyEncoded: ByteArray): ByteArray =
        ecdh(privateKey, decodePublicKey(peerPublicKeyEncoded))

    /** 以本端密钥对与对端公钥编码计算共享密钥。 */
    fun sharedSecret(own: KeyPair, peerPublicKeyEncoded: ByteArray): ByteArray =
        ecdh(own.private, decodePublicKey(peerPublicKeyEncoded))

    // ---------- HKDF-SHA256 ----------

    /** HKDF-Extract：PRK = HMAC-SHA256(salt, ikm)；salt 为空时使用 32 字节 0。 */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val key = if (salt.isEmpty()) ByteArray(HASH_SIZE) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand：OKM = T(1) | T(2) | ...，长度上限 255 * 32。 */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length >= 0 && length <= 255 * HASH_SIZE) { "HKDF 输出长度非法: $length" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var position = 0
        var counter = 1
        while (position < length) {
            mac.reset()
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val copyLength = minOf(block.size, length - position)
            System.arraycopy(block, 0, out, position, copyLength)
            position += copyLength
            counter += 1
        }
        return out
    }

    /** HKDF 一步式：Extract 后立即 Expand。 */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hkdfExtract(salt, ikm), info, length)

    // ---------- 会话密钥与 SAS 派生 ----------

    /**
     * 按 §10 派生会话密钥。
     *
     * salt = nonceC || nonceS；k_c2s = Expand(prk, "pt/v1/c2s" || sessionId, 32)；k_s2c 同理；SAS 见 [sasFromPrk]。
     */
    fun deriveSessionKeys(sharedSecret: ByteArray, nonceC: ByteArray, nonceS: ByteArray, sessionId: Int): SessionKeys {
        require(nonceC.size == ProtocolConstants.HANDSHAKE_NONCE_SIZE) { "nonceC 必须为 16 字节, 实际 " + nonceC.size }
        require(nonceS.size == ProtocolConstants.HANDSHAKE_NONCE_SIZE) { "nonceS 必须为 16 字节, 实际 " + nonceS.size }
        val prk = hkdfExtract(nonceC + nonceS, sharedSecret)
        val sessionBytes = be32(sessionId)
        val c2s = hkdfExpand(prk, ProtocolConstants.KDF_INFO_C2S.toByteArray(Charsets.UTF_8) + sessionBytes, KEY_SIZE)
        val s2c = hkdfExpand(prk, ProtocolConstants.KDF_INFO_S2C.toByteArray(Charsets.UTF_8) + sessionBytes, KEY_SIZE)
        return SessionKeys(c2s, s2c, sasFromPrk(prk))
    }

    /** 仅派生 SAS，便于 UI 提前展示与单测。 */
    fun deriveSas(sharedSecret: ByteArray, nonceC: ByteArray, nonceS: ByteArray): Int =
        sasFromPrk(hkdfExtract(nonceC + nonceS, sharedSecret))

    /** SAS = be32(HKDF-Expand(prk, "pt/v1/sas", 4)) mod 10000。 */
    fun sasFromPrk(prk: ByteArray): Int {
        val bytes = hkdfExpand(prk, ProtocolConstants.KDF_INFO_SAS.toByteArray(Charsets.UTF_8), 4)
        val raw = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
        return (raw % SAS_MODULUS).toInt()
    }

    // ---------- AES-256-GCM ----------

    /** 12 字节 Nonce = be32(sessionId) || be64(msgSeq)，每帧唯一。 */
    fun nonce(sessionId: Int, msgSeq: Int): ByteArray =
        ByteBuffer.allocate(NONCE_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(sessionId)
            .putLong(msgSeq.toLong() and 0xFFFFFFFFL)
            .array()

    /** AES-256-GCM 加密，返回密文 + 16B Tag。 */
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        requireKey(key)
        require(nonce.size == NONCE_SIZE) { "GCM Nonce 必须为 12 字节, 实际 " + nonce.size }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(TAG_SIZE_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** AES-256-GCM 解密并校验 Tag，失败抛 DECRYPT_FAILED。 */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        requireKey(key)
        require(nonce.size == NONCE_SIZE) { "GCM Nonce 必须为 12 字节, 实际 " + nonce.size }
        if (ciphertext.size < TAG_SIZE_BYTES) throw ProtocolException.decryptFailed()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(TAG_SIZE_BITS, nonce))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw ProtocolException.decryptFailed(e)
        }
    }

    /** 用会话方向密钥与帧头加解密 payload（AAD = 20 字节帧头）。 */
    fun encryptPayload(key: ByteArray, header: FrameHeader, plaintext: ByteArray): ByteArray =
        encrypt(key, nonce(header.sessionId, header.msgSeq), header.encode(), plaintext)

    /** 用会话方向密钥与帧头解密 payload。 */
    fun decryptPayload(key: ByteArray, header: FrameHeader, ciphertext: ByteArray): ByteArray =
        decrypt(key, nonce(header.sessionId, header.msgSeq), header.encode(), ciphertext)

    /** 按 §6 直接封装一帧：payloadLen = 明文 + 16B Tag，flags 自动置 ENCRYPTED。 */
    fun encryptFrame(
        key: ByteArray,
        msgType: Int,
        flags: Int,
        sessionId: Int,
        msgSeq: Int,
        plaintext: ByteArray,
    ): Frame {
        val payloadLen = plaintext.size + ProtocolConstants.GCM_TAG_SIZE
        if (!ProtocolConstants.isPayloadLenAllowed(payloadLen)) throw ProtocolException.frameTooLarge(payloadLen)
        val header = FrameHeader(
            msgType = msgType,
            flags = flags or FrameFlags.ENCRYPTED,
            sessionId = sessionId,
            payloadLen = payloadLen,
            msgSeq = msgSeq,
        )
        return Frame(header, encrypt(key, nonce(sessionId, msgSeq), header.encode(), plaintext))
    }

    /** 解密一帧，AAD 使用收到的原始帧头。 */
    fun decryptFrame(key: ByteArray, frame: Frame): ByteArray =
        decrypt(key, nonce(frame.header.sessionId, frame.header.msgSeq), frame.header.encode(), frame.payload)

    // ---------- 内部工具 ----------

    private fun requireKey(key: ByteArray) {
        require(key.size == KEY_SIZE) { "AES-256 密钥必须为 32 字节, 实际 " + key.size }
    }

    /** 压缩点还原 Y：y^2 = x^3 + a*x + b (mod p)，P-256 的 p 满足 p = 3 (mod 4)。 */
    private fun decompressY(x: BigInteger, odd: Boolean, params: ECParameterSpec): BigInteger {
        val field = params.curve.field
        if (field !is ECFieldFp) throw ProtocolException(ErrorCode.MALFORMED_TLV, "P-256 曲线域类型非法")
        val p = field.p
        val rhs = x.modPow(THREE, p).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        if (y.multiply(y).mod(p) != rhs) throw ProtocolException(ErrorCode.MALFORMED_TLV, "压缩公钥的 X 坐标不在 P-256 上")
        if (y.testBit(0) != odd) y = p.subtract(y)
        return y
    }

    /** 把 BigInteger 右对齐填入定长数组（大端）。 */
    private fun toFixed(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        val copyLength = minOf(raw.size, length)
        System.arraycopy(raw, raw.size - copyLength, out, length - copyLength, copyLength)
        return out
    }

    companion object {
        /** NIST P-256 曲线名。 */
        const val CURVE_NAME: String = "secp256r1"

        /** EC 算法名。 */
        const val EC_ALGORITHM: String = "EC"

        /** ECDH 算法名。 */
        const val ECDH_ALGORITHM: String = "ECDH"

        /** HKDF 使用的 HMAC 算法。 */
        const val HMAC_ALGORITHM: String = "HmacSHA256"

        /** GCM 变换。 */
        const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"

        /** AES 算法名。 */
        const val AES_ALGORITHM: String = "AES"

        /** SHA-256 摘要长度。 */
        const val HASH_SIZE: Int = 32

        /** AES-256 密钥长度。 */
        const val KEY_SIZE: Int = 32

        /** GCM Nonce 长度。 */
        const val NONCE_SIZE: Int = 12

        /** GCM Tag 字节数。 */
        const val TAG_SIZE_BYTES: Int = 16

        /** GCM Tag 位数。 */
        const val TAG_SIZE_BITS: Int = 128

        /** 压缩公钥长度。 */
        const val COMPRESSED_PUBKEY_SIZE: Int = 33

        /** 非压缩公钥长度。 */
        const val UNCOMPRESSED_PUBKEY_SIZE: Int = 65

        /** 共享密钥长度。 */
        const val SHARED_SECRET_SIZE: Int = 32

        /** P-256 坐标长度。 */
        const val FIELD_SIZE: Int = 32

        /** SAS 模数。 */
        const val SAS_MODULUS: Int = 10000

        private val THREE: BigInteger = BigInteger.valueOf(3)

        /** be32 编码。 */
        fun be32(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

        /** be64 编码。 */
        fun be64(value: Long): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

        /** SHA-256 摘要。 */
        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        /** 4 字节 SAS 结果转 0 - 9999。 */
        fun sasBytesToSas(bytes: ByteArray): Int {
            require(bytes.size == 4) { "SAS 字节必须为 4, 实际 " + bytes.size }
            val raw = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
            return (raw % SAS_MODULUS).toInt()
        }
    }
}
