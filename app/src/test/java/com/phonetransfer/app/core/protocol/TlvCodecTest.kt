package com.phonetransfer.app.core.protocol

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

/** TLV 编解码测试：往返、未知 tag 跳过、缺失字段、空值、u64 大端。 */
class TlvCodecTest {

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
    fun roundTripPreservesOrderAndValues() {
        val nonce = ByteArray(16) { (it + 1).toByte() }
        val writer = TlvWriter()
        writer
            .putU32(TlvTag.T_SESSION_ID, 0xDEADBEEFL)
            .putU16(TlvTag.T_PAIR_METHOD, 2)
            .putU64(TlvTag.T_TIMESTAMP, 1700000000123L)
            .putString(TlvTag.T_DEVICE_BRAND, "Xiaomi")
            .putBytes(TlvTag.T_NONCE, nonce)
            .putBool(TlvTag.T_SAS_CONFIRMED, true)
            .putEmpty(TlvTag.T_ITEM_BEGIN)
        val bytes = writer.toByteArray()
        eq(writer.size, bytes.size)

        val reader = TlvReader(bytes)
        val tlvs = reader.readAll()
        eq(7, tlvs.size)
        eq(TlvTag.T_SESSION_ID, tlvs[0].tag)
        eqL(0xDEADBEEFL, tlvs[0].asU32())
        eq(TlvTag.T_PAIR_METHOD, tlvs[1].tag)
        eq(2, tlvs[1].asU16())
        eq(TlvTag.T_TIMESTAMP, tlvs[2].tag)
        eqL(1700000000123L, tlvs[2].asU64())
        eq(TlvTag.T_DEVICE_BRAND, tlvs[3].tag)
        assertEquals("Xiaomi", tlvs[3].asString())
        eq(TlvTag.T_NONCE, tlvs[4].tag)
        assertArrayEquals(nonce, tlvs[4].asBytes())
        eq(TlvTag.T_SAS_CONFIRMED, tlvs[5].tag)
        assertTrue(tlvs[5].asBool())
        eq(TlvTag.T_ITEM_BEGIN, tlvs[6].tag)
        eq(0, tlvs[6].length)

        eqL(0xDEADBEEFL, reader.requireU32(TlvTag.T_SESSION_ID))
        assertEquals("Xiaomi", reader.requireString(TlvTag.T_DEVICE_BRAND))
        assertTrue(reader.requireBool(TlvTag.T_SAS_CONFIRMED))
        eq(2, reader.requireU16(TlvTag.T_PAIR_METHOD))
        assertFalse(reader.bool(TlvTag.T_INCLUDE_EXIF_LOCATION, false))
        eq(7, reader.u8(TlvTag.T_BATTERY_LEVEL, 7))
        assertEquals("fallback", reader.string(TlvTag.T_MIME, "fallback"))
    }

    @Test
    fun unknownTagIsSkippedInsteadOfFailing() {
        val writer = TlvWriter()
        writer.putU32(TlvTag.T_SESSION_ID, 1)
        writer.putString(0x8001, "vendor")
        writer.putU32(TlvTag.T_ERROR_CODE, 0x1002)
        val bytes = writer.toByteArray()

        val reader = TlvReader(bytes)
        val tlvs = reader.readAll()
        eq(3, tlvs.size)
        eq(0x8001, tlvs[1].tag)
        assertEquals("vendor", tlvs[1].asString())
        eqL(0x1002L, reader.requireU32(TlvTag.T_ERROR_CODE))
        assertNotNull(reader.find(0x8001))
        assertNull(reader.find(0x7FFF))
        assertTrue(TlvTag.isVendor(0x8001))
        assertFalse(TlvTag.isKnown(0x8001))
        assertTrue(TlvTag.isKnown(TlvTag.T_SESSION_ID))
        assertEquals("0x8001", TlvTag.nameOf(0x8001))

        var seen = 0
        val streamed = TlvReader(bytes)
        while (streamed.hasNext()) {
            streamed.next()
            seen += 1
        }
        eq(3, seen)
    }

    @Test
    fun missingRequiredFieldYieldsMissingFieldError() {
        val bytes = TlvWriter().putString(TlvTag.T_DEVICE_BRAND, "Apple").toByteArray()
        val reader = TlvReader(bytes)
        try {
            reader.requireU32(TlvTag.T_SESSION_ID)
            fail("缺失必备字段应抛异常")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.MISSING_FIELD, e.errorCode)
            eq(0x1002, e.code)
            assertTrue(ErrorCode.MISSING_FIELD.isFatal)
            assertFalse(ErrorCode.CHUNK_SHA_MISMATCH.isFatal)
            assertTrue(ErrorCode.CHUNK_SHA_MISMATCH.isRetryable)
            assertTrue(ErrorCode.OK.isOk)
            assertEquals(ErrorCode.ACK_TIMEOUT, ErrorCode.fromCode(0x4003))
            assertNull(ErrorCode.fromCode(0x9999))
        }
    }

    @Test
    fun emptyValueIsPreservedAndNotNull() {
        val bytes = TlvWriter().putEmpty(TlvTag.T_ITEM_END).toByteArray()
        eq(6, bytes.size)
        val reader = TlvReader(bytes)
        val tlv = reader.requireTlv(TlvTag.T_ITEM_END)
        eq(0, tlv.length)
        assertTrue(tlv.isEmpty())
        val value = reader.findBytes(TlvTag.T_ITEM_END)
        assertNotNull(value)
        eq(0, value!!.size)
    }

    @Test
    fun u64IsBigEndianAndAcceptsFullRange() {
        val bytes = TlvWriter().putU64(TlvTag.T_ITEM_ID, 0x0102030405060708L).toByteArray()
        eq(14, bytes.size)
        eq(0x03, bytes[0].toInt() and 0xFF)
        eq(0x10, bytes[1].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), bytes.copyOfRange(6, 14))
        eqL(0x0102030405060708L, TlvReader(bytes).requireU64(TlvTag.T_ITEM_ID))

        val allOnes = TlvWriter().putU64(TlvTag.T_ITEM_ID, -1L).toByteArray()
        eqL(-1L, TlvReader(allOnes).requireU64(TlvTag.T_ITEM_ID))
        assertArrayEquals(ByteArray(8) { 0xFF.toByte() }, allOnes.copyOfRange(6, 14))
    }

    @Test
    fun tagHeaderIsSixBytesAndU32IsUnsigned() {
        val bytes = TlvWriter().putU32(TlvTag.T_MANIFEST_ID, 0xFFFFFFFFL).toByteArray()
        eq(10, bytes.size)
        eq(0x03, bytes[0].toInt() and 0xFF)
        eq(0x03, bytes[1].toInt() and 0xFF)
        eq(4, readIntBE(bytes, 2))
        eqL(4294967295L, TlvReader(bytes).requireU32(TlvTag.T_MANIFEST_ID))
        eqL(4294967295L, TlvReader(bytes).findU32(TlvTag.T_MANIFEST_ID)!!)
        // u32 字段按 u64 读取属于格式非法：find* 系列在「字段存在但长度不符」时按 MALFORMED_TLV 处理
        expectFailure(ErrorCode.MALFORMED_TLV) { TlvReader(bytes).findU64(TlvTag.T_MANIFEST_ID) }
    }

    @Test
    fun truncatedAndOversizedTlvRaiseMalformed() {
        expectFailure(ErrorCode.MALFORMED_TLV) { TlvReader(ByteArray(4)).next() }
        expectFailure(ErrorCode.MALFORMED_TLV) { TlvReader(ByteArray(4)).fields() }

        val declared = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(1.toShort())
            .putInt(100)
            .put(byteArrayOf(1, 2))
            .array()
        expectFailure(ErrorCode.MALFORMED_TLV) { TlvReader(declared).next() }
        expectFailure(ErrorCode.MALFORMED_TLV) { TlvReader(declared).fields() }
    }

    @Test
    fun wrongSizedValueRaisesMalformed() {
        val bytes = TlvWriter().putU16(TlvTag.T_PAIR_METHOD, 1).toByteArray()
        expectFailure(ErrorCode.MALFORMED_TLV) { TlvReader(bytes).requireU32(TlvTag.T_PAIR_METHOD) }
        expectFailure(ErrorCode.MALFORMED_TLV) { TlvReader(bytes).requireU8(TlvTag.T_PAIR_METHOD) }
    }

    @Test
    fun stringIsUtf8WithoutTrailingZero() {
        val bytes = TlvWriter().putString(TlvTag.T_DEVICE_MODEL, "会话").toByteArray()
        eq(12, bytes.size)
        assertTrue((bytes[bytes.size - 1].toInt() and 0xFF) != 0)
        assertEquals("会话", TlvReader(bytes).requireString(TlvTag.T_DEVICE_MODEL))
        assertFalse(Tlv.of(TlvTag.T_MIME, byteArrayOf()).asBytes().isNotEmpty())
    }

    @Test
    fun codecObjectRoundTripsAndReaderResetWorks() {
        val payload = TlvCodec.encode(
            Tlv.of(TlvTag.T_SESSION_ID, byteArrayOf(0, 0, 0, 5)),
            Tlv.of(TlvTag.T_DEVICE_BRAND, "OPPO".toByteArray(Charsets.UTF_8)),
        )
        val map = TlvCodec.decodeToMap(payload)
        eq(2, map.size)
        eqL(5L, map.getValue(TlvTag.T_SESSION_ID).asU32())
        assertEquals("OPPO", map.getValue(TlvTag.T_DEVICE_BRAND).asString())
        eq(2, TlvCodec.decode(payload).size)
        eq(payload.size, TlvCodec.encode(TlvCodec.decode(payload)).size)

        val reader = TlvReader(payload)
        eq(2, reader.readAll().size)
        assertFalse(reader.hasNext())
        reader.reset()
        assertTrue(reader.hasNext())
        eq(2, reader.readAll().size)
        eq(0, reader.remaining)
        assertNull(reader.nextOrNull())
    }
}
