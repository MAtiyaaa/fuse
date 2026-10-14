package io.github.matiyaaa.fuse.ui.designsystem.effects

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Fuseline 4.2: work that doesn't feed the first screen waits for it. The apps [hold] the gate
 * before the library opens, and Fuse [open]s it once Home is drawn and has settled (or after
 * [LIMIT_MS] whatever happens), so series grouping, suggestions, the RomM refresh and the automatic
 * scan never compete with the first frames. Nothing holds it in tests or tools, where it is open.
 */
object StartupGate {
    private val settled = MutableStateFlow(true)

    val isOpen: Boolean get() = settled.value

    /** Called by an app before its library opens. */
    fun hold() {
        settled.value = false
    }

    fun open() {
        settled.value = true
    }

    /** Returns once the first screen has settled (at once when nothing holds the gate). */
    suspend fun settled() {
        if (settled.value) return
        kotlinx.coroutines.withTimeoutOrNull(LIMIT_MS) { settled.first { it } }
        settled.value = true
    }

    /** The longest anything waits, so a first screen that never settles holds nothing back for long. */
    const val LIMIT_MS = 12_000L
}
