package com.phonetransfer.app.core.transport

import com.phonetransfer.app.core.crypto.CryptoEngine
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.protocol.ProtocolConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件备份路径的端到端流式测试：发送端从磁盘文件流式读取（不回退内存），
 * 接收端经 [PtReceiveTarget] 流式落盘并校验（commit 返回落盘位置）。
 * 覆盖：大文件多块 ACK 节奏（> 8 块）、空文件、落盘位置回传、取消/中止清理。
 */
class PtSessionStreamTest {

    /** 文件落盘目标：先写进目标文件，commit 回读校验后返回路径。 */
    private class FileTarget(private val file: File, private val entry: PtManifestEntry) : PtReceiveTarget {
        private val stream = file.outputStream()
        override val output: OutputStream = stream
        override fun commit(): String {
            stream.close()
            val bytes = file.readBytes()
            check(bytes.size.toLong() == entry.size) { "长度不符" }
            check(CryptoEngine.sha256(bytes).contentEquals(entry.sha256)) { "摘要不符" }
            return file.absolutePath
        }
        override fun abort() {
            runCatching { stream.close() }
            file.delete()
        }
    }

    private class Recorder : PtSessionListener {
        val results = AtomicReference<List<PtItemResult>>()
        override fun onResult(results: List<PtItemResult>) { this.results.set(results) }
    }

    private data class Run(
        val senderResults: List<PtItemResult>,
        val receiverResults: List<PtItemResult>,
    )

    private fun runSession(
        payloads: List<PtPayload>,
        targetDir: File,
        chunkSize: Int = ProtocolConstants.CHUNK_SIZE,
        onReceiverTarget: ((PtManifestEntry) -> File)? = null,
    ): Run {
        val server = ServerSocket(0)
        val receiverEvents = Recorder()
        val senderEvents = Recorder()
        val failure = AtomicReference<Throwable>()
        val receiverThread = thread(name = "pt-receiver") {
            try {
                server.accept().use { socket ->
                    PtSession(
                        role = SessionRole.RECEIVER,
                        pairingCode = "123456",
                        wantedTypes = payloads.map { it.itemType }.toSet(),
                        listener = receiverEvents,
                        chunkSize = chunkSize,
                        receiveTarget = { entry ->
                            val file = (onReceiverTarget?.invoke(entry)
                                ?: File(targetDir, entry.name))
                            FileTarget(file, entry)
                        },
                        requireReceiveTarget = true,
                    ).runReceiver(socket)
                }
            } catch (t: Throwable) { failure.set(t) }
        }
        Socket("127.0.0.1", server.localPort).use { socket ->
            PtSession(
                role = SessionRole.SENDER,
                pairingCode = "123456",
                payloads = payloads,
                listener = senderEvents,
                chunkSize = chunkSize,
            ).runSender(socket)
        }
        receiverThread.join(60_000)
        server.close()
        failure.get()?.let { throw AssertionError("接收端失败: " + it.message, it) }
        return Run(
            senderEvents.results.get() ?: throw AssertionError("发送端没有结果"),
            receiverEvents.results.get() ?: throw AssertionError("接收端没有结果"),
        )
    }

    @Test
    fun largeFileStreamsAndLandsOnDiskVerified() {
        val dir = createTempDir()
        try {
            // 3.2 MiB：默认 1 MiB 块 → 4 块；二进制内容含全字节值。
            val content = ByteArray(3_400_000) { (it * 31 % 251).toByte() }
            val source = File(dir, "movie.mp4").apply { writeBytes(content) }
            val payload = PtPayload(
                1L, ItemType.VIDEO, "movie.mp4", content.size.toLong(),
                CryptoEngine.sha256(content),
                { source.inputStream() },
            )
            val targetDir = File(dir, "backup").apply { mkdirs() }
            val run = runSession(listOf(payload), targetDir)
            assertEquals(1, run.receiverResults.size)
            val result = run.receiverResults[0]
            assertTrue("接收端应校验通过", result.verified)
            assertEquals("落盘位置应回传", File(targetDir, "movie.mp4").absolutePath, result.savedLocation)
            assertArrayEquals("落盘内容应与源文件一致", content, File(targetDir, "movie.mp4").readBytes())
            // 发送端也能看到结果（不含落盘位置）。
            assertEquals(1, run.senderResults.size)
            assertTrue(run.senderResults[0].savedLocation.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun emptyFileTransfersWithZeroChunks() {
        val dir = createTempDir()
        try {
            val content = ByteArray(0)
            val payload = PtPayload(
                1L, ItemType.DOCUMENT, "empty.txt", 0L, CryptoEngine.sha256(content),
                { ByteArrayInputStream(content) },
            )
            val targetDir = File(dir, "backup").apply { mkdirs() }
            val run = runSession(listOf(payload), targetDir)
            assertEquals(1, run.receiverResults.size)
            assertTrue(run.receiverResults[0].verified)
            assertEquals(0L, run.receiverResults[0].byteCount)
            assertTrue("空文件也应真实落盘", File(targetDir, "empty.txt").exists())
            assertEquals(0, File(targetDir, "empty.txt").readBytes().size)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun moreThanEightChunksUsesAckPacingWithoutDeadlock() {
        val dir = createTempDir()
        try {
            // 用 256 KiB 最小块：3 MiB 文件 → 12 块，触发 8 块节奏 ACK + 尾 ACK。
            val chunk = ProtocolConstants.MIN_CHUNK_SIZE
            val content = ByteArray(3 * 1024 * 1024) { (it * 17 % 253).toByte() }
            val payload = PtPayload(
                1L, ItemType.PHOTO, "big.jpg", content.size.toLong(), CryptoEngine.sha256(content),
                { ByteArrayInputStream(content) },
            )
            val targetDir = File(dir, "backup").apply { mkdirs() }
            val run = runSession(listOf(payload), targetDir, chunkSize = chunk)
            assertTrue(run.receiverResults[0].verified)
            assertArrayEquals(content, File(targetDir, "big.jpg").readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun abortDeletesPartialFileOnCancel() {
        val dir = createTempDir()
        try {
            val entry = PtManifestEntry(1L, ItemType.VIDEO, "partial.bin", 100L, ByteArray(32))
            val file = File(dir, "partial.bin")
            val target = FileTarget(file, entry)
            target.output.write(ByteArray(50) { 7 })
            target.abort()
            assertTrue("abort 应删除未完成的临时文件", !file.exists())
        } finally { dir.deleteRecursively() }
    }
}
