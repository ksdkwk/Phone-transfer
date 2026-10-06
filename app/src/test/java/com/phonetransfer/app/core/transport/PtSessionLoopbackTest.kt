package com.phonetransfer.app.core.transport

import com.phonetransfer.app.core.protocol.ErrorCode
import com.phonetransfer.app.core.protocol.ItemType
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实会话的端到端测试：在 127.0.0.1 上起一个接收端线程、主线程做发送端，
 * 走完整协议（HELLO → 密钥协商 → SAS 比对 → 清单 → 分块加密传输 → 校验 → 关闭）。
 *
 * 这里验证的是除「链路」以外的全部真实实现：帧层、TLV、ECDH/HKDF、AES-GCM、分块与摘要。
 */
class PtSessionLoopbackTest {

    private fun payload(id: Long, type: ItemType, size: Int) =
        PtPayload(id, type, "item-" + id, ByteArray(size) { (it % 251).toByte() })

    private class Recorder : PtSessionListener {
        val sas = AtomicInteger(-1)
        val results = AtomicReference<List<PtItemResult>>()
        val manifest = AtomicReference<List<PtManifestEntry>>()
        val states = ArrayList<String>()
        override fun onSas(sas: Int) { this.sas.set(sas) }
        override fun onResult(results: List<PtItemResult>) { this.results.set(results) }
        override fun onManifest(entries: List<PtManifestEntry>) { manifest.set(entries) }
        override fun onState(state: com.phonetransfer.app.core.session.SessionState) { states.add(state.name) }
    }

    @Test
    fun handshakeDerivesSameSasAndTransfersEncryptedBytes() {
        val server = ServerSocket(0)
        val receiverEvents = Recorder()
        val senderEvents = Recorder()
        val failure = AtomicReference<Throwable>()
        val items = listOf(
            payload(1, ItemType.CONTACT, 350_000),
            payload(2, ItemType.PHOTO, 1_200_000),
        )

        val receiverThread = thread(name = "pt-receiver") {
            try {
                server.accept().use { socket ->
                    PtSession(
                        role = SessionRole.RECEIVER,
                        pairingCode = "123456",
                        wantedTypes = setOf(ItemType.CONTACT, ItemType.PHOTO),
                        listener = receiverEvents,
                        deviceModel = "receiver",
                    ).runReceiver(socket)
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        Socket("127.0.0.1", server.localPort).use { socket ->
            PtSession(
                role = SessionRole.SENDER,
                pairingCode = "123456",
                payloads = items,
                listener = senderEvents,
                deviceModel = "sender",
            ).runSender(socket)
        }
        receiverThread.join(30_000)
        server.close()

        failure.get()?.let { throw AssertionError("接收端失败: " + it.message, it) }

        // 双端派生出同一个 SAS（协议 §10）
        assertTrue("SAS 未产生", senderEvents.sas.get() >= 0)
        assertEquals(senderEvents.sas.get(), receiverEvents.sas.get())

        val results = receiverEvents.results.get() ?: throw AssertionError("接收端没有结果")
        assertEquals(2, results.size)
        assertTrue("存在校验失败的条目", results.all { it.verified })
        assertEquals(350_000L, results[0].byteCount)
        assertEquals(1_200_000L, results[1].byteCount)
        assertEquals(ItemType.CONTACT, results[0].itemType)

        // 清单也真实送达
        assertEquals(2, receiverEvents.manifest.get()?.size)
        // 状态机走完到 CLOSED
        assertTrue(senderEvents.states.contains("TRANSFERRING"))
        assertTrue(senderEvents.states.contains("CLOSED"))
    }

    @Test
    fun onlySelectedTypesAreTransferred() {
        val server = ServerSocket(0)
        val receiverEvents = Recorder()
        val failure = AtomicReference<Throwable>()
        val items = listOf(
            payload(1, ItemType.CONTACT, 20_000),
            payload(2, ItemType.PHOTO, 20_000),
            payload(3, ItemType.VIDEO, 20_000),
        )
        val receiverThread = thread(name = "pt-receiver") {
            try {
                server.accept().use { socket ->
                    PtSession(
                        role = SessionRole.RECEIVER,
                        pairingCode = "654321",
                        wantedTypes = setOf(ItemType.PHOTO),
                        listener = receiverEvents,
                    ).runReceiver(socket)
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        Socket("127.0.0.1", server.localPort).use { socket ->
            PtSession(
                role = SessionRole.SENDER,
                pairingCode = "654321",
                payloads = items,
            ).runSender(socket)
        }
        receiverThread.join(30_000)
        server.close()
        failure.get()?.let { throw AssertionError("接收端失败: " + it.message, it) }
        val results = receiverEvents.results.get()!!
        assertEquals(1, results.size)
        assertEquals(ItemType.PHOTO, results[0].itemType)
    }

    @Test
    fun wrongPairingCodeIsRejected() {
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable>()
        val receiverThread = thread(name = "pt-receiver") {
            try {
                server.accept().use { socket ->
                    PtSession(
                        role = SessionRole.RECEIVER,
                        pairingCode = "111111",
                        wantedTypes = setOf(ItemType.CONTACT),
                    ).runReceiver(socket)
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        try {
            Socket("127.0.0.1", server.localPort).use { socket ->
                PtSession(
                    role = SessionRole.SENDER,
                    pairingCode = "999999",
                    payloads = listOf(payload(1, ItemType.CONTACT, 1000)),
                ).runSender(socket)
            }
        } catch (_: Exception) {
            // 发送端在接收端拒绝并断开后报通道关闭，属预期
        }
        receiverThread.join(30_000)
        server.close()
        val error = failure.get() ?: throw AssertionError("接收端应拒绝错误配对码")
        assertTrue("异常类型: " + error, error is PtSessionException)
        assertEquals(ErrorCode.PAIR_CODE_MISMATCH, (error as PtSessionException).errorCode)
    }
}
