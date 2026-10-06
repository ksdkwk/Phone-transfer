package com.phonetransfer.app.softap

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

data class BackupSnapshot(
    val active: Boolean = false,
    val phase: String = "idle",
    val status: String = "",
    val credentials: String = "",
    val sas: Int? = null,
    val done: Long = 0,
    val total: Long = 0,
    val itemsDone: Int = 0,
    val itemCount: Int = 0,
    val report: String = "",
)

/** Immutable snapshots cross threads; rotation attaches to the current service, not a new session. */
object BackupState {
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(BackupSnapshot) -> Unit>()
    @Volatile var snapshot = BackupSnapshot()
        private set

    @Synchronized fun update(change: (BackupSnapshot) -> BackupSnapshot) {
        snapshot = change(snapshot)
        val value = snapshot
        main.post { observers.forEach { it(value) } }
    }
    fun observe(observer: (BackupSnapshot) -> Unit) {
        observers.add(observer)
        observer(snapshot)
    }
    fun remove(observer: (BackupSnapshot) -> Unit) { observers.remove(observer) }
}
