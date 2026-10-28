package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.patches.DolphinCheats
import io.github.matiyaaa.fuse.launch.patches.DuckStationPatchRules
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.launch.patches.EnhancementRules
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.ModRules
import io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules
import io.github.matiyaaa.fuse.launch.patches.PlannedFile
import io.github.matiyaaa.fuse.launch.patches.PpssppHome
import io.github.matiyaaa.fuse.launch.patches.RetroArchCheats
import io.github.matiyaaa.fuse.launch.settings.SettingsEdit
import io.github.matiyaaa.fuse.library.disc.DiscIdentity
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementPreview
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementRemoval
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementView
import io.github.matiyaaa.fuse.ui.shell.store.Enhancements
import io.github.matiyaaa.fuse.ui.shell.store.SourceForm
import io.github.matiyaaa.fuse.ui.shell.store.SourceListing
import io.github.matiyaaa.fuse.sync.SaveAdapters
import io.github.matiyaaa.fuse.sync.SaveQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A person's own cheats, patches and texture packs for a game, put where its emulator reads them
 * (see [EnhancementRules] for each emulator's rules). Nothing changes until the preview of exactly
 * which files go in, which they replace and which settings they turn on has been accepted; then a
 * restoration point is made first (see [EmulatorFiles.install]), and taking one out again puts back
 * what was there before it, settings included, unless something was changed in the emulator since.
 */
internal class NativeEnhancements(
    private val ctx: StoreContext,
    private val detectNow: suspend () -> Unit,
    private val discIdentity: suspend (GameId) -> DiscIdentity?,
    private val pspGameId: suspend (Game) -> String?,
    private val retroArchRun: suspend (Game) -> NativePatches.RetroArchRun? = { null },
    private val now: () -> Long = { ctx.now() },
    /** The game as the emulator it starts in here would look for it (that emulator, its file, its id); null when none would start it. */
    private val launchQuery: suspend (Game) -> SaveQuery? = { null },
) {
    private val data = ctx.data
    private val fs get() = ctx.services.fs

    /** What was added for one game, as recorded: enough to show it and to take it out again. */
    @Serializable
    private data class Record(
        val id: String,
        val kind: EnhancementKind,
        val emulator: String,
        val name: String,
        val addedAt: Long,
        val files: Int,
        val target: String,
        /** The file a person's codes were added to the end of, and those codes, to take out again. */
        val appendedTo: String? = null,
        val appended: String? = null,
        /** The file [appendedTo] wasn't there before. */
        val appendedNew: Boolean = false,
        /** The emulator's settings file for the game that Fuse changed for it, and what it changed ([SettingsEdit] records). */
        val settingsFile: String? = null,
        val settings: Set<String> = emptySet(),
        /** Where Fuse fetched it from ("GameBanana"), its page there and its version. */
        val origin: String? = null,
        val originUrl: String? = null,
        val version: String? = null,
    )

    /** An accepted plan waits here, by its token, until it is added or left. */
    private data class Plan(
        val game: GameId,
        val record: Record,
        val source: String?,
        val files: List<PlannedFile>,
        val texts: Map<String, String>,
        /** Settings to change in [Record.settingsFile]: section, key, value. */
        val settings: List<Triple<String, String, String>>,
        /** Files whose on-and-off records Fuse keeps (by its patches kind) and which this replaces. */
        val forget: List<Pair<String, String>>,
    )

    private val plans = HashMap<String, Plan>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(id: GameId): Enhancements {
        val records = records(id)
        val game = data.games.get(id)
        val supported = game?.let { kinds(it) }.orEmpty()
        return Enhancements(
            added = records.sortedByDescending { it.addedAt }.map { r ->
                EnhancementView(
                    id = r.id,
                    kind = r.kind,
                    name = r.name,
                    emulator = r.emulator,
                    files = r.files,
                    addedAt = r.addedAt,
                    where = shown(r.appendedTo ?: r.target),
                    settingsChanged = r.settings.isNotEmpty(),
                    origin = r.origin,
                    originUrl = r.originUrl,
                    version = r.version,
                )
            },
            kinds = supported,
        )
    }

    /** The kinds a person can add for [game] here, by the emulator it starts in. */
    private suspend fun kinds(game: Game): List<EnhancementKind> {
        if (ctx.services.emulatorFiles == null) return emptyList()
        if (retroArchRun(game) != null) return listOf(EnhancementKind.CHEATS)
        return when (game.platformId.value) {
            "ps2", "psx" -> listOf(EnhancementKind.CHEATS, EnhancementKind.PATCHES, EnhancementKind.TEXTURES)
            "psp" -> listOf(EnhancementKind.CHEATS, EnhancementKind.TEXTURES)
            "ngc", "wii" -> listOf(EnhancementKind.CHEATS, EnhancementKind.TEXTURES, EnhancementKind.MODS)
            in MOD_PLATFORMS -> listOf(EnhancementKind.MODS)
            else -> emptyList()
        }
    }

    private fun no(title: String, reason: String) = EnhancementPreview.Unavailable(title, reason)

    private suspend fun list(folder: String): List<io.github.matiyaaa.fuse.library.FsEntry> =
        try { fs.list(folder) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }

    private fun installed(name: String): InstalledEmulator? = ctx.installed.value.firstOrNull { it.id.value.substringAfter('.') == name }

    /** [path] with the home folder shown as `~`. */
    private fun shown(path: String): String {
        val home = ctx.services.emulators.homeDir?.replace('\\', '/')?.trimEnd('/')
        val p = path.replace('\\', '/')
        return if (!home.isNullOrBlank() && home.length > 1 && p.startsWith("$home/")) "~" + p.removePrefix(home) else p
    }

    /**
     * Works out what adding [source] (a file, folder or `.zip`) to game [id] as [kind] would do, and
     * holds it for [add]. Nothing changes here.
     */
    suspend fun plan(id: GameId, kind: EnhancementKind, source: String): EnhancementPreview = planOnly(id, kind, source)

    /**
     * [plan] for a file Fuse fetched: named [name] (the mod's own name) and remembered as from
     * [origin] ([url], [version]), so its page can be found again and a newer version noticed.
     */
    suspend fun plan(id: GameId, kind: EnhancementKind, source: String, name: String, origin: String, url: String, version: String?): EnhancementPreview {
        val preview = planOnly(id, kind, source)
        if (preview !is EnhancementPreview.Ready) return preview
        val plan = plans[preview.token] ?: return preview
        plans[preview.token] = plan.copy(record = plan.record.copy(name = name, origin = origin, originUrl = url, version = version))
        return preview.copy(name = name)
    }

    private suspend fun planOnly(id: GameId, kind: EnhancementKind, source: String): EnhancementPreview {
        val game = data.games.get(id) ?: return no("Game not found", "This game is no longer in the library.")
        val files = ctx.services.emulatorFiles ?: return no("Not on this device", "Your own cheats, patches and textures can be added from Fuse on a computer.")
        if (ctx.installed.value.isEmpty()) detectNow()
        val listing = files.inspect(source) ?: return no("Fuse can't read that", "It may have moved, or Fuse may not be allowed to open it. Choose a file, a folder or a .zip archive.")
        if (listing.truncated) return no("That's too much", "It holds more than ${EnhancementRules.MAX_ENTRIES} files. Choose the pack's own folder or archive.")
        if (listing.entries.isEmpty()) return no("Nothing in it", "${listing.name} holds no files.")
        retroArchRun(game)?.let { run ->
            if (kind != EnhancementKind.CHEATS) return no("Not for RetroArch", "Fuse adds cheat files for games that start in RetroArch. Its cores keep patches and textures their own way; add them in RetroArch.")
            return retroArchCheats(id, game, files, run, source, listing)
        }
        return when (game.platformId.value) {
            "ps2" -> pcsx2(id, kind, files, source, listing)
            "psx" -> duckstation(id, kind, files, source, listing)
            "psp" -> ppsspp(id, game, kind, files, source, listing)
            "ngc", "wii", in MOD_PLATFORMS -> mods(id, game, kind, files, source, listing)
            else -> no("Not for this game", "Fuse adds cheats, patches, textures and mods for games that start in PCSX2, DuckStation, PPSSPP, RetroArch, Dolphin, Cemu, RPCS3, shadPS4 and the Switch emulators.")
        }
    }

    // Choosing what in the source is meant -------------------------------------------------------

    /** The text files in [listing] with one of [extensions] (any, for a single file), each with its text. */
    private suspend fun texts(files: EmulatorFiles, source: String, listing: SourceListing, extensions: Set<String>): List<Pair<String, String>> {
        val entries = if (listing.form == SourceForm.FILE) listing.entries else listing.entries.filter { EnhancementRules.ext(it) in extensions && !EnhancementRules.unsafe(it) }
        return entries.take(MAX_CODE_FILES).mapNotNull { e -> files.sourceText(source, e)?.let { e to it } }
    }

    private fun serialKey(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** A serial or game id named in [path] (a folder or file name) that isn't [serial]: what it was made for instead. */
    private fun madeFor(path: String, serial: String): String? {
        val want = serialKey(serial)
        for (part in path.replace('\\', '/').split('/')) {
            val m = SERIAL.find(part.uppercase()) ?: continue
            val found = m.value
            if (serialKey(found) != want) return found
        }
        return null
    }

    private fun token(): String = buildString {
        append(now().toString(36))
        append('-')
        repeat(6) { append(ALPHABET[kotlin.random.Random.nextInt(ALPHABET.length)]) }
    }

    private fun preview(plan: Plan, replaces: List<String>, rejected: List<String>, notes: List<String>, settingsSaid: List<String>, codes: Int, bytes: Long): EnhancementPreview.Ready {
        val t = token()
        plans[t] = plan
        val r = plan.record
        return EnhancementPreview.Ready(
            token = t,
            kind = r.kind,
            emulator = r.emulator,
            name = r.name,
            target = shown(r.target),
            adds = (plan.files.map { it.to } + plan.texts.keys).filterNot { it in replaces || it == r.appendedTo }.map(::shown),
            replaces = replaces.map(::shown),
            appendsTo = r.appendedTo?.let(::shown),
            settings = settingsSaid,
            rejected = rejected,
            notes = notes,
            codes = codes,
            bytes = bytes,
        )
    }

    // PCSX2 ---------------------------------------------------------------------------------------

    private suspend fun pcsx2(id: GameId, kind: EnhancementKind, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        val pcsx2 = installed("pcsx2") ?: return no("PCSX2 isn't installed", "Install PCSX2 and start it once, then add this.")
        val home = files.pcsx2(pcsx2) ?: return no("PCSX2 isn't set up yet", "Start PCSX2 once and finish its first-run setup, then come back.")
        val disc = discIdentity(id) ?: return no("Fuse can't read this disc", "PCSX2 files these under the game's serial and CRC, which Fuse reads from ISO, BIN, CHD and CSO images. This one doesn't say them.")
        val crc = disc.crc ?: return no("Not a PS2 disc", "This image doesn't start a PS2 program.")
        val crcText = disc.crcText!!
        val settingsFile = "${home.gameSettings}/${Pcsx2PatchRules.gameSettingsName(disc.serial, crc)}"
        if (kind == EnhancementKind.TEXTURES) {
            if (home.textures.isEmpty()) return no("PCSX2's textures folder isn't known", "Start PCSX2 once so it sets up its folders.")
            val pack = EnhancementRules.texturePack("pcsx2", listing.entries)
                ?: return no("Not a PCSX2 texture pack", "PCSX2 reads .png and .dds pictures (or zips of them) from a game's replacements folder. Nothing in ${listing.name} is one.")
            pack.files.firstNotNullOfOrNull { madeFor(it.first, disc.serial) }?.let {
                return no("Made for another game", "This pack is in a folder for $it, and this game is ${disc.serial}. PCSX2 matches textures to the game they were made for.")
            }
            val target = "${home.textures}/${disc.serial}/replacements"
            val planned = pack.files.map { (from, rel) -> PlannedFile(from, "$target/$rel") }
            val game = IniText(fs.readText(settingsFile, MAX_TEXT) ?: "")
            val global = fs.readText(home.settingsFile, MAX_TEXT)?.let { IniText(it) }
            val on = (game.bool(GS, LOAD_TEXTURES) ?: global?.bool(GS, LOAD_TEXTURES)) == true
            val settings = if (on) emptyList() else listOf(Triple(GS, LOAD_TEXTURES, "true"))
            return texturePlan(id, "PCSX2", "pcsx2", listing, source, target, planned, pack.rejected, settingsFile, settings,
                if (on) emptyList() else listOf("Turns on Load Textures for this game in PCSX2"),
                listOf("PCSX2 shows these with its hardware renderers (Vulkan, OpenGL, Direct3D, Metal), not the software one."))
        }
        val folder = if (kind == EnhancementKind.CHEATS) home.cheats else home.patches
        if (folder.isEmpty()) return no("PCSX2's folder isn't known", "Start PCSX2 once so it sets up its folders.")
        val found = texts(files, source, listing, setOf("pnach", "txt"))
        val rejected = ArrayList<String>()
        val planned = ArrayList<PlannedFile>()
        val texts = LinkedHashMap<String, String>()
        var codes = 0
        for ((entry, text) in found) {
            val name = entry.substringAfterLast('/')
            // A name that says another CRC was made for another version of the game, or is its
            // makers' way of keeping a variant off (PCSX2 wouldn't read it for this game).
            val other = EnhancementRules.pnachCrc(name)
            if (other != null && other != crcText) {
                rejected += "$name (named for CRC $other, which isn't this game's $crcText, so PCSX2 wouldn't read it)"
                continue
            }
            val n = EnhancementRules.codesIn("pcsx2", text)
            if (n == 0) {
                rejected += "$name (no PCSX2 codes in it)"
                continue
            }
            codes += n
            var dest = "$folder/${EnhancementRules.pnachName(disc.serial, crc, name)}"
            var k = 2
            while (dest in texts) dest = "$folder/${EnhancementRules.pnachName(disc.serial, crc, name.substringBeforeLast('.') + " $k")}".also { k++ }
            texts[dest] = text
        }
        if (texts.isEmpty()) {
            return no("No PCSX2 codes in it", rejected.firstOrNull()?.let { "Fuse looked for .pnach files and found nothing it can add: $it." } ?: "Fuse looked for .pnach files in ${listing.name} and found none.")
        }
        val replaces = texts.keys.filter { fs.stat(it) != null }
        val record = Record(token(), kind, "PCSX2", listing.name, now(), texts.size, folder)
        val word = if (kind == EnhancementKind.CHEATS) "cheats" else "patches"
        val plan = Plan(id, record, null, planned, texts, emptyList(), emptyList())
        // Files that change the same memory as each other, or as one PCSX2 already has for the game.
        val mine = texts.map { (path, text) -> path.substringAfterLast('/') to EnhancementRules.pnachAddresses(text) }
        val theirs = list(folder).filter { !it.isDirectory && Pcsx2PatchRules.matchesPnach(it.name, disc.serial, crc) && "$folder/${it.name}" !in texts }
            .mapNotNull { f -> fs.readText(f.path, MAX_TEXT)?.let { f.name to EnhancementRules.pnachAddresses(it) } }
        val overlaps = buildList {
            for ((i, a) in mine.withIndex()) {
                for (b in mine.drop(i + 1) + theirs) if ((a.second intersect b.second).isNotEmpty()) add("${a.first} and ${b.first}")
            }
        }
        val notes = listOfNotNull(
            "They show in this game's ${word.replaceFirstChar { it.uppercase() }} list, off until you turn them on.",
            overlaps.takeIf { it.isNotEmpty() }?.let { "${it.joinToString("; ")} change the same memory. PCSX2 applies every one turned on, so turn on only one of each." },
        )
        return preview(plan, replaces, rejected, notes, emptyList(), codes, texts.values.sumOf { it.length.toLong() })
    }

    // DuckStation ---------------------------------------------------------------------------------

    private suspend fun duckstation(id: GameId, kind: EnhancementKind, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        val ds = installed("duckstation") ?: return no("DuckStation isn't installed", "Install DuckStation and start it once, then add this.")
        val home = files.duckstation(ds) ?: return no("DuckStation isn't set up yet", "Start DuckStation once and finish its first-run setup, then come back.")
        val disc = discIdentity(id) ?: return no("Fuse can't read this disc", "DuckStation files these under the game's serial, which Fuse reads from ISO, BIN, CUE and CHD images. PBP files aren't read.")
        if (disc.ps2) return no("Not a PlayStation disc", "This image starts a PS2 program.")
        val serial = disc.serial
        val settingsFile = "${home.gameSettings}/${DuckStationPatchRules.gameSettingsName(serial)}"
        if (kind == EnhancementKind.TEXTURES) {
            if (home.textures.isEmpty()) return no("DuckStation's textures folder isn't known", "Start DuckStation once so it sets up its folders.")
            val pack = EnhancementRules.texturePack("duckstation", listing.entries)
                ?: return no("Not a DuckStation texture pack", "DuckStation reads .png, .jpg and .webp pictures from a game's replacements folder. Nothing in ${listing.name} is one.")
            pack.files.firstNotNullOfOrNull { madeFor(it.first, serial) }?.let {
                return no("Made for another game", "This pack is in a folder for $it, and this game is $serial. DuckStation matches textures to the game they were made for.")
            }
            val target = "${home.textures}/$serial/replacements"
            val planned = pack.files.map { (from, rel) -> PlannedFile(from, "$target/$rel") }
            val (vram, textures) = EnhancementRules.duckStationNeeds(pack.files.map { it.second })
            val game = IniText(fs.readText(settingsFile, MAX_TEXT) ?: "")
            val global = fs.readText(home.settingsFile, MAX_TEXT)?.let { IniText(it) }
            fun on(section: String, key: String) = (game.bool(section, key) ?: global?.bool(section, key)) == true
            val settings = ArrayList<Triple<String, String, String>>()
            val said = ArrayList<String>()
            if (vram && !on(TR, "EnableVRAMWriteReplacements")) {
                settings += Triple(TR, "EnableVRAMWriteReplacements", "true")
                said += "Turns on Enable Background Replacement (VRAM writes) for this game in DuckStation"
            }
            if (textures && !on(TR, "EnableTextureReplacements")) {
                settings += Triple(TR, "EnableTextureReplacements", "true")
                said += "Turns on Enable Texture Replacement for this game in DuckStation"
            }
            if (textures && !on("GPU", "EnableTextureCache")) {
                settings += Triple("GPU", "EnableTextureCache", "true")
                said += "Turns on the GPU texture cache for this game, which texture replacement needs"
            }
            val notes = buildList {
                add("DuckStation shows these with its hardware renderers, not the software one.")
                if (!vram && !textures) add("None of the pictures is named the way DuckStation names replacements (vram-write-, texupload-, texpage-), so it may show none of them.")
            }
            return texturePlan(id, "DuckStation", "duckstation", listing, source, target, planned, pack.rejected, settingsFile, settings, said, notes)
        }
        val folder = if (kind == EnhancementKind.CHEATS) home.cheats else home.patches
        val found = texts(files, source, listing, setOf("cht", "txt"))
        val rejected = ArrayList<String>()
        val blocks = ArrayList<String>()
        var codes = 0
        for ((entry, text) in found) {
            val name = entry.substringAfterLast('/')
            val other = madeFor(name.substringBeforeLast('.'), serial)
            if (other != null) {
                rejected += "$name (made for $other; this game is $serial)"
                continue
            }
            val n = EnhancementRules.codesIn("duckstation", text)
            if (n == 0) {
                rejected += "$name (no DuckStation codes in it)"
                continue
            }
            codes += n
            blocks += text.replace("\r\n", "\n").trim()
        }
        if (blocks.isEmpty()) return no("No DuckStation codes in it", rejected.firstOrNull()?.let { "Fuse found nothing it can add: $it." } ?: "Fuse looked for .cht files in ${listing.name} and found none.")
        return appendPlan(id, kind, "DuckStation", listing, "$folder/$serial.cht", blocks.joinToString("\n\n"), rejected, codes, ppsspp = false)
    }

    // PPSSPP --------------------------------------------------------------------------------------

    private suspend fun ppsspp(id: GameId, game: Game, kind: EnhancementKind, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        if (kind == EnhancementKind.PATCHES) return no("PPSSPP has no patch files", "PPSSPP's own changes to games are cheats. Add them as cheats.")
        val pp = installed("ppsspp") ?: return no("PPSSPP isn't installed", "Install PPSSPP and start it once, then add this.")
        val home = files.ppsspp(pp) ?: return no("PPSSPP isn't set up yet", "Start PPSSPP once so it makes its memory stick, then come back.")
        val gameId = pspGameId(game) ?: return no("Fuse can't tell which game this is", "PPSSPP files these under the game's id (like ULUS10041), which Fuse reads from ISO, CSO and CHD images and EBOOT.PBP files. This one doesn't say it.")
        if (kind == EnhancementKind.TEXTURES) {
            val pack = EnhancementRules.texturePack("ppsspp", listing.entries)
                ?: return no("Not a PPSSPP texture pack", "A PPSSPP texture pack has a textures.ini (or textures.zip) in it. ${listing.name} has neither.")
            // textures.ini can list the games the pack is for ([games]); else the folder it is in says.
            val ini = pack.files.firstOrNull { it.second.equals("textures.ini", ignoreCase = true) }?.let { files.sourceText(source, it.first) }
            val listed = ini?.let { IniText(it).keys("games") }.orEmpty().map(::serialKey)
            if (listed.isNotEmpty() && serialKey(gameId) !in listed) {
                return no("Made for another game", "This pack's textures.ini lists the games it is for, and this game ($gameId) isn't one of them.")
            }
            if (listed.isEmpty()) {
                pack.files.firstNotNullOfOrNull { (from, rel) -> if (rel.contains('/')) null else madeFor(from.substringBeforeLast('/', ""), gameId) }?.let {
                    return no("Made for another game", "This pack is in a folder for $it, and this game is $gameId. PPSSPP reads a pack from the folder named for its game.")
                }
            }
            val target = "${home.memstick}/PSP/TEXTURES/$gameId"
            val planned = pack.files.map { (from, rel) -> PlannedFile(from, "$target/$rel") }
            val gameSettings = fs.readText(home.gameSettingsFile(gameId), MAX_TEXT)?.let { IniText(it) }
            val global = fs.readText(home.settingsFile, MAX_TEXT)?.let { IniText(it) }
            val replaceOn = (gameSettings?.bool("Graphics", "ReplaceTextures") ?: global?.bool("Graphics", "ReplaceTextures")) != false
            val settings = if (!replaceOn && gameSettings != null) listOf(Triple("Graphics", "ReplaceTextures", "True")) else emptyList()
            val notes = buildList {
                if (!replaceOn && gameSettings == null) add("PPSSPP's Replace textures is off. Turn it on in its Tools, Developer tools, for these to show.")
            }
            return texturePlan(id, "PPSSPP", "ppsspp", listing, source, target, planned, pack.rejected,
                home.gameSettingsFile(gameId).takeIf { settings.isNotEmpty() }, settings,
                if (settings.isEmpty()) emptyList() else listOf("Turns on Replace textures in this game's own PPSSPP settings"), notes)
        }
        val found = texts(files, source, listing, setOf("ini", "txt", "db"))
        val rejected = ArrayList<String>()
        val blocks = ArrayList<String>()
        var codes = 0
        for ((entry, text) in found) {
            val name = entry.substringAfterLast('/')
            // Strict here: a file whose games are all others is not for this one.
            val games = text.lineSequence().map { it.trim() }.filter { it.startsWith("_S ") }.map { it.substring(3).trim() }.toList()
            if (games.isNotEmpty() && games.none { serialKey(it) == serialKey(gameId) }) {
                rejected += "$name (made for ${games.first()}; this game is $gameId)"
                continue
            }
            val n = EnhancementRules.codesIn("ppsspp", text, gameId)
            if (n == 0) {
                rejected += "$name (no PPSSPP cheats in it)"
                continue
            }
            codes += n
            blocks += text.replace("\r\n", "\n").trim()
        }
        if (blocks.isEmpty()) return no("No PPSSPP cheats in it", rejected.firstOrNull()?.let { "Fuse found nothing it can add: $it." } ?: "Fuse looked for cheat files in ${listing.name} and found none.")
        return appendPlan(id, kind, "PPSSPP", listing, home.cheatFile(gameId), blocks.joinToString("\n\n"), rejected, codes, ppsspp = true)
    }

    // RetroArch -----------------------------------------------------------------------------------

    private suspend fun retroArchCheats(id: GameId, game: Game, files: EmulatorFiles, run: NativePatches.RetroArchRun, source: String, listing: SourceListing): EnhancementPreview {
        val home = files.retroArch(run.installed) ?: return no("RetroArch isn't set up yet", "Start RetroArch once so it writes its settings, then come back.")
        val core = io.github.matiyaaa.fuse.sync.retroArchLibraryName(run.core) ?: return no("Fuse doesn't know this core's name", "RetroArch files cheats under the core's own name, which Fuse doesn't know for ${run.core}.")
        val content = RetroArchCheats.contentName(run.content)
        val found = texts(files, source, listing, setOf("cht"))
        val good = found.filter { EnhancementRules.codesIn("retroarch", it.second) > 0 }
        val rejected = (found - good.toSet()).map { "${it.first.substringAfterLast('/')} (no RetroArch cheats in it)" }
        // One file is the game's cheats; with several, the one named for the game.
        val chosen = good.singleOrNull() ?: good.firstOrNull { RetroArchCheats.contentName(it.first).equals(content, ignoreCase = true) }
            ?: return if (good.isEmpty()) {
                no("No RetroArch cheats in it", "A RetroArch cheat file is a .cht with a cheats = line. Fuse found none in ${listing.name}.")
            } else {
                no("Which one?", "${listing.name} holds ${good.size} cheat files and none is named for $content. Choose the one for this game.")
            }
        val dest = RetroArchCheats.gameFile(home.cheats, core, content)
        val replaces = if (fs.stat(dest) != null) listOf(dest) else emptyList()
        val record = Record(token(), EnhancementKind.CHEATS, "RetroArch", listing.name, now(), 1, dest.substringBeforeLast('/'))
        val plan = Plan(id, record, null, emptyList(), mapOf(dest to chosen.second), emptyList(), listOf("retroarch.cheats" to dest))
        val n = EnhancementRules.codesIn("retroarch", chosen.second)
        val notes = buildList {
            add("It becomes this game's cheats with $core, and they show in the Cheats list, off until you turn them on.")
            if (replaces.isNotEmpty()) add("The game's cheat file there now is kept, and put back when you take this out.")
        }
        return preview(plan, replaces, rejected + (good - chosen).map { "${it.first.substringAfterLast('/')} (another game's)" }, notes, emptyList(), n, chosen.second.length.toLong())
    }

    // Mods: Dolphin, Cemu, Switch emulators, RPCS3, shadPS4 --------------------------------------

    /**
     * A mod (or, for Dolphin, a texture pack) for a game that starts in one of the emulators
     * [ModRules] knows, put where that emulator reads it. Which emulator is the one the game starts
     * in now ([launchQuery]).
     */
    private suspend fun mods(id: GameId, game: Game, kind: EnhancementKind, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        val query = launchQuery(game) ?: return no("No emulator for this game", "Fuse puts mods where the game's emulator reads them. Install one for ${platformName(game)} first.")
        val emulator = SaveAdapters.baseId(query.emulatorId)
        return when {
            emulator == "rpcs3" -> rpcs3Mod(id, game, kind, files, source, listing)
            emulator == "shadps4" -> shadPs4Mod(id, game, kind, files, source, listing)
            else -> {
                val home = files.modHome(query).getOrElse { return no("Fuse can't add this here", it.message ?: "Fuse doesn't put mods in place for this emulator.") }
                when (home.emulator) {
                    "dolphin" -> if (kind == EnhancementKind.CHEATS) dolphinCheats(id, home, files, source, listing) else dolphinMod(id, kind, home, source, listing)
                    "cemu" -> cemuMod(id, kind, home, files, source, listing)
                    "ryujinx" -> switchMod(id, kind, home, "Ryujinx", source, listing)
                    else -> switchMod(id, kind, home, emulatorTitle(home.emulator), source, listing)
                }
            }
        }
    }

    private fun platformName(game: Game) = ctx.platforms.byId(game.platformId)?.name ?: "this system"

    private fun emulatorTitle(id: String) = when (id) {
        "eden" -> "Eden"
        "citron" -> "Citron"
        "sudachi" -> "Sudachi"
        "yuzu" -> "yuzu"
        "suyu" -> "suyu"
        "torzu" -> "torzu"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    /** [name], or `name 2`, `name 3`... when a folder of that name is already in [parent] and isn't one of this game's mods. */
    private suspend fun freeFolder(parent: String, name: String): String {
        val taken = list(parent).map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var n = 2
        while ("$name $n".lowercase() in taken) n++
        return "$name $n"
    }

    private suspend fun modPlan(
        id: GameId,
        kind: EnhancementKind,
        emulator: String,
        listing: SourceListing,
        source: String,
        target: String,
        planned: List<PlannedFile>,
        rejected: List<String>,
        settingsFile: String? = null,
        settings: List<Triple<String, String, String>> = emptyList(),
        said: List<String> = emptyList(),
        notes: List<String>,
    ): EnhancementPreview {
        if (planned.isEmpty()) return no("Nothing to add", "Nothing in ${listing.name} goes where $emulator reads mods.")
        val record = Record(token(), kind, emulator, listing.name, now(), planned.size, target, settingsFile = settingsFile)
        val replaces = planned.map { it.to }.filter { fs.stat(it) != null }
        val plan = Plan(id, record, source, planned, emptyMap(), settings, emptyList())
        return preview(plan, replaces, rejected, conflicts(id, kind, emulator, target, planned) + notes, said, 0, listing.bytes)
    }

    /**
     * Mods already added for the game that change a file this one changes too: each mod sits in a
     * folder of its own, and the emulator layers them, so for each such file only one of them counts.
     * Said before anything is added, with the files (at most a few named).
     */
    private suspend fun conflicts(id: GameId, kind: EnhancementKind, emulator: String, target: String, planned: List<PlannedFile>): List<String> {
        if (kind != EnhancementKind.MODS) return emptyList()
        // Compared without case (the emulators' own file systems may not tell case apart), shown as this mod has them.
        val mine = planned.associate { it.to.removePrefix("$target/").lowercase() to it.to.removePrefix("$target/") }
        return records(id).filter { it.kind == EnhancementKind.MODS && it.emulator == emulator && it.target != target }.mapNotNull { other ->
            val theirs = filesUnder(other.target).map { it.lowercase() }.toSet()
            val both = mine.filterKeys { it in theirs }.values.sorted()
            if (both.isEmpty()) return@mapNotNull null
            val named = both.take(3).joinToString(", ") + if (both.size > 3) " and ${both.size - 3} more" else ""
            "${other.name} changes ${if (both.size == 1) "the same file" else "the same ${both.size} files"} ($named). $emulator uses only one of them, so turn one off if the game looks wrong."
        }
    }

    /** The files below [dir], as paths relative to it; at most [MAX_WALK] are looked at. */
    private suspend fun filesUnder(dir: String): List<String> {
        val out = ArrayList<String>()
        val queue = ArrayDeque(listOf(dir to ""))
        while (queue.isNotEmpty() && out.size < MAX_WALK) {
            val (path, rel) = queue.removeFirst()
            val entries = runCatching { fs.list(path) }.getOrDefault(emptyList())
            for (e in entries) {
                val r = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                if (e.isDirectory) queue.addLast(e.path to r) else out += r
            }
        }
        return out
    }

    private suspend fun switchMod(id: GameId, kind: EnhancementKind, home: io.github.matiyaaa.fuse.sync.ModHome, emulator: String, source: String, listing: SourceListing): EnhancementPreview {
        if (kind != EnhancementKind.MODS) return no("Add it as a mod", "$emulator reads a game's changes as mods.")
        val ryujinx = home.emulator == "ryujinx"
        val layout = ModRules.switchMod(listing.entries, listing.name, if (ryujinx) setOf("romfs", "exefs") else ModRules.SWITCH_PARTS)
            ?: return no("Not a Switch mod", "A Switch mod is a folder with ${if (ryujinx) "a romfs or exefs folder" else "an exefs, romfs or cheats folder"} in it. ${listing.name} has none.")
        madeForTitle(listing.entries, home.gameId)?.let {
            return no("Made for another game", "This mod is in a folder for title $it, and this game is ${home.gameId}. $emulator reads mods from the folder named for their game.")
        }
        val folder = freeFolder(home.folder, layout.folder ?: ModRules.folderName(listing.name))
        val target = "${home.folder}/$folder"
        val planned = layout.files.map { (from, rel) -> PlannedFile(from, "$target/$rel") }
        val notes = buildList {
            if (ryujinx) add("It shows in Ryujinx's Manage Mods for this game. Make sure it is ticked there.")
            else add("$emulator uses it the next time the game starts. It shows in the game's Add-ons, where it can be turned off.")
            if (layout.files.any { it.second.lowercase().startsWith("exefs/") }) add("It changes the game's code (exefs), so it is made for one version of the game. Check it matches the update you have.")
        }
        return modPlan(id, kind, emulator, listing, source, target, planned, layout.rejected, notes = notes)
    }

    private suspend fun cemuMod(id: GameId, kind: EnhancementKind, home: io.github.matiyaaa.fuse.sync.ModHome, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        if (kind != EnhancementKind.MODS) return no("Add it as a mod", "Cemu reads a game's changes as graphic packs, which Fuse adds as mods.")
        val layout = ModRules.cemuPack(listing.entries, listing.name)
            ?: return no("Not a Cemu graphic pack", "A Cemu graphic pack is a folder with a rules.txt in it. ${listing.name} has none.")
        // A pack names the games it is for in its rules.txt; one for none of this game's ids is another game's.
        val rulesEntry = layout.files.firstOrNull { it.second.equals("rules.txt", ignoreCase = true) }?.first
        val ids = rulesEntry?.let { files.sourceText(source, it) }?.let(ModRules::cemuTitleIds).orEmpty()
        if (ids.isNotEmpty() && home.gameId.isNotEmpty() && home.gameId.lowercase() !in ids) {
            return no("Made for another game", "This pack's rules.txt lists the games it is for, and this game (${home.gameId}) isn't one of them.")
        }
        val folder = freeFolder(home.folder, layout.folder ?: ModRules.folderName(listing.name))
        val target = "${home.folder}/$folder"
        val planned = layout.files.map { (from, rel) -> PlannedFile(from, "$target/$rel") }
        val notes = listOf("Turn it on in Cemu, Options, Graphic packs (it is listed under this game), then start the game.")
        return modPlan(id, kind, "Cemu", listing, source, target, planned, layout.rejected, notes = notes)
    }

    private suspend fun dolphinMod(id: GameId, kind: EnhancementKind, home: io.github.matiyaaa.fuse.sync.ModHome, source: String, listing: SourceListing): EnhancementPreview {
        val ini = IniText(home.settingsFile?.let { fs.readText(it, MAX_TEXT) } ?: "")
        if (kind == EnhancementKind.TEXTURES) {
            val (layout, madeFor) = ModRules.dolphinTextures(listing.entries)
                ?: return no("Not a Dolphin texture pack", "Dolphin reads .png and .dds pictures named tex1_... Nothing in ${listing.name} is one.")
            if (madeFor != null && madeFor != home.gameId && madeFor != home.gameId.take(3)) {
                return no("Made for another game", "This pack is in a folder for $madeFor, and this game is ${home.gameId}. Dolphin matches textures to the game they were made for.")
            }
            val target = "${home.folder}/${home.gameId}"
            val planned = layout.files.map { (from, rel) -> PlannedFile(from, "$target/$rel") }
            val on = ini.bool(DOLPHIN_VIDEO, "HiresTextures") == true
            return modPlan(
                id, kind, "Dolphin", listing, source, target, planned, layout.rejected,
                settingsFile = home.settingsFile.takeIf { !on },
                settings = if (on) emptyList() else listOf(Triple(DOLPHIN_VIDEO, "HiresTextures", "True")),
                said = if (on) emptyList() else listOf("Turns on Load Custom Textures for this game in Dolphin"),
                notes = listOf("Dolphin loads these as the game starts; restart the game if it is running."),
            )
        }
        if (kind != EnhancementKind.MODS) return no("Not for Dolphin", "Add Dolphin's cheats as cheats, texture packs as textures, and graphics mods as mods.")
        val layout = ModRules.dolphinGraphicsMod(listing.entries, listing.name)
            ?: return no("Not a Dolphin graphics mod", "A Dolphin graphics mod is a folder with a metadata.json in it. ${listing.name} has none. For a texture pack, add it as textures.")
        val parent = "${home.graphicsMods}/${home.gameId}"
        val folder = freeFolder(parent, layout.folder ?: ModRules.folderName(listing.name))
        val target = "$parent/$folder"
        val planned = layout.files.map { (from, rel) -> PlannedFile(from, "$target/$rel") }
        val on = ini.bool(DOLPHIN_VIDEO, "EnableMods") == true
        return modPlan(
            id, kind, "Dolphin", listing, source, target, planned, layout.rejected,
            settingsFile = home.settingsFile.takeIf { !on },
            settings = if (on) emptyList() else listOf(Triple(DOLPHIN_VIDEO, "EnableMods", "True")),
            said = if (on) emptyList() else listOf("Turns on graphics mods for this game in Dolphin"),
            notes = listOf("Dolphin keeps each graphics mod off until it is ticked: right-click the game, Properties, Graphics Mods."),
        )
    }

    /**
     * Dolphin's Gecko and Action Replay codes ([DolphinCheats]) from a person's file, added to the
     * end of the game's own settings file, off, with Enable Cheats for this game; codes the game
     * already has are left out, and a file named for another game is turned away.
     */
    private suspend fun dolphinCheats(id: GameId, home: io.github.matiyaaa.fuse.sync.ModHome, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        val dest = home.settingsFile ?: return no("Dolphin's game settings aren't known yet", "Start Dolphin once so it sets up its folders, then add these.")
        val texts = texts(files, source, listing, setOf("ini", "txt"))
        if (texts.isEmpty()) return no("No codes in it", "Dolphin's cheats come as text: a game's .ini with [Gecko] or [ActionReplay] codes, or a .txt of Gecko codes each named with $.")
        // A Dolphin game file is named for its game id (GALE01.ini): one for another game is that game's.
        texts.firstNotNullOfOrNull { (name, _) ->
            name.substringAfterLast('/').substringBeforeLast('.').uppercase().takeIf { Regex("^[A-Z0-9]{6}$").matches(it) && it != home.gameId.uppercase() && it.take(3) != home.gameId.uppercase().take(3) }
        }?.let { return no("Made for another game", "This file is named for $it, and this game is ${home.gameId}. Dolphin files a game's codes under its own id.") }
        val existing = fs.readText(dest, MAX_TEXT).orEmpty()
        val read = texts.map { DolphinCheats.parse(it.second) }
        val all = read.flatMap { it.codes }.distinctBy { it.kind to it.name }
        val known = DolphinCheats.Kind.entries.associateWith { DolphinCheats.namesIn(existing, it) }
        val (fresh, already) = all.partition { it.name !in known.getValue(it.kind) }
        if (fresh.isEmpty()) {
            return if (all.isEmpty()) no("No codes Dolphin reads", "Nothing in ${listing.name} is a Gecko or Action Replay code as Dolphin reads them.")
            else no("Already there", "This game already has every code in ${listing.name}.")
        }
        val rejected = read.flatMap { it.rejected } + already.map { "$${it.name}: this game has it already" }
        return appendPlan(id, EnhancementKind.CHEATS, "Dolphin", listing, dest, DolphinCheats.block(fresh), rejected, fresh.size, ppsspp = false) { had ->
            listOfNotNull(
                "They are added to the end of the game's settings file; what is in it now stays, and a copy of it is kept.".takeIf { had },
                "Turn the ones you want on in Dolphin: right-click the game, Properties, Gecko Codes or AR Codes. Cheats are turned on for this game.",
            )
        }
    }

    private suspend fun rpcs3Mod(id: GameId, game: Game, kind: EnhancementKind, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        if (kind != EnhancementKind.MODS) return no("Add it as a mod", "RPCS3 has no folder for cheats or texture packs; its patches are added in its own Patch Manager.")
        val rpcs3 = installed("rpcs3") ?: return no("RPCS3 isn't installed", "Install RPCS3 and start it once, then add this.")
        val usrdir = files.rpcs3GameFiles(rpcs3, game.location.launchPath, game.tags.serial)
            ?: return no("Fuse can't find the game's files", "RPCS3 has no folder for mods: a mod's files stand in for the game's own in its USRDIR. Fuse finds that folder for games installed in RPCS3 and for game folders (PS3_GAME/USRDIR).")
        val layout = ModRules.rpcs3Files(listing.entries)
            ?: return no("Fuse can't tell where its files go", "A PS3 mod shows where its files go with a USRDIR folder. ${listing.name} has none, so follow its own instructions.")
        val planned = layout.files.map { (from, rel) -> PlannedFile(from, "$usrdir/$rel") }
        val notes = listOf(
            "RPCS3 has no folder for mods, so these files take the place of the game's own. Every file they replace is kept, and put back when you take this out.",
            "Installing a game update in RPCS3 after this can replace these files again.",
        )
        return modPlan(id, kind, "RPCS3", listing, source, usrdir, planned, layout.rejected, notes = notes)
    }

    private suspend fun shadPs4Mod(id: GameId, game: Game, kind: EnhancementKind, files: EmulatorFiles, source: String, listing: SourceListing): EnhancementPreview {
        if (kind != EnhancementKind.MODS) return no("Add it as a mod", "shadPS4 reads a game's changes as mods.")
        val (mods, top) = files.shadPs4Mods(game.location.launchPath)
            ?: return no("Fuse can't find the game's folder", "shadPS4 reads mods from a folder beside the game's own (its folder name with -mods). Fuse finds it for games kept as folders with an eboot.bin.")
        val layout = ModRules.shadPs4Files(listing.entries, top)
            ?: return no("Fuse can't tell where its files go", "A shadPS4 mod is laid out like the game's folder, and nothing in ${listing.name} matches the game's own folders (${top.take(4).joinToString(", ")}). Follow its own instructions.")
        val planned = layout.files.map { (from, rel) -> PlannedFile(from, "$mods/$rel") }
        val notes = listOf("shadPS4 reads these over the game's own files, which stay as they are.")
        return modPlan(id, kind, "shadPS4", listing, source, mods, planned, layout.rejected, notes = notes)
    }

    /** A Switch title id named in [entries]' folders that isn't [titleId]: the game the mod was made for instead. */
    private fun madeForTitle(entries: List<String>, titleId: String): String? {
        for (e in entries.take(2000)) for (part in e.replace('\\', '/').split('/').dropLast(1)) {
            // A game's update and add-ons share its id's first twelve digits.
            if (ModRules.isTitleId(part) && part.startsWith("01") && !part.take(12).equals(titleId.take(12), ignoreCase = true)) return part.uppercase()
        }
        return null
    }

    // Shared --------------------------------------------------------------------------------------

    private suspend fun texturePlan(
        id: GameId,
        emulator: String,
        family: String,
        listing: SourceListing,
        source: String,
        target: String,
        planned: List<PlannedFile>,
        rejected: List<String>,
        settingsFile: String?,
        settings: List<Triple<String, String, String>>,
        said: List<String>,
        notes: List<String>,
    ): EnhancementPreview {
        val record = Record(token(), EnhancementKind.TEXTURES, emulator, listing.name, now(), planned.size, target, settingsFile = settingsFile)
        // Another pack already added here: its files would be mixed with this one's.
        val others = records(id).filter { it.kind == EnhancementKind.TEXTURES && it.target == target }
        val replaces = ArrayList<String>()
        for (p in planned) if (fs.stat(p.to) != null) replaces += p.to
        val plan = Plan(id, record, source, planned, emptyMap(), settings, emptyList())
        val allNotes = notes + listOfNotNull(
            others.firstOrNull()?.let { "${it.name} is already added for this game. Pictures with the same names are replaced by this pack's, and come back when you take this one out." },
            "$family textures load as the game starts; restart the game if it is running.".takeIf { family == "ppsspp" },
        )
        return preview(plan, replaces, rejected, allNotes, said, 0, listing.bytes)
    }

    private suspend fun appendPlan(
        id: GameId,
        kind: EnhancementKind,
        emulator: String,
        listing: SourceListing,
        dest: String,
        block: String,
        rejected: List<String>,
        codes: Int,
        ppsspp: Boolean,
        /** What the preview says about where they go and turning them on, when it isn't the game's Cheats list in Fuse. */
        notesFor: ((existing: Boolean) -> List<String>)? = null,
    ): EnhancementPreview {
        if (block.length > MAX_TEXT / 4) return no("That's a lot of codes", "Fuse adds up to 256 KB of codes at a time. Choose the file for this game alone.")
        val existing = fs.readText(dest, MAX_TEXT)
        val record = Record(
            token(), kind, emulator, listing.name, now(), 1, dest.substringBeforeLast('/'),
            appendedTo = dest, appended = block, appendedNew = existing == null,
        )
        // The codes are added to the file as it is when the preview is accepted (see [add]).
        val plan = Plan(id, record, null, emptyList(), emptyMap(), emptyList(), emptyList())
        val word = if (kind == EnhancementKind.CHEATS) "Cheats" else "Patches"
        val notes = notesFor?.invoke(existing != null) ?: buildList {
            if (existing != null) add("They are added to the end of the game's file; what is in it now stays, and a copy of it is kept.")
            add("They show in this game's $word list, off until you turn them on.")
        }
        return preview(plan, emptyList(), rejected, notes, emptyList(), codes, block.length.toLong())
    }

    /** Adds what [token]'s preview showed. Null when it worked, else why it didn't. */
    suspend fun add(token: String): String? {
        val plan = plans.remove(token) ?: return "That preview is out of date. Choose the file again."
        val files = ctx.services.emulatorFiles ?: return "Not on this device."
        val r = plan.record
        // The game's settings, changed with the files: read now, so nothing set in between is lost.
        var settings = emptySet<String>()
        var settingsText: String? = null
        if (plan.settings.isNotEmpty() && r.settingsFile != null) {
            val ini = IniText(fs.readText(r.settingsFile, MAX_TEXT) ?: "")
            for ((section, key, value) in plan.settings) settings = SettingsEdit.change(ini, settings, section, key, value)
            settingsText = ini.toString()
        }
        var record = r
        var texts = if (settingsText != null) plan.texts + (r.settingsFile!! to settingsText) else plan.texts
        if (r.appendedTo != null && r.appended != null) {
            val current = fs.readText(r.appendedTo, MAX_TEXT)
            texts = texts + (r.appendedTo to EnhancementRules.append(current, r.appended))
            record = record.copy(appendedNew = current == null)
        }
        if (!files.install(plan.source, plan.files, texts, r.id)) return "Fuse couldn't put the files in place, so it changed nothing. ${r.emulator}'s folder may be read-only, or the source may have moved."
        for ((kind, path) in plan.forget) {
            data.owned.set(kind, path, emptySet())
            data.owned.set(kind, "$path#made", emptySet())
        }
        save(plan.game, records(plan.game) + record.copy(settings = settings))
        return null
    }

    /** Takes out what was added as [enhancement]; [force] puts back the earlier file even though it changed since. */
    suspend fun remove(id: GameId, enhancement: String, force: Boolean = false): EnhancementRemoval {
        val files = ctx.services.emulatorFiles ?: return EnhancementRemoval.Failed("Not on this device.")
        val all = records(id)
        val r = all.firstOrNull { it.id == enhancement } ?: return EnhancementRemoval.Removed
        val keep = HashSet<String>()
        // Settings first: what this turned on goes back to what it was, unless another one still needs it or it was changed since.
        if (r.settingsFile != null && r.settings.isNotEmpty()) {
            val needed = all.filter { it.id != r.id && it.settingsFile == r.settingsFile }.flatMap { it.settings }.map { it.substringBefore('\t') + "\t" + it.split('\t').getOrElse(1) { "" } }.toSet()
            val mine = r.settings.filter { (it.substringBefore('\t') + "\t" + it.split('\t').getOrElse(1) { "" }) !in needed }.toSet()
            val text = fs.readText(r.settingsFile, MAX_TEXT)
            if (text != null && mine.isNotEmpty()) {
                val ini = IniText(text)
                SettingsEdit.restore(ini, mine)
                if (!files.write(r.settingsFile, ini.toString())) return EnhancementRemoval.Failed("Fuse couldn't change ${r.emulator}'s settings for this game, so it took nothing out.")
            }
            keep += r.settingsFile
        }
        if (r.appendedTo != null && r.appended != null && !force) {
            val current = fs.readText(r.appendedTo, MAX_TEXT)
            if (current != null) {
                val rest = EnhancementRules.removeAppended(current, r.appended, ppsspp = r.emulator == "PPSSPP")
                    ?: return EnhancementRemoval.ChangedSince(
                        "The codes Fuse added to ${shown(r.appendedTo)} were changed in ${r.emulator} since. Put the file back as it was before they were added? Anything changed in it since is lost.",
                    )
                if (!(rest.isEmpty() && r.appendedNew)) {
                    if (!files.write(r.appendedTo, rest)) return EnhancementRemoval.Failed("Fuse couldn't change ${shown(r.appendedTo)}.")
                    keep += r.appendedTo
                }
            }
        }
        if (!files.undo(r.id, keep)) return EnhancementRemoval.Failed("Fuse couldn't put everything back as it was. What it couldn't is kept aside, so try again.")
        save(id, all.filter { it.id != r.id })
        return EnhancementRemoval.Removed
    }

    private suspend fun records(id: GameId): List<Record> = data.owned.get(KIND, id.value.toString()).mapNotNull {
        try { json.decodeFromString(Record.serializer(), it) } catch (e: Exception) { null }
    }

    private suspend fun save(id: GameId, records: List<Record>) {
        data.owned.set(KIND, id.value.toString(), records.map { json.encodeToString(Record.serializer(), it) }.toSet())
    }

    private companion object {
        const val KIND = "enhancements"
        const val MAX_TEXT = 1024 * 1024
        const val MAX_CODE_FILES = 64

        /** Files looked at in one added mod when checking what another would change too. */
        const val MAX_WALK = 20_000
        const val GS = "EmuCore/GS"
        const val LOAD_TEXTURES = "LoadTextureReplacements"
        const val TR = "TextureReplacements"
        const val DOLPHIN_VIDEO = "Video_Settings"

        /** Systems whose games Fuse adds mods to, by the emulator they start in (Dolphin's have textures too). */
        val MOD_PLATFORMS = setOf("wiiu", "switch", "ps3", "ps4")
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        val SERIAL = Regex("(?<![A-Z])[A-Z]{4}[-_. ]?[0-9]{3}[.]?[0-9]{2}(?![0-9])")
    }
}
