package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.InstalledPort
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ports.PortsClient
import io.github.matiyaaa.fuse.integrations.ports.PortsIndexReader
import io.github.matiyaaa.fuse.integrations.ports.PortsList
import io.github.matiyaaa.fuse.integrations.ports.PortsSignature
import io.github.matiyaaa.fuse.launch.ports.AnyPs5Recompile
import io.github.matiyaaa.fuse.launch.ports.OverlayStep
import io.github.matiyaaa.fuse.launch.ports.PortAdapter
import io.github.matiyaaa.fuse.launch.ports.PortLayout
import io.github.matiyaaa.fuse.launch.ports.PortPlan
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PortData
import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.PortCopy
import io.github.matiyaaa.fuse.ui.shell.store.PortFiles
import io.github.matiyaaa.fuse.ui.shell.store.PortJob
import io.github.matiyaaa.fuse.ui.shell.store.PortView
import io.github.matiyaaa.fuse.ui.shell.store.PortsOps
import io.github.matiyaaa.fuse.ui.shell.store.PortsState
import io.github.matiyaaa.fuse.ui.shell.store.recover
import io.github.matiyaaa.fuse.ui.shell.store.replace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fuse Ports on a computer ([PortsOps]). The list is the one inside Fuse, or a newer one kept in
 * Fuse's folder of ports, believed only when its signature verifies with the key Fuse carries.
 * Installing downloads the build for this computer, checks it against the list's SHA-256, unpacks it
 * beside the copy in use and only then puts it in place. Setting up links the person's files in
 * where the port reads them beside itself ([PortPlan.step]), writes how it starts, and adds its game
 * to the library, so it plays like any other.
 */
internal class PortsStore(
    private val ctx: StoreContext,
    private val files: PortFiles,
    private val library: () -> LibraryOps,
    private val write: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val publicKey: () -> String? = { PortsSignature.PUBLIC_KEY },
    /** Where the person's own recomps are found and their folders read; null where there are none. */
    recompFiles: io.github.matiyaaa.fuse.ui.shell.store.RecompFiles? = null,
    /** Where a port's game may be besides this computer: RomM and the household's other devices (a test's own). */
    internal var sources: PortGameSources = PortGameSources.None,
) : PortsOps {
    override val supported: Boolean = true

    private val mutable = MutableStateFlow(PortsState(folder = files.folder, canCheck = publicKey() != null))
    override val state: StateFlow<PortsState> = mutable.asStateFlow()

    private val downloader by lazy { ApkDownloader(ctx.services.http) }
    private val client by lazy { PortsClient(ctx.services.http, publicKey = publicKey) }
    private val lock = Mutex()

    @kotlin.concurrent.Volatile private var list: PortsList? = null
    @kotlin.concurrent.Volatile private var umu: String? = null
    @kotlin.concurrent.Volatile private var lastLook = 0L
    private val jobs = MutableStateFlow<Map<String, PortJob>>(emptyMap())
    private val running = HashMap<String, Job>()

    /** What each port's chosen folders lack, by port then data id; and its game in the library. */
    private val lacks = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    private val games = MutableStateFlow<Map<String, GameId>>(emptyMap())

    /** PlayStation 5 games recompiled with AnyPS5, each kept as a port of its own. */
    private val recompiler = AnyPs5Recompiler(ctx, files, library, write, removePort = { remove(it) })

    /** Recomps: the person's own beside Fuse's, and every one's mods, graphics and controls. */
    private val recomps = RecompKeeper(
        ctx, files, recompFiles, library,
        installed = { ctx.settings.value.ports.installed },
        playWith = { playWith(it) },
        setPlayWith = { game, id -> setPlayWith(game, id) },
    )

    init {
        ctx.scope.launch { ctx.settings.drop(1).collect { publish() } }
        ctx.scope.launch { jobs.collect { publish() } }
        // The list inside Fuse and the one kept before are read at once: nothing is fetched here.
        ctx.scope.launch { lock.withLock { load() }; recheck() }
    }

    override fun open() {
        ctx.scope.launch {
            load()
            recheck()
            look(force = false)
        }
    }

    override fun refresh() {
        ctx.scope.launch {
            load()
            look(force = true)
        }
    }

    /** The list Fuse has: the newest of the one inside it and a signed one kept from before. */
    private suspend fun load() {
        umu = files.umuRun()
        if (list == null) {
            val inside = files.bundledIndex()?.let { PortsIndexReader.read(it).getOrNull() }
            val kept = keptList()
            list = listOfNotNull(kept, inside).maxByOrNull { it.index.revision * 2 + if (it.signed) 1 else 0 }
            // A copy an update was replacing when Fuse stopped goes back in place.
            for (id in ctx.settings.value.ports.installed.keys) files.recover(layout(id).install)
        }
        publish()
    }

    /** The signed list kept in Fuse's folder, checked again: a file changed since is never believed. */
    private suspend fun keptList(): PortsList? {
        val key = publicKey() ?: return null
        val text = ctx.services.fs.readText("${files.folder}/$INDEX", MAX_INDEX) ?: return null
        val sig = ctx.services.fs.readText("${files.folder}/$INDEX.sig", 1024) ?: return null
        if (!PortsSignature.verify(text.encodeToByteArray(), sig, key)) return null
        return PortsIndexReader.read(text, sig).getOrNull()
    }

    /** Looks for a newer signed list: only once Ports is turned on, and while Fuse carries the key to check it with. */
    private suspend fun look(force: Boolean) {
        if (!ctx.settings.value.ports.enabled || publicKey() == null) return
        if (!force && ctx.now() - lastLook < LOOK_EVERY_MS) return
        lastLook = ctx.now()
        mutable.update { it.copy(refreshing = true) }
        val problem = when (val r = client.fetch(newerThan = list?.index?.revision ?: 0)) {
            is ApiResult.Success -> {
                r.value?.let { fresh ->
                    list = fresh
                    files.makeFolder(files.folder)
                    files.writeText("${files.folder}/$INDEX", fresh.text)
                    fresh.signature?.let { files.writeText("${files.folder}/$INDEX.sig", it) }
                }
                null
            }
            is ApiResult.Failure -> r.message
        }
        mutable.update { it.copy(refreshing = false, problem = problem) }
        publish()
    }

    /** Looks again at each port's chosen folders and its game in the library. */
    private suspend fun recheck() {
        val all = list?.index?.ports.orEmpty()
        val chosen = ctx.settings.value.ports.folders
        lacks.value = all.associate { p -> p.id to p.data.mapNotNull { d -> chosen[p.id]?.get(d.id)?.let { f -> lacking(d, f)?.let { d.id to it } } }.toMap() }
        games.value = all.mapNotNull { p -> ctx.data.games.idByPath(layout(p.id).launcher)?.let { p.id to it } }.toMap()
        publish()
    }

    private fun publish() {
        val l = list ?: return
        val settings = ctx.settings.value.ports
        val views = l.index.ports.map { port ->
            val build = PortPlan.build(port, files.os, files.arch, umu = umu != null)
            val installed = settings.installed[port.id]
            val chosen = settings.folders[port.id].orEmpty()
            val missing = port.data.mapNotNull { d ->
                val folder = chosen[d.id]
                when {
                    folder == null -> d.id to "Not chosen"
                    else -> lacks.value[port.id]?.get(d.id)?.let { d.id to it }
                }
            }.toMap()
            PortView(
                port = port,
                build = build,
                whyNot = if (build == null) PortPlan.noBuild(port, files.os) else null,
                installedVersion = installed?.port?.version,
                installedSha = installed?.build?.sha256,
                job = jobs.value[port.id],
                // Only a signed list's word, for exactly this file, makes a port verified.
                verified = build?.verified?.takeIf { l.signed },
                folders = chosen,
                missing = missing,
                game = games.value[port.id]?.takeIf { installed != null },
            )
        }
        mutable.update {
            it.copy(ports = views, revision = l.index.revision, signed = l.signed, canCheck = publicKey() != null, loaded = true, folder = files.folder)
        }
    }

    override fun install(id: String) {
        synchronized(running) {
            if (running[id]?.isActive == true) return
            running[id] = ctx.scope.launch { lock.withLock { runInstall(id) } }
        }
    }

    override fun cancel(id: String) {
        synchronized(running) { running.remove(id) }?.cancel()
        jobs.update { it - id }
    }

    private fun fail(id: String, message: String) = jobs.update { it + (id to PortJob.Failed(message)) }

    private suspend fun runInstall(id: String) {
        if (list == null) load()
        val port = list?.index?.ports?.firstOrNull { it.id == id } ?: return fail(id, "That port isn't in the list any more.")
        val build = PortPlan.build(port, files.os, files.arch, umu = files.umuRun() != null) ?: return fail(id, PortPlan.noBuild(port, files.os))
        val layout = layout(id)
        jobs.update { it + (id to PortJob.Downloading(null, 0, build.size)) }
        val fileName = build.url.substringAfterLast('/').substringBefore('?').ifBlank { "${port.id}.download" }
        val sink = files.newDownload("${port.id}-$fileName") ?: return fail(id, "Fuse couldn't prepare its download folder.")
        try {
            // The list's SHA-256 is the one the file must have: anything else is deleted, never installed.
            downloader.download(build.url, sink, build.size, "sha256:${build.sha256}", files.freeBytes()) { written, total ->
                jobs.update { it + (id to PortJob.Downloading(total?.let { t -> (written.toFloat() / t).coerceIn(0f, 1f) }, written, total)) }
            }
            jobs.update { it + (id to PortJob.Unpacking) }
            val stage = "${layout.root}/build.new"
            files.remove(stage)
            if (!files.makeFolder(layout.root)) return fail(id, "Fuse couldn't make its folder for ${port.name}.")
            try {
                files.unpack(sink.path, build.kind, fileName, stage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                files.remove(stage)
                return fail(id, e.message ?: "${port.name}'s download couldn't be unpacked.")
            }
            if (ctx.services.fs.stat("$stage/${build.run}") == null) {
                files.remove(stage)
                return fail(id, "The download had no ${build.run} in it, so it wasn't installed.")
            }
            // What the port wrote in the copy in use (its saves and settings, beside itself) goes into the new one.
            val shipped = filesIn(stage)
            ctx.settings.value.ports.installed[id]?.let { old -> carryWritten(old, layout, stage) }
            carryKept(port, ctx.settings.value.ports.installed[id]?.build, build, layout, stage)
            // Only now is the copy in use replaced, and it is kept until the new one is in place (its
            // links go with it; setting up makes them again).
            if (!files.replace(stage, layout.install)) {
                files.remove(stage)
                return fail(id, "Fuse couldn't put ${port.name} in place. The copy you had is as it was.")
            }
            // Which files the build itself has, to tell them from what the port writes at the next update.
            if (shipped == null || !files.writeText(manifest(layout), shipped.joinToString("\n"))) files.remove(manifest(layout))
            write { s -> s.copy(ports = s.ports.copy(installed = s.ports.installed + (id to InstalledPort(port, build, emptyList(), ctx.now())))) }
            jobs.update { it - id }
            // Installed, and shown so: setting it up is all that's left, which never makes an install
            // asked for now (an update) do nothing. That one waits for it, then runs.
            val self = currentCoroutineContext()[Job]
            synchronized(running) { if (running[id] === self) running.remove(id) }
            val chosen = ctx.settings.value.ports.folders[id].orEmpty()
            if (port.data.all { it.id in chosen }) setUp(id)?.let { problem -> fail(id, problem) }
            recheck()
        } catch (e: CancellationException) {
            jobs.update { it - id }
            throw e
        } catch (e: DownloadException) {
            fail(id, e.message ?: "The download failed.")
        } finally {
            sink.discard()
        }
    }

    override suspend fun chooseFolder(id: String, dataId: String, folder: String): String? = lock.withLock {
        if (list == null) load()
        val port = list?.index?.ports?.firstOrNull { it.id == id } ?: return@withLock "That port isn't in the list any more."
        val data = port.data.firstOrNull { it.id == dataId } ?: return@withLock "${port.name} doesn't need that."
        val path = folder.replace('\\', '/').trimEnd('/')
        lacking(data, path)?.let { return@withLock it }
        // A folder chosen by hand is no longer a library game's ([useCopy] says when it is).
        write { s -> s.copy(ports = s.ports.copy(folders = s.ports.folders + (id to (s.ports.folders[id].orEmpty() + (dataId to path))), copies = s.ports.copies - id)) }
        val problem = if (id in ctx.settings.value.ports.installed && port.data.all { it.id in ctx.settings.value.ports.folders[id].orEmpty() }) setUp(id) else null
        recheck()
        problem
    }

    override suspend fun remove(id: String): Boolean = lock.withLock {
        cancel(id)
        val layout = layout(id)
        ctx.data.games.idByPath(layout.launcher)?.let { library().removeFromFuse(it) }
        // Fuse's copy and its links go (a link is removed itself, never what it points at); the home stays.
        val ok = files.remove(layout.install) and files.remove(layout.launcher)
        files.remove(manifest(layout))
        files.remove("${layout.root}/build.new")
        files.remove("${layout.install}.old")
        // Games played with it go back to their emulators.
        write { s -> s.copy(ports = s.ports.copy(installed = s.ports.installed - id, playWith = s.ports.playWith.filterValues { it != id })) }
        recheck()
        ok
    }

    override suspend fun copiesInLibrary(id: String, dataId: String): List<PortCopy> {
        if (list == null) lock.withLock { load() }
        val port = list?.index?.ports?.firstOrNull { it.id == id } ?: return emptyList()
        val data = port.data.firstOrNull { it.id == dataId } ?: return emptyList()
        // Found by the game's name, then believed only by its files: a folder the port can read, or
        // for a recompilation the ROM of its system it was made from, by its hash.
        val found = runCatching { ctx.data.games.search(port.game, limit = LIBRARY_LOOK) }.getOrDefault(emptyList())
            .filter { it.platformId != PortAdapter.PLATFORM && (data.rom == null || it.platformId.value == data.rom!!.system) }
        val out = ArrayList<PortCopy>()
        for (summary in found) {
            val game = runCatching { ctx.data.games.get(summary.id) }.getOrNull() ?: continue
            val path = game.location.path.replace('\\', '/').trimEnd('/')
            if (path.isEmpty() || !FsPath.isAbsolute(path)) continue
            val places = if (data.rom != null) listOf(game.location.launchPath.replace('\\', '/')) else listOfNotNull(path, FsPath.parent(path)).distinct()
            val folder = places.firstOrNull { lacking(data, it) == null } ?: continue
            if (out.none { it.folder == folder }) out += PortCopy(game.id, game.displayTitle, ctx.platformName(game.platformId), folder)
        }
        val name = TitleText.normalize(port.game)
        return out.sortedByDescending { TitleText.normalize(it.title) == name }
    }

    override suspend fun useCopy(id: String, dataId: String, copy: PortCopy): String? {
        val problem = chooseFolder(id, dataId, copy.folder)
        if (problem == null) write { s -> s.copy(ports = s.ports.copy(copies = s.ports.copies + (id to copy.game.value))) }
        return problem
    }

    override suspend fun copiesElsewhere(id: String, dataId: String): List<io.github.matiyaaa.fuse.ui.shell.store.PortGameCopy> {
        if (list == null) lock.withLock { load() }
        val port = list?.index?.ports?.firstOrNull { it.id == id } ?: return emptyList()
        val data = port.data.firstOrNull { it.id == dataId } ?: return emptyList()
        // By the game's name, on its ROM's system, or a computer's for a game's folder.
        val name = AndroidPortCatalog.key(port.game)
        return runCatching { sources.games(systemsOf(data)) }.getOrDefault(emptyList())
            .filter { name in AndroidPortCatalog.titlesOf(io.github.matiyaaa.fuse.model.GameTitles(original = it.title)) }
    }

    override suspend fun bringGame(id: String, copy: io.github.matiyaaa.fuse.ui.shell.store.PortGameCopy): String? {
        if (list == null) lock.withLock { load() }
        val port = list?.index?.ports?.firstOrNull { it.id == id } ?: return "Fuse doesn't know that port any more."
        val data = port.data.firstOrNull { copy.platform.value in systemsOf(it) } ?: return "${port.name} doesn't read that game's files."
        sources.bring(copy)?.let { return it }
        ctx.scope.launch {
            // Once it is in the library, the port reads it, when its files are what the port needs.
            val game = sources.arrived(copy).first { it != null }!!
            copiesInLibrary(id, data.id).firstOrNull { it.game == game }?.let { useCopy(id, data.id, it) }
        }
        return null
    }

    /** The systems a port's game files [data] come from: its ROM's, else a computer's. */
    private fun systemsOf(data: io.github.matiyaaa.fuse.model.PortData): Set<String> = data.rom?.let { setOf(it.system) } ?: COMPUTER_SYSTEMS

    override suspend fun portsFor(game: GameId): List<PortView> {
        val g = runCatching { ctx.data.games.get(game) }.getOrNull() ?: return emptyList()
        if (g.platformId == PortAdapter.PLATFORM) return emptyList()
        val name = TitleText.normalize(g.displayTitle)
        val path = g.location.path.replace('\\', '/').trimEnd('/')
        val near = setOfNotNull(path, FsPath.parent(path))
        val s = ctx.settings.value.ports
        val listed = mutable.value.ports.filter { v ->
            v.ready && (s.copies[v.port.id] == game.value || s.folders[v.port.id].orEmpty().values.any { it in near } || TitleText.normalize(v.port.game) == name)
        }
        // A build AnyPS5 made of this very game (it isn't in the list of ports).
        val recompiled = s.installed.filter { (id, _) -> AnyPs5Recompile.isRecompiled(id) && s.copies[id] == game.value }.mapNotNull { (id, installed) ->
            val launcherGame = runCatching { ctx.data.games.idByPath(layout(id).launcher) }.getOrNull() ?: return@mapNotNull null
            PortView(installed.port, installed.build, null, installedVersion = installed.port.version, installedSha = installed.build.sha256, game = launcherGame)
        }
        return listed + recompiled
    }

    override suspend fun recompileOffer(game: GameId) = recompiler.offer(game)

    override suspend fun recompile(game: GameId, onStep: (String) -> Unit): String? = recompiler.recompile(game, onStep)

    override suspend fun removeRecompiled(game: GameId): Boolean = recompiler.remove(game)

    override suspend fun setPlayWith(game: GameId, portId: String?) = write { s ->
        val key = game.value.toString()
        s.copy(ports = s.ports.copy(playWith = if (portId == null) s.ports.playWith - key else s.ports.playWith + (key to portId)))
    }

    override fun playWith(game: GameId): String? = ctx.settings.value.ports.playWith[game.value.toString()]

    /**
     * The port's own game for [game], found from what is kept (not from what Ports shows), so a
     * start from Steam before Ports was ever opened finds it too.
     */
    override suspend fun playTarget(game: GameId): GameId? {
        val portId = playWith(game) ?: return null
        if (portId.startsWith(RecompKeeper.YOURS_PREFIX)) return recomps.yoursTarget(game, ::layout)
        if (portId !in ctx.settings.value.ports.installed) return null
        return runCatching { ctx.data.games.idByPath(layout(portId).launcher) }.getOrNull()?.takeIf { it != game }
    }

    /** [playTarget] with the port's name, for the game's page. */
    suspend fun playTargetNamed(game: GameId): Pair<GameId, String>? {
        val target = playTarget(game) ?: return null
        val portId = playWith(game) ?: return null
        if (portId.startsWith(RecompKeeper.YOURS_PREFIX)) {
            return target to (io.github.matiyaaa.fuse.launch.ports.NativePorts.name(portId.removePrefix(RecompKeeper.YOURS_PREFIX)) ?: return null)
        }
        return target to (ctx.settings.value.ports.installed[portId]?.port?.name ?: return null)
    }

    override suspend fun yourRecomps() = recomps.yours(fresh = true)

    override suspend fun yourRecompsFor(game: GameId) = recomps.yoursFor(game)

    override suspend fun playWithYours(game: GameId, recomp: io.github.matiyaaa.fuse.ui.shell.store.FoundRecomp) = recomps.playWithYours(game, recomp, ::layout)

    override suspend fun recompFor(game: GameId) = recomps.placeFor(game, ::layout)

    override suspend fun recompMods(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace) = recomps.mods(place)

    override suspend fun addRecompMod(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace, file: String) = recomps.addMod(place, file)

    override suspend fun removeRecompMod(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace, name: String) = recomps.removeMod(place, name)

    override suspend fun recompGraphics(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace) = recomps.graphics(place)

    override suspend fun setRecompGraphic(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace, key: String, choice: io.github.matiyaaa.fuse.launch.ports.RecompChoice) =
        recomps.setGraphic(place, key, choice)

    override suspend fun recompProfiles(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace) = recomps.profiles(place)

    override suspend fun saveRecompProfile(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace, name: String) = recomps.saveProfile(place, name)

    override suspend fun useRecompProfile(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace, name: String) = recomps.useProfile(place, name)

    override suspend fun removeRecompProfile(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace, name: String) = recomps.removeProfile(place, name)

    override suspend fun recompRelease(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace) = recomps.release(place)

    override suspend fun updateYourRecomp(place: io.github.matiyaaa.fuse.ui.shell.store.RecompPlace) = recomps.updateYours(place)

    /**
     * Sets [id] up with the folders chosen: the overlay's links made again, its saves' folder there,
     * how it starts written, its game in the library. Null when it is ready, else why not.
     */
    private suspend fun setUp(id: String): String? {
        val installed = ctx.settings.value.ports.installed[id] ?: return "Install it first."
        val port = installed.port
        val build = installed.build
        val layout = layout(id)
        val chosen = ctx.settings.value.ports.folders[id].orEmpty()
        for (d in port.data) {
            val folder = chosen[d.id] ?: return "Choose ${d.name}."
            lacking(d, folder)?.let { return it }
        }
        jobs.update { it + (id to PortJob.SettingUp) }
        try {
            for (rel in installed.links) {
                val at = "${layout.install}/$rel"
                if (files.isLink(at)) files.remove(at)
            }
            val made = ArrayList<String>()
            val problem = port.data.firstOrNull { it.overlay != null }?.let { d -> overlay(d, chosen.getValue(d.id), layout, made) }
                ?: keepFolders(port, build, layout, made)
            write { s -> s.copy(ports = s.ports.copy(installed = s.ports.installed + (id to installed.copy(links = made)))) }
            if (problem != null) return problem
            if (!files.makeFolder(layout.home)) return "Fuse couldn't make ${port.name}'s home in its folder."
            port.saves?.takeIf { it.isNotEmpty() }?.let { files.makeFolder("${layout.home}/$it") }
            for (d in port.data) d.rom?.let { rom -> placeRom(rom, chosen.getValue(d.id), layout)?.let { return it } }
            val launcher = PortPlan.launcher(port, build, layout, chosen, files.umuRun())
                ?: return if (build.runner == io.github.matiyaaa.fuse.model.PortRunner.UMU) PortPlan.noBuild(port, files.os) else "Choose its game files first."
            if (!files.writeText(layout.launcher, launcher.encode())) return "Fuse couldn't write how ${port.name} starts."
            val game = library().addGameFile(layout.launcher, PortAdapter.PLATFORM, title = port.game)
                ?: return "Fuse couldn't add ${port.game} to the library."
            games.update { it + (id to game) }
            return null
        } finally {
            jobs.update { if (it[id] == PortJob.SettingUp) it - id else it }
        }
    }

    /**
     * Links the person's folder [person] into the port's copy, entry by entry, as [PortPlan.step]
     * says: the game's files where the port reads them, a real folder of Fuse's own where one of
     * the port's own paths is further down, and the port's own folders as links into its home.
     * What the build has itself is never replaced. Each link made is added to [made].
     */
    private suspend fun overlay(d: PortData, person: String, layout: PortLayout, made: MutableList<String>): String? {
        val o = d.overlay ?: return null
        val fs = ctx.services.fs
        val base = if (o.into.isEmpty()) layout.install else "${layout.install}/${o.into}"
        if (!files.makeFolder(base)) return "Fuse couldn't make its copy's folder."
        fun rel(at: String) = at.removePrefix("${layout.install}/")

        suspend fun walk(from: String, relative: String, into: String): String? {
            val entries = runCatching { fs.list(from) }.getOrNull() ?: return "Fuse can't read $from."
            val there = runCatching { fs.list(into) }.getOrNull().orEmpty().associateBy { it.name.lowercase() }
            for (e in entries.sortedBy { it.name }) {
                val r = if (relative.isEmpty()) e.name else "$relative/${e.name}"
                when (PortPlan.step(r, e.isDirectory, o.own)) {
                    OverlayStep.LINK -> if (e.name.lowercase() !in there) {
                        val at = "$into/${e.name}"
                        files.link(e.path, at)?.let { return it }
                        made += rel(at)
                    }
                    OverlayStep.DESCEND -> {
                        val existing = there[e.name.lowercase()]?.takeIf { it.isDirectory && !it.isSymlink }?.path
                        val dir = existing ?: "$into/${e.name}".also { if (!files.makeFolder(it)) return "Fuse couldn't make a folder in its copy." }
                        walk(e.path, r, dir)?.let { return it }
                    }
                    OverlayStep.OWN_FOLDER, OverlayStep.SKIP -> Unit
                }
            }
            return null
        }
        walk(person, "", base)?.let { return it }
        // The port's own folders: links into its home, found by name whatever their case in the copy.
        for (own in PortPlan.ownFolders(o.own)) {
            var dir = base
            val parts = own.split('/')
            for ((i, part) in parts.withIndex()) {
                val found = runCatching { fs.list(dir) }.getOrNull().orEmpty().firstOrNull { it.name.equals(part, ignoreCase = true) }
                if (i < parts.lastIndex) {
                    dir = found?.path ?: "$dir/$part".also { if (!files.makeFolder(it)) return "Fuse couldn't make a folder in its copy." }
                } else if (found == null) {
                    val home = "${layout.home}/$own"
                    if (!files.makeFolder(home)) return "Fuse couldn't make ${d.name}'s own folder."
                    val at = "$dir/$part"
                    files.link(home, at)?.let { return it }
                    made += rel(at)
                }
            }
        }
        return null
    }

    /**
     * The folders [port] keeps beside its program ([io.github.matiyaaa.fuse.model.Port.keep]): each a
     * link to the same path in its home, made there first, unless the build has that path itself.
     * Each link made is added to [made].
     */
    private suspend fun keepFolders(port: io.github.matiyaaa.fuse.model.Port, build: io.github.matiyaaa.fuse.model.PortBuild, layout: PortLayout, made: MutableList<String>): String? {
        val fs = ctx.services.fs
        val beside = PortPlan.programFolder(build).let { if (it.isEmpty()) layout.install else "${layout.install}/$it" }
        for (folder in PortPlan.ownFolders(port.keep)) {
            val at = "$beside/$folder"
            if (fs.stat(at) != null || files.isLink(at)) continue
            val home = "${layout.home}/$folder"
            if (!files.makeFolder(home)) return "Fuse couldn't make ${port.name}'s own folder."
            if (!files.makeFolder(at.substringBeforeLast('/'))) return "Fuse couldn't make a folder in its copy."
            files.link(home, at)?.let { return it }
            made += at.removePrefix("${layout.install}/")
        }
        return null
    }

    /** Where the list of the files a port's build has itself is kept, beside its copy. */
    private fun manifest(layout: PortLayout) = "${layout.root}/build.files"

    /**
     * The files [dir] holds, relative to it with `/`: never into a link, nor into one of [skip]
     * (the links Fuse made, a Windows junction among them, which leads to the person's own folder
     * or the port's home). Null when there are more than [MANIFEST_LIMIT].
     */
    private suspend fun filesIn(dir: String, skip: Set<String> = emptySet()): List<String>? {
        val fs = ctx.services.fs
        val out = ArrayList<String>()
        suspend fun walk(at: String, rel: String): Boolean {
            val entries = runCatching { fs.list(at) }.getOrNull() ?: return true
            for (e in entries) {
                val r = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                if (e.isSymlink || r in skip) continue
                if (e.isDirectory) {
                    if (!walk(e.path, r)) return false
                } else {
                    out += r
                    if (out.size > MANIFEST_LIMIT) return false
                }
            }
            return true
        }
        return if (walk(dir, "")) out else null
    }

    /**
     * On an update, every file the port wrote in the copy in use ([old]: its saves and settings,
     * when it keeps them beside itself) moved into the new copy ([stage]): a file its build didn't
     * have (the list kept at its install, [manifest]), never one inside a link Fuse made, and never
     * over a file the new build has. Without that list (a port installed before Fuse kept one),
     * nothing is moved.
     */
    private suspend fun carryWritten(old: InstalledPort, layout: PortLayout, stage: String) {
        val fs = ctx.services.fs
        val shipped = fs.readText(manifest(layout), maxBytes = MANIFEST_BYTES)?.lineSequence()?.filter { it.isNotEmpty() }?.toHashSet() ?: return
        val written = filesIn(layout.install, old.links.toHashSet()) ?: return
        for (rel in written) {
            if (rel in shipped) continue
            val to = "$stage/$rel"
            if (fs.stat(to) != null) continue
            files.makeFolder(to.substringBeforeLast('/'))
            files.move("${layout.install}/$rel", to)
        }
    }

    /**
     * On an update, the files [port] keeps beside its program (its settings) moved from the copy in
     * use ([old] its build, null on a first install) into the new one ([stage], of [build]), in place
     * of the file the new build has there.
     */
    private suspend fun carryKept(port: io.github.matiyaaa.fuse.model.Port, old: io.github.matiyaaa.fuse.model.PortBuild?, build: io.github.matiyaaa.fuse.model.PortBuild, layout: PortLayout, stage: String) {
        if (old == null) return
        val fs = ctx.services.fs
        fun under(root: String, b: io.github.matiyaaa.fuse.model.PortBuild) = PortPlan.programFolder(b).let { if (it.isEmpty()) root else "$root/$it" }
        for (file in PortPlan.keptFiles(port.keep)) {
            val from = "${under(layout.install, old)}/$file"
            val to = "${under(stage, build)}/$file"
            if (fs.stat(from) == null || files.isLink(from)) continue
            files.remove(to)
            files.makeFolder(to.substringBeforeLast('/'))
            files.move(from, to)
        }
    }

    /**
     * A recompilation's ROM where it looks for it in its home: a link to the person's [file] when
     * that is already in the form it reads, else a copy turned into it. The person's file stays as it is.
     */
    private suspend fun placeRom(rom: io.github.matiyaaa.fuse.model.PortRom, file: String, layout: PortLayout): String? {
        val at = "${layout.home}/${rom.place}"
        val bytes = romBytes(file) ?: return "Fuse can't read that ROM."
        files.makeFolder(at.substringBeforeLast('/'))
        files.remove(at)
        return if (PortPlan.isBigEndianRom(bytes)) {
            files.link(file, at)
        } else {
            val turned = PortPlan.bigEndianRom(bytes) ?: return "That isn't an N64 ROM."
            if (files.writeBytes(at, turned)) null else "Fuse couldn't put the ROM in its folder."
        }
    }

    /** The whole of [file], when it is a file no larger than any ROM. */
    private suspend fun romBytes(file: String): ByteArray? {
        val st = runCatching { ctx.services.fs.stat(file) }.getOrNull()?.takeIf { !it.isDirectory && it.sizeBytes in 1..MAX_ROM } ?: return null
        return runCatching { ctx.services.fs.readBytes(file, 0, st.sizeBytes.toInt()) }.getOrNull()
    }

    /** What [folder] lacks of [d]'s needs, said for the person; null when it has them all. */
    private suspend fun lacking(d: PortData, folder: String): String? {
        val fs = ctx.services.fs
        d.rom?.let { rom ->
            val bytes = romBytes(folder) ?: return "Choose the ROM file itself."
            return if (PortPlan.isRom(rom, bytes)) null else "That isn't the ROM it is made from. ${d.about}"
        }
        if (runCatching { fs.stat(folder) }.getOrNull()?.isDirectory != true) return "Fuse can't open that folder."
        // As PortPlan.missing says, with the folder looked at here (each need: any one of its names).
        val missing = d.needs.filter { names -> names.none { has(folder, it, d.exact) } }
        return if (missing.isEmpty()) null else "That folder has no ${PortPlan.describe(missing)}."
    }

    /** True when [relative] is inside [folder], its letter case as written when [exact], else any. */
    private suspend fun has(folder: String, relative: String, exact: Boolean): Boolean {
        var dir = folder
        val parts = relative.split('/')
        for ((i, part) in parts.withIndex()) {
            val entries = runCatching { ctx.services.fs.list(dir) }.getOrNull() ?: return false
            val hit = entries.firstOrNull { if (exact) it.name == part else it.name.equals(part, ignoreCase = true) } ?: return false
            if (i < parts.lastIndex && !hit.isDirectory) return false
            dir = hit.path
        }
        return true
    }

    private fun layout(id: String) = PortLayout("${files.folder.trimEnd('/')}/$id", id)

    companion object {
        const val INDEX = "index.json"

        /** How many of the library's games with the port's game's name are looked in for its files. */
        private const val LIBRARY_LOOK = 40

        /** The largest ROM read to check it (an N64 cartridge holds 64 MB at most). */
        private const val MAX_ROM = 128L * 1024 * 1024

        /** The most files a build's list holds; a build with more keeps none, and nothing is carried at its update. */
        private const val MANIFEST_LIMIT = 50_000

        /** The largest list of a build's files read back. */
        private const val MANIFEST_BYTES = 16 shl 20
        private const val MAX_INDEX = 1 shl 20

        /** How often opening Ports looks for a newer signed list. */
        private const val LOOK_EVERY_MS = 6L * 60 * 60 * 1000

        /** The systems a computer game's folder is found under, on RomM and other devices. */
        private val COMPUTER_SYSTEMS = setOf("win", "dos", "win3x", "win9x")
    }
}
