package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.desktop.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Makes sure Fuse is really gone once it quits, so Steam's Exit Game finishes: Steam waits for every
 * process its app started, and stayed on "Exiting game" while a game or Cartridge that Fuse started
 * still ran, or while a step of Fuse's own shutdown hung. When Fuse quits (by itself, or told to by
 * Steam), the games and Cartridge it started end with it, and Fuse ends within [DEADLINE_MS] whatever
 * is left. Only what Fuse started to play is ended, never its own helpers (an update being applied,
 * Steam being restarted), which are meant to outlive it.
 */
object ExitGuard {
    private val played = ConcurrentHashMap.newKeySet<ProcessHandle>()
    @Volatile private var installed = false
    @Volatile private var deadlineSet = false

    /** A game or app Fuse started to play: it ends when Fuse does. */
    fun played(process: Process) {
        played += process.toHandle()
        process.onExit().thenRun { played -= process.toHandle() }
    }

    /** Watches for Fuse quitting (a shutdown hook); once is enough. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        Runtime.getRuntime().addShutdownHook(Thread({ quitting() }, "fuse-exit-guard"))
    }

    /** Fuse is quitting: its games end, and Fuse is gone by the deadline whatever still runs. */
    fun quitting() {
        deadline()
        endPlayed()
    }

    /** Fuse ends within [DEADLINE_MS] from now, even when something in its shutdown hangs. */
    @Synchronized
    fun deadline() {
        if (deadlineSet) return
        deadlineSet = true
        // A daemon thread: it never keeps Fuse up, and halts it only if quitting takes too long.
        Thread({
            Thread.sleep(DEADLINE_MS)
            Log.warn("Fuse didn't finish quitting in ${DEADLINE_MS / 1000} s; ending it")
            Runtime.getRuntime().halt(0)
        }, "fuse-exit-deadline").apply { isDaemon = true; start() }
    }

    /**
     * Asks what Fuse started to play, and what those started, to close: the polite signal a window's
     * close sends (SIGTERM), never a forced end, so a game saves on its way out as when closed
     * itself. False when nothing of Fuse's is running.
     */
    fun askPlayedToClose(): Boolean {
        val all = played.filter { it.isAlive }.flatMap { h -> runCatching { h.descendants().toList() }.getOrDefault(emptyList()) + h }.distinct()
        if (all.isEmpty()) return false
        Log.info("asking what Fuse started to close: " + all.joinToString { it.pid().toString() })
        all.forEach { runCatching { it.destroy() } }
        return true
    }

    /** Ends what Fuse started to play, the processes those started too: asked first, then made to. */
    fun endPlayed() {
        val all = played.filter { it.isAlive }.flatMap { h -> runCatching { h.descendants().toList() }.getOrDefault(emptyList()) + h }.distinct()
        if (all.isEmpty()) return
        Log.info("ending what Fuse started before quitting: " + all.joinToString { it.pid().toString() })
        all.forEach { runCatching { it.destroy() } }
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACE_MS)
        while (all.any { it.isAlive } && System.nanoTime() < until) Thread.sleep(50)
        all.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
    }

    const val DEADLINE_MS = 6_000L
    private const val GRACE_MS = 1_500L
}
