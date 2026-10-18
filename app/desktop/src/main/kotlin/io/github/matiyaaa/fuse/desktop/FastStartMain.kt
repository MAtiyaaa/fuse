package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopFuseServices
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.FastStart
import io.github.matiyaaa.fuse.ui.shell.store.createRunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Fuse Fast Start (`Fuse --run <id>`), for a game's entry in Steam: the game starts with no window,
 * exactly as Play starts it ([FastStart]), and Fuse leaves once it has ended and its saves are kept
 * and sent. Only Fuse's database, its secret store and Fuse One's rounds for the saves are opened:
 * no window, no graphics toolkit, no scan, art, download or household list.
 *
 * When something only the person can decide comes up (two saves, another device still playing),
 * nothing is decided here: [run] answers null and Fuse opens on the game as `--play` does.
 */
internal object FastStartMain {
    /** The process's exit code once the game is over, or null to open Fuse on the game instead. */
    fun run(dirs: FuseDirs, id: Long): Int? = runBlocking {
        Log.info("Fast Start: Fuse's code ran ${sinceStart()} ms after Fuse started")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val services = DesktopFuseServices.create(dirs, scope)
        Log.info("Fast Start: services ready at ${sinceStart()} ms")
        // How long from the process starting to the emulator's own process, measured each time.
        services.launcherHooks.onGameStarted = { Log.info("Fast Start: the game started ${sinceStart()} ms after Fuse did") }
        try {
            val store = createRunStore(services, scope)
            Log.info("Fast Start: store ready at ${sinceStart()} ms")
            when (val outcome = FastStart.run(store, GameId(id))) {
                FastStart.Outcome.Played -> 0
                is FastStart.Outcome.OpenedApp -> {
                    Log.info("Fast Start: only ${outcome.app} opened, so there is no game to follow")
                    0
                }
                is FastStart.Outcome.NeedsYou -> {
                    Log.info("Fast Start: Fuse opens on the game, since ${outcome.why}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.warn("Fast Start couldn't start game $id", e)
            null
        } finally {
            scope.cancel()
            runCatching { services.close() }
        }
    }

    /** Milliseconds since this process began. */
    private fun sinceStart(): Long =
        (System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().startTime).coerceAtLeast(0)

    /** The game `--run <id>` asks for, or null. */
    fun gameOf(args: Array<String>): Long? = args.indexOf(FLAG).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() }

    const val FLAG = "--run"
}
