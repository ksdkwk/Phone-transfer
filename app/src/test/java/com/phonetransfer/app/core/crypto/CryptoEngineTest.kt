package com.phonetransfer.app.core.crypto

import com.phonetransfer.app.core.protocol.ErrorCode
import com.phonetransfer.app.core.protocol.FrameFlags
import com.phonetransfer.app.core.protocol.FrameHeader
import com.phonetransfer.app.core.protocol.MessageType
import com.phonetransfer.app.core.protocol.ProtocolException
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 加密核心测试：ECDH、HKDF 向量、密钥派生、SAS、AES-GCM 与转录哈希。 */
class CryptoEngineTest {

    private val engine = CryptoEngine()

    private fun eq(expected: Int, actual: Int) = assertEquals(expected.toLong(), actual.toLong())

    private fun eqL(expected: Long, actual: Long) = assertEquals(expected, actual)

    private fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value < 16) builder.append('0')
            builder.append(value.toString(16))
        }
        return builder.toString()
    }

    private fun fromHex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun expectFailure(expected: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("期望抛出 " + expected.name)
        } catch (e: ProtocolException) {
            assertEquals(expected, e.errorCode)
        }
    }

    private fun key(): ByteArray = ByteArray(32) { (it * 7 + 1).toByte() }

    @Test
    fun ecdhAgreesOnBothSidesThroughCompressedKeys() {
        val alice = engine.generateKeyPair()
        val bob = engine.generateKeyPair()
        val alicePub = engine.encodePublicKey(alice.public as ECPublicKey)
        val bobPub = engine.encodePublicKey(bob.public as ECPublicKey)
        eq(33, alicePub.size)
        val prefix = alicePub[0].toInt() and 0xFF
        assertTrue(prefix == 0x02 || prefix == 0x03)

        val sharedAlice = engine.sharedSecret(alice, bobPub)
        val sharedBob = engine.sharedSecret(bob, alicePub)
        eq(32, sharedAlice.size)
        assertArrayEquals(sharedAlice, sharedBob)

        val decodedBob = engine.decodePublicKey(bobPub)
        assertArrayEquals(bobPub, engine.encodePublicKey(decodedBob))
        assertArrayEquals(sharedAlice, engine.ecdh(alice.private, decodedBob))
        assertArrayEquals(sharedAlice, engine.ecdh(alice.private, bobPub))
    }

    @Test
    fun uncompressedEncodingRoundTripsAndInvalidKeysAreRejected() {
        val pair = engine.generateKeyPair()
        val uncompressed = engine.encodePublicKeyUncompressed(pair.public as ECPublicKey)
        eq(65, uncompressed.size)
        eq(0x04, uncompressed[0].toInt() and 0xFF)
        val decoded = engine.decodePublicKey(uncompressed)
        assertArrayEquals(engine.encodePublicKey(pair.public as ECPublicKey), engine.encodePublicKey(decoded))

        expectFailure(ErrorCode.MALFORMED_TLV) { engine.decodePublicKey(ByteArray(0)) }
        expectFailure(ErrorCode.MALFORMED_TLV) { engine.decodePublicKey(ByteArray(33)) }
        expectFailure(ErrorCode.MALFORMED_TLV) { engine.decodePublicKey(ByteArray(10)) }
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = fromHex("000102030405060708090a0b0c")
        val info = fromHex("f0f1f2f3f4f5f6f7f8f9")

        val prk = engine.hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk))

        val okm = engine.hkdfExpand(prk, info, 42)
        eq(42, okm.size)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )

        assertEquals(hex(prk), hex(engine.hkdfExtract(salt, ikm)))
        assertEquals(hex(okm), hex(engine.hkdf(ikm, salt, info, 42)))
        assertEquals(hex(engine.hkdfExtract(ByteArray(0), ikm)), hex(engine.hkdfExtract(ByteArray(32), ikm)))
    }

    @Test
    fun sessionKeyDerivationIsDeterministicAndDirectional() {
        val shared = ByteArray(32) { it.toByte() }
        val nonceC = ByteArray(16) { 0x11 }
        val nonceS = ByteArray(16) { 0x22 }
        val sessionId = 0x009F21C3
        val first = engine.deriveSessionKeys(shared, nonceC, nonceS, sessionId)
        val second = engine.deriveSessionKeys(shared, nonceC, nonceS, sessionId)

        assertArrayEquals(first.kC2S, second.kC2S)
        assertArrayEquals(first.kS2C, second.kS2C)
        eq(first.sas, second.sas)
        eq(32, first.kC2S.size)
        eq(32, first.kS2C.size)
        assertFalse(first.kC2S.contentEquals(first.kS2C))
        assertTrue(first.sas >= 0 && first.sas < 10000)
        eq(4, first.sasDigits.length)

        val otherSession = engine.deriveSessionKeys(shared, nonceC, nonceS, 0x009F21C4)
        assertFalse(first.kC2S.contentEquals(otherSession.kC2S))
        assertFalse(first.kS2C.contentEquals(otherSession.kS2C))

        val swappedNonce = engine.deriveSessionKeys(shared, nonceS, nonceC, sessionId)
        assertFalse(first.kC2S.contentEquals(swappedNonce.kC2S))

        val otherShared = engine.deriveSessionKeys(ByteArray(32) { (it + 1).toByte() }, nonceC, nonceS, sessionId)
        assertFalse(first.kC2S.contentEquals(otherShared.kC2S))
    }

    @Test
    fun sasIsAlwaysInFourDigitRange() {
        repeat(24) {
            val alice = engine.generateKeyPair()
            val bob = engine.generateKeyPair()
            val shared = engine.sharedSecret(alice, engine.encodePublicKey(bob.public as ECPublicKey))
            val sas = engine.deriveSas(shared, engine.randomBytes(16), engine.randomBytes(16))
            assertTrue(sas in 0..9999)
            eq(4, sas.toString().padStart(4, '0').length)
        }
    }

    @Test
    fun nonceIsTwelveBytesAndVariesWithSeqAndSession() {
        val first = engine.nonce(0x01020304, 1)
        eq(12, first.size)
        assertFalse(first.contentEquals(engine.nonce(0x01020304, 2)))
        assertFalse(first.contentEquals(engine.nonce(0x01020305, 1)))
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), first.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1), first.copyOfRange(4, 12))
        assertEquals("010203040000000000000001", hex(first))
    }

    @Test
    fun gcmFrameRoundTripAndTamperDetection() {
        val key = key()
        val plaintext = "迁移 payload".toByteArray(Charsets.UTF_8)
        val frame = engine.encryptFrame(key, MessageType.SAS_NOTIFY.code, FrameFlags.NONE, 0x11223344, 1, plaintext)

        assertTrue(frame.header.encrypted)
        eq(plaintext.size + 16, frame.header.payloadLen)
        eq(plaintext.size + 16, frame.payload.size)
        assertArrayEquals(plaintext, engine.decryptFrame(key, frame))

        val flipped = frame.payload.copyOf()
        flipped[flipped.size - 1] = (flipped[flipped.size - 1] + 1).toByte()
        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decryptFrame(key, frame.copy(payload = flipped)) }

        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decryptFrame(key, frame.copy(header = frame.header.copy(msgSeq = 2))) }
        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decryptFrame(key, frame.copy(header = frame.header.copy(sessionId = 5))) }
        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decryptFrame(ByteArray(32) { 9 }, frame) }
    }

    @Test
    fun gcmRejectsTamperedAadNonceKeyAndShortCiphertext() {
        val key = key()
        val nonce = engine.nonce(5, 9)
        val aad = FrameHeader(
            msgType = MessageType.CONTROL.code,
            flags = FrameFlags.ENCRYPTED,
            sessionId = 5,
            payloadLen = 20,
            msgSeq = 9,
        ).encode()
        val plaintext = byteArrayOf(1, 2, 3, 4)
        val ciphertext = engine.encrypt(key, nonce, aad, plaintext)
        eq(20, ciphertext.size)
        assertArrayEquals(plaintext, engine.decrypt(key, nonce, aad, ciphertext))

        val badAad = aad.copyOf()
        badAad[19] = (badAad[19] + 1).toByte()
        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decrypt(key, nonce, badAad, ciphertext) }
        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decrypt(key, engine.nonce(5, 10), aad, ciphertext) }
        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decrypt(ByteArray(32) { (it * 3 + 2).toByte() }, nonce, aad, ciphertext) }
        expectFailure(ErrorCode.DECRYPT_FAILED) { engine.decrypt(key, nonce, aad, ByteArray(8)) }
    }

    @Test
    fun payloadHelpersUseFrameHeaderAsAad() {
        val key = key()
        val plaintext = ByteArray(64) { (it * 5).toByte() }
        val header = FrameHeader(
            msgType = MessageType.CHUNK.code,
            flags = FrameFlags.ENCRYPTED,
            sessionId = 0x22334455,
            payloadLen = plaintext.size + 16,
            msgSeq = 3,
        )
        val ciphertext = engine.encryptPayload(key, header, plaintext)
        assertArrayEquals(plaintext, engine.decryptPayload(key, header, ciphertext))
        expectFailure(ErrorCode.DECRYPT_FAILED) {
            engine.decryptPayload(key, header.copy(flags = FrameFlags.ENCRYPTED or FrameFlags.REQUIRES_ACK), ciphertext)
        }
    }

    @Test
    fun transcriptHashCoversHeadersAndPayloadsInOrder() {
        val payload1 = byteArrayOf(1, 2)
        val payload2 = byteArrayOf(3, 4)
        val header1 = FrameHeader(msgType = MessageType.HELLO.code, flags = 0, sessionId = 0, payloadLen = 2, msgSeq = 0)
        val header2 = FrameHeader(msgType = MessageType.KEY_EXCHANGE.code, flags = 0, sessionId = 0, payloadLen = 2, msgSeq = 1)

        val hash = TranscriptHash()
        hash.update(header1, payload1)
        hash.update(header2, payload2)
        val expected = TranscriptHash.hash(header1.encode(), payload1, header2.encode(), payload2)
        eq(32, expected.size)
        assertArrayEquals(expected, hash.digest())

        val reordered = TranscriptHash.hash(header2.encode(), payload2, header1.encode(), payload1)
        assertFalse(expected.contentEquals(reordered))

        val single = TranscriptHash()
        single.update(header1, payload1)
        assertArrayEquals(TranscriptHash.hash(header1.encode(), payload1), single.digest())

        eq(64, single.digestHex().length)
        assertArrayEquals(TranscriptHash.hash(header1.encode(), payload1), TranscriptHash.hashFrame(header1, payload1))
    }

    @Test
    fun sessionKeysWipeOnDestroy() {
        val keys = SessionKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }, 4821)
        assertEquals("4821", keys.sasDigits)
        assertFalse(keys.isDestroyed)
        keys.close()
        assertTrue(keys.isDestroyed)
        assertArrayEquals(ByteArray(32), keys.kC2S)
        assertArrayEquals(ByteArray(32), keys.kS2C)
        keys.destroy()
        assertTrue(keys.isDestroyed)
        assertTrue(SessionKeys.SAS_MODULUS == 10000)
    }

    @Test
    fun randomBytesAreFilledAndDistinct() {
        val first = engine.randomBytes(32)
        val second = engine.randomBytes(32)
        eq(32, first.size)
        assertFalse(first.contentEquals(second))
        assertFalse(first.contentEquals(ByteArray(32)))
        eq(32, CryptoEngine.sha256(first).size)
    }
}
