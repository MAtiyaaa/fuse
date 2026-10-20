package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.services.KnownFolders
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.GameMode
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamArtShape
import io.github.matiyaaa.fuse.library.steam.SteamCollection
import io.github.matiyaaa.fuse.library.steam.SteamCollectionChoices
import io.github.matiyaaa.fuse.library.steam.SteamCollections
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamGamePlanItem
import io.github.matiyaaa.fuse.library.steam.SteamGames
import io.github.matiyaaa.fuse.library.steam.SteamHabit
import io.github.matiyaaa.fuse.library.steam.SteamHealthIssue
import io.github.matiyaaa.fuse.library.steam.SteamLearn
import io.github.matiyaaa.fuse.library.steam.SteamLearned
import io.github.matiyaaa.fuse.library.steam.SteamShortcut
import io.github.matiyaaa.fuse.library.steam.SteamShortcuts
import io.github.matiyaaa.fuse.library.steam.SteamStart
import io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveChange
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveQueue
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLogo
import io.github.matiyaaa.fuse.ui.shell.platform.SteamUndoStep
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Puts Fuse in Steam's library as a non-Steam game, for each Steam user on this computer, so Game
 * Mode can start it. The entry runs the AppImage (or the installed program) full screen, with
 * Fuse's own art in every place Steam shows a game: the library capsule, the wide capsule, the
 * hero, the logo over it and the icon (in the user's `config/grid`, by the entry's id; art already
 * there, such as the person's own, is left as it is). The first time a user's list is changed, a
 * copy of it is kept next to it.
 */
internal class DesktopSteam(
    private val folders: KnownFolders,
    /** What Steam should start for Fuse; the running AppImage or program unless a test says otherwise. */
    private val programPath: () -> String? = ::runningProgram,
    /** Fuse's data folder, where a restart of Steam leaves its job and its note; null: no restarts from inside Steam. */
    private val dataDir: String? = null,
    /** Whether Steam is running; the system's process list unless a test says otherwise. */
    private val steamOpen: (() -> Boolean)? = null,
    /** Makes changes through Steam's running client by its debug port when the person allows it (Fuse itself, not a helper or test). */
    clientLink: Boolean = false,
) : SteamIntegration {
    override val gameMode: Boolean get() = GameMode.active

    /**
     * Fuse runs inside Steam: Game Mode, or started from its Steam entry (Steam names the entry it
     * started in the environment). Closing Steam closes this Fuse too, so a helper of its own does
     * the restart and opens Fuse again (see [SteamApplyMain]).
     */
    override val closesFuse: Boolean get() = gameMode || insideSteam(System.getenv())

    override suspend fun added(): Boolean = withContext(Dispatchers.IO) {
        val exe = program() ?: return@withContext false
        userLists().any { f -> SteamShortcuts.contains(runCatching { f.readBytes() }.getOrNull(), quote(exe)) }
    }

    override suspend fun installed(): Boolean = withContext(Dispatchers.IO) {
        folders.steamPlaces().first.any { root -> File(root, "userdata").isDirectory || File(root, "steamapps").isDirectory || File(root, "config").isDirectory }
    }

    override suspend fun hasUser(): Boolean = withContext(Dispatchers.IO) { userConfigDirs(create = false).isNotEmpty() || userDataDirs().isNotEmpty() }

    override val canBeAdded: Boolean get() = program()?.let { File(it).canExecute() || (DesktopOs.isMac && File(it).isDirectory) } == true

    /** Steam on Linux reads a Direct entry's launch options through the shell, so Fuse's mark stays with it (see [SteamGames]). */
    override val directEntries: Boolean get() = DesktopOs.current == DesktopOs.LINUX

    /**
     * [games] as this computer's Steam can have them: a Direct entry's program as a full path (a plan
     * may name one Steam finds on `PATH`, such as `flatpak`), one written the person's own way only
     * while their program is still there (else as Play starts it), and a game whose Direct entry
     * can't be made here (another system, a program not found) through Fast Start instead.
     */
    private fun prepared(games: List<SteamGameEntry>): List<SteamGameEntry> = games.map { g ->
        val d = g.direct ?: return@map g
        val theirs = d.written?.takeIf { w -> SteamLearn.words(w.exe).firstOrNull()?.let { File(it).isFile } == true }
        if (directEntries && theirs != null && SteamGames.canWrite(d)) return@map g
        val program = d.argv.firstOrNull()?.let { p -> if (File(p).isAbsolute) p.takeIf { File(it).exists() } else Processes.which(p) }
        val ready = program?.let { d.copy(argv = listOf(it) + d.argv.drop(1), written = null) }
        if (directEntries && ready != null && SteamGames.canWrite(ready)) g.copy(direct = ready) else g.copy(direct = null)
    }

    private val mutation = Mutex()

    override suspend fun addFuse(): Result<String> = mutate(repair = false)

    override suspend fun steamRunning(): Boolean = withContext(Dispatchers.IO) { steamRunningNow() }

    override suspend fun gameRunning(): Boolean = withContext(Dispatchers.IO) { gameRunningNow() }

    @Volatile private var waiting: kotlinx.coroutines.Job? = null
    override val waitingForSteam: Boolean get() = waiting?.isActive == true

    override suspend fun addWhenSteamCloses(): Result<String> {
        if (!steamRunning()) return addFuse()
        if (waitingForSteam) return Result.success("Fuse goes into Steam's library when Steam next closes.")
        waiting = watcher.launch {
            // Steam is never closed or started by this: Fuse only waits for it to be gone.
            while (steamRunningNow()) delay(STEAM_POLL_MS)
            mutate(repair = false).onSuccess { Log.info("Steam: Fuse added after Steam closed") }.onFailure { Log.warn("Steam: adding Fuse after Steam closed failed", it) }
        }
        return Result.success("Fuse goes into Steam's library when Steam next closes. Steam isn't closed for it.")
    }

    override suspend fun removeFuse(): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            val exe = program()
            var done = 0
            for (dir in userConfigDirs(create = false)) {
                val file = File(dir, "shortcuts.vdf").takeIf { it.isFile } ?: continue
                val before = runCatching { file.readBytes() }.getOrNull() ?: continue
                val after = SteamShortcuts.remove(before) { path, name -> isFuse(path, name, exe) } ?: continue
                if (after.contentEquals(before)) continue
                val backup = File(dir, "shortcuts.vdf.before-fuse-removal")
                runCatching { backup.writeBytes(before) }
                replaceAtomically(file, after)
                done++
            }
            if (done == 0) Result.success("Fuse wasn't in Steam's library.") else Result.success("Fuse is out of Steam's library. Your other shortcuts are as they were.")
        }
    }
    override suspend fun rebuild(): Result<String> = lifecycle(repair = true)
    override suspend fun addForSetup(): Result<String> = lifecycle(repair = false)

    /** Closes Steam, writes Fuse's entry while it is closed, and starts Steam again (never while a game runs). */
    private suspend fun lifecycle(repair: Boolean): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            val executable = program()
            if (executable == null || (!File(executable).canExecute() && !(DesktopOs.isMac && File(executable).isDirectory)))
                return@withContext Result.failure(IllegalStateException("Run Fuse from its AppImage or installed copy, then retry."))
            if (userConfigDirs(create = false).isEmpty())
                return@withContext Result.failure(IllegalStateException("Sign in to Steam once before adding Fuse."))
            restart(SteamRestartJob(executable, home = "", note = "", gameMode = gameMode, fuse = if (repair) "repair" else "add")) { mutateUnlocked(repair) }
        }
    }

    override suspend fun addGamesRestartingSteam(games: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean): Result<String> {
        if (games.isEmpty()) return Result.success("Nothing to add.")
        // Steam's running client takes them through the plugin: no restart needed.
        if (!steamRunning() || changesLive) return addGames(games, art, collections)
        return mutation.withLock {
            withContext(Dispatchers.IO) {
                val executable = program() ?: return@withContext Result.failure(IllegalStateException("Run Fuse from its AppImage or installed copy: Steam starts these games through it."))
                waitingGames?.cancel()
                restart(SteamRestartJob(executable, home = "", note = "", gameMode = gameMode, games = SteamRestartJob.games(games, art), collections = collections)) {
                    writeGames(games, art, collections)
                }
            }
        }
    }

    /**
     * Restarts Steam around [apply]. From outside Steam, here and now; from inside it (see
     * [closesFuse]) by handing [job] to a helper that outlives Steam and this Fuse, and opens Fuse
     * again afterwards, where setup or the page it was on picks up.
     */
    private fun restart(job: SteamRestartJob, apply: () -> Result<String>): Result<String> {
        val ops = SystemSteamProcessOps(fuseProgram = { program() }, fuseGameId = { fuseGameId() })
        if (!closesFuse) return SteamRestarter(ops, gameMode = false).run(reopenFuse = false, apply = apply)
        if (ops.gameRunning()) return Result.failure(IllegalStateException("A game is running in Steam. Fuse never closes it: try again when it ends, or wait for Steam to close."))
        val data = dataDir ?: return Result.failure(IllegalStateException("Steam can't be restarted from here. Add it when Steam closes instead."))
        val file = File(data, "steam-restart.json")
        val full = job.copy(home = folders.homePath, note = File(data, SteamApplyMain.NOTE).path)
        runCatching { full.write(file) }.onFailure { return Result.failure(IllegalStateException("Fuse couldn't prepare the restart: ${it.message}")) }
        if (!SteamApplyMain.launch(full.program, file)) {
            file.delete()
            return Result.failure(IllegalStateException("Fuse couldn't start its helper to restart Steam. Add it when Steam closes instead."))
        }
        return Result.success("Steam and Fuse are restarting. Fuse opens again in a moment, right where you were.")
    }

    /** What a helper's job asks for, done now that Steam is closed (see [SteamApplyMain]). */
    internal fun applyRestartJob(job: SteamRestartJob): Result<String> {
        val parts = buildList {
            job.fuse?.let { add(mutateUnlocked(repair = it == "repair")) }
            if (job.games.isNotEmpty()) add(writeGames(job.games.map { it.entry }, job.games.associate { it.id to it.art }, job.collections))
        }
        // Steam is closed: the moment to give Fuse's entries their icon back if it went missing.
        runCatching { dressEntries() }
        parts.firstOrNull { it.isFailure }?.let { return it }
        return Result.success(parts.mapNotNull { it.getOrNull() }.joinToString(" ").ifEmpty { "Nothing needed changing." })
    }

    /** Steam's id for running Fuse's own entry (`steam://rungameid/...`), from the first Steam user that has it. */
    internal fun fuseGameId(): Long? {
        val exe = program()
        for (dir in userConfigDirs(create = false)) {
            val bytes = File(dir, "shortcuts.vdf").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() } ?: continue
            val id = SteamShortcuts.idsOf(bytes) { path, name -> isFuse(path, name, exe) }.firstOrNull() ?: continue
            // A shortcut's 32-bit id in the top half, and the "shortcut" kind in the bottom.
            return ((id and 0xFFFFFFFFL) shl 32) or 0x02000000L
        }
        return null
    }

    private suspend fun mutate(repair: Boolean): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) { mutateUnlocked(repair) }
    }

    private fun mutateUnlocked(repair: Boolean): Result<String> {
        val exe = program()?.takeIf { File(it).canExecute() || (DesktopOs.isMac && File(it).isDirectory) } ?: return Result.failure(IllegalStateException("Fuse isn't running from its AppImage or an installed copy, so Steam would have nothing to start."))
        if (steamRunningNow()) return Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits. Then add Fuse again."))
        val users = userConfigDirs()
        if (users.isEmpty()) return Result.failure(IllegalStateException("No Steam user here yet. Sign in to Steam once, close it, then try again."))
        val base = SteamShortcut(
            name = "Fuse",
            exe = quote(exe),
            startDir = quote(File(exe).parent ?: "."),
            launchOptions = "--fullscreen",
            tags = listOf("Fuse"),
        )
        var done = 0
        for (dir in users) {
            val file = File(dir, "shortcuts.vdf")
            try {
                // Steam's art for the entry, then the entry pointing at its icon.
                val oldId = SteamShortcuts.idsOf(File(dir, "shortcuts.vdf").takeIf { it.isFile }?.readBytes()) { path, name -> isFuse(path, name, exe) }.firstOrNull()
                val artId = oldId ?: SteamShortcuts.appId(base.exe, base.name).toLong().and(0xFFFFFFFFL)
                val icon = runCatching { placeArt(File(dir, "grid"), artId) }.onFailure { Log.warn("could not give Steam Fuse's art in $dir", it) }.getOrNull()
                val shortcut = base.copy(icon = icon ?: "")
                val before = if (file.isFile) file.readBytes() else null
                val owned = { path: String, name: String -> isFuse(path, name, exe) }
                val iconExists = { path: String -> File(path).isFile }
                val after = (if (repair) SteamShortcuts.rebuild(before, shortcut, owned, iconExists) else SteamShortcuts.add(before, shortcut, owned, iconExists)) ?: continue
                if (before != null) {
                    val backup = File(dir, "shortcuts.vdf.before-fuse")
                    if (!backup.exists()) backup.writeBytes(before)
                }
                if (repair && before != null) Files.copy(file.toPath(), File(dir, "shortcuts.vdf.before-fuse-repair").toPath(), StandardCopyOption.REPLACE_EXISTING)
                replaceAtomically(file, after)
                done++
            } catch (e: Exception) {
                Log.warn("could not add Fuse to Steam in $dir", e)
            }
        }
        if (done == 0) return Result.failure(IllegalStateException("Steam's library couldn't be written."))
        else return Result.success(if (done == 1) "Fuse is in Steam's library. Start Steam and find it under Non-Steam." else "Fuse is in Steam's library for $done users. Start Steam and find it under Non-Steam.")
    }

    /**
     * Gives every Steam entry that starts Fuse its art, whoever made it: Add Fuse to Steam, or
     * Steam's own Add a Non-Steam Game (which names it after the AppImage, often with a version).
     * Only art files are written, never Steam's list, so this is safe while Steam runs; Steam shows
     * them the next time it draws its library. Art already there stays. Returns how many entries.
     *
     * With Steam closed, an entry whose icon is missing (none, or a picture since removed) is also
     * pointed at Fuse's icon; Steam reads its list as it starts. While Steam runs it would write its
     * own copy over that, so the list waits for a start with Steam closed, or Add Fuse to Steam.
     */
    fun dressEntries(): Int {
        val exe = program()
        val steamClosed = !steamRunningNow()
        var n = 0
        for (dir in userConfigDirs(create = false)) {
            val file = File(dir, "shortcuts.vdf")
            val bytes = file.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() } ?: continue
            val owned = { path: String, name: String -> isFuse(path, name, exe) }
            val icons = HashMap<Long, String>()
            for (id in SteamShortcuts.idsOf(bytes, owned)) {
                runCatching { placeArt(File(dir, "grid"), id) }.onSuccess { icons[id] = it; n++ }.onFailure { Log.warn("could not give Steam Fuse's art in $dir", it) }
            }
            if (!steamClosed) continue
            val repaired = SteamShortcuts.repairIcons(bytes, owned, iconExists = { File(it).isFile }) { icons[it].orEmpty() } ?: continue
            runCatching { replaceAtomically(file, repaired) }
                .onSuccess { Log.info("Steam: Fuse's icon put back in $dir") }
                .onFailure { Log.warn("could not put Fuse's icon back in $dir", it) }
        }
        return n
    }

    // ------------------------------------------------------------------------------------ games

    /** A Fuse, by its program: the one running, or one named like Fuse (an older AppImage). */
    private fun fuseProgram(path: String): Boolean = isFuse(path, "", program())

    override suspend fun gamesInSteam(): Set<Long> = withContext(Dispatchers.IO) {
        userLists().flatMap { f -> SteamGames.inSteam(runCatching { f.readBytes() }.getOrNull(), ::fuseProgram).keys }.toSet()
    }

    override suspend fun planGames(games: List<SteamGameEntry>): List<SteamGamePlanItem>? = withContext(Dispatchers.IO) {
        val exe = program() ?: return@withContext null
        val ready = prepared(games)
        val dirs = userConfigDirs(create = false)
        if (dirs.isEmpty()) return@withContext null
        // Each Steam user's list on its own; a game counts as new if it is new for any of them.
        val plans = dirs.mapNotNull { dir -> SteamGames.plan(File(dir, "shortcuts.vdf").takeIf { it.isFile }?.readBytes(), exe, ready, ::fuseProgram) }
        if (plans.isEmpty()) return@withContext null
        val order = listOf(SteamGameAction.CREATE, SteamGameAction.UPDATE, SteamGameAction.ELSEWHERE, SteamGameAction.UNCHANGED)
        ready.distinctBy { it.gameId }.map { g ->
            val actions = plans.mapNotNull { p -> p.firstOrNull { it.entry.gameId == g.gameId }?.action }
            SteamGamePlanItem(g, order.firstOrNull { it in actions } ?: SteamGameAction.UNCHANGED)
        }
    }

    @Volatile private var waitingGames: kotlinx.coroutines.Job? = null

    override suspend fun addGames(games: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean): Result<String> {
        if (games.isEmpty()) return Result.success("Nothing to add.")
        if (steamRunning()) {
            // With the Fuse Decky plugin there, Steam's running client makes them now.
            addLive(games, art, collections)?.let { return it }
            // Steam is never closed for this: Fuse waits for it to be gone, then writes.
            addWhenClosed(games, art, collections)
            return Result.success(if (games.size == 1) "It goes into Steam's library when Steam next closes. Steam isn't closed for it." else "They go into Steam's library when Steam next closes. Steam isn't closed for it.")
        }
        return mutation.withLock { withContext(Dispatchers.IO) { writeGames(games, art, collections) } }
    }

    override val waitingForSteamGames: Boolean get() = waitingGames?.isActive == true

    override val live: SteamLiveQueue = SteamLiveQueue(System::currentTimeMillis)

    /** Steam's folders here, with Flatpak Steam's data folder, where Decky Loader's installer puts the debug port's flag too. */
    private fun steamRoots(): List<File> =
        (folders.steamPlaces().first.map(::File) + File(folders.homePath, ".var/app/com.valvesoftware.Steam/data/Steam"))
            .filter { it.isDirectory }

    private val steamClient = DesktopSteamClient(::steamRoots, live)
    override val client: io.github.matiyaaa.fuse.ui.shell.platform.SteamClientLink = steamClient

    /**
     * [games] put in Steam through its running client while it runs (by the Fuse Decky plugin, bridge
     * protocol 2, or by Fuse through Steam's debug port): each new one added, each of Fuse's brought
     * up to date, with their pictures, exactly as a write of the file would make them. Steam writes
     * them into its file when it quits; collections, which Steam keeps apart, follow then. Null when
     * Steam's client can't be reached; games it didn't make go the usual way, when Steam closes.
     */
    private suspend fun addLive(games: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean): Result<String>? {
        val queue = live.takeIf { it.connected } ?: return null
        val exe = program() ?: return null
        val ready = prepared(games)
        val lists = withContext(Dispatchers.IO) { userLists().mapNotNull { runCatching { it.readBytes() }.getOrNull() } }
        val known = lists.fold(HashMap<Long, Long>()) { m, b -> m.apply { SteamGames.inSteam(b, ::fuseProgram).forEach { (k, v) -> putIfAbsent(k, v) } } }
        val made = queue.made()
        val plans = lists.mapNotNull { SteamGames.plan(it, exe, ready, ::fuseProgram) }.ifEmpty { listOfNotNull(SteamGames.plan(null, exe, ready, ::fuseProgram)) }
        val changes = ArrayList<SteamLiveChange>()
        for (g in ready.distinctBy { it.gameId }) {
            val actions = plans.mapNotNull { p -> p.firstOrNull { it.entry.gameId == g.gameId }?.action }
            val appId = made[g.gameId] ?: known[g.gameId]
            if (appId == null && SteamGameAction.ELSEWHERE in actions) continue
            if (appId != null && made[g.gameId] == null && actions.all { it == SteamGameAction.UNCHANGED }) continue
            val (program, dir, options) = SteamGames.startOf(lists.firstOrNull { SteamGames.inSteam(it, ::fuseProgram).containsKey(g.gameId) }, exe, g, ::fuseProgram)
            val pictures = art[g.gameId]?.let(::liveArt).orEmpty()
            changes += if (appId == null) {
                SteamLiveChange(
                    op = SteamLiveChange.Op.ADD, gameId = g.gameId, name = g.name, exe = program, startDir = dir, launchOptions = options, art = pictures,
                    logo = SteamLogo.FUSE.takeIf { "logo" in pictures },
                )
            } else {
                SteamLiveChange(op = SteamLiveChange.Op.UPDATE, gameId = g.gameId, appId = appId, name = g.name, exe = program, startDir = dir, launchOptions = options)
            }
        }
        if (changes.isEmpty()) return Result.success("Already in Steam's library, as they are.")
        val results = queue.offer(changes)
        val failed = changes.indices.filter { !results[it].ok }.map { changes[it].gameId }.toSet()
        results.filter { !it.ok }.forEach { Log.warn("Steam: its running client didn't make a change: ${it.message}") }
        // What it didn't make, and the collections, go in when Steam next closes.
        val later = games.filter { it.gameId in failed }
        if (later.isNotEmpty() || collections) addWhenClosed(later.ifEmpty { games }, art, collections)
        val inNow = changes.size - failed.size
        return Result.success(
            when {
                inNow == 0 -> "Steam didn't take them while it runs, so they go in when Steam next closes."
                failed.isNotEmpty() -> "$inNow in Steam now; the rest go in when Steam next closes."
                inNow == 1 -> "In Steam now." + if (collections) " Its collection follows when Steam next closes." else ""
                else -> "$inNow in Steam now." + if (collections) " Their collections follow when Steam next closes." else ""
            },
        )
    }

    /** Fuse's pictures for a live change, by Steam's asset kinds: only files that are there, each in its shape ([SteamArtShape]). */
    private fun liveArt(art: SteamArt): Map<String, String> = listOfNotNull(
        art.portrait?.let { Triple("capsule", "portrait", it) }, art.hero?.let { Triple("hero", "hero", it) }, art.logo?.let { Triple("logo", "logo", it) },
        art.wide?.let { Triple("header", "wide", it) }, art.icon?.let { Triple("icon", "icon", it) },
    ).filter { (_, kind, path) -> File(path).isFile && inShape(kind, File(path)) }.associate { (asset, _, path) -> asset to path }

    /** Writes [games] (and their collections) once Steam has closed by itself; Steam is never closed for it. */
    private fun addWhenClosed(games: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean) {
        waitingGames?.cancel()
        waitingGames = watcher.launch {
            while (steamRunningNow()) delay(STEAM_POLL_MS)
            live.steamClosed()
            mutation.withLock { writeGames(games, art, collections) }
                .onSuccess { Log.info("Steam: games written after Steam closed") }
                .onFailure { Log.warn("Steam: writing games after Steam closed failed", it) }
        }
    }

    private fun writeGames(asked: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean): Result<String> {
        val games = prepared(asked)
        val exe = program()?.takeIf { File(it).canExecute() || (DesktopOs.isMac && File(it).isDirectory) }
            ?: return Result.failure(IllegalStateException("Run Fuse from its AppImage or installed copy: Steam starts these games through it."))
        if (steamRunningNow()) return Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
        val users = userConfigDirs()
        if (users.isEmpty()) return Result.failure(IllegalStateException("No Steam user here yet. Sign in to Steam once, close it, then try again."))
        var added = 0
        var updated = 0
        var elsewhere = 0
        var written = 0
        val step = nextStep()
        for (dir in users) {
            try {
                val file = File(dir, "shortcuts.vdf")
                val before = if (file.isFile) file.readBytes() else null
                val plan = SteamGames.plan(before, exe, games, ::fuseProgram) ?: continue
                // Steam's art for each entry, by the id it has (or will have), before the entry names its icon.
                val ids = SteamGames.inSteam(before, ::fuseProgram)
                val icons = HashMap<Long, String>()
                for (item in plan) {
                    if (item.action == SteamGameAction.ELSEWHERE) continue
                    val pictures = art[item.entry.gameId] ?: continue
                    val id = ids[item.entry.gameId] ?: SteamGames.newArtId(exe, item.entry)
                    runCatching { placeGameArt(File(dir, "grid"), id, pictures) }
                        .onSuccess { icon -> if (icon.isNotEmpty()) icons[item.entry.gameId] = icon }
                        .onFailure { Log.warn("could not give Steam a game's art in $dir", it) }
                }
                val after = SteamGames.write(before, exe, games, ::fuseProgram) { icons[it].orEmpty() } ?: continue
                if (before == null || !after.contentEquals(before)) {
                    // The list as it was, for Undo.
                    keepUndo(dir, before, step, changesInWords(plan.count { it.action == SteamGameAction.CREATE }, plan.count { it.action == SteamGameAction.UPDATE }))
                    replaceAtomically(file, after)
                }
                if (collections) runCatching { writeCollections(dir, after, games.mapNotNull { it.collection }.toSet()) }.onFailure { Log.warn("could not write Steam collections in $dir", it) }
                added = maxOf(added, plan.count { it.action == SteamGameAction.CREATE })
                updated = maxOf(updated, plan.count { it.action == SteamGameAction.UPDATE })
                elsewhere = maxOf(elsewhere, plan.count { it.action == SteamGameAction.ELSEWHERE })
                written++
            } catch (e: Exception) {
                Log.warn("could not add games to Steam in $dir", e)
            }
        }
        if (written == 0) return Result.failure(IllegalStateException("Steam's library couldn't be read, so nothing was changed."))
        val parts = listOfNotNull(
            added.takeIf { it > 0 }?.let { if (it == 1) "1 game added" else "$it games added" },
            updated.takeIf { it > 0 }?.let { if (it == 1) "1 brought up to date" else "$it brought up to date" },
            elsewhere.takeIf { it > 0 }?.let { if (it == 1) "1 already in Steam from another tool, left as it is" else "$it already in Steam from other tools, left as they are" },
        )
        return Result.success(if (parts.isEmpty()) "Already in Steam's library, as they are." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ". Start Steam to see them.")
    }

    override suspend fun removeGames(gameIds: Set<Long>?): Result<String> {
        if (steamRunning()) removeLive(gameIds)?.let { return it }
        return removeFromFiles(gameIds)
    }

    /**
     * Fuse's entries for [gameIds] (null: all) taken out through the Fuse Decky plugin while Steam
     * runs. Null when the plugin isn't there. Steam writes its file without them when it quits.
     */
    private suspend fun removeLive(gameIds: Set<Long>?): Result<String>? {
        val queue = live.takeIf { it.connected } ?: return null
        val lists = withContext(Dispatchers.IO) { userLists().mapNotNull { runCatching { it.readBytes() }.getOrNull() } }
        val ids = HashMap<Long, Long>()
        lists.forEach { b -> SteamGames.inSteam(b, ::fuseProgram).forEach { (k, v) -> ids.putIfAbsent(k, v) } }
        ids.putAll(queue.made())
        val changes = ids.filterKeys { gameIds == null || it in gameIds }.map { (game, app) -> SteamLiveChange(op = SteamLiveChange.Op.REMOVE, gameId = game, appId = app) }
        if (changes.isEmpty()) return Result.success("None of them were in Steam's library.")
        val done = queue.offer(changes).count { it.ok }
        if (done < changes.size) return Result.failure(IllegalStateException("Steam didn't take them all out while it runs. Close Steam, then try again."))
        // Collections Fuse made for them are put right once Steam closes.
        addWhenClosed(emptyList(), emptyMap(), collections = true)
        return Result.success(if (done == 1) "1 game is out of Steam's library." else "$done games are out of Steam's library.")
    }

    private suspend fun removeFromFiles(gameIds: Set<Long>?): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            var removed = 0
            val step = nextStep()
            for (dir in userConfigDirs(create = false)) {
                val file = File(dir, "shortcuts.vdf").takeIf { it.isFile } ?: continue
                val before = runCatching { file.readBytes() }.getOrNull() ?: continue
                val had = SteamGames.inSteam(before, ::fuseProgram).keys.let { k -> if (gameIds == null) k else k intersect gameIds }
                val after = SteamGames.remove(before, gameIds, ::fuseProgram) ?: continue
                if (after.contentEquals(before)) continue
                keepUndo(dir, before, step, if (had.size == 1) "Took 1 game out" else "Took ${had.size} games out")
                replaceAtomically(file, after)
                runCatching { writeCollections(dir, after, emptySet()) }
                removed = maxOf(removed, had.size)
            }
            Result.success(
                when (removed) {
                    0 -> "None of them were in Steam's library."
                    1 -> "1 game is out of Steam's library. Your other shortcuts stay as they are."
                    else -> "$removed games are out of Steam's library. Your other shortcuts stay as they are."
                },
            )
        }
    }

    override suspend fun repairGames(library: Set<Long>, moved: List<SteamGameEntry>, art: Map<Long, SteamArt>): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            val rewritten = prepared(moved)
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            val exe = program() ?: return@withContext Result.failure(IllegalStateException("Run Fuse from its AppImage or installed copy, then try again."))
            var changed = 0
            val step = nextStep()
            for (dir in userConfigDirs(create = false)) {
                val file = File(dir, "shortcuts.vdf").takeIf { it.isFile } ?: continue
                val before = runCatching { file.readBytes() }.getOrNull() ?: continue
                val repaired = SteamGames.repair(before, library, ::fuseProgram) ?: continue
                // Entries that start an older Fuse, an older way or a missing emulator: the same entry, written
                // again as each game starts now (its art id stays).
                val ids = SteamGames.inSteam(repaired, ::fuseProgram)
                val icons = HashMap<Long, String>()
                for (g in rewritten) {
                    val pictures = art[g.gameId] ?: continue
                    val id = ids[g.gameId] ?: continue
                    runCatching { placeGameArt(File(dir, "grid"), id, pictures) }.onSuccess { if (it.isNotEmpty()) icons[g.gameId] = it }
                }
                val after = SteamGames.write(repaired, exe, rewritten.filter { it.gameId in ids }, ::fuseProgram) { icons[it].orEmpty() } ?: continue
                if (after.contentEquals(before)) continue
                keepUndo(dir, before, step, "Put Fuse's entries right")
                replaceAtomically(file, after)
                runCatching { writeCollections(dir, after, emptySet()) }
                changed++
            }
            if (changed == 0) Result.success("Nothing needed changing.") else Result.success("Steam's entries are put right. Your other shortcuts stay as they are.")
        }
    }

    override suspend fun gamesHealth(library: Set<Long>, wanted: Map<Long, SteamGameEntry>, unavailable: Set<Long>): List<SteamHealthIssue> = withContext(Dispatchers.IO) {
        val exe = program() ?: return@withContext emptyList()
        // Each as it would be written here (a Direct entry's program found, or Fast Start).
        val now = prepared(wanted.values.toList()).associateBy { it.gameId }
        userLists().flatMap { f ->
            SteamGames.health(runCatching { f.readBytes() }.getOrNull(), exe, library, ::fuseProgram, exists = { File(it).exists() }, wanted = now, unavailable = unavailable)
        }.distinct()
    }

    override suspend fun directGames(): Map<Long, Long> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, Long>()
        for (f in userLists()) out.putAll(SteamGames.directIn(runCatching { f.readBytes() }.getOrNull(), ::fuseProgram))
        out
    }

    override suspend fun gameStarts(): Map<Long, SteamStart> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, SteamStart>()
        for (f in userLists()) SteamGames.starts(runCatching { f.readBytes() }.getOrNull(), ::fuseProgram).forEach { (id, start) -> out.putIfAbsent(id, start) }
        out
    }

    override suspend fun learnShortcuts(files: Map<String, Long>): List<SteamLearned> = withContext(Dispatchers.IO) {
        userLists().flatMap { f -> SteamLearn.find(runCatching { f.readBytes() }.getOrNull(), ::fuseProgram, files) }.distinctBy { it.appId to it.gameId }
    }

    override suspend fun playFromSteam(gameId: Long): Result<String> {
        val queue = live.takeIf { it.connected } ?: return Result.failure(IllegalStateException("Starting a game through Steam needs Steam open, and Fuse reaching it: turn on Change Steam While It Runs in Settings, Library, Steam, or add the Fuse plugin for Decky."))
        val appId = queue.made()[gameId] ?: withContext(Dispatchers.IO) {
            userLists().firstNotNullOfOrNull { f -> SteamGames.inSteam(runCatching { f.readBytes() }.getOrNull(), ::fuseProgram)[gameId] }
        } ?: return Result.failure(IllegalStateException("It isn't in Steam's library yet. Add it to Steam first."))
        val result = queue.offer(listOf(SteamLiveChange(op = SteamLiveChange.Op.RUN, gameId = gameId, appId = appId))).single()
        return if (result.ok) Result.success("Starting through Steam") else Result.failure(IllegalStateException(result.message ?: "Steam didn't start it"))
    }

    override suspend fun habits(files: Map<String, Long>, systems: Map<Long, String>): List<SteamHabit> = withContext(Dispatchers.IO) {
        SteamLearn.habits(userLists().map { f -> runCatching { f.readBytes() }.getOrNull() }, ::fuseProgram, files, systems::get)
    }

    override suspend fun entryStarts(games: List<SteamGameEntry>): Map<Long, Triple<String, String, String>> = withContext(Dispatchers.IO) {
        val exe = program() ?: return@withContext emptyMap()
        val bytes = userLists().firstOrNull()?.let { f -> runCatching { f.readBytes() }.getOrNull() }
        prepared(games).associate { it.gameId to SteamGames.startOf(bytes, exe, it, ::fuseProgram) }
    }

    override suspend fun takeOver(takes: List<Pair<SteamLearned, SteamGameEntry>>): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            if (takes.isEmpty()) return@withContext Result.success("Nothing to take over.")
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            val exe = program() ?: return@withContext Result.failure(IllegalStateException("Run Fuse from its AppImage or installed copy, then try again."))
            val wanted = prepared(takes.map { it.second }).associateBy { it.gameId }
            val byId = takes.mapNotNull { (learned, entry) -> wanted[entry.gameId]?.let { learned.appId to it } }.toMap()
            var changed = 0
            val step = nextStep()
            for (dir in userConfigDirs(create = false)) {
                val file = File(dir, "shortcuts.vdf").takeIf { it.isFile } ?: continue
                val before = runCatching { file.readBytes() }.getOrNull() ?: continue
                val after = SteamGames.takeOver(before, exe, byId, ::fuseProgram) ?: continue
                if (after.contentEquals(before)) continue
                // The list as it was, for Undo.
                keepUndo(dir, before, step, if (byId.size == 1) "Took over 1 shortcut" else "Took over ${byId.size} shortcuts")
                replaceAtomically(file, after)
                runCatching { writeCollections(dir, after, wanted.values.mapNotNull { it.collection }.toSet()) }
                    .onFailure { Log.warn("could not write Steam collections in $dir", it) }
                changed++
            }
            when {
                changed == 0 -> Result.success("Nothing needed changing.")
                takes.size == 1 -> Result.success("It's Fuse's now, with its name and art as they were. Start Steam to see it.")
                else -> Result.success("${takes.size} are Fuse's now, with their names and art as they were. Start Steam to see them.")
            }
        }
    }

    override val canUndoGames: Boolean get() = userConfigDirs(create = false).any { undoFiles(it).isNotEmpty() }

    override suspend fun undoSteps(): List<SteamUndoStep> = withContext(Dispatchers.IO) {
        val steps = HashMap<Long, SteamUndoStep>()
        for (dir in userConfigDirs(create = false)) {
            for ((id, file) in undoFiles(dir)) steps.putIfAbsent(id, SteamUndoStep(id, File(file.parentFile, "$id.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { "Fuse's change" }, id))
        }
        steps.values.sortedByDescending { it.id }
    }

    override suspend fun undoGames(): Result<String> {
        val last = undoSteps().firstOrNull() ?: return Result.failure(IllegalStateException("There's no change of Fuse's to undo."))
        return undoTo(last.id).map { "Steam's library is back as it was before Fuse's last change." }
    }

    override suspend fun undoTo(stepId: Long): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            var undone = 0
            for (dir in userConfigDirs(create = false)) {
                val files = undoFiles(dir)
                val backup = files[stepId] ?: continue
                val bytes = backup.readBytes()
                val file = File(dir, "shortcuts.vdf")
                if (bytes.isEmpty()) {
                    // There was no list before Fuse's change: only Fuse's games come out again.
                    file.takeIf { it.isFile }?.let { f -> SteamGames.remove(f.readBytes(), null, ::fuseProgram)?.let { replaceAtomically(f, it) } }
                } else {
                    replaceAtomically(file, bytes)
                }
                runCatching { writeCollections(dir, file.takeIf { it.isFile }?.readBytes(), emptySet()) }
                // This change and every later one are undone with it.
                for ((id, f) in files) if (id >= stepId) {
                    f.delete()
                    File(f.parentFile, "$id.txt").delete()
                }
                undone++
            }
            if (undone == 0) Result.failure(IllegalStateException("There's no change of Fuse's to undo.")) else Result.success("Steam's library is back as it was before that change.")
        }
    }

    @Volatile private var lastStep = 0L

    /** A new write's step: when it was made, and always after the one before (a clock ticks coarsely on some systems). */
    @Synchronized
    private fun nextStep(): Long = maxOf(System.currentTimeMillis(), lastStep + 1).also { lastStep = it }

    /**
     * Steam's list as it was before a write of Fuse's, kept by [step] (when it was made) with what
     * it did, for Undo. Only the last [UNDO_STEPS] are kept; the single copy earlier Fuses kept goes.
     */
    private fun keepUndo(dir: File, before: ByteArray?, step: Long, what: String) {
        val folder = File(dir, UNDO_DIR).apply { mkdirs() }
        File(folder, "$step.vdf").writeBytes(before ?: ByteArray(0))
        File(folder, "$step.txt").writeText(what)
        undoFiles(dir).keys.sortedDescending().drop(UNDO_STEPS).forEach { old ->
            File(folder, "$old.vdf").delete()
            File(folder, "$old.txt").delete()
        }
        File(dir, GAMES_BACKUP).delete()
    }

    /** The lists Undo can put back in [dir], by step; the single copy an earlier Fuse kept counts as one. */
    private fun undoFiles(dir: File): Map<Long, File> {
        val out = HashMap<Long, File>()
        File(dir, UNDO_DIR).listFiles { f -> f.name.endsWith(".vdf") }?.forEach { f -> f.nameWithoutExtension.toLongOrNull()?.let { out[it] = f } }
        File(dir, GAMES_BACKUP).takeIf { it.isFile }?.let { out.putIfAbsent(it.lastModified(), it) }
        return out
    }

    /** What a write did, in words, for the list of changes Undo shows. */
    private fun changesInWords(added: Int, updated: Int): String = listOfNotNull(
        added.takeIf { it > 0 }?.let { if (it == 1) "Added 1 game" else "Added $it games" },
        updated.takeIf { it > 0 }?.let { if (it == 1) "brought 1 up to date" else "brought $it up to date" },
    ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Changed Fuse's entries" }

    /**
     * Steam's collections for Fuse's games in [shortcuts]: one per system they are tagged with,
     * written into the user's cloud storage file, where Steam keeps collections. Only Fuse's own
     * collections change. Nothing is written when Steam hasn't made the file yet.
     */
    private fun writeCollections(dir: File, shortcuts: ByteArray?, names: Set<String>): Set<String> {
        val (file, text) = collectionsFile(dir) ?: return emptySet()
        val choices = choices(dir)
        // Fuse's own that Steam Cloud dropped (not ones the person deleted) come back with the rest.
        val dropped = SteamCollections.dropped(text, choices)
        val members = SteamGames.byCollection(shortcuts, SteamCollections.fuseNames(text) + names + dropped + choices.into.keys + choices.back, ::fuseProgram).filterValues { it.isNotEmpty() }
        val out = SteamCollections.write(text, members, System.currentTimeMillis() / 1000, choices) ?: return emptySet()
        val written = choices.written + members.keys.filter { it !in choices.into }
        if (written != choices.written || choices.back.isNotEmpty()) saveChoices(dir, choices.copy(written = written, back = emptySet()))
        if (out == text) return emptySet()
        text?.let { File(file.parentFile, "${file.name}.before-fuse-games").writeText(it) }
        replaceAtomically(file, out.encodeToByteArray())
        return dropped.intersect(members.keys)
    }

    /** The namespace file Steam keeps this user's collections in, and what it holds (null for none yet); null without a cloud folder. */
    private fun collectionsFile(dir: File): Pair<File, String?>? {
        val cloud = File(dir, "cloudstorage").takeIf { it.isDirectory } ?: return null
        val namespace = SteamCollections.activeNamespace(File(cloud, "cloud-storage-namespaces.json").takeIf { it.isFile }?.readText())
        val file = File(cloud, "cloud-storage-namespace-$namespace.json")
        return file to file.takeIf { it.isFile }?.readText()
    }

    private val choicesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** What the person chose for their collections in [dir] (Steam's `config` folder), kept beside Steam's files. */
    private fun choices(dir: File): SteamCollectionChoices =
        File(dir, CHOICES).takeIf { it.isFile }?.let { f -> runCatching { choicesJson.decodeFromString(SteamCollectionChoices.serializer(), f.readText()) }.getOrNull() }
            ?: SteamCollectionChoices()

    private fun saveChoices(dir: File, choices: SteamCollectionChoices) {
        replaceAtomically(File(dir, CHOICES), choicesJson.encodeToString(SteamCollectionChoices.serializer(), choices).encodeToByteArray())
    }

    override suspend fun theirCollections(): List<SteamCollection> = withContext(Dispatchers.IO) {
        userConfigDirs(create = false).flatMap { dir -> collectionsFile(dir)?.second?.let(SteamCollections::theirs).orEmpty() }.distinctBy { it.id }
    }

    override suspend fun collectionChoices(): SteamCollectionChoices = withContext(Dispatchers.IO) {
        userConfigDirs(create = false).firstOrNull()?.let(::choices) ?: SteamCollectionChoices()
    }

    override suspend fun chooseCollection(fuseName: String, theirs: String, how: io.github.matiyaaa.fuse.library.steam.SteamCollectionWay): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            for (dir in userConfigDirs(create = false)) {
                val c = choices(dir)
                saveChoices(
                    dir,
                    when (how) {
                        io.github.matiyaaa.fuse.library.steam.SteamCollectionWay.APART -> c.copy(
                            into = c.into - fuseName, renamed = c.renamed - theirs, kept = c.kept + theirs,
                            back = if (c.into[fuseName] == theirs) c.back + fuseName else c.back,
                        )
                        io.github.matiyaaa.fuse.library.steam.SteamCollectionWay.INTO -> c.copy(into = c.into + (fuseName to theirs), renamed = c.renamed - theirs, kept = c.kept - theirs)
                        io.github.matiyaaa.fuse.library.steam.SteamCollectionWay.RENAMED -> c.copy(into = c.into + (fuseName to theirs), renamed = c.renamed + (theirs to fuseName), kept = c.kept - theirs)
                    },
                )
            }
            // Steam writes its collections when it quits: written now when it's closed, else once it closes.
            if (steamRunningNow()) {
                addWhenClosed(emptyList(), emptyMap(), collections = true)
                Result.success("Done when Steam next closes. Steam isn't closed for it.")
            } else {
                for (dir in userConfigDirs(create = false)) {
                    runCatching { writeCollections(dir, File(dir, "shortcuts.vdf").takeIf { it.isFile }?.readBytes(), emptySet()) }
                        .onFailure { Log.warn("could not write Steam collections in $dir", it) }
                }
                Result.success("Done. Start Steam to see it.")
            }
        }
    }

    override suspend fun keepCollections(onPutBack: (Int) -> Unit) {
        var open = withContext(Dispatchers.IO) { steamRunningNow() }
        while (true) {
            delay(STEAM_POLL_MS)
            val now = withContext(Dispatchers.IO) { steamRunningNow() }
            // Steam just closed after a session: what Steam Cloud dropped of Fuse's collections goes back.
            if (open && !now) {
                val back = mutation.withLock {
                    withContext(Dispatchers.IO) {
                        userConfigDirs(create = false).sumOf { dir ->
                            runCatching { writeCollections(dir, File(dir, "shortcuts.vdf").takeIf { it.isFile }?.readBytes(), emptySet()).size }.getOrDefault(0)
                        }
                    }
                }
                if (back > 0) {
                    Log.info("Steam: put back $back of Fuse's collections Steam Cloud dropped")
                    onPutBack(back)
                }
            }
            open = now
        }
    }

    /**
     * A game's art in [grid] for the entry Steam knows as [id], from the files Fuse keeps: box art as
     * the portrait capsule, wide art as the capsule, the hero, the logo and the icon. Art already
     * there (the person's, or Steam's) stays. Pictures Steam can't read (WebP) are made PNG. Returns
     * the icon's path, or empty.
     */
    internal fun placeGameArt(grid: File, id: Long, art: SteamArt): String {
        grid.mkdirs()
        for ((kind, source, name) in listOf(
            Triple("portrait", art.portrait, "${id}p"), Triple("wide", art.wide, "$id"), Triple("hero", art.hero, "${id}_hero"),
            Triple("logo", art.logo, "${id}_logo"), Triple("icon", art.icon, "${id}_icon"),
        )) {
            val from = source?.let(::File)?.takeIf { it.isFile } ?: continue
            if (listOf("png", "jpg", "jpeg", "webp").any { File(grid, "$name.$it").exists() }) continue
            // Only a picture in the shape Steam shows it in; otherwise Steam's own stays.
            if (!inShape(kind, from)) continue
            val ext = from.extension.lowercase()
            val (bytes, out) = when (ext) {
                "png", "jpg", "jpeg" -> from.readBytes() to ext
                else -> (runCatching { org.jetbrains.skia.Image.makeFromEncoded(from.readBytes()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes }.getOrNull() ?: continue) to "png"
            }
            val target = File(grid, "$name.$out")
            val tmp = File(grid, ".$name.$out.fuse-tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeBytes(bytes)
            }
        }
        return listOf("png", "jpg", "jpeg").map { File(grid, "${id}_icon.$it") }.firstOrNull { it.isFile }?.absolutePath.orEmpty()
    }

    /** Whether the picture in [file] is in the shape Steam shows a [kind] in; unreadable pictures aren't. */
    private fun inShape(kind: String, file: File): Boolean {
        val (w, h) = imageSize(file) ?: return false
        return SteamArtShape.fits(kind, w, h)
    }

    /** A picture's width and height from its header (PNG, JPEG), or by decoding it (WebP and the rest). */
    private fun imageSize(file: File): Pair<Int, Int>? = runCatching {
        javax.imageio.ImageIO.createImageInputStream(file)?.use { input ->
            val reader = javax.imageio.ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {
                reader.setInput(input, true, true)
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull() ?: runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).let { it.width to it.height } }.getOrNull()

    private fun program(): String? = programPath()

    private fun quote(path: String): String = "\"$path\""

    companion object {
        /** What Steam should start: the AppImage Fuse runs from, or the installed program. */
        /** Steam started this process from one of its entries: it says which in the environment. */
        internal fun insideSteam(env: Map<String, String>): Boolean =
            !env["SteamGameId"].isNullOrBlank() || !env["SteamAppId"].isNullOrBlank() || env["SteamClientLaunch"] == "1"

        internal fun runningProgram(): String? {
            System.getenv("APPIMAGE")?.takeIf { File(it).isFile }?.let { return it }
            val command = ProcessHandle.current().info().command().orElse(null) ?: return null
            // A packaged app runs its own launcher; a bare java (a development run) has nothing to offer.
            if (File(command).name.lowercase().startsWith("java")) return null
            if (DesktopOs.isMac) {
                val app = generateSequence(File(command)) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
                if (app != null) return app.absolutePath
            }
            return command
        }

        private const val STEAM_POLL_MS = 5_000L

        /** What the person chose for their collections beside Fuse's, beside each user's Steam files. */
        private const val CHOICES = "fuse-collections.json"

        /** Each Steam user's list as it was before Fuse's last change to its games, for Undo. */
        private const val GAMES_BACKUP = "shortcuts.vdf.before-fuse-games"

        /** Where each user's lists before Fuse's writes are kept, for Undo, and how many. */
        private const val UNDO_DIR = "fuse-steam-undo"
        private const val UNDO_STEPS = 10
        private val FUSE_PROGRAM = Regex("(?i)^fuse([-_. ][^/\\\\]*)?\\.(appimage|exe)$|^fuse$")

        /** Same-directory atomic replacement never deletes the valid original on failure. */
        internal fun replaceAtomically(file: File, bytes: ByteArray) {
            val temp = Files.createTempFile(file.parentFile.toPath(), ".fuse-steam-", ".tmp")
            try {
                java.io.FileOutputStream(temp.toFile()).use { out -> out.write(bytes); out.fd.sync() }
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        }

        internal fun isFuse(path: String, name: String, running: String?): Boolean {
            if (running != null && File(path).absolutePath == File(running).absolutePath) return true
            return FUSE_PROGRAM.matches(File(path).name)
        }
    }

    /**
     * Writes Fuse's art for [shortcut] into [grid], named as Steam looks for a non-Steam game's:
     * `<id>p.png` (library capsule), `<id>.png` (wide capsule), `<id>_hero.png`, `<id>_logo.png` and
     * `<id>_icon.png`. Returns the icon's path for the entry.
     */
    internal fun placeArt(grid: File, shortcut: SteamShortcut): String =
        placeArt(grid, SteamShortcuts.appId(shortcut.exe, shortcut.name).toLong() and 0xFFFFFFFFL)

    /** [placeArt] for the entry Steam knows as [id]. */
    internal fun placeArt(grid: File, id: Long): String {
        grid.mkdirs()
        for ((resource, name) in listOf("portrait" to "${id}p", "capsule" to "$id", "hero" to "${id}_hero", "logo" to "${id}_logo", "icon" to "${id}_icon")) {
            // Art already there for this entry (the person's own, from SteamGridDB or Steam) stays;
            // only a picture an earlier Fuse put there itself is brought up to date.
            val kept = listOf("png", "jpg", "jpeg", "webp").map { File(grid, "$name.$it") }.filter { it.exists() }
            if (kept.any { !fusesOldArt(it) }) continue
            val bytes = javaClass.getResourceAsStream("/steam/$resource.png")?.use { it.readBytes() } ?: continue
            val target = File(grid, "$name.png")
            val tmp = File(grid, ".$name.png.fuse-tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeBytes(bytes)
            }
        }
        return listOf("png", "jpg", "jpeg").map { File(grid, "${id}_icon.$it") }.firstOrNull { it.isFile }?.absolutePath.orEmpty()
    }

    /**
     * Whether [file] is art an earlier Fuse placed and has since redrawn: the 0.3.6.4 logo, the
     * lockup edge to edge, which Steam's in-game menu stretched across its whole width.
     */
    private fun fusesOldArt(file: File): Boolean = runCatching {
        file.length() < 1_000_000 && java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) } in REDRAWN_ART
    }.getOrDefault(false)

    /** Every Steam user's own folder (`userdata/<id>`), signed in at least once. */
    private fun userDataDirs(): List<File> = folders.steamPlaces().first
        .map { File(it, "userdata") }
        .flatMap { root -> root.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty() }

    /** Every Steam user's config folder (`userdata/<id>/config`), on every Steam install found. */
    private fun userConfigDirs(create: Boolean = true): List<File> = folders.steamPlaces().first
        .map { File(it, "userdata") }
        .flatMap { root -> root.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty() }
        .map { File(it, "config").apply { if (create) mkdirs() } }
        .filter { it.isDirectory }
        .distinctBy { it.canonicalPath }

    private fun userLists(): List<File> = userConfigDirs().map { File(it, "shortcuts.vdf") }.filter { it.isFile }

    private val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    init {
        // After [watcher] and [steamClient] exist: properties start in the order they are written.
        if (clientLink) steamClient.start(watcher)
    }

    /**
     * A game started by Steam is running: Steam's launch wrapper ("reaper") is alive on Linux. The
     * wrapper that started Fuse itself, from its Steam entry, isn't a game.
     */
    private fun gameRunningNow(): Boolean = SystemSteamProcessOps(fuseProgram = { program() }, fuseGameId = { null }).gameRunning()

    private fun steamRunningNow(): Boolean = steamOpen?.invoke() ?: when (DesktopOs.current) {
        DesktopOs.WINDOWS -> Processes.run(listOf("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH"), timeoutMs = 3_000)?.stdout?.contains("steam.exe", ignoreCase = true) == true
        DesktopOs.MACOS -> Processes.run(listOf("pgrep", "-x", "steam_osx"), timeoutMs = 3_000)?.exitCode == 0
        DesktopOs.LINUX -> Processes.run(listOf("pgrep", "-x", "steam"), timeoutMs = 3_000)?.exitCode == 0
    }
}

/** SHA-256 of art earlier Fuses placed in Steam and later redrew, replaced when found unchanged. */
private val REDRAWN_ART = setOf(
    // logo.png, 0.3.6.4 to 0.4.2.6: 1200 by 344, the lockup with no room around it.
    "a25fa0c34cdbee789e3ba5c99b103aabca74392aa04e647bc0c09feb64a8fb50",
)
