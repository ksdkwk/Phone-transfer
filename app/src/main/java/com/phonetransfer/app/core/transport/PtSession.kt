package com.phonetransfer.app.core.transport

import com.phonetransfer.app.core.crypto.CryptoEngine
import com.phonetransfer.app.core.crypto.SessionKeys
import com.phonetransfer.app.core.crypto.TranscriptHash
import com.phonetransfer.app.core.protocol.Chunk
import com.phonetransfer.app.core.protocol.ErrorCode
import com.phonetransfer.app.core.protocol.Frame
import com.phonetransfer.app.core.protocol.FrameCodec
import com.phonetransfer.app.core.protocol.FrameFlags
import com.phonetransfer.app.core.protocol.FrameHeader
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.protocol.MessageType
import com.phonetransfer.app.core.protocol.ProtocolConstants
import com.phonetransfer.app.core.protocol.ProtocolException
import com.phonetransfer.app.core.protocol.Tlv
import com.phonetransfer.app.core.protocol.TlvReader
import com.phonetransfer.app.core.protocol.TlvTag
import com.phonetransfer.app.core.protocol.TlvWriter
import com.phonetransfer.app.core.session.SessionState
import com.phonetransfer.app.core.session.SessionStateMachine
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/** 会话角色：旧机（发送端，TCP 客户端）/ 新机（接收端，TCP 服务端）。 */
enum class SessionRole { SENDER, RECEIVER }

/** 发送端要传的一个数据项。 */
class PtPayload(
    val itemId: Long,
    val itemType: ItemType,
    val name: String,
    val size: Long,
    val sha256: ByteArray,
    val openStream: () -> InputStream,
) {
    constructor(itemId: Long, itemType: ItemType, name: String, bytes: ByteArray) :
        this(itemId, itemType, name, bytes.size.toLong(), CryptoEngine.sha256(bytes),
            { ByteArrayInputStream(bytes) })

    init {
        require(size >= 0 && sha256.size == 32)
    }
}

/** 写入未验证临时文件；commit 只在文件长度和清单摘要均验证后调用。 */
interface PtReceiveTarget {
    val output: OutputStream
    fun commit(): String
    fun abort()
}

/** 清单条目（MANIFEST_OFFER 的一条）。 */
class PtManifestEntry(
    val itemId: Long,
    val itemType: ItemType,
    val name: String,
    val size: Long,
    val sha256: ByteArray,
)

/** 条目结果。 */
class PtItemResult(
    val itemId: Long,
    val itemType: ItemType,
    val name: String,
    val byteCount: Long,
    val sha256: ByteArray,
    val verified: Boolean,
    val savedLocation: String = "",
)

/** 会话事件回调（在调用 runXxx 的线程上触发）。 */
interface PtSessionListener {
    fun onState(state: SessionState) {}
    fun onPeer(model: String, osName: Int) {}
    fun onSas(sas: Int) {}
    fun onManifest(entries: List<PtManifestEntry>) {}
    fun onProgress(itemsDone: Int, itemCount: Int, bytesDone: Long, bytesTotal: Long) {}
    fun onResult(results: List<PtItemResult>) {}
}

class PtSessionException(
    val errorCode: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Phone-transfer 真实会话（协议规范 §9~§13）。
 *
 * 纯 JVM 实现，不依赖 Android：可在两端之间跑在任意 [Socket] 上
 * （Wi-Fi Direct / SoftAP / 局域网都只是提供这条 TCP 连接）。
 *
 * 已实现：HELLO/HELLO_ACK、PAIR_REQUEST/PAIR_ACCEPT（配对码哈希校验）、
 * KEY_EXCHANGE（P-256 ECDH + HKDF）、SAS_NOTIFY（人工比对，含转录哈希）、SAS_CONFIRM、
 * SESSION_READY、MANIFEST_OFFER/ITEM_SELECT、ITEM_BEGIN/CHUNK/CHUNK_ACK/ITEM_END、
 * TRANSFER_COMPLETE/VERIFY_RESULT/SESSION_CLOSE。
 *
 * 尚未实现（已在协议文档中标注为后续项）：分块 SACK 重传窗口、断点续传位图落盘、zstd 压缩。
 */
class PtSession(
    private val role: SessionRole,
    private val pairingCode: String,
    private val payloads: List<PtPayload> = emptyList(),
    private val wantedTypes: Set<ItemType> = emptySet(),
    private val listener: PtSessionListener = object : PtSessionListener {},
    private val chunkSize: Int = ProtocolConstants.CHUNK_SIZE,
    /** 本机型号，仅用于 HELLO 展示；由调用方注入，保持本类纯 JVM。 */
    private val deviceModel: String = "",
    /**
     * SAS 人工确认门：握手算出 SAS 后调用，返回 false 表示用户判定不一致，会话立即终止。
     * 真实链路下会阻塞等待用户在两台手机上比对（协议 §10）；单测里默认直接通过。
     */
    private val sasConfirmer: (Int) -> Boolean = { true },
    /**
     * 发送端在 MANIFEST_OFFER 前调用，用来等待用户在界面上选好数据项（可阻塞）。
     * 为空时使用构造参数 payloads。
     */
    private val payloadProvider: (() -> List<PtPayload>)? = null,
    /**
     * 接收端在回 ITEM_SELECT 前调用，用来等待用户在界面上完成勾选（可阻塞）。
     * 为空时使用构造参数 wantedTypes。
     */
    private val selectionProvider: (() -> Set<ItemType>)? = null,
    private val receiveTarget: ((PtManifestEntry) -> PtReceiveTarget)? = null,
    private val requireReceiveTarget: Boolean = false,
) {

    private val crypto = CryptoEngine()
    private val machine = SessionStateMachine()
    private val transcript = TranscriptHash()

    private val ownDeviceId: ByteArray = crypto.randomBytes(ProtocolConstants.DEVICE_ID_SIZE)
    private val ownNonce: ByteArray = crypto.randomBytes(ProtocolConstants.HANDSHAKE_NONCE_SIZE)
    private var peerDeviceId: ByteArray = ByteArray(ProtocolConstants.DEVICE_ID_SIZE)
    private var peerNonce: ByteArray = ByteArray(ProtocolConstants.HANDSHAKE_NONCE_SIZE)
    private var peerModel: String = ""

    private var sessionId: Int = 0
    private var sendSeq: Int = 0
    private var recvSeq: Int = 0
    private var keys: SessionKeys? = null
    private var peerPublic: ECPublicKey? = null

    private var bytesTotal: Long = 0
    private var bytesDone: Long = 0
    private val results = ArrayList<PtItemResult>()

    /** 发送端入口：out/in 来自已连接的 Socket；返回接收端条目结果（本端为空）。 */
    fun runSender(socket: Socket) {
        socket.tcpNoDelay = true
        run(socket.getOutputStream(), socket.getInputStream(), SessionRole.SENDER)
    }

    /** 按会话自身的角色运行（编排层用它统一入口）。 */
    fun run(socket: Socket) {
        socket.tcpNoDelay = true
        run(socket.getOutputStream(), socket.getInputStream(), role)
    }

    /** 接收端入口：socket 来自 accept()。 */
    fun runReceiver(socket: Socket) {
        socket.tcpNoDelay = true
        run(socket.getOutputStream(), socket.getInputStream(), SessionRole.RECEIVER)
    }

    private fun run(out: OutputStream, input: InputStream, effectiveRole: SessionRole) {
        require(effectiveRole == role) { "会话角色不一致" }
        require(pairingCode.matches(Regex("[0-9]{6}")))
        require(chunkSize in 1..(ProtocolConstants.MAX_FRAME_PAYLOAD - 76))
        try {
            performHandshake(out, input)
            if (role == SessionRole.SENDER) runSenderBody(out, input) else runReceiverBody(out, input)
        } finally {
            // 会话结束立即销毁密钥（协议 §10），并把终态通知给监听者
            keys?.destroy()
            keys = null
            transition(SessionState.CLOSED)
        }
    }

    // ---------------------------------------------------------------- 握手

    private fun performHandshake(out: OutputStream, input: InputStream) {
        transition(SessionState.DISCOVERING)
        if (role == SessionRole.SENDER) {
            send(out, MessageType.HELLO, helloBody())
            transition(SessionState.HELLO)
            val ack = TlvReader(plain(receive(input))).fields()
            readPeerHello(ack)
            send(out, MessageType.PAIR_REQUEST, pairRequestBody())
            transition(SessionState.PAIRING)
            // 必须等对端的 PAIR_ACCEPT：配对码不匹配时对端会先报错并断开（规范 §9）
            val accept = TlvReader(plain(receive(input))).fields()
            val acceptedHash = accept[TlvTag.T_PAIR_CODE_HASH]?.asBytes()
            if (acceptedHash != null && !acceptedHash.contentEquals(pairCodeHash())) {
                throw PtSessionException(ErrorCode.PAIR_CODE_MISMATCH, "对端拒绝配对码")
            }
        } else {
            val hello = TlvReader(plain(receive(input))).fields()
            readPeerHello(hello)
            transition(SessionState.HELLO)
            send(out, MessageType.HELLO_ACK, helloBody())
            val pairRequest = TlvReader(plain(receive(input))).fields()
            verifyPairingCode(pairRequest)
            send(out, MessageType.PAIR_ACCEPT, pairAcceptBody())
            transition(SessionState.PAIRING)
        }

        // KEY_EXCHANGE：约定发送端先发（规范 §10）
        val ownPair = crypto.generateKeyPair()
        transition(SessionState.KEY_EXCHANGE)
        if (role == SessionRole.SENDER) {
            send(out, MessageType.KEY_EXCHANGE, keyExchangeBody(ownPair))
            val peer = TlvReader(plain(receive(input)))
            peerPublic = decodePeerKey(peer)
        } else {
            val peer = TlvReader(plain(receive(input)))
            peerPublic = decodePeerKey(peer)
            send(out, MessageType.KEY_EXCHANGE, keyExchangeBody(ownPair))
        }

        deriveKeys(ownPair)
        val k = keys!!
        listener.onSas(k.sas)
        transition(SessionState.SAS_PENDING)

        // SAS_NOTIFY 起加密。
        // 注意：TranscriptHash.digest() 计算后会复位，因此这里只算一次并复用。
        val transcriptHash = transcript.digest()
        send(out, MessageType.SAS_NOTIFY, sasBody(transcriptHash))
        val peerSas = TlvReader(plain(receive(input)))
        val peerTranscript = peerSas.findBytes(TlvTag.T_TRANSCRIPT_HASH)
        if (peerTranscript != null && !peerTranscript.contentEquals(transcriptHash)) {
            throw PtSessionException(ErrorCode.BAD_TRANSCRIPT, "握手转录哈希不一致（疑似被篡改）")
        }
        val peerSasValue = peerSas.findU16(TlvTag.T_SAS) ?: -1
        if (peerSasValue != k.sas) {
            throw PtSessionException(
                ErrorCode.BAD_TRANSCRIPT,
                "SAS 不一致：本端 " + k.sas + " 对端 " + peerSasValue + "（疑似中间人）",
            )
        }
        // 用户人工比对：不一致必须终止（防中间人）
        if (!sasConfirmer(k.sas)) {
            throw PtSessionException(ErrorCode.SAS_REJECTED, "用户判定 SAS 不一致，会话终止")
        }
        send(out, MessageType.SAS_CONFIRM, confirmBody())
        val peerConfirm = TlvReader(plain(receive(input)))
        if (peerConfirm.findBool(TlvTag.T_SAS_CONFIRMED) != true) {
            throw PtSessionException(ErrorCode.SAS_REJECTED, "对端拒绝 SAS")
        }
        transition(SessionState.READY)

        if (role == SessionRole.RECEIVER) {
            sessionId = randomSessionId()
            send(out, MessageType.SESSION_READY, sessionReadyBody())
        } else {
            val ready = TlvReader(plain(receive(input))).fields()
            sessionId = (ready[TlvTag.T_SESSION_ID]?.asU32() ?: 0L).toInt()
        }
        transition(SessionState.NEGOTIATING)
    }

    private fun helloBody(): ByteArray = TlvWriter()
        .putU16(TlvTag.T_PROTOCOL_VERSION, ProtocolConstants.PROTOCOL_VERSION)
        .putU16(TlvTag.T_ROLE, if (role == SessionRole.SENDER) 1 else 2)
        .putU16(TlvTag.T_OS_NAME, 1)
        .putString(TlvTag.T_DEVICE_MODEL, deviceModel)
        .putBytes(TlvTag.T_NONCE, ownNonce)
        .putBytes(TlvTag.T_DEVICE_ID, ownDeviceId)
        .toByteArray()

    private fun readPeerHello(fields: Map<Int, Tlv>) {
        peerNonce = fields[TlvTag.T_NONCE]?.asBytes() ?: ByteArray(ProtocolConstants.HANDSHAKE_NONCE_SIZE)
        peerDeviceId = fields[TlvTag.T_DEVICE_ID]?.asBytes() ?: ByteArray(ProtocolConstants.DEVICE_ID_SIZE)
        peerModel = fields[TlvTag.T_DEVICE_MODEL]?.asString() ?: ""
        listener.onPeer(peerModel, fields[TlvTag.T_OS_NAME]?.asU16() ?: 1)
    }

    private fun pairCodeHash(): ByteArray {
        val codeBytes = pairingCode.toByteArray(Charsets.UTF_8)
        val first = if (role == SessionRole.SENDER) ownDeviceId else peerDeviceId
        val second = if (role == SessionRole.SENDER) peerDeviceId else ownDeviceId
        return CryptoEngine.sha256(codeBytes + first + second)
    }

    private fun pairRequestBody(): ByteArray = TlvWriter()
        .putU16(TlvTag.T_PAIR_METHOD, 2)
        .putBytes(TlvTag.T_PAIR_CODE_HASH, pairCodeHash())
        .toByteArray()

    private fun pairAcceptBody(): ByteArray = TlvWriter()
        .putU16(TlvTag.T_PAIR_METHOD, 2)
        .putBytes(TlvTag.T_PAIR_CODE_HASH, pairCodeHash())
        .toByteArray()

    /** 接收端校验配对码：只比对哈希，配对码本身不上网（规范 §9）。 */
    private fun verifyPairingCode(fields: Map<Int, Tlv>) {
        val received = fields[TlvTag.T_PAIR_CODE_HASH]?.asBytes()
            ?: throw ProtocolException.missingField("T_PAIR_CODE_HASH")
        if (!received.contentEquals(pairCodeHash())) {
            throw PtSessionException(ErrorCode.PAIR_CODE_MISMATCH, "配对码不匹配")
        }
    }

    private fun keyExchangeBody(pair: KeyPair): ByteArray = TlvWriter()
        .putU16(TlvTag.T_CIPHER_SUITE, 1)
        .putBytes(TlvTag.T_ECDH_PUBKEY, crypto.encodePublicKey(pair.public as ECPublicKey))
        .putU16(TlvTag.T_KEY_ID, 1)
        .toByteArray()

    private fun decodePeerKey(reader: TlvReader): ECPublicKey {
        val suite = reader.findU16(TlvTag.T_CIPHER_SUITE) ?: 1
        if (suite != 1) throw PtSessionException(ErrorCode.MALFORMED_TLV, "不支持的加密套件: " + suite)
        val encoded = reader.findBytes(TlvTag.T_ECDH_PUBKEY)
            ?: throw ProtocolException.missingField("T_ECDH_PUBKEY")
        return crypto.decodePublicKey(encoded)
    }

    private fun deriveKeys(ownPair: KeyPair) {
        val peer = peerPublic ?: throw PtSessionException(ErrorCode.INTERNAL_ERROR, "对端公钥缺失")
        val shared = crypto.ecdh(ownPair.private, peer)
        val nonceC = if (role == SessionRole.SENDER) ownNonce else peerNonce
        val nonceS = if (role == SessionRole.SENDER) peerNonce else ownNonce
        keys = crypto.deriveSessionKeys(shared, nonceC, nonceS, 0)
    }

    private fun sasBody(transcriptHash: ByteArray): ByteArray = TlvWriter()
        .putU16(TlvTag.T_SAS, keys!!.sas)
        .putU16(TlvTag.T_SAS_FORMAT, 1)
        .putBytes(TlvTag.T_TRANSCRIPT_HASH, transcriptHash)
        .toByteArray()

    private fun confirmBody(): ByteArray = TlvWriter().putBool(TlvTag.T_SAS_CONFIRMED, true).toByteArray()

    private fun sessionReadyBody(): ByteArray = TlvWriter()
        .putU32(TlvTag.T_SESSION_ID, sessionId.toLong() and 0xFFFFFFFFL)
        .putU32(TlvTag.T_CHUNK_SIZE, chunkSize.toLong())
        .putU32(TlvTag.T_WINDOW_SIZE, ProtocolConstants.WINDOW_SIZE.toLong())
        .putU16(TlvTag.T_COMPRESSION, 0)
        .toByteArray()

    // ------------------------------------------------------------ 发送端主体

    private fun runSenderBody(out: OutputStream, input: InputStream) {
        transition(SessionState.TRANSFERRING)
        val outgoing = payloadProvider?.invoke() ?: payloads
        require(outgoing.size <= 512 && outgoing.map { it.itemId }.toSet().size == outgoing.size)
        val total = outgoing.fold(0L) { sum, p -> Math.addExact(sum, p.size) }
        val offer = TlvWriter().putU32(TlvTag.T_MANIFEST_ID, 1L).putU64(TlvTag.T_TOTAL_BYTES, total)
        outgoing.forEach { p ->
            offer.putU64(TlvTag.T_ITEM_ID, p.itemId)
                .putU16(TlvTag.T_ITEM_TYPE, p.itemType.code)
                .putString(TlvTag.T_ITEM_NAME, p.name)
                .putU64(TlvTag.T_ITEM_SIZE, p.size)
                .putBytes(TlvTag.T_ITEM_SHA256, p.sha256)
        }
        send(out, MessageType.MANIFEST_OFFER, offer.toByteArray())
        val select = TlvReader(plain(receiveExpected(input, MessageType.ITEM_SELECT)))
        val picked = decodeSelection(select.findBytes(TlvTag.T_SELECTED_ITEM_TYPES))
        plain(receiveExpected(input, MessageType.TRANSFER_START))
        val selected = outgoing.filter { it.itemType in picked }
        bytesTotal = selected.fold(0L) { sum, p -> Math.addExact(sum, p.size) }
        var itemsDone = 0
        listener.onProgress(0, selected.size, 0, bytesTotal)
        selected.forEach { p ->
            val count = if (p.size == 0L) 0L else (p.size - 1) / chunkSize + 1
            require(count <= Int.MAX_VALUE)
            send(out, MessageType.ITEM_BEGIN, TlvWriter()
                .putU64(TlvTag.T_ITEM_ID, p.itemId)
                .putU32(TlvTag.T_CHUNK_SIZE, chunkSize.toLong())
                .putU32(TlvTag.T_BLOCK_COUNT, count).toByteArray())
            var seq = 0
            var offset = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            p.openStream().use { source ->
                while (offset < p.size) {
                    val data = FrameCodec.readFully(source, minOf(chunkSize.toLong(), p.size - offset).toInt())
                    digest.update(data)
                    send(out, MessageType.CHUNK, Chunk.of(p.itemId, seq, offset, data).encode())
                    offset += data.size
                    bytesDone += data.size
                    // Read ACKs during transfer: prevents full-duplex socket buffer deadlock on large files.
                    if ((seq + 1) % ProtocolConstants.ACK_INTERVAL_CHUNKS == 0) {
                        readAck(input, p.itemId, seq)
                    }
                    seq++
                    listener.onProgress(itemsDone, selected.size, bytesDone, bytesTotal)
                }
                if (source.read() != -1 || !digest.digest().contentEquals(p.sha256)) {
                    throw PtSessionException(ErrorCode.ITEM_SHA_MISMATCH, "源文件在传输期间发生变化")
                }
            }
            send(out, MessageType.ITEM_END, TlvWriter().putU64(TlvTag.T_ITEM_ID, p.itemId)
                .putBytes(TlvTag.T_ITEM_SHA256, p.sha256).toByteArray())
            readAck(input, p.itemId, seq - 1)
            results.add(PtItemResult(p.itemId, p.itemType, p.name, p.size, p.sha256, true))
            itemsDone++
            listener.onProgress(itemsDone, selected.size, bytesDone, bytesTotal)
        }
        send(out, MessageType.TRANSFER_COMPLETE, TlvWriter().putU64(TlvTag.T_TOTAL_BYTES, bytesDone)
            .putU32(TlvTag.T_ITEM_COUNT, itemsDone.toLong()).toByteArray())
        transition(SessionState.VERIFYING)
        val verify = TlvReader(plain(receiveExpected(input, MessageType.VERIFY_RESULT)))
        val ok = verify.findU16(TlvTag.T_VERIFY_RESULT) == 0
        transition(SessionState.COMPLETING)
        send(out, MessageType.SESSION_CLOSE, TlvWriter().putU16(TlvTag.T_REASON, 0)
            .putBool(TlvTag.T_CLEANUP_CONFIRM, true).toByteArray())
        plain(receiveExpected(input, MessageType.SESSION_CLOSE))
        if (!ok) throw PtSessionException(ErrorCode.ITEM_SHA_MISMATCH, "接收端校验未通过")
        listener.onResult(results.toList())
    }

    private fun readAck(input: InputStream, id: Long, base: Int) {
        val ack = TlvReader(plain(receiveExpected(input, MessageType.CHUNK_ACK)))
        if (ack.requireU64(TlvTag.T_ITEM_ID) != id ||
            ack.findU32(TlvTag.T_CHUNK_ACK_BASE) != (base.toLong() and 0xFFFFFFFFL)) {
            throw PtSessionException(ErrorCode.MALFORMED_TLV, "确认块序号不匹配")
        }
    }

    private fun receiveExpected(input: InputStream, type: MessageType): Frame {
        val frame = receive(input)
        if (frame.header.messageType() != type) {
            throw PtSessionException(ErrorCode.MALFORMED_TLV, "预期消息 " + type)
        }
        return frame
    }

    private fun decodeSelection(bitmap: ByteArray?): Set<ItemType> {
        if (bitmap == null || bitmap.isEmpty()) return emptySet()
        val mask = bitmap.foldIndexed(0L) { index, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * index)) }
        return ItemType.entries.filter { (mask shr (it.code - 1)) and 1L == 1L }.toSet()
    }

    private fun encodeSelection(types: Set<ItemType>): ByteArray {
        val bytes = ByteArray(8)
        types.forEach { t ->
            val bit = t.code - 1
            bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------ 接收端主体

    private fun runReceiverBody(out: OutputStream, input: InputStream) {
        transition(SessionState.TRANSFERRING)
        val entries = readManifest(plain(receiveExpected(input, MessageType.MANIFEST_OFFER)))
        require(entries.size <= 512 && entries.map { it.itemId }.toSet().size == entries.size)
        require(entries.all { it.size >= 0 && it.sha256.size == 32 })
        if (requireReceiveTarget && receiveTarget == null) error("未设置备份目录")
        listener.onManifest(entries)
        val wanted = selectionProvider?.invoke() ?: wantedTypes
        val picked = if (selectionProvider == null && wanted.isEmpty()) entries
            else entries.filter { it.itemType in wanted }
        send(out, MessageType.ITEM_SELECT, TlvWriter()
            .putBytes(TlvTag.T_SELECTED_ITEM_TYPES, encodeSelection(picked.map { it.itemType }.toSet()))
            .putU16(TlvTag.T_CONFLICT_POLICY, 1).toByteArray())
        send(out, MessageType.TRANSFER_START, TlvWriter().putU32(TlvTag.T_MANIFEST_ID, 1L).toByteArray())
        bytesTotal = picked.fold(0L) { sum, p -> Math.addExact(sum, p.size) }
        bytesDone = 0
        var current: PtManifestEntry? = null
        var target: PtReceiveTarget? = null
        var output: OutputStream? = null
        var digest = MessageDigest.getInstance("SHA-256")
        var seen = 0
        var itemBytes = 0L
        var expectedChunks = 0L
        var negotiatedChunk = 0L
        listener.onProgress(0, picked.size, 0, bytesTotal)
        try {
            while (true) {
                val frame = receive(input)
                when (frame.header.messageType()) {
                    MessageType.ITEM_BEGIN -> {
                        if (current != null) error("文件尚未结束")
                        val r = TlvReader(plain(frame))
                        val id = r.requireU64(TlvTag.T_ITEM_ID)
                        val entry = picked.firstOrNull { it.itemId == id }
                            ?: error("收到未选择的文件")
                        if (results.any { it.itemId == id }) error("重复文件")
                        negotiatedChunk = r.findU32(TlvTag.T_CHUNK_SIZE) ?: error("缺少块大小")
                        require(negotiatedChunk in 1..(ProtocolConstants.MAX_FRAME_PAYLOAD - 76).toLong())
                        expectedChunks = if (entry.size == 0L) 0 else (entry.size - 1) / negotiatedChunk + 1
                        require(expectedChunks <= Int.MAX_VALUE && r.findU32(TlvTag.T_BLOCK_COUNT) == expectedChunks)
                        current = entry
                        target = receiveTarget?.invoke(entry)
                        // Legacy protocol tests/P2P can discard data; backup mode mandates a persistent target.
                        output = target?.output ?: object : OutputStream() {
                            override fun write(b: Int) {}
                            override fun write(b: ByteArray, off: Int, len: Int) {}
                        }
                        digest = MessageDigest.getInstance("SHA-256")
                        seen = 0
                        itemBytes = 0
                    }
                    MessageType.CHUNK -> {
                        val entry = current ?: error("块没有对应文件")
                        val chunk = Chunk.decode(plain(frame))
                        require(chunk.itemId == entry.itemId && chunk.chunkSeq == seen && chunk.offset == itemBytes)
                        require(chunk.data.isNotEmpty() && chunk.data.size.toLong() == minOf(negotiatedChunk, entry.size - itemBytes))
                        if (!chunk.sha256Matches()) throw PtSessionException(ErrorCode.CHUNK_SHA_MISMATCH, "块摘要不一致")
                        output!!.write(chunk.data)
                        digest.update(chunk.data)
                        itemBytes += chunk.data.size
                        bytesDone += chunk.data.size
                        seen++
                        listener.onProgress(results.size, picked.size, bytesDone, bytesTotal)
                        if (seen % ProtocolConstants.ACK_INTERVAL_CHUNKS == 0) sendAck(out, entry.itemId, seen - 1)
                    }
                    MessageType.ITEM_END -> {
                        val entry = current ?: error("没有正在接收的文件")
                        val r = TlvReader(plain(frame))
                        val hash = digest.digest()
                        val ok = r.requireU64(TlvTag.T_ITEM_ID) == entry.itemId &&
                            itemBytes == entry.size && seen.toLong() == expectedChunks &&
                            hash.contentEquals(entry.sha256) && hash.contentEquals(r.requireBytes(TlvTag.T_ITEM_SHA256))
                        output!!.close()
                        if (!ok) throw PtSessionException(ErrorCode.ITEM_SHA_MISMATCH, "文件长度或清单摘要不一致")
                        val location = target?.commit().orEmpty()
                        target = null
                        results.add(PtItemResult(entry.itemId, entry.itemType, entry.name, itemBytes, hash, true, location))
                        current = null
                        output = null
                        sendAck(out, entry.itemId, seen - 1)
                        listener.onProgress(results.size, picked.size, bytesDone, bytesTotal)
                    }
                    MessageType.TRANSFER_COMPLETE -> {
                        val r = TlvReader(plain(frame))
                        require(current == null && results.size == picked.size && bytesDone == bytesTotal)
                        require(r.findU32(TlvTag.T_ITEM_COUNT) == results.size.toLong() && r.requireU64(TlvTag.T_TOTAL_BYTES) == bytesDone)
                        transition(SessionState.VERIFYING)
                        send(out, MessageType.VERIFY_RESULT, TlvWriter().putU16(TlvTag.T_VERIFY_RESULT, 0).toByteArray())
                        transition(SessionState.COMPLETING)
                        val close = TlvReader(plain(receiveExpected(input, MessageType.SESSION_CLOSE)))
                        require(close.findBool(TlvTag.T_CLEANUP_CONFIRM) == true)
                        send(out, MessageType.SESSION_CLOSE, TlvWriter().putU16(TlvTag.T_REASON, 0)
                            .putBool(TlvTag.T_CLEANUP_CONFIRM, true).toByteArray())
                        listener.onResult(results.toList())
                        return
                    }
                    else -> throw PtSessionException(ErrorCode.MALFORMED_TLV, "接收端收到意外消息")
                }
            }
        } finally {
            runCatching { output?.close() }
            target?.abort()
        }
    }

    private fun sendAck(out: OutputStream, itemId: Long, base: Int) {
        send(out, MessageType.CHUNK_ACK, TlvWriter()
            .putU64(TlvTag.T_ITEM_ID, itemId)
            .putU32(TlvTag.T_CHUNK_ACK_BASE, base.toLong() and 0xFFFFFFFFL)
            .putU32(TlvTag.T_CHUNK_ACK_BITMAP, 0L)
            .putU32(TlvTag.T_CREDIT, ProtocolConstants.WINDOW_SIZE.toLong())
            .toByteArray())
    }

    private fun readManifest(payload: ByteArray): List<PtManifestEntry> {
        val reader = TlvReader(payload)
        val entries = ArrayList<PtManifestEntry>()
        var id: Long? = null
        var type: ItemType? = null
        var name = ""
        var size = 0L
        var sha: ByteArray? = null
        for (tlv in reader.readAll()) {
            when (tlv.tag) {
                TlvTag.T_ITEM_ID -> {
                    if (id != null && type != null && sha != null) {
                        entries.add(PtManifestEntry(id, type, name, size, sha))
                    }
                    id = tlv.asU64()
                    type = null
                    name = ""
                    size = 0L
                    sha = null
                }
                TlvTag.T_ITEM_TYPE -> type = ItemType.fromCode(tlv.asU16())
                TlvTag.T_ITEM_NAME -> name = tlv.asString()
                TlvTag.T_ITEM_SIZE -> size = tlv.asU64()
                TlvTag.T_ITEM_SHA256 -> sha = tlv.value
            }
        }
        if (id != null && type != null && sha != null) entries.add(PtManifestEntry(id, type, name, size, sha))
        return entries
    }

    // ------------------------------------------------------------ 帧读写

    private fun send(out: OutputStream, type: MessageType, payload: ByteArray) {
        val k = keys
        val encrypted = k != null
        val payloadLen = payload.size + if (encrypted) ProtocolConstants.GCM_TAG_SIZE else 0
        val header = FrameHeader(
            msgType = type.code,
            flags = if (encrypted) FrameFlags.ENCRYPTED else 0,
            sessionId = sessionId,
            payloadLen = payloadLen,
            msgSeq = sendSeq,
        )
        val body = if (encrypted) crypto.encryptPayload(sendKey(k!!), header, payload) else payload
        val frame = Frame(header, body)
        FrameCodec.writeFrame(out, frame)
        if (!encrypted) transcript.update(frame.header, frame.payload)
        sendSeq += 1
    }

    private fun receive(input: InputStream): Frame {
        val frame = FrameCodec.readFrameOrNull(input)
            ?: throw PtSessionException(ErrorCode.CHANNEL_LOST, "连接已关闭")
        frame.header.validate()
        if (frame.header.msgSeq != recvSeq || (keys != null && !frame.header.encrypted)) {
            throw PtSessionException(ErrorCode.DECRYPT_FAILED, "帧序号异常或加密降级")
        }
        if (!frame.header.encrypted) transcript.update(frame.header, frame.payload)
        recvSeq = frame.header.msgSeq + 1
        return frame
    }

    private fun plain(frame: Frame): ByteArray {
        if (!frame.header.encrypted) return frame.payload
        val k = keys ?: throw PtSessionException(ErrorCode.DECRYPT_FAILED, "未协商密钥却收到加密帧")
        return crypto.decryptPayload(recvKey(k), frame.header, frame.payload)
    }

    private fun sendKey(k: SessionKeys): ByteArray = if (role == SessionRole.SENDER) k.kC2S else k.kS2C

    private fun recvKey(k: SessionKeys): ByteArray = if (role == SessionRole.SENDER) k.kS2C else k.kC2S

    private fun transition(state: SessionState) {
        machine.tryTransition(state)
        listener.onState(machine.state)
    }

    private fun randomSessionId(): Int {
        val b = crypto.randomBytes(4)
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    /** 供测试与日志查看当前状态。 */
    val state: SessionState get() = machine.state
}
