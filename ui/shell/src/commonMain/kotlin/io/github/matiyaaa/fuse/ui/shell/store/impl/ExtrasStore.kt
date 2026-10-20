package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.extras.BiosPlacement
import io.github.matiyaaa.fuse.launch.extras.CemuGraphicPacks
import io.github.matiyaaa.fuse.launch.extras.Rpcs3PatchFile
import io.github.matiyaaa.fuse.launch.extras.SwitchFirmware
import io.github.matiyaaa.fuse.launch.extras.XeniaPatches
import io.github.matiyaaa.fuse.launch.online.CemuNetwork
import io.github.matiyaaa.fuse.launch.patches.PlannedFile
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.Extra
import io.github.matiyaaa.fuse.ui.shell.store.ExtraKind
import io.github.matiyaaa.fuse.ui.shell.store.ExtrasOps
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ExtrasOps] on a computer: each extra found, fetched and put in place as the emulator itself does
 * it (see [CemuGraphicPacks], [Rpcs3PatchFile], [XeniaPatches], [SwitchFirmware], [BiosPlacement],
 * [CemuNetwork]), through [io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles], which keeps a
 * restoration point for everything it replaces. One at a time.
 */
internal class ExtrasStore(
    private val ctx: StoreContext,
    private val detect: suspend () -> Unit,
    /** The firmware check's latest answer, by system. */
    private val bios: () -> Map<PlatformId, BiosStatus>,
    private val refreshBios: () -> Unit,
) : ExtrasOps {
    private val lock = Mutex()
    private val files get() = ctx.services.emulatorFiles
    private val fs get() = ctx.services.fs

    private suspend fun installed(emulator: String): InstalledEmulator? {
        if (ctx.installed.value.isEmpty()) detect()
        return ctx.installed.value.firstOrNull { family(it) == emulator }
    }

    private fun family(e: InstalledEmulator) = e.id.value.substringAfter('.').let { if (it.startsWith("retroarch")) "retroarch" else it }

    override suspend fun offered(emulator: String): List<Extra> {
        val f = files ?: return emptyList()
        val inst = installed(emulator) ?: return emptyList()
        return buildList {
            when (emulator) {
                "cemu" -> {
                    val home = f.cemu(inst)
                    val version = home?.let { fs.readText("${it.data}/graphicPacks/${CemuGraphicPacks.FOLDER}/${CemuGraphicPacks.VERSION_FILE}", 4096) }?.lineSequence()?.firstOrNull()?.trim()
                    add(Extra(
                        ExtraKind.CEMU_GRAPHIC_PACKS, "Community Graphic Packs",
                        "The latest from cemu-project, as Cemu's own download gets them: higher resolutions, frame rates and fixes. Turn each on in Cemu's Graphic Packs window",
                        state = version?.takeIf { it.isNotEmpty() } ?: "Not yet", undoable = undoPoint(emulator, ExtraKind.CEMU_GRAPHIC_PACKS) != null,
                    ))
                    val service = home?.let { fs.readText("${it.config}/settings.xml", MAX_TEXT) }?.let(CemuNetwork::service)
                    add(Extra(
                        ExtraKind.CEMU_PRETENDO, "Pretendo Network",
                        "Online play and Miiverse through Pretendo for the account Cemu plays as. Needs your Wii U's online files and a Pretendo Network ID in Cemu's Online Play settings",
                        on = service == CemuNetwork.PRETENDO,
                    ))
                }
                "rpcs3" -> add(Extra(
                    ExtraKind.RPCS3_PATCHES, "Latest Patches",
                    "rpcs3.net's patch file, as RPCS3's Patch Manager downloads it. Turn each patch on per game in Fuse or RPCS3",
                    undoable = undoPoint(emulator, ExtraKind.RPCS3_PATCHES) != null,
                ))
                "xenia" -> add(Extra(
                    ExtraKind.XENIA_PATCHES, "Game Patches",
                    "Xenia Canary's community patches: frame rates, resolutions and fixes. Patches you turned on stay on",
                    undoable = undoPoint(emulator, ExtraKind.XENIA_PATCHES) != null,
                ))
                "eden", "citron" -> add(Extra(
                    ExtraKind.SWITCH_FIRMWARE, "Install System Firmware",
                    "From a firmware zip or folder you dumped from your own Switch, into ${inst.name}'s system memory, as its own Install Firmware does",
                    needsFile = true, undoable = undoPoint(emulator, ExtraKind.SWITCH_FIRMWARE) != null,
                ))
            }
            if (emulator in FIRMWARE_HOMES) {
                val systems = firmwareSystems(emulator)
                if (systems.isNotEmpty()) add(Extra(
                    ExtraKind.PLACE_FIRMWARE, "Put Your BIOS Here",
                    "Copies the BIOS Fuse found for ${systems.joinToString { it.second }} into ${inst.name}'s own folder. Files already there stay",
                ))
            }
        }
    }

    /** The systems [emulator] reads firmware for from a folder, with firmware Fuse found: (id, short name). */
    private fun firmwareSystems(emulator: String): List<Pair<String, String>> {
        val found = bios().filterValues { it.found.isNotEmpty() }
        val mine = when (emulator) {
            "duckstation" -> setOf("psx")
            "pcsx2" -> setOf("ps2")
            else -> found.keys.map { it.value }.filter { id -> ctx.platform(PlatformId(id))?.bios?.installedInEmulator != true }.toSet()
        }
        return found.keys.map { it.value }.filter { it in mine }.sorted().map { it to (ctx.platform(PlatformId(it))?.shortName ?: it) }
    }

    override suspend fun run(emulator: String, kind: ExtraKind, file: String?, onProgress: (Float) -> Unit): Result<String> = lock.withLock {
        runCatching {
            val inst = installed(emulator) ?: error("It isn't installed here.")
            when (kind) {
                ExtraKind.CEMU_GRAPHIC_PACKS -> cemuPacks(inst, onProgress)
                ExtraKind.CEMU_PRETENDO -> pretendo(inst)
                ExtraKind.RPCS3_PATCHES -> rpcs3Patches(inst)
                ExtraKind.XENIA_PATCHES -> xeniaPatches(inst, onProgress)
                ExtraKind.SWITCH_FIRMWARE -> switchFirmware(inst, file ?: error("Choose the firmware first."), onProgress)
                ExtraKind.PLACE_FIRMWARE -> placeFirmware(inst, emulator)
            }
        }
    }

    private fun need() = files ?: error("Fuse can't reach emulators' folders on this device.")

    private suspend fun getText(url: String): String {
        val response = ctx.services.http.get(url) { header("User-Agent", "Fuse") }
        if (response.status.value !in 200..299) error("The server said no (HTTP ${response.status.value}). Try again later.")
        return response.bodyAsText()
    }

    private suspend fun cemuPacks(inst: InstalledEmulator, onProgress: (Float) -> Unit): String {
        val f = need()
        val home = f.cemu(inst) ?: error("Start Cemu once so it makes its folders, then try again.")
        val folder = "${home.data}/graphicPacks/${CemuGraphicPacks.FOLDER}"
        val release = CemuGraphicPacks.release(getText(CemuGraphicPacks.RELEASE_URL)) ?: error("GitHub's answer about the graphic packs wasn't readable.")
        if (CemuGraphicPacks.upToDate(fs.readText("$folder/${CemuGraphicPacks.VERSION_FILE}", 4096), release)) return "Cemu's community graphic packs are up to date (${release.name})."
        val zip = f.fetch(release.url, "graphicPacks.zip", release.size, null, CemuGraphicPacks.MAX_ZIP, { onProgress(it * 0.8f) })
            ?: error("The graphic packs didn't download. Check this computer is online, then try again.")
        try {
            val listing = ctx.services.archives?.list(zip) ?: error("The graphic packs' zip couldn't be read.")
            val entries = CemuGraphicPacks.files(listing)
            if (entries.isEmpty()) error("The graphic packs' zip had nothing in it.")
            val point = restorePoint("cemu-packs")
            val ok = f.replaceFolder(folder, zip, entries.map { PlannedFile(it, "$folder/$it") }, mapOf("$folder/${CemuGraphicPacks.VERSION_FILE}" to release.name), point)
            if (!ok) error("Fuse couldn't put the graphic packs in Cemu's folder. Nothing was changed.")
            remember(inst, ExtraKind.CEMU_GRAPHIC_PACKS, point)
            onProgress(1f)
            return "Cemu has the community graphic packs (${release.name}). Turn them on in its Graphic Packs window."
        } finally {
            f.clearFetched()
        }
    }

    private suspend fun pretendo(inst: InstalledEmulator): String {
        val f = need()
        val home = f.cemu(inst) ?: error("Start Cemu once so it makes its settings, then try again.")
        val path = "${home.config}/settings.xml"
        val xml = fs.readText(path, MAX_TEXT) ?: error("Cemu's settings weren't readable.")
        val on = CemuNetwork.service(xml) == CemuNetwork.PRETENDO
        val next = CemuNetwork.withService(xml, if (on) CemuNetwork.OFFLINE else CemuNetwork.PRETENDO)
        if (!f.write(path, next)) error("Fuse couldn't change Cemu's settings. Close Cemu, then try again.")
        return if (on) "Cemu's account plays offline again." else "Cemu's account goes online through the Pretendo Network. Close Cemu first if it's open: it keeps its own copy of its settings while it runs."
    }

    private suspend fun rpcs3Patches(inst: InstalledEmulator): String {
        val f = need()
        val config = f.rpcs3Config(inst) ?: error("Start RPCS3 once so it makes its folders, then try again.")
        val path = "$config/patches/${Rpcs3PatchFile.FILE}"
        val current = fs.readText(path, MAX_PATCHES)
        return when (val answer = Rpcs3PatchFile.read(getText(Rpcs3PatchFile.url(current)))) {
            Rpcs3PatchFile.Answer.UpToDate -> "RPCS3's patches are up to date."
            is Rpcs3PatchFile.Answer.Failed -> error(answer.reason)
            is Rpcs3PatchFile.Answer.Newer -> {
                val point = restorePoint("rpcs3-patches")
                // As RPCS3 does: the file it replaces kept beside it as patch.yml.old.
                val texts = buildMap {
                    put(path, answer.text)
                    current?.let { put("$config/patches/${Rpcs3PatchFile.OLD}", it) }
                }
                if (!f.install(null, emptyList(), texts, point)) error("Fuse couldn't write RPCS3's patch file. Nothing was changed.")
                remember(inst, ExtraKind.RPCS3_PATCHES, point)
                "RPCS3 has the latest patches from rpcs3.net."
            }
        }
    }

    private suspend fun xeniaPatches(inst: InstalledEmulator, onProgress: (Float) -> Unit): String {
        val f = need()
        val archives = ctx.services.archives ?: error("Fuse can't unpack 7z archives on this device.")
        val root = f.xeniaRoot(inst) ?: error("Start Xenia once so it makes its folders, then try again.")
        val folder = "$root/${XeniaPatches.FOLDER}"
        val archive = f.fetch(XeniaPatches.URL, "game-patches.7z", null, null, XeniaPatches.MAX_ARCHIVE, { onProgress(it * 0.6f) })
            ?: error("The patches didn't download. Check this computer is online, then try again.")
        val unpacked = FsPath.join(ctx.services.cacheDir, "xenia-patches-${ctx.now()}")
        try {
            archives.unpack(archive, unpacked).getOrElse { error(it.message ?: "The patches couldn't be unpacked.") }
            onProgress(0.75f)
            val listing = walk(unpacked)
            val patches = XeniaPatches.files(listing)
            if (patches.isEmpty()) error("The patches' archive had no patches in it.")
            // The patches turned on before, by file and name, are on again in the new files.
            val before = fs.list(folder).filter { !it.isDirectory && it.name.endsWith(".patch.toml") }
                .associate { it.name to XeniaPatches.enabled(fs.readText(it.path, MAX_TEXT).orEmpty()) }
            val texts = patches.associate { (entry, name) ->
                val text = fs.readText("$unpacked/$entry", MAX_TEXT) ?: error("$name couldn't be read.")
                "$folder/$name" to XeniaPatches.carryOver(text, before[name].orEmpty())
            }
            val point = restorePoint("xenia-patches")
            if (!f.replaceFolder(folder, null, emptyList(), texts, point)) error("Fuse couldn't put the patches in Xenia's folder. Nothing was changed.")
            remember(inst, ExtraKind.XENIA_PATCHES, point)
            onProgress(1f)
            val kept = before.values.sumOf { it.size }
            return "Xenia has ${patches.size} game patches" + if (kept > 0) ", with the $kept you turned on still on." else ". Turn one on in its file in Xenia's patches folder."
        } finally {
            fs.delete(unpacked)
            f.clearFetched()
        }
    }

    private suspend fun switchFirmware(inst: InstalledEmulator, source: String, onProgress: (Float) -> Unit): String {
        val f = need()
        val registered = f.firmwareFolder(inst, "switch") ?: error("Start ${inst.name} once so it makes its folders, then try again.")
        val kind = source.substringAfterLast('.', "").lowercase()
        val isFolder = fs.stat(source)?.isDirectory == true
        var staged: String? = null
        try {
            val (from, entries) = when {
                isFolder -> source to fs.list(source).filter { !it.isDirectory }.map { it.name }
                kind == "zip" -> source to (ctx.services.archives?.list(source)?.map { it.first } ?: error("The firmware zip couldn't be read."))
                kind == "7z" || kind == "rar" -> {
                    val archives = ctx.services.archives ?: error("Fuse can't unpack that here.")
                    val out = FsPath.join(ctx.services.cacheDir, "switch-firmware-${ctx.now()}")
                    archives.unpack(source, out) { onProgress(it * 0.5f) }.getOrElse { error(it.message ?: "The firmware couldn't be unpacked.") }
                    staged = out
                    out to walk(out)
                }
                else -> error("Choose the firmware as a zip, a 7z or a folder of .nca files.")
            }
            val ncas = SwitchFirmware.files(entries)
            if (!SwitchFirmware.isFirmware(ncas)) error("That isn't a Switch firmware: it has ${ncas.size} system files, and a firmware has well over a hundred.")
            val point = restorePoint("switch-firmware")
            val planned = ncas.map { PlannedFile(it, "$registered/${it.substringAfterLast('/')}") }
            if (!f.replaceFolder(registered, from, planned, emptyMap(), point)) error("Fuse couldn't put the firmware in ${inst.name}'s system memory. Nothing was changed.")
            remember(inst, ExtraKind.SWITCH_FIRMWARE, point)
            refreshBios()
            onProgress(1f)
            return "${inst.name} has the firmware: ${ncas.size} system files. It also needs your keys (prod.keys) in its keys folder."
        } finally {
            staged?.let { fs.delete(it) }
        }
    }

    private suspend fun placeFirmware(inst: InstalledEmulator, emulator: String): String {
        val f = need()
        var copied = 0
        val skipped = ArrayList<String>()
        for ((platform, short) in firmwareSystems(emulator)) {
            val status = bios()[PlatformId(platform)] ?: continue
            val folder = f.firmwareFolder(inst, platform) ?: continue
            val present = f.names(folder).orEmpty()
            for ((from, to) in BiosPlacement.copies(status.found, folder, present)) {
                val point = restorePoint("bios-$platform")
                if (f.install(from, listOf(PlannedFile("", to)), emptyMap(), point)) copied++ else skipped += "$short ${FsPath.name(from)}"
            }
        }
        refreshBios()
        return when {
            copied == 0 && skipped.isEmpty() -> "${inst.name} already has every BIOS Fuse found."
            skipped.isEmpty() -> "Copied $copied ${if (copied == 1) "BIOS file" else "BIOS files"} into ${inst.name}'s folder."
            else -> "Copied $copied; Fuse couldn't copy ${skipped.joinToString()}."
        }
    }

    override suspend fun undo(emulator: String, kind: ExtraKind): Result<String> = lock.withLock {
        runCatching {
            val f = need()
            val inst = installed(emulator) ?: error("It isn't installed here.")
            val point = undoPoint(emulator, kind) ?: error("There's nothing of Fuse's to put back.")
            if (!f.undo(point)) error("Fuse couldn't put everything back as it was. What it couldn't is kept aside, so try again.")
            ctx.data.owned.set(OWNED, key(emulator, kind), emptySet())
            if (kind == ExtraKind.SWITCH_FIRMWARE) refreshBios()
            "${inst.name} is back as it was before."
        }
    }

    private var points = 0

    /** A restoration point's name, never one used before in this run. */
    private fun restorePoint(what: String): String = "extra-$what-${ctx.now()}-${++points}"

    /** Every file below [root], as paths below it, up to a few folders deep. */
    private suspend fun walk(root: String): List<String> {
        val out = ArrayList<String>()
        suspend fun go(dir: String, prefix: String, depth: Int) {
            if (depth > WALK_DEPTH) return
            for (e in fs.list(dir)) {
                if (e.isSymlink) continue
                if (e.isDirectory) go(e.path, "$prefix${e.name}/", depth + 1) else out += "$prefix${e.name}"
            }
        }
        go(root, "", 0)
        return out
    }

    private suspend fun remember(inst: InstalledEmulator, kind: ExtraKind, point: String) =
        ctx.data.owned.set(OWNED, key(family(inst), kind), setOf(point))

    private suspend fun undoPoint(emulator: String, kind: ExtraKind): String? = ctx.data.owned.get(OWNED, key(emulator, kind)).firstOrNull()

    private fun key(emulator: String, kind: ExtraKind) = "$emulator:${kind.name}"

    private companion object {
        const val OWNED = "extras"
        const val MAX_TEXT = 1024 * 1024
        const val MAX_PATCHES = 64 * 1024 * 1024
        const val WALK_DEPTH = 4

        /** The emulators that read firmware from a folder of their own (see [BiosPlacement]). */
        val FIRMWARE_HOMES = setOf("duckstation", "pcsx2", "retroarch")
    }
}
