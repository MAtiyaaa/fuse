package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ephemeral application state shared by every window, isolated from persistent profile settings. */
class DisplaySession {
    /** All windows leave production content together while the developer sandbox is open. */
    var rehearsalOpen by mutableStateOf(false)

    private val state = MutableStateFlow(false)
    val standby = state.asStateFlow()
    var lastWakeAt: Long = 0L
        private set

    fun enterStandby() { state.value = true }

    /** All displays wake together; also resets idle time after a companion touch. */
    fun wake(now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()) {
        lastWakeAt = now
        state.value = false
    }
}
