package com.phonetransfer.app.core.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 帧层字节级测试：20B 帧头布局、往返读写、上限校验、msgSeq 单调。 */
class FrameCodecTest {

    private fun eq(expected: Int, actual: Int) = assertEquals(expected.toLong(), actual.toLong())

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun expectFailure(expected: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("期望抛出 " + expected.name)
        } catch (e: ProtocolException) {
            assertEquals(expected, e.errorCode)
        }
    }

    private fun rawHeader(
        version: Int,
        msgType: Int,
        flags: Int,
        sessionId: Int,
        payloadLen: Int,
        msgSeq: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(FrameHeader.SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(ProtocolConstants.MAGIC)
        buffer.put(version.toByte())
        buffer.put(msgType.toByte())
        buffer.put(flags.toByte())
        buffer.put(0.toByte())
        buffer.putInt(sessionId)
        buffer.putInt(payloadLen)
        buffer.putInt(msgSeq)
        return buffer.array()
    }

    @Test
    fun headerEncodesExactTwentyBytesInDocumentedLayout() {
        val header = FrameHeader(
            msgType = MessageType.CHUNK.code,
            flags = FrameFlags.ENCRYPTED or FrameFlags.REQUIRES_ACK,
            sessionId = 0x01020304,
            payloadLen = 3,
            msgSeq = 7,
        )
        val bytes = header.encode()
        eq(20, bytes.size)
        assertEquals("50 54 46 01 01 11 05 00 01 02 03 04 00 00 00 03 00 00 00 07", hex(bytes))
        eq(0x50544601, header.magic)
        eq(1, header.version)
        eq(0x11, header.msgType)
        eq(5, header.flags)
        eq(0, header.reserved)
        eq(3, header.payloadLen)
        eq(7, header.msgSeq)
        assertTrue(header.encrypted)
        assertTrue(header.requiresAck)
        assertFalse(header.compressed)
    }

    @Test
    fun headerDecodesBackFromBytesAndBuffer() {
        val original = FrameHeader(
            msgType = MessageType.ITEM_BEGIN.code,
            flags = FrameFlags.ENCRYPTED,
            sessionId = 0x7FFFFFFF,
            payloadLen = 4096,
            msgSeq = 0x0F0F0F0F,
        )
        val bytes = original.encode()
        assertEquals(original, FrameHeader.decode(bytes))
        assertEquals(original, FrameHeader.decode(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)))
        eq(4096 - 16, original.plaintextLength)
        assertEquals(MessageType.ITEM_BEGIN, original.messageType())
        assertNull(MessageType.fromCode(0x99))

        val prefixed = ByteArray(4) + bytes
        assertEquals(original, FrameHeader.decode(prefixed, 4))
        expectFailure(ErrorCode.MALFORMED_TLV) { FrameHeader.decode(ByteArray(19)) }
    }

    @Test
    fun frameRoundTripThroughStreamKeepsSequenceMonotonic() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val out = ByteArrayOutputStream()
        for (seq in 0 until 5) {
            FrameCodec.writeFrame(
                out,
                FrameCodec.frame(MessageType.CHUNK.code, FrameFlags.ENCRYPTED, 0x0A0B0C0D, seq, payload),
            )
        }
        val input = ByteArrayInputStream(out.toByteArray())
        var lastSeq = -1
        var count = 0
        while (true) {
            val frame = FrameCodec.readFrameOrNull(input) ?: break
            assertTrue(frame.header.msgSeq > lastSeq)
            lastSeq = frame.header.msgSeq
            eq(0x0A0B0C0D, frame.header.sessionId)
            eq(4, frame.payloadLen)
            assertArrayEquals(payload, frame.payload)
            count += 1
        }
        eq(5, count)
        eq(5, lastSeq + 1)
    }

    @Test
    fun singleFrameRoundTripsThroughByteArray() {
        val payload = ByteArray(300) { (it % 97).toByte() }
        val frame = FrameCodec.frame(MessageType.MANIFEST_OFFER.code, FrameFlags.ENCRYPTED, 0x11223344, 12, payload)
        eq(320, FrameCodec.encode(frame).size)
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame.header, decoded.header)
        assertArrayEquals(payload, decoded.payload)
        assertNotNull(decoded.header.messageType())
        assertNull(FrameCodec.readFrameOrNull(ByteArrayInputStream(ByteArray(0))))
        expectFailure(ErrorCode.MALFORMED_TLV) { FrameCodec.decode(FrameCodec.encode(frame) + byteArrayOf(0)) }
    }

    @Test
    fun readFrameThrowsEofOnEmptyAndTruncatedStreams() {
        try {
            FrameCodec.readFrame(ByteArrayInputStream(ByteArray(0)))
            fail("空流应抛 EOFException")
        } catch (e: EOFException) {
            assertNotNull(e.message)
        }
        val headerOnly = rawHeader(1, MessageType.PING.code, FrameFlags.ENCRYPTED, 1, 8, 0)
        try {
            FrameCodec.readFrame(ByteArrayInputStream(headerOnly))
            fail("payload 缺失应抛 EOFException")
        } catch (e: EOFException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun oversizedPayloadLenIsRejected() {
        val oversized = rawHeader(
            1,
            MessageType.CHUNK.code,
            FrameFlags.ENCRYPTED,
            1,
            ProtocolConstants.MAX_FRAME_PAYLOAD + 1,
            0,
        )
        expectFailure(ErrorCode.FRAME_TOO_LARGE) { FrameHeader.decode(oversized) }
        expectFailure(ErrorCode.FRAME_TOO_LARGE) { FrameCodec.readFrame(ByteArrayInputStream(oversized)) }
        expectFailure(ErrorCode.FRAME_TOO_LARGE) {
            FrameHeader(
                msgType = MessageType.CHUNK.code,
                flags = FrameFlags.ENCRYPTED,
                sessionId = 1,
                payloadLen = ProtocolConstants.MAX_FRAME_PAYLOAD + 1,
                msgSeq = 0,
            ).encode()
        }
    }

    @Test
    fun magicVersionAndReservedFlagsAreValidated() {
        val badMagic = rawHeader(1, MessageType.HELLO.code, 0, 1, 0, 0)
        badMagic[0] = 0x51
        expectFailure(ErrorCode.MALFORMED_TLV) { FrameHeader.decode(badMagic) }

        expectFailure(ErrorCode.PROTOCOL_VERSION_MISMATCH) {
            FrameHeader.decode(rawHeader(2, MessageType.HELLO.code, 0, 1, 0, 0))
        }
        assertNotNull(FrameHeader.decode(rawHeader(2, MessageType.HELLO.code, 0, 1, 0, 0), 0, 2))

        expectFailure(ErrorCode.MALFORMED_TLV) {
            FrameHeader.decode(rawHeader(1, MessageType.HELLO.code, 0x10, 1, 0, 0))
        }
    }

    @Test
    fun payloadLengthMustMatchPayloadSize() {
        val header = FrameHeader(
            msgType = MessageType.ERROR.code,
            flags = FrameFlags.ENCRYPTED,
            sessionId = 1,
            payloadLen = 10,
            msgSeq = 0,
        )
        expectFailure(ErrorCode.MALFORMED_TLV) {
            FrameCodec.writeFrame(ByteArrayOutputStream(), header, byteArrayOf(1, 2, 3))
        }
        assertFalse(FrameFlags.isValid(FrameFlags.RESERVED_MASK))
        assertTrue(FrameFlags.isValid(FrameFlags.ENCRYPTED or FrameFlags.REQUIRES_ACK))
        eq(0x0F, FrameFlags.with(FrameFlags.NONE, 0x01, 0x02, 0x04, 0x08))
        eq(0x01, FrameFlags.without(0x0F, 0x02, 0x04, 0x08))
        assertEquals("ENCRYPTED|REQUIRES_ACK", FrameFlags.describe(FrameFlags.ENCRYPTED or FrameFlags.REQUIRES_ACK))
        assertEquals("NONE", FrameFlags.describe(FrameFlags.NONE))
    }
}
