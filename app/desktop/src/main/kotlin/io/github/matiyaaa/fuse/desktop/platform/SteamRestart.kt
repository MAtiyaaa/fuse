package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * What a restart of Steam is for, written down for the helper that does it when Fuse can't (Fuse
 * runs inside Steam: Game Mode, or started from its Steam entry, so it closes along with Steam).
 * [fuse] is "add" or "repair" for Fuse's own entry; [games] go in with their [art].
 */
internal data class SteamRestartJob(
    val program: String,
    /** The person's home (the helper may run with less of Fuse's environment than Fuse had). */
    val home: String,
    /** Where the helper leaves what happened, for Fuse's next start. */
    val note: String,
    /** Fuse was in Game Mode: Steam is the session there. */
    val gameMode: Boolean,
    val fuse: String? = null,
    val games: List<Game> = emptyList(),
    val collections: Boolean = false,
    /** Fuse is opened again from Steam once Steam is back (it was running inside Steam). */
    val reopen: Boolean = true,
) {
    data class Game(
        val id: Long,
        val name: String,
        val collection: String? = null,
        val portrait: String? = null,
        val wide: String? = null,
        val hero: String? = null,
        val logo: String? = null,
        val icon: String? = null,
    ) {
        val entry get() = SteamGameEntry(id, name, collection)
        val art get() = SteamArt(portrait, wide, hero, logo, icon)
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(toJson().toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun toJson(): JsonObject = buildJsonObject {
        put("program", program)
        put("home", home)
        put("note", note)
        put("gameMode", gameMode)
        fuse?.let { put("fuse", it) }
        put("collections", collections)
        put("reopen", reopen)
        put("games", buildJsonArray {
            for (g in games) add(buildJsonObject {
                put("id", g.id)
                put("name", g.name)
                g.collection?.let { put("collection", it) }
                g.portrait?.let { put("portrait", it) }
                g.wide?.let { put("wide", it) }
                g.hero?.let { put("hero", it) }
                g.logo?.let { put("logo", it) }
                g.icon?.let { put("icon", it) }
            })
        })
    }

    companion object {
        fun read(file: File): SteamRestartJob? = runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            SteamRestartJob(
                program = o.text("program")!!,
                home = o.text("home")!!,
                note = o.text("note")!!,
                gameMode = o["gameMode"]?.jsonPrimitive?.booleanOrNull ?: false,
                fuse = o.text("fuse"),
                collections = o["collections"]?.jsonPrimitive?.booleanOrNull ?: false,
                reopen = o["reopen"]?.jsonPrimitive?.booleanOrNull ?: true,
                games = o["games"]?.jsonArray.orEmpty().map { e ->
                    val g = e.jsonObject
                    Game(g["id"]!!.jsonPrimitive.longOrNull!!, g.text("name")!!, g.text("collection"), g.text("portrait"), g.text("wide"), g.text("hero"), g.text("logo"), g.text("icon"))
                },
            )
        }.getOrNull()

        fun games(games: List<SteamGameEntry>, art: Map<Long, SteamArt>): List<Game> = games.map { g ->
            val a = art[g.gameId]
            Game(g.gameId, g.name, g.collection, a?.portrait, a?.wide, a?.hero, a?.logo, a?.icon)
        }
    }
}

/** What restarting Steam needs from the system, so the order of it can be tested without one. */
internal interface SteamProcessOps {
    fun steamRunning(): Boolean
    /** A game started by Steam is running (Fuse's own entry, when Fuse runs inside Steam, doesn't count). */
    fun gameRunning(): Boolean
    /** Asks Steam to quit the way it quits by itself; false when it couldn't be asked. */
    fun askSteamToClose(): Boolean
    /** Starts Steam again: the desktop client, or Game Mode's session where Steam is the session. */
    fun startSteam(gameMode: Boolean): Boolean
    /** Opens Fuse from its Steam entry. */
    fun openFuse(): Boolean
    fun sleep(ms: Long)
}

/**
 * Closes Steam, makes the change while it is closed, and brings Steam back, in that order. Never
 * while a game Steam started is running; never writes while Steam is still open. In Game Mode
 * Steam is the session: when it quits, the session ends and the login manager starts Game Mode
 * again (gamescope-session-plus on Bazzite and ChimeraOS, SteamOS's own session), so the session
 * is only asked back (`steamos-session-select gamescope`) if it hasn't come back by itself.
 */
internal class SteamRestarter(private val ops: SteamProcessOps, private val gameMode: Boolean) {

    fun run(reopenFuse: Boolean, apply: () -> Result<String>): Result<String> {
        if (ops.gameRunning()) return Result.failure(IllegalStateException("A game is running in Steam. Fuse never closes it: try again when it ends, or wait for Steam to close."))
        if (ops.steamRunning()) {
            if (!ops.askSteamToClose()) return Result.failure(IllegalStateException("Steam couldn't be asked to close. Close it and try again."))
            if (!waitFor(CLOSE_WAIT_MS) { !ops.steamRunning() }) return Result.failure(IllegalStateException("Steam hasn't closed yet. Finish its prompts, then try again."))
        }
        val result = apply()
        // Steam back: Game Mode's session usually restarts it by itself; the desktop client never does.
        val back = if (gameMode) waitFor(SESSION_WAIT_MS) { ops.steamRunning() } || (ops.startSteam(gameMode = true) && waitFor(START_WAIT_MS) { ops.steamRunning() })
        else ops.startSteam(gameMode = false) && waitFor(START_WAIT_MS) { ops.steamRunning() }
        if (back && reopenFuse) {
            // Steam answers links only once its interface is up; give it a moment after it starts.
            ops.sleep(SETTLE_MS)
            ops.openFuse()
        }
        return result
    }

    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        var waited = 0L
        while (waited <= ms) {
            if (done()) return true
            ops.sleep(POLL_MS)
            waited += POLL_MS
        }
        return done()
    }

    companion object {
        const val POLL_MS = 250L
        const val CLOSE_WAIT_MS = 30_000L
        const val SESSION_WAIT_MS = 20_000L
        const val START_WAIT_MS = 60_000L
        const val SETTLE_MS = 8_000L
    }
}

/** The real system: `steam -shutdown`, `pgrep`, the session switcher and Steam's own links. */
internal class SystemSteamProcessOps(private val fuseProgram: () -> String?, private val fuseGameId: () -> Long?) : SteamProcessOps {
    override fun steamRunning(): Boolean = when (DesktopOs.current) {
        DesktopOs.WINDOWS -> Processes.run(listOf("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH"), timeoutMs = 3_000)?.stdout?.contains("steam.exe", ignoreCase = true) == true
        DesktopOs.MACOS -> Processes.run(listOf("pgrep", "-x", "steam_osx"), timeoutMs = 3_000)?.exitCode == 0
        DesktopOs.LINUX -> Processes.run(listOf("pgrep", "-x", "steam"), timeoutMs = 3_000)?.exitCode == 0
    }

    override fun gameRunning(): Boolean {
        if (DesktopOs.current != DesktopOs.LINUX) return false
        val lines = Processes.run(listOf("pgrep", "-af", "SteamLaunch AppId="), timeoutMs = 3_000)?.takeIf { it.exitCode == 0 }?.stdout ?: return false
        return steamGamesIn(lines, fuseProgram())
    }

    override fun askSteamToClose(): Boolean {
        val steam = Processes.which("steam") ?: return false
        return Processes.run(listOf(steam, "-shutdown"), timeoutMs = 5_000) != null
    }

    override fun startSteam(gameMode: Boolean): Boolean {
        if (gameMode) {
            // SteamOS, Bazzite and ChimeraOS all have it: it ends the session and Game Mode starts again.
            val select = Processes.which("steamos-session-select") ?: return false
            return Processes.spawn(listOf(select, "gamescope")) != null
        }
        val steam = Processes.which("steam") ?: return false
        return Processes.spawn(listOf(steam)) != null
    }

    override fun openFuse(): Boolean {
        val id = fuseGameId() ?: return false
        val steam = Processes.which("steam") ?: return false
        return Processes.spawn(listOf(steam, "steam://rungameid/$id")) != null
    }

    override fun sleep(ms: Long) = Thread.sleep(ms)

    companion object {
        /**
         * Whether [pgrepLines] (`pgrep -af "SteamLaunch AppId="`) has a game in it: every Steam launch
         * wrapper except the one that started Fuse itself (its command names [fuseProgram]).
         */
        fun steamGamesIn(pgrepLines: String, fuseProgram: String?): Boolean = pgrepLines.lineSequence()
            .map { it.trim() }
            .filter { it.contains("SteamLaunch AppId=") }
            .any { line -> fuseProgram.isNullOrEmpty() || !line.contains(fuseProgram) }
    }
}

/**
 * The helper's side: run as `Fuse --steam-apply <job>` outside Steam's process tree, it restarts
 * Steam around the job, leaves what happened as a note for Fuse's next start, and exits.
 */
internal object SteamApplyMain {
    const val ARG = "--steam-apply"
    const val NOTE = "steam-restart.note"

    /** What Steam and its links need to find the running session. */
    private val PASSED_ENV = listOf("DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "GAMESCOPE_WAYLAND_DISPLAY", "APPIMAGE")

    fun run(jobFile: File): Int {
        val job = SteamRestartJob.read(jobFile) ?: return 2
        jobFile.delete()
        val steam = DesktopSteam(io.github.matiyaaa.fuse.desktop.services.KnownFolders(job.home, DesktopOs.current), programPath = { job.program })
        val ops = SystemSteamProcessOps(fuseProgram = { job.program }, fuseGameId = { steam.fuseGameId() })
        val result = SteamRestarter(ops, gameMode = job.gameMode).run(job.reopen) { steam.applyRestartJob(job) }
        val note = result.fold({ it }, { it.message ?: "Steam's library couldn't be updated. Nothing was lost." })
        runCatching { File(job.note).writeText(note) }
        Log.info("Steam restart: $note")
        return if (result.isSuccess) 0 else 1
    }

    /**
     * Starts the helper so it outlives Steam (and this Fuse with it): as its own systemd user unit,
     * or in a session of its own where systemd isn't there. False when neither could start.
     */
    fun launch(program: String, jobFile: File): Boolean {
        val argv = listOf(program, ARG, jobFile.absolutePath)
        Processes.which("systemd-run")?.let { run ->
            // A user unit starts with the user manager's environment, not Fuse's: carry the session's.
            val env = PASSED_ENV.mapNotNull { k -> System.getenv(k)?.takeIf { it.isNotEmpty() }?.let { "--setenv=$k=$it" } }
            if (Processes.spawn(listOf(run, "--user", "--collect", "--quiet") + env + argv) != null) return true
        }
        Processes.which("setsid")?.let { setsid ->
            if (Processes.spawn(listOf(setsid, "-f") + argv) != null) return true
        }
        return false
    }
}
