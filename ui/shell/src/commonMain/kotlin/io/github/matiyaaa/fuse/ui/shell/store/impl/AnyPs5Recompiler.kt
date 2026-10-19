package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.InstalledPort
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.github.GitHubReleases
import io.github.matiyaaa.fuse.launch.ports.AnyPs5Recompile
import io.github.matiyaaa.fuse.launch.ports.PortAdapter
import io.github.matiyaaa.fuse.launch.ports.PortLayout
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PortFileKind
import io.github.matiyaaa.fuse.ui.shell.store.InstallerRun
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.PortFiles
import io.github.matiyaaa.fuse.ui.shell.store.RecompileOffer
import io.github.matiyaaa.fuse.ui.shell.store.replace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recompiling the person's PlayStation 5 dumps with AnyPS5 ([AnyPs5Recompile]), on a computer
 * with Fuse Ports' folder. AnyPS5 itself is fetched from its newest release on GitHub only when the
 * person asks to recompile a game, both its files checked against the SHA-256 GitHub gives for them,
 * and kept per release (`anyps5/<tag>/`) while any game's build uses it. Each game's build is made
 * beside the one in use and only put in place once AnyPS5 has made its program; the person's dump is
 * only ever read and linked to.
 */
internal class AnyPs5Recompiler(
    private val ctx: StoreContext,
    private val files: PortFiles,
    private val library: () -> LibraryOps,
    private val write: suspend ((AppSettings) -> AppSettings) -> Unit,
    /** Removes a recompiled game's build as Ports removes a port (its library game and Play With with it). */
    private val removePort: suspend (String) -> Boolean,
) {
    private val downloader by lazy { ApkDownloader(ctx.services.http) }
    private val lock = Mutex()

    private fun layout(id: String) = PortLayout("${files.folder.trimEnd('/')}/$id", id)

    /** AnyPS5's own files for release [tag]: the relinker in `bin/`, its libraries in `prx/libs/`. */
    private fun tool(tag: String) = "${files.folder.trimEnd('/')}/$TOOL/${safe(tag)}"

    private fun safe(tag: String) = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The dump [game] is played from: the folder of its eboot.bin; null when it is something else (a build AnyPS5 already made). */
    private fun dumpOf(game: Game): String? {
        val launch = game.location.launchPath.replace('\\', '/')
        if (!FsPath.name(launch).equals("eboot.bin", ignoreCase = true)) return null
        return FsPath.parent(launch)
    }

    suspend fun offer(id: GameId): RecompileOffer? {
        val game = runCatching { ctx.data.games.get(id) }.getOrNull() ?: return null
        if (game.platformId.value != PS5) return null
        val dump = dumpOf(game) ?: return null
        AnyPs5Recompile.unsupported(files.os, files.arch)?.let { return RecompileOffer(whyNot = it) }
        if (ctx.services.emulatorFiles == null) return RecompileOffer(whyNot = "Fuse can't run AnyPS5's recompiler on this device.")
        val portId = AnyPs5Recompile.id(game.tags.serial)
            ?: return RecompileOffer(whyNot = "Fuse can't tell this game's title id (like PPSA01234), which its build is kept under. Name its folder with it, like Game [PPSA01234].")
        lacks(dump)?.let { return RecompileOffer(whyNot = it) }
        val built = ctx.settings.value.ports.installed[portId]?.port?.version
        return RecompileOffer(builtWith = built, warning = processorWarning())
    }

    /** What [dump] lacks for the relinker, read from its top; null when it has it all. */
    private suspend fun lacks(dump: String): String? {
        val entries = runCatching { ctx.services.fs.list(dump) }.getOrNull() ?: return "Fuse can't read the dump's folder."
        val eboot = entries.firstOrNull { !it.isDirectory && it.name == "eboot.bin" }
        val head = eboot?.let { runCatching { ctx.services.fs.readBytes(it.path, 0, 4) }.getOrNull() ?: ByteArray(0) }
        return AnyPs5Recompile.lacks(entries.map { it.name }, head?.let(AnyPs5Recompile::executable))
    }

    /** On Linux, the processor extensions a recompiled game needs that this one hasn't, said once; null when it has them (or Fuse can't tell). */
    private suspend fun processorWarning(): String? {
        if (files.os != "linux") return null
        val cpuinfo = runCatching { ctx.services.fs.readText("/proc/cpuinfo", CPUINFO_LIMIT) }.getOrNull() ?: return null
        val missing = AnyPs5Recompile.missingExtensions(cpuinfo).ifEmpty { return null }
        return "This processor lacks ${missing.joinToString(", ")}, which the PS5's code uses, so a recompiled game stops as it starts."
    }

    suspend fun recompile(id: GameId, onStep: (String) -> Unit): String? = lock.withLock {
        val game = runCatching { ctx.data.games.get(id) }.getOrNull() ?: return@withLock "This game is no longer in the library."
        val offer = offer(id) ?: return@withLock "Only a PlayStation 5 dump with its eboot.bin is recompiled."
        offer.whyNot?.let { return@withLock it }
        val dump = dumpOf(game)!!
        val portId = AnyPs5Recompile.id(game.tags.serial)!!
        val run = ctx.services.emulatorFiles ?: return@withLock "Fuse can't run AnyPS5's recompiler on this device."
        val (tag, relinker) = when (val t = toolHere(onStep)) {
            is Tool.Ready -> t.tag to t
            is Tool.Problem -> return@withLock t.message
        }
        onStep("Recompiling ${game.displayTitle}")
        val layout = layout(portId)
        val stage = "${layout.root}/build.new"
        files.remove(stage)
        if (!files.makeFolder(stage) || !files.makeFolder(layout.home)) return@withLock "Fuse couldn't make a folder for ${game.displayTitle}'s build."
        try {
            prepare(stage, dump, layout, tag)?.let { return@withLock it }
            val program = "$stage/${AnyPs5Recompile.program(files.os)}"
            val argv = AnyPs5Recompile.argv("${tool(tag)}/bin/${AnyPs5Recompile.relinker(files.os)}", files.os, "$dump/eboot.bin", program)
            val result = run.runInstaller(InstallerRun(argv, workingDir = stage, timeoutMs = RELINK_MS)) { line -> line.trim().takeIf { it.isNotEmpty() }?.let { onStep(it.take(120)) } }
                ?: return@withLock "Fuse can't run AnyPS5's recompiler on this device."
            when {
                !result.started -> return@withLock "AnyPS5's recompiler didn't start."
                result.timedOut -> return@withLock "AnyPS5 took longer than ${RELINK_MS / 60_000} minutes and was stopped."
                result.exitCode != 0 -> return@withLock "AnyPS5 couldn't recompile ${game.displayTitle}" +
                    (result.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.let { ": ${it.take(240)}" } ?: ".")
            }
            if (runCatching { ctx.services.fs.stat(program) }.getOrNull() == null) return@withLock "AnyPS5 said it was done, but made no program."
            // Only now is the build in use replaced, and it is kept until the new one is in place; its
            // home (saves) stays where it is.
            if (!files.replace(stage, layout.install)) return@withLock "Fuse couldn't put ${game.displayTitle}'s build in place. The build you had is as it was."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock e.message ?: "Fuse couldn't make ${game.displayTitle}'s build."
        } finally {
            files.remove(stage)
        }
        val launcher = AnyPs5Recompile.launcher(layout, files.os, tag)
        if (!files.writeText(layout.launcher, launcher.encode())) return@withLock "Fuse couldn't write how ${game.displayTitle}'s build starts."
        val port = AnyPs5Recompile.port(portId, game.displayTitle, tag, files.os, relinker.url, relinker.size, relinker.sha256)
        library().addGameFile(layout.launcher, PortAdapter.PLATFORM, title = game.displayTitle)
            ?: return@withLock "Fuse couldn't add ${game.displayTitle}'s build to the library."
        // Played with from now on, as a port the person chose would be.
        write { s ->
            s.copy(
                ports = s.ports.copy(
                    installed = s.ports.installed + (portId to InstalledPort(port, port.builds.single(), emptyList(), ctx.now())),
                    copies = s.ports.copies + (portId to id.value),
                    playWith = s.ports.playWith + (id.value.toString() to portId),
                ),
            )
        }
        tidy()
        null
    }

    /**
     * The build's folder before AnyPS5 runs: its libraries (the release's, linked), `app0/` of links
     * to the dump's own entries but its module folders, and the folders the game writes linked into
     * the home. Null when it is ready, else why not.
     */
    private suspend fun prepare(stage: String, dump: String, layout: PortLayout, tag: String): String? {
        files.link("${tool(tag)}/prx/libs", "$stage/libs")?.let { return it }
        if (!files.makeFolder("$stage/app0")) return "Fuse couldn't make the build's app0 folder."
        val names = runCatching { ctx.services.fs.list(dump) }.getOrNull()?.map { it.name } ?: return "Fuse can't read the dump's folder."
        for (name in AnyPs5Recompile.app0Links(names)) files.link("$dump/$name", "$stage/app0/$name")?.let { return it }
        for (own in AnyPs5Recompile.OWN_FOLDERS) {
            if (!files.makeFolder("${layout.home}/$own")) return "Fuse couldn't make the build's $own folder."
            files.link("${layout.home}/$own", "$stage/$own")?.let { return it }
        }
        return null
    }

    private sealed interface Tool {
        data class Ready(val tag: String, val url: String, val size: Long, val sha256: String) : Tool
        data class Problem(val message: String) : Tool
    }

    /**
     * AnyPS5's newest release, fetched and unpacked when Fuse hasn't it yet; the one Fuse has when
     * GitHub doesn't answer. Its relinker and libraries are both checked against GitHub's SHA-256.
     */
    private suspend fun toolHere(onStep: (String) -> Unit): Tool {
        val have = ctx.settings.value.ports.anyPs5
        val token = runCatching { ctx.services.secrets.get(DefaultAppStoreOps.TOKEN_KEY) }.getOrNull()
        val release = when (val r = GitHubReleases(ctx.services.http, token = { token }).latest(AnyPs5Recompile.OWNER, AnyPs5Recompile.REPO)) {
            is ApiResult.Success -> r.value
            else -> null
        }
        if (release == null) {
            val kept = have?.let { installedTool(it) }
            return kept ?: Tool.Problem(if (have == null) "Fuse couldn't reach AnyPS5's releases on GitHub. Check the connection and try again." else "AnyPS5's files are gone from Fuse's folder, and GitHub didn't answer to fetch them again.")
        }
        val tag = release.tag
        val relinker = release.assets.firstOrNull { it.name == AnyPs5Recompile.relinkerAsset(files.os, tag) }
        val libraries = release.assets.firstOrNull { it.name == AnyPs5Recompile.librariesAsset(files.os, tag) }
        if (relinker == null || libraries == null) return Tool.Problem("AnyPS5 $tag has no recompiler for this computer.")
        // GitHub's own SHA-256 of each file is what it must match; without one nothing is downloaded.
        fun sha(digest: String?) = digest?.takeIf { it.startsWith(SHA256) }?.removePrefix(SHA256)?.takeIf { it.length == 64 }
        val relinkerSha = sha(relinker.digest)
        if (relinkerSha == null || sha(libraries.digest) == null) return Tool.Problem("GitHub gave no checksum for AnyPS5 $tag's files, so Fuse didn't download them.")
        val ready = Tool.Ready(tag, relinker.url, relinker.sizeBytes, relinkerSha)
        if (have == tag && installedTool(tag) != null) return ready
        val stage = "${tool(tag)}.new"
        files.remove(stage)
        try {
            for ((asset, into, kind) in listOf(Triple(relinker, "$stage/bin", PortFileKind.FILE), Triple(libraries, "$stage/prx", PortFileKind.ZIP))) {
                onStep("Downloading AnyPS5 $tag")
                val sink = files.newDownload(asset.name) ?: return Tool.Problem("Fuse couldn't prepare its download folder.")
                try {
                    downloader.download(asset.url, sink, asset.sizeBytes, asset.digest, files.freeBytes()) { _, _ -> }
                    files.unpack(sink.path, kind, AnyPs5Recompile.relinker(files.os), into)
                } finally {
                    sink.discard()
                }
            }
            if (runCatching { ctx.services.fs.list("$stage/prx/libs") }.getOrNull().isNullOrEmpty()) return Tool.Problem("AnyPS5 $tag's libraries had no libs folder, so it wasn't kept.")
            if (!files.replace(stage, tool(tag))) return Tool.Problem("Fuse couldn't put AnyPS5 in its folder.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            return Tool.Problem(e.message ?: "AnyPS5's download failed.")
        } catch (e: Exception) {
            return Tool.Problem(e.message ?: "AnyPS5's files couldn't be unpacked.")
        } finally {
            files.remove(stage)
        }
        write { s -> s.copy(ports = s.ports.copy(anyPs5 = tag)) }
        return ready
    }

    /** The release [tag] Fuse already has, as it was recorded with a game it built; null when its files aren't all here. */
    private suspend fun installedTool(tag: String): Tool.Ready? {
        val fs = ctx.services.fs
        if (runCatching { fs.stat("${tool(tag)}/bin/${AnyPs5Recompile.relinker(files.os)}") }.getOrNull() == null) return null
        if (runCatching { fs.list("${tool(tag)}/prx/libs") }.getOrNull().isNullOrEmpty()) return null
        val build = ctx.settings.value.ports.installed.values.firstOrNull { AnyPs5Recompile.isRecompiled(it.port.id) && it.port.version == tag }?.build
        return Tool.Ready(tag, build?.url.orEmpty(), build?.size ?: 0, build?.sha256.orEmpty())
    }

    /** Releases no game's build uses any more, and that aren't the newest Fuse has, are taken out of Fuse's folder. */
    private suspend fun tidy() {
        val used = ctx.settings.value.ports.installed.filterKeys(AnyPs5Recompile::isRecompiled).values.map { safe(it.port.version) }.toSet() +
            listOfNotNull(ctx.settings.value.ports.anyPs5?.let(::safe))
        val here = runCatching { ctx.services.fs.list("${files.folder.trimEnd('/')}/$TOOL") }.getOrNull().orEmpty()
        for (e in here) if (e.isDirectory && e.name !in used && !e.name.endsWith(".new")) files.remove(e.path)
    }

    /** Takes [id]'s build out, as a port is removed; its home (saves) stays. */
    suspend fun remove(id: GameId): Boolean = lock.withLock {
        val game = runCatching { ctx.data.games.get(id) }.getOrNull() ?: return@withLock false
        val portId = AnyPs5Recompile.id(game.tags.serial) ?: return@withLock false
        if (portId !in ctx.settings.value.ports.installed) return@withLock false
        val ok = removePort(portId)
        write { s -> s.copy(ports = s.ports.copy(copies = s.ports.copies - portId)) }
        tidy()
        ok
    }

    private companion object {
        const val PS5 = "ps5"

        /** AnyPS5's own files, by release, in Fuse's folder of ports. */
        const val TOOL = "anyps5"

        /** A large game's code takes the relinker a while; it is stopped after this. */
        const val RELINK_MS = 45 * 60 * 1000L

        const val CPUINFO_LIMIT = 256 * 1024

        const val SHA256 = "sha256:"
    }
}
