package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.services.KnownFolders
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.GameMode
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamCollections
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamGamePlanItem
import io.github.matiyaaa.fuse.library.steam.SteamGames
import io.github.matiyaaa.fuse.library.steam.SteamHealthIssue
import io.github.matiyaaa.fuse.library.steam.SteamShortcut
import io.github.matiyaaa.fuse.library.steam.SteamShortcuts
import io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration
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
        if (!steamRunning()) return addGames(games, art, collections)
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
                val after = (if (repair) SteamShortcuts.rebuild(before, shortcut) { path, name -> isFuse(path, name, exe) } else SteamShortcuts.add(before, shortcut) { path, name -> isFuse(path, name, exe) }) ?: continue
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
     */
    fun dressEntries(): Int {
        val exe = program()
        var n = 0
        for (dir in userConfigDirs(create = false)) {
            val bytes = File(dir, "shortcuts.vdf").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() } ?: continue
            for (id in SteamShortcuts.idsOf(bytes) { path, name -> isFuse(path, name, exe) }) {
                runCatching { placeArt(File(dir, "grid"), id) }.onSuccess { n++ }.onFailure { Log.warn("could not give Steam Fuse's art in $dir", it) }
            }
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
        val dirs = userConfigDirs(create = false)
        if (dirs.isEmpty()) return@withContext null
        // Each Steam user's list on its own; a game counts as new if it is new for any of them.
        val plans = dirs.mapNotNull { dir -> SteamGames.plan(File(dir, "shortcuts.vdf").takeIf { it.isFile }?.readBytes(), exe, games, ::fuseProgram) }
        if (plans.isEmpty()) return@withContext null
        val order = listOf(SteamGameAction.CREATE, SteamGameAction.UPDATE, SteamGameAction.ELSEWHERE, SteamGameAction.UNCHANGED)
        games.distinctBy { it.gameId }.map { g ->
            val actions = plans.mapNotNull { p -> p.firstOrNull { it.entry.gameId == g.gameId }?.action }
            SteamGamePlanItem(g, order.firstOrNull { it in actions } ?: SteamGameAction.UNCHANGED)
        }
    }

    @Volatile private var waitingGames: kotlinx.coroutines.Job? = null

    override suspend fun addGames(games: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean): Result<String> {
        if (games.isEmpty()) return Result.success("Nothing to add.")
        if (steamRunning()) {
            waitingGames?.cancel()
            waitingGames = watcher.launch {
                // Steam is never closed for this: Fuse waits for it to be gone, then writes.
                while (steamRunningNow()) delay(STEAM_POLL_MS)
                mutation.withLock { writeGames(games, art, collections) }
                    .onSuccess { Log.info("Steam: games added after Steam closed") }
                    .onFailure { Log.warn("Steam: adding games after Steam closed failed", it) }
            }
            return Result.success(if (games.size == 1) "It goes into Steam's library when Steam next closes. Steam isn't closed for it." else "They go into Steam's library when Steam next closes. Steam isn't closed for it.")
        }
        return mutation.withLock { withContext(Dispatchers.IO) { writeGames(games, art, collections) } }
    }

    override val waitingForSteamGames: Boolean get() = waitingGames?.isActive == true

    private fun writeGames(games: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean): Result<String> {
        val exe = program()?.takeIf { File(it).canExecute() || (DesktopOs.isMac && File(it).isDirectory) }
            ?: return Result.failure(IllegalStateException("Run Fuse from its AppImage or installed copy: Steam starts these games through it."))
        if (steamRunningNow()) return Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
        val users = userConfigDirs()
        if (users.isEmpty()) return Result.failure(IllegalStateException("No Steam user here yet. Sign in to Steam once, close it, then try again."))
        var added = 0
        var updated = 0
        var elsewhere = 0
        var written = 0
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
                    File(dir, GAMES_BACKUP).writeBytes(before ?: ByteArray(0))
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

    override suspend fun removeGames(gameIds: Set<Long>?): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            var removed = 0
            for (dir in userConfigDirs(create = false)) {
                val file = File(dir, "shortcuts.vdf").takeIf { it.isFile } ?: continue
                val before = runCatching { file.readBytes() }.getOrNull() ?: continue
                val had = SteamGames.inSteam(before, ::fuseProgram).keys.let { k -> if (gameIds == null) k else k intersect gameIds }
                val after = SteamGames.remove(before, gameIds, ::fuseProgram) ?: continue
                if (after.contentEquals(before)) continue
                File(dir, GAMES_BACKUP).writeBytes(before)
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
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            val exe = program() ?: return@withContext Result.failure(IllegalStateException("Run Fuse from its AppImage or installed copy, then try again."))
            var changed = 0
            for (dir in userConfigDirs(create = false)) {
                val file = File(dir, "shortcuts.vdf").takeIf { it.isFile } ?: continue
                val before = runCatching { file.readBytes() }.getOrNull() ?: continue
                val repaired = SteamGames.repair(before, library, ::fuseProgram) ?: continue
                // Entries that start an older Fuse: the same entry, pointed at this one (its art id stays).
                val ids = SteamGames.inSteam(repaired, ::fuseProgram)
                val icons = HashMap<Long, String>()
                for (g in moved) {
                    val pictures = art[g.gameId] ?: continue
                    val id = ids[g.gameId] ?: continue
                    runCatching { placeGameArt(File(dir, "grid"), id, pictures) }.onSuccess { if (it.isNotEmpty()) icons[g.gameId] = it }
                }
                val after = SteamGames.write(repaired, exe, moved.filter { it.gameId in ids }, ::fuseProgram) { icons[it].orEmpty() } ?: continue
                if (after.contentEquals(before)) continue
                File(dir, GAMES_BACKUP).writeBytes(before)
                replaceAtomically(file, after)
                runCatching { writeCollections(dir, after, emptySet()) }
                changed++
            }
            if (changed == 0) Result.success("Nothing needed changing.") else Result.success("Steam's entries are put right. Your other shortcuts stay as they are.")
        }
    }

    override suspend fun gamesHealth(library: Set<Long>): List<SteamHealthIssue> = withContext(Dispatchers.IO) {
        val exe = program() ?: return@withContext emptyList()
        userLists().flatMap { f -> SteamGames.health(runCatching { f.readBytes() }.getOrNull(), exe, library, ::fuseProgram) }.distinct()
    }

    override val canUndoGames: Boolean get() = userConfigDirs(create = false).any { File(it, GAMES_BACKUP).isFile }

    override suspend fun undoGames(): Result<String> = mutation.withLock {
        withContext(Dispatchers.IO) {
            if (steamRunningNow()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits."))
            var undone = 0
            for (dir in userConfigDirs(create = false)) {
                val backup = File(dir, GAMES_BACKUP).takeIf { it.isFile } ?: continue
                val bytes = backup.readBytes()
                val file = File(dir, "shortcuts.vdf")
                if (bytes.isEmpty()) {
                    // There was no list before Fuse's change: only Fuse's games come out again.
                    file.takeIf { it.isFile }?.let { f -> SteamGames.remove(f.readBytes(), null, ::fuseProgram)?.let { replaceAtomically(f, it) } }
                } else {
                    replaceAtomically(file, bytes)
                }
                runCatching { writeCollections(dir, file.takeIf { it.isFile }?.readBytes(), emptySet()) }
                backup.delete()
                undone++
            }
            if (undone == 0) Result.failure(IllegalStateException("There's no change of Fuse's to undo.")) else Result.success("Steam's library is back as it was before Fuse's last change.")
        }
    }

    /**
     * Steam's collections for Fuse's games in [shortcuts]: one per system they are tagged with,
     * written into the user's cloud storage file, where Steam keeps collections. Only Fuse's own
     * collections change. Nothing is written when Steam hasn't made the file yet.
     */
    private fun writeCollections(dir: File, shortcuts: ByteArray?, names: Set<String>) {
        val cloud = File(dir, "cloudstorage").takeIf { it.isDirectory } ?: return
        val namespace = SteamCollections.activeNamespace(File(cloud, "cloud-storage-namespaces.json").takeIf { it.isFile }?.readText())
        val file = File(cloud, "cloud-storage-namespace-$namespace.json")
        val text = file.takeIf { it.isFile }?.readText()
        val members = SteamGames.byCollection(shortcuts, SteamCollections.fuseNames(text) + names, ::fuseProgram).filterValues { it.isNotEmpty() }
        val out = SteamCollections.write(text, members, System.currentTimeMillis() / 1000) ?: return
        if (out == text) return
        text?.let { File(cloud, "${file.name}.before-fuse-games").writeText(it) }
        replaceAtomically(file, out.encodeToByteArray())
    }

    /**
     * A game's art in [grid] for the entry Steam knows as [id], from the files Fuse keeps: box art as
     * the portrait capsule, wide art as the capsule, the hero, the logo and the icon. Art already
     * there (the person's, or Steam's) stays. Pictures Steam can't read (WebP) are made PNG. Returns
     * the icon's path, or empty.
     */
    internal fun placeGameArt(grid: File, id: Long, art: SteamArt): String {
        grid.mkdirs()
        for ((source, name) in listOf(art.portrait to "${id}p", art.wide to "$id", art.hero to "${id}_hero", art.logo to "${id}_logo", art.icon to "${id}_icon")) {
            val from = source?.let(::File)?.takeIf { it.isFile } ?: continue
            if (listOf("png", "jpg", "jpeg", "webp").any { File(grid, "$name.$it").exists() }) continue
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

        /** Each Steam user's list as it was before Fuse's last change to its games, for Undo. */
        private const val GAMES_BACKUP = "shortcuts.vdf.before-fuse-games"
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
            // Art already there for this entry (the person's own, from SteamGridDB or Steam) stays.
            if (listOf("png", "jpg", "jpeg", "webp").any { File(grid, "$name.$it").exists() }) continue
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

    /** Every Steam user's config folder (`userdata/<id>/config`), on every Steam install found. */
    private fun userConfigDirs(create: Boolean = true): List<File> = folders.steamPlaces().first
        .map { File(it, "userdata") }
        .flatMap { root -> root.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty() }
        .map { File(it, "config").apply { if (create) mkdirs() } }
        .filter { it.isDirectory }
        .distinctBy { it.canonicalPath }

    private fun userLists(): List<File> = userConfigDirs().map { File(it, "shortcuts.vdf") }.filter { it.isFile }

    private val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * A game started by Steam is running: Steam's launch wrapper ("reaper") is alive on Linux. The
     * wrapper that started Fuse itself, from its Steam entry, isn't a game.
     */
    private fun gameRunningNow(): Boolean = SystemSteamProcessOps(fuseProgram = { program() }, fuseGameId = { null }).gameRunning()

    private fun steamRunningNow(): Boolean = when (DesktopOs.current) {
        DesktopOs.WINDOWS -> Processes.run(listOf("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH"), timeoutMs = 3_000)?.stdout?.contains("steam.exe", ignoreCase = true) == true
        DesktopOs.MACOS -> Processes.run(listOf("pgrep", "-x", "steam_osx"), timeoutMs = 3_000)?.exitCode == 0
        DesktopOs.LINUX -> Processes.run(listOf("pgrep", "-x", "steam"), timeoutMs = 3_000)?.exitCode == 0
    }
}
