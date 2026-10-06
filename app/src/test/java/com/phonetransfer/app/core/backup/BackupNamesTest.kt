package com.phonetransfer.app.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件名规则：对端提供的名字只是「标签」，必须经过清洗才允许落到 SAF；
 * 重名时自动追加序号，绝不覆盖已有备份。
 */
class BackupNamesTest {

    @Test
    fun safeStripsControlCharsAndPathSeparators() {
        assertEquals("a_b_c", BackupNames.safe("a/b\\c"))
        assertEquals("photo_001.jpg", BackupNames.safe("photo_001.jpg"))
        // 控制字符（含 0 字节）替换成下划线。
        assertEquals("a_b", BackupNames.safe("a\u0000b"))
        assertEquals("a_b", BackupNames.safe("a\nb"))
    }

    @Test
    fun safeRejectsWindowsReservedChars() {
        val dirty = "a:b*c?d\"e<f>g|h"
        val clean = BackupNames.safe(dirty)
        assertFalse("不能含 Windows 保留字符", clean.any { it in ":*?\"<>|" })
        assertEquals("a_b_c_d_e_f_g_h", clean)
    }

    @Test
    fun safeTrimsDotsAndWhitespace() {
        assertEquals("name", BackupNames.safe("  name  "))
        assertEquals("name", BackupNames.safe("...name..."))
        // 前导点会被去掉，避免产生隐藏文件。
        assertEquals("hidden", BackupNames.safe(".hidden"))
    }

    @Test
    fun safeFallsBackToUnnamed() {
        assertEquals("unnamed", BackupNames.safe(""))
        assertEquals("unnamed", BackupNames.safe("   "))
        assertEquals("unnamed", BackupNames.safe("..."))
    }

    @Test
    fun safeTruncatesLongNames() {
        val long = "a".repeat(300) + ".jpg"
        val clean = BackupNames.safe(long)
        assertTrue("最长 160 字符", clean.length <= 160)
    }

    @Test
    fun uniqueReturnsBaseWhenNoConflict() {
        assertEquals("photo.jpg", BackupNames.unique("photo.jpg") { false })
    }

    @Test
    fun uniqueAppendsSequenceBeforeExtension() {
        val existing = mutableSetOf("photo.jpg")
        val name = BackupNames.unique("photo.jpg") { it in existing }
        assertEquals("photo (1).jpg", name)
        existing.add(name)
        assertEquals("photo (2).jpg", BackupNames.unique("photo.jpg") { it in existing })
    }

    @Test
    fun uniqueHandlesNamesWithoutExtension() {
        val existing = mutableSetOf("README")
        assertEquals("README (1)", BackupNames.unique("README") { it in existing })
    }

    @Test
    fun uniqueSanitizesBeforeDeduplicating() {
        // 脏名字先清洗再查重：「a/b」和「a_b」冲突时走序号。
        val existing = mutableSetOf("a_b")
        assertEquals("a_b (1)", BackupNames.unique("a/b") { it in existing })
    }
}
