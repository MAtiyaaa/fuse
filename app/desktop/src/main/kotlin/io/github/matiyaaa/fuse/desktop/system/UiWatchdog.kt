package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.desktop.Log
import java.awt.EventQueue

/**
 * Notices the interface thread (AWT's event thread, which Compose draws on) stalling: a heartbeat is
 * posted to it every [beatMs], and when one waits longer than [stallMs] the thread's stack goes into
 * Fuse's log once for that stall, with how long it lasted when it ends. A frozen screen then says
 * what it was waiting on, instead of nothing.
 */
internal class UiWatchdog(private val beatMs: Long = 250, private val stallMs: Long = 2_000) {
    @Volatile private var answeredAt = System.nanoTime()
    @Volatile private var running = true

    fun start() {
        val ui = arrayOfNulls<Thread>(1)
        EventQueue.invokeLater { ui[0] = Thread.currentThread() }
        Thread({
            // When the heartbeat now waiting was posted; 0 while the thread keeps up.
            var waitingSince = 0L
            var reported = false
            while (running) {
                val posted = System.nanoTime()
                EventQueue.invokeLater { answeredAt = System.nanoTime() }
                try {
                    Thread.sleep(beatMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (answeredAt >= posted) {
                    if (reported) Log.info("the interface thread is answering again after ${(System.nanoTime() - waitingSince) / 1_000_000} ms")
                    waitingSince = 0L
                    reported = false
                    continue
                }
                if (waitingSince == 0L) waitingSince = posted
                val waited = (System.nanoTime() - waitingSince) / 1_000_000
                if (!reported && waited >= stallMs) {
                    reported = true
                    Log.warn("the interface thread has been busy for $waited ms")
                    ui[0]?.stackTrace?.let { trace -> Log.detail(trace.take(MAX_FRAMES).joinToString("\n") { "    at $it" }) }
                }
            }
        }, "fuse-ui-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
    }

    private companion object {
        const val MAX_FRAMES = 60
    }
}
