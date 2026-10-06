package com.phonetransfer.app.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 会话状态机测试：合法迁移、非法迁移、终态与迁移表。 */
class SessionStateMachineTest {

    private val happyPath = listOf(
        SessionState.DISCOVERING,
        SessionState.HELLO,
        SessionState.PAIRING,
        SessionState.KEY_EXCHANGE,
        SessionState.SAS_PENDING,
        SessionState.READY,
        SessionState.NEGOTIATING,
        SessionState.TRANSFERRING,
        SessionState.VERIFYING,
        SessionState.COMPLETING,
        SessionState.CLOSED,
        SessionState.IDLE,
    )

    private fun drivenTo(target: SessionState): SessionStateMachine {
        val path = ArrayList<SessionState>()
        path.add(SessionState.DISCOVERING)
        path.add(SessionState.HELLO)
        path.add(SessionState.PAIRING)
        path.add(SessionState.KEY_EXCHANGE)
        path.add(SessionState.SAS_PENDING)
        path.add(SessionState.READY)
        path.add(SessionState.NEGOTIATING)
        path.add(SessionState.TRANSFERRING)
        when (target) {
            SessionState.VERIFYING -> path.add(SessionState.VERIFYING)
            SessionState.PAUSED, SessionState.FAILED, SessionState.RESUMING -> path.add(target)
            else -> Unit
        }
        val machine = SessionStateMachine()
        for (state in path) {
            assertTrue(machine.transition(state))
        }
        assertEquals(target, machine.state)
        return machine
    }

    @Test
    fun happyPathFollowsDocumentedTable() {
        val machine = SessionStateMachine()
        assertEquals(SessionState.IDLE, machine.state)
        assertFalse(machine.isTerminal)
        for (state in happyPath) {
            assertTrue("应可迁移到 " + state, machine.canTransitionTo(state))
            assertTrue(machine.transition(state))
            assertEquals(state, machine.state)
        }
        assertEquals(SessionState.IDLE, machine.state)
        assertEquals(happyPath.size + 1, machine.history.size)
        assertEquals(SessionState.IDLE, machine.history.first())
    }

    @Test
    fun illegalTransitionsAreRejected() {
        val machine = SessionStateMachine()
        assertFalse(machine.canTransitionTo(SessionState.READY))
        assertFalse(machine.canTransitionTo(SessionState.IDLE))
        try {
            machine.transition(SessionState.READY)
            fail("非法迁移应抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IDLE"))
        }
        assertEquals(SessionState.IDLE, machine.state)
        assertFalse(machine.tryTransition(SessionState.TRANSFERRING))
        assertEquals(SessionState.IDLE, machine.state)
        assertEquals(listOf(SessionState.IDLE), machine.history)
    }

    @Test
    fun terminalAndTransferPhasesAreClassified() {
        assertTrue(SessionState.CLOSED.isTerminal)
        assertTrue(SessionState.FAILED.isTerminal)
        assertTrue(SessionState.FAILED.isFailure)
        assertFalse(SessionState.TRANSFERRING.isTerminal)
        assertFalse(SessionState.IDLE.isTerminal)
        assertTrue(SessionState.TRANSFERRING.isTransferPhase)
        assertTrue(SessionState.PAUSED.isTransferPhase)
        assertTrue(SessionState.RESUMING.isTransferPhase)
        assertFalse(SessionState.VERIFYING.isTransferPhase)

        val machine = SessionStateMachine()
        machine.transition(SessionState.DISCOVERING)
        machine.transition(SessionState.FAILED)
        assertTrue(machine.isTerminal)
        assertTrue(machine.canTransitionTo(SessionState.RESUMING))
        assertTrue(machine.transition(SessionState.RESUMING))
        assertFalse(machine.isTerminal)
    }

    @Test
    fun pauseResumeAndFailureBranchesWork() {
        val transfer = drivenTo(SessionState.TRANSFERRING)
        assertTrue(transfer.canTransitionTo(SessionState.PAUSED))
        assertTrue(transfer.canTransitionTo(SessionState.RESUMING))
        assertTrue(transfer.transition(SessionState.PAUSED))
        assertTrue(transfer.canTransitionTo(SessionState.CLOSED))
        assertTrue(transfer.transition(SessionState.TRANSFERRING))
        assertTrue(transfer.transition(SessionState.RESUMING))
        assertTrue(transfer.transition(SessionState.PAUSED))
        assertTrue(transfer.transition(SessionState.FAILED))
        assertTrue(transfer.canTransitionTo(SessionState.CLOSED))
        assertTrue(transfer.transition(SessionState.RESUMING))
        assertTrue(transfer.transition(SessionState.TRANSFERRING))
        assertFalse(transfer.canTransitionTo(SessionState.CLOSED))
    }

    @Test
    fun transitionCallbackAndResetBehave() {
        val machine = SessionStateMachine()
        val seen = ArrayList<String>()
        machine.onTransition = { from, to -> seen.add(from.name + ">" + to.name) }
        machine.transition(SessionState.DISCOVERING)
        machine.transition(SessionState.HELLO)
        assertEquals(listOf("IDLE>DISCOVERING", "DISCOVERING>HELLO"), seen)
        assertEquals(listOf(SessionState.IDLE, SessionState.DISCOVERING, SessionState.HELLO), machine.history)

        machine.reset()
        assertEquals(SessionState.IDLE, machine.state)
        assertEquals(listOf(SessionState.IDLE), machine.history)
        assertEquals(SessionState.IDLE, SessionStateMachine().state)
    }

    @Test
    fun allowedTargetsMatchSpecTable() {
        assertEquals(setOf(SessionState.DISCOVERING), SessionStateMachine.allowedFrom(SessionState.IDLE))
        assertEquals(setOf(SessionState.HELLO, SessionState.FAILED), SessionStateMachine.allowedFrom(SessionState.DISCOVERING))
        assertEquals(setOf(SessionState.PAIRING, SessionState.FAILED), SessionStateMachine.allowedFrom(SessionState.HELLO))
        assertEquals(setOf(SessionState.KEY_EXCHANGE, SessionState.FAILED), SessionStateMachine.allowedFrom(SessionState.PAIRING))
        assertEquals(setOf(SessionState.SAS_PENDING, SessionState.FAILED), SessionStateMachine.allowedFrom(SessionState.KEY_EXCHANGE))
        assertEquals(setOf(SessionState.READY, SessionState.FAILED), SessionStateMachine.allowedFrom(SessionState.SAS_PENDING))
        assertEquals(setOf(SessionState.NEGOTIATING, SessionState.CLOSED), SessionStateMachine.allowedFrom(SessionState.READY))
        assertEquals(setOf(SessionState.TRANSFERRING, SessionState.CLOSED), SessionStateMachine.allowedFrom(SessionState.NEGOTIATING))
        assertEquals(
            setOf(SessionState.PAUSED, SessionState.VERIFYING, SessionState.FAILED, SessionState.RESUMING),
            SessionStateMachine.allowedFrom(SessionState.TRANSFERRING),
        )
        assertEquals(
            setOf(SessionState.TRANSFERRING, SessionState.CLOSED, SessionState.FAILED),
            SessionStateMachine.allowedFrom(SessionState.PAUSED),
        )
        assertEquals(
            setOf(SessionState.TRANSFERRING, SessionState.PAUSED, SessionState.FAILED),
            SessionStateMachine.allowedFrom(SessionState.RESUMING),
        )
        assertEquals(
            setOf(SessionState.COMPLETING, SessionState.TRANSFERRING),
            SessionStateMachine.allowedFrom(SessionState.VERIFYING),
        )
        assertEquals(setOf(SessionState.CLOSED), SessionStateMachine.allowedFrom(SessionState.COMPLETING))
        assertEquals(setOf(SessionState.IDLE), SessionStateMachine.allowedFrom(SessionState.CLOSED))
        assertEquals(setOf(SessionState.CLOSED, SessionState.RESUMING), SessionStateMachine.allowedFrom(SessionState.FAILED))
        assertTrue(SessionStateMachine.allowedFrom(SessionState.READY).isNotEmpty())
        assertTrue(SessionStateMachine.TRANSITIONS.size == SessionState.values().size)
    }
}
