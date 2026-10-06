package com.phonetransfer.app.ui.transfer.flow

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.phonetransfer.app.R
import com.phonetransfer.app.core.capability.Scenario
import com.phonetransfer.app.core.crypto.CryptoEngine
import com.phonetransfer.app.core.crypto.SessionKeys
import com.phonetransfer.app.core.protocol.ItemType
import com.phonetransfer.app.core.protocol.ProtocolConstants
import com.phonetransfer.app.core.session.SessionState
import com.phonetransfer.app.core.session.SessionStateMachine
import com.phonetransfer.app.core.settings.ConflictPolicy
import com.phonetransfer.app.core.transport.PtItemResult
import com.phonetransfer.app.ui.common.SubPageHost
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/** 换机方式：左半部分 / 右半部分两个入口。 */
enum class TransferMode(@StringRes val titleRes: Int) {
    SAME_BRAND(R.string.transfer_same_brand),
    CROSS_BRAND(R.string.transfer_cross_brand),
}

/** 本机角色（协议 §7.4 T_ROLE）。 */
enum class DeviceRole(@StringRes val labelRes: Int) {
    OLD_PHONE(R.string.flow_role_old),
    NEW_PHONE(R.string.flow_role_new),
}

/** 报告里的一行：数据项 + 结果。 */
data class ItemOutcome(val itemType: ItemType, @StringRes val statusRes: Int)

@StringRes
fun SessionState.labelRes(): Int = when (this) {
    SessionState.IDLE -> R.string.flow_phase_idle
    SessionState.DISCOVERING -> R.string.flow_phase_discovering
    SessionState.HELLO -> R.string.flow_phase_hello
    SessionState.PAIRING -> R.string.flow_phase_pairing
    SessionState.KEY_EXCHANGE -> R.string.flow_phase_key_exchange
    SessionState.SAS_PENDING -> R.string.flow_phase_sas_pending
    SessionState.READY -> R.string.flow_phase_ready
    SessionState.NEGOTIATING -> R.string.flow_phase_negotiating
    SessionState.TRANSFERRING -> R.string.flow_phase_transferring
    SessionState.PAUSED -> R.string.flow_phase_paused
    SessionState.RESUMING -> R.string.flow_phase_resuming
    SessionState.VERIFYING -> R.string.flow_phase_verifying
    SessionState.COMPLETING -> R.string.flow_phase_completing
    SessionState.CLOSED -> R.string.flow_phase_closed
    SessionState.FAILED -> R.string.flow_phase_failed
}

/** 把毫秒格式化成「x 分 yy 秒」/「x 秒」（走资源，随语言本地化）。 */
fun Fragment.formatElapsed(millis: Long): String {
    val totalSeconds = if (millis < 0) 0 else millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        getString(R.string.flow_duration_min_sec, minutes, seconds)
    } else {
        getString(R.string.flow_duration_sec, seconds)
    }
}

/** 在同一个内容容器里打开下一步（带返回栈）。 */
fun Fragment.openFlowStep(fragment: Fragment) {
    (activity as? SubPageHost)?.openSubPage(fragment)
}

/** 结束整条流程，回到「换机」功能区。 */
fun Fragment.closeFlow() {
    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
}

/**
 * 换机主流程的内存会话。
 *
 * 设计取舍：
 *  · 由现有的 [SessionStateMachine] 驱动阶段，界面只渲染状态，避免 UI 自造状态机；
 *  · 配对码与 SAS 走真实的密码学实现（[CryptoEngine]：P-256 ECDH + HKDF + SAS），
 *    但「对端」是本机生成的临时密钥对——因为通道层尚未接入，属于演练；
 *  · 会话密钥只在内存中，流程结束或取消时调用 [clear] 立即销毁（协议 §10）。
 */
object TransferFlowSession {

    private val random = SecureRandom()
    private val crypto = CryptoEngine()

    var mode: TransferMode = TransferMode.SAME_BRAND
        private set
    var role: DeviceRole = DeviceRole.OLD_PHONE

    /** 旧机选择的换机技术；新机由旧机的建网方式决定，不单独选。 */
    var channel: TransferChannel = TransferChannel.WIFI_DIRECT
    var pairingCode: String = "000000"
        private set
    var sas: Int = 0
    var sessionId: Int = 0
        private set
    var keys: SessionKeys? = null
        private set
    var conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME
        private set
    var includeExifLocation: Boolean = false
        private set
    var cancelled: Boolean = false
        private set
    var startedAtMs: Long = 0L
        private set
    var finishedAtMs: Long = 0L
        private set

    // ---- 真实链路（Wi-Fi Direct）运行时由 P2pTransfer 写入，界面轮询读取 ----
    @Volatile var realSession: Boolean = false
    @Volatile var realBytesDone: Long = 0
    @Volatile var realBytesTotal: Long = 0
    @Volatile var realItemsDone: Int = 0
    @Volatile var realItemCount: Int = 0
    @Volatile var realError: String = ""
    val realResults: MutableList<PtItemResult> = mutableListOf()

    fun resetReal() {
        realSession = false
        realBytesDone = 0
        realBytesTotal = 0
        realItemsDone = 0
        realItemCount = 0
        realError = ""
        realResults.clear()
    }

    val machine = SessionStateMachine()
    val selectedItems: MutableSet<ItemType> = LinkedHashSet()
    val outcomes: MutableList<ItemOutcome> = mutableListOf()

    /** V1.0 只承诺 Android ↔ Android（可行性文档 §9.1）。 */
    val scenario: Scenario get() = Scenario.A_TO_A

    val isFinished: Boolean
        get() = machine.state == SessionState.CLOSED || machine.state == SessionState.FAILED

    fun start(mode: TransferMode) {
        clear()
        this.mode = mode
        pairingCode = newPairingCode()
        startedAtMs = System.currentTimeMillis()
        machine.reset(SessionState.IDLE)
        machine.tryTransition(SessionState.DISCOVERING)
    }

    /** 旧机输入新机上显示的配对码。 */
    fun setPairingCode(code: String) {
        pairingCode = code
    }

    fun regeneratePairingCode() {
        pairingCode = newPairingCode()
    }

    /**
     * 演练握手：本机临时密钥对与一个本机生成的「对端」临时密钥对做真实 ECDH，
     * 双方独立派生共享密钥并校验一致，再用 HKDF 派生出会话密钥与 4 位 SAS。
     */
    fun performHandshake(): Boolean {
        machine.tryTransition(SessionState.HELLO)
        machine.tryTransition(SessionState.PAIRING)
        machine.tryTransition(SessionState.KEY_EXCHANGE)

        keys?.destroy()
        keys = null

        val local: KeyPair = crypto.generateKeyPair()
        val peer: KeyPair = crypto.generateKeyPair()
        val localPublic = crypto.encodePublicKey(local.public as ECPublicKey)
        val peerPublic = crypto.encodePublicKey(peer.public as ECPublicKey)
        val sharedLocal = crypto.sharedSecret(local, peerPublic)
        val sharedPeer = crypto.sharedSecret(peer, localPublic)
        if (!sharedLocal.contentEquals(sharedPeer)) {
            machine.tryTransition(SessionState.FAILED)
            return false
        }
        val nonceC = crypto.randomBytes(ProtocolConstants.HANDSHAKE_NONCE_SIZE)
        val nonceS = crypto.randomBytes(ProtocolConstants.HANDSHAKE_NONCE_SIZE)
        sessionId = randomInt()
        val derived = crypto.deriveSessionKeys(sharedLocal, nonceC, nonceS, sessionId)
        keys = derived
        sas = derived.sas
        machine.tryTransition(SessionState.SAS_PENDING)
        return true
    }

    fun confirmSas() {
        machine.tryTransition(SessionState.READY)
        machine.tryTransition(SessionState.NEGOTIATING)
    }

    fun abortSas() {
        cancelled = true
        machine.tryTransition(SessionState.FAILED)
        finishedAtMs = System.currentTimeMillis()
        buildOutcomes()
    }

    fun beginTransfer(policy: ConflictPolicy, exif: Boolean) {
        conflictPolicy = policy
        includeExifLocation = exif
        machine.tryTransition(SessionState.TRANSFERRING)
    }

    fun finishTransfer() {
        machine.tryTransition(SessionState.VERIFYING)
        buildOutcomes()
        machine.tryTransition(SessionState.COMPLETING)
        machine.tryTransition(SessionState.CLOSED)
        finishedAtMs = System.currentTimeMillis()
    }

    fun cancelTransfer() {
        cancelled = true
        machine.tryTransition(SessionState.FAILED)
        machine.tryTransition(SessionState.CLOSED)
        finishedAtMs = System.currentTimeMillis()
        buildOutcomes()
    }

    /** 销毁会话密钥并清空流程状态（协议 §10：不留任何密钥残留）。 */
    fun clear() {
        resetReal()
        keys?.destroy()
        keys = null
        selectedItems.clear()
        outcomes.clear()
        cancelled = false
        sas = 0
        sessionId = 0
        finishedAtMs = 0L
        machine.reset(SessionState.IDLE)
    }

    private fun buildOutcomes() {
        outcomes.clear()
        val status = if (cancelled) R.string.flow_report_status_cancelled else R.string.flow_report_status_done
        selectedItems.forEach { outcomes.add(ItemOutcome(it, status)) }
    }

    private fun newPairingCode(): String =
        (0 until ProtocolConstants.PAIR_CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }

    private fun randomInt(): Int {
        val bytes = crypto.randomBytes(4)
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }
}
