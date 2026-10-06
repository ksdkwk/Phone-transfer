package com.phonetransfer.app.core.protocol

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CHUNK 固定头测试：60 字节偏移、payloadLen 计算与往返。 */
class ChunkLayoutTest {

    private fun eq(expected: Int, actual: Int) = assertEquals(expected.toLong(), actual.toLong())

    private fun eqL(expected: Long, actual: Long) = assertEquals(expected, actual)

    private fun expectFailure(expected: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("期望抛出 " + expected.name)
        } catch (e: ProtocolException) {
            assertEquals(expected, e.errorCode)
        }
    }

    private fun readIntBE(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xFF) shl 24) or
            ((bytes[index + 1].toInt() and 0xFF) shl 16) or
            ((bytes[index + 2].toInt() and 0xFF) shl 8) or
            (bytes[index + 3].toInt() and 0xFF)

    @Test
    fun fixedHeaderIsSixtyBytesWithDocumentedOffsets() {
        val data = byteArrayOf(1, 2, 3, 4)
        val chunk = Chunk.of(
            itemId = 0x0102030405060708L,
            chunkSeq = 0x01020304,
            offset = 0x1112131415161718L,
            data = data,
        )
        val header = chunk.header.encode()
        eq(60, header.size)
        eq(ChunkHeader.SIZE, header.size)
        eq(ChunkLayout.FIXED_HEADER_SIZE, header.size)
        eq(0x01, header[0].toInt() and 0xFF)
        eq(0x08, header[7].toInt() and 0xFF)
        eq(0x01, header[8].toInt() and 0xFF)
        eq(0x04, header[11].toInt() and 0xFF)
        eq(0x11, header[16].toInt() and 0xFF)
        eq(0x18, header[23].toInt() and 0xFF)
        eq(4, readIntBE(header, 24))
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), header.copyOfRange(28, 60))
        assertTrue(chunk.sha256Matches())
    }

    @Test
    fun payloadLengthIncludesHeaderDataAndTag() {
        eq(1076, ChunkLayout.payloadLength(1000))
        eq(1076, ChunkLayout.encryptedPayloadLength(1000))
        eq(76, ChunkLayout.payloadLength(0))
        eq(ProtocolConstants.CHUNK_FIXED_HEADER_SIZE + 1000 + ProtocolConstants.GCM_TAG_SIZE, ChunkLayout.payloadLength(1000))
        eq(ProtocolConstants.MAX_FRAME_PAYLOAD - 76, ChunkLayout.maxDataLen())

        val chunk = Chunk.of(1L, 0, 0L, ByteArray(1000))
        eq(1076, chunk.header.payloadLength())
        expectFailure(ErrorCode.FRAME_TOO_LARGE) { ChunkLayout.payloadLength(ProtocolConstants.MAX_FRAME_PAYLOAD) }
    }

    @Test
    fun chunkRoundTripsThroughPlaintext() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val original = Chunk.of(itemId = 42L, chunkSeq = 3, offset = 3000L, data = data)
        val plain = original.encode()
        eq(1060, plain.size)

        val decoded = Chunk.decode(plain)
        eqL(42L, decoded.itemId)
        eq(3, decoded.chunkSeq)
        eqL(3000L, decoded.offset)
        eq(1000, decoded.dataLen)
        assertArrayEquals(data, decoded.data)
        assertTrue(decoded.sha256Matches())
        assertEquals(original.header, decoded.header)
        assertArrayEquals(original.actualSha256(), decoded.actualSha256())

        val headerOnly = ChunkHeader.decode(plain)
        eqL(42L, headerOnly.itemId)
        eq(3, headerOnly.chunkSeq)
        eqL(3000L, headerOnly.offset)
        eq(1000, headerOnly.dataLen)
    }

    @Test
    fun decodeRejectsInconsistentLengths() {
        val plain = Chunk.of(1L, 0, 0L, ByteArray(10)).encode()
        expectFailure(ErrorCode.MALFORMED_TLV) { Chunk.decode(plain.copyOfRange(0, plain.size - 1)) }
        expectFailure(ErrorCode.MALFORMED_TLV) { Chunk.decode(ByteArray(59)) }
        expectFailure(ErrorCode.MALFORMED_TLV) { Chunk.decode(plain.copyOf(plain.size + 1)) }
        expectFailure(ErrorCode.MALFORMED_TLV) { ChunkHeader.decode(ByteArray(0)) }
    }

    @Test
    fun tamperedDataIsDetectedBySha256() {
        val chunk = Chunk.of(7L, 1, 0L, byteArrayOf(1, 2, 3, 4))
        assertTrue(chunk.sha256Matches())
        val tampered = chunk.copy(data = byteArrayOf(1, 2, 3, 5))
        assertFalse(tampered.sha256Matches())
        assertArrayEquals(chunk.actualSha256(), chunk.header.chunkSha256)
        assertFalse(chunk.actualSha256().contentEquals(tampered.actualSha256()))
        eqL(7L, chunk.copy(header = chunk.header.copy(itemId = 7L)).itemId)
    }
}
