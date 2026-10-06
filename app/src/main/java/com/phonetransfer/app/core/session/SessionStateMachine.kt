package com.phonetransfer.app.core.session

/**
 * 会话状态机（规范 §14）。
 *
 * 迁移表严格对应文档表格；[transition] 在非法迁移时抛 IllegalStateException，
 * [tryTransition] 在非法迁移时返回 false 且不改变状态。
 */
class SessionStateMachine(initialState: SessionState = SessionState.IDLE) {

    private val historyList = ArrayList<SessionState>()

    /** 当前状态。 */
    var state: SessionState = initialState
        private set

    /** 迁移回调，参数为 (from, to)。 */
    var onTransition: ((from: SessionState, to: SessionState) -> Unit)? = null

    init {
        historyList.add(initialState)
    }

    /** 当前状态全部合法目标。 */
    fun allowedTargets(): Set<SessionState> = TRANSITIONS[state] ?: emptySet()

    /** 当前状态是否可迁移到目标状态。 */
    fun canTransitionTo(target: SessionState): Boolean = target in allowedTargets()

    /** 当前状态是否为终态。 */
    val isTerminal: Boolean get() = state.isTerminal

    /** 迁移历史（含初始状态）。 */
    val history: List<SessionState> get() = ArrayList(historyList)

    /**
     * 执行迁移；非法迁移抛 IllegalStateException。
     *
     * @return 状态确实发生变化时为 true（表中不存在自迁移，故合法迁移恒为 true）。
     */
    fun transition(target: SessionState): Boolean {
        if (!canTransitionTo(target)) {
            throw IllegalStateException("非法状态迁移: " + state + " -> " + target)
        }
        return moveTo(target)
    }

    /** 宽松迁移：非法时返回 false 且状态不变。 */
    fun tryTransition(target: SessionState): Boolean =
        if (canTransitionTo(target)) moveTo(target) else false

    /** 复位到指定状态并清空历史。 */
    fun reset(target: SessionState = SessionState.IDLE) {
        state = target
        historyList.clear()
        historyList.add(target)
    }

    private fun moveTo(target: SessionState): Boolean {
        if (target == state) return false
        val from = state
        state = target
        historyList.add(target)
        onTransition?.invoke(from, target)
        return true
    }

    companion object {

        /** §14 迁移表：状态 -> 允许迁移到的状态集合。 */
        val TRANSITIONS: Map<SessionState, Set<SessionState>> = mapOf(
            SessionState.IDLE to setOf(SessionState.DISCOVERING),
            SessionState.DISCOVERING to setOf(SessionState.HELLO, SessionState.FAILED),
            SessionState.HELLO to setOf(SessionState.PAIRING, SessionState.FAILED),
            SessionState.PAIRING to setOf(SessionState.KEY_EXCHANGE, SessionState.FAILED),
            SessionState.KEY_EXCHANGE to setOf(SessionState.SAS_PENDING, SessionState.FAILED),
            SessionState.SAS_PENDING to setOf(SessionState.READY, SessionState.FAILED),
            SessionState.READY to setOf(SessionState.NEGOTIATING, SessionState.CLOSED),
            SessionState.NEGOTIATING to setOf(SessionState.TRANSFERRING, SessionState.CLOSED),
            SessionState.TRANSFERRING to setOf(
                SessionState.PAUSED,
                SessionState.VERIFYING,
                SessionState.FAILED,
                SessionState.RESUMING,
            ),
            SessionState.PAUSED to setOf(SessionState.TRANSFERRING, SessionState.CLOSED, SessionState.FAILED),
            SessionState.RESUMING to setOf(SessionState.TRANSFERRING, SessionState.PAUSED, SessionState.FAILED),
            SessionState.VERIFYING to setOf(SessionState.COMPLETING, SessionState.TRANSFERRING),
            SessionState.COMPLETING to setOf(SessionState.CLOSED),
            SessionState.CLOSED to setOf(SessionState.IDLE),
            SessionState.FAILED to setOf(SessionState.CLOSED, SessionState.RESUMING),
        )

        /** 指定状态的合法目标集合。 */
        fun allowedFrom(state: SessionState): Set<SessionState> = TRANSITIONS[state] ?: emptySet()
    }
}
