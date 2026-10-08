package io.github.matiyaaa.fuse.sync

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.CRC32

/**
 * Read-only source inspection, backed by a private copy. Adapter destinations and canonical save
 * names remain the source of truth. Unknown bytes and emulator states are never guessed into a
 * battery-save slot. PPSSPP's SAVEDATA hierarchy: https://www.ppsspp.org/docs/faq/ .
 */
class JvmSaveImporter(private val dir: File, private val limits: Limits = Limits()) {
    data class Limits(val files: Int = 32_000, val bytes: Long = 1024L * 1024 * 1024, val fileBytes: Long = 256L * 1024 * 1024)
    internal data class Binding(val query: SaveQuery, val spot: SaveSpot, val files: Map<String, File>)
    private data class Session(val plan: SaveImportPlan, val folder: File, val bindings: Map<String, Binding>)
    private val sessions = LinkedHashMap<String, Session>()

    /** Copies and validates the entire source before discovering any destinations. */
    fun scan(source: File, games: List<SaveQuery>, env: SaveEnvironment): SaveImportPlan {
        require(source.exists()) { "That save source is no longer available" }
        require(!Files.isSymbolicLink(source.toPath())) { "Choose the actual save, rather than a symbolic link" }
        // Previews cannot survive a process restart and are bounded within one process.
        if (sessions.isEmpty()) dir.listFiles()?.forEach { it.deleteRecursively() }
        while (sessions.size >= 3) discard(sessions.values.first().plan)
        val id = SyncCrypto.token(12)
        val stage = File(dir, id).apply { mkdirs() }
        try {
            val files = stage(source, stage)
            val bindings = LinkedHashMap<String, Binding>()
            val entries = LinkedHashMap<String, SaveImportEntry>()
            val used = HashSet<String>()
            // The adapter understands its own target hierarchy. This also works for an archive of
            // an entire emulator folder, independent of the source machine's absolute data path.
            for (q in games.distinctBy { it.game.id + "|" + it.emulatorId }) {
                val spots = SaveAdapters.forEmulator(q.emulatorId)?.locate(q, env).orEmpty()
                    .filter { it.available && it.kind != SaveKind.STATE }
                for ((spotIndex, spot) in spots.withIndex()) {
                    if (spot.root != null) {
                        val serial = q.serial?.filter { it.isLetterOrDigit() }?.lowercase()
                        val roots = files.keys.flatMap { path ->
                            val parts = path.split('/')
                            val result = ArrayList<String>()
                            for (i in 0 until parts.lastIndex) {
                                val name = parts[i].lowercase()
                                // PSP/PS3 save directories may append a slot suffix to the title id.
                                if (serial != null && serial.length >= 4 && (name == serial ||
                                        (spot.format in setOf("psp.savedata", "ps3.savedata") && name.startsWith(serial)))) {
                                    var end = i
                                    // Citra's title split into two hex components, then data.
                                    if (spot.format == "3ds.savedata" && i + 2 < parts.size && parts[i + 2] == "data") end = i + 2
                                    result += parts.take(end + 1).joinToString("/")
                                }
                            }
                            // Reverse the adapter's destination suffix (Wii NAND and split 3DS IDs).
                            val rootParts = spot.root.trimEnd('/').split('/').filter { it.isNotBlank() }
                            val significant = when (spot.format) {
                                "wii.nand" -> rootParts.takeLast(4)
                                "3ds.savedata" -> rootParts.takeLast(4)
                                "wiiu.savedata" -> rootParts.takeLast(3)
                                else -> emptyList()
                            }
                            if (significant.isNotEmpty()) {
                                for (i in 0..parts.size - significant.size) {
                                    if (parts.subList(i, i + significant.size).map(String::lowercase) == significant.map(String::lowercase)) {
                                        result += parts.take(i + significant.size).joinToString("/")
                                    }
                                }
                            }
                            result
                        }.toMutableList().apply {
                            if (spot.format == "switch.savedata") {
                                val switchId = SwitchIds.of(q, env)
                                for ((name, extra) in files.filterKeys { it.endsWith("/ExtraData0") }) {
                                    val bytes = ByteArray(8)
                                    val count = extra.inputStream().use { input ->
                                        var offset = 0
                                        while (offset < 8) { val read = input.read(bytes, offset, 8 - offset); if (read < 0) break; offset += read }
                                        offset
                                    }
                                    val id = bytes.reversed().joinToString("") { "%02X".format(it.toInt() and 255) }
                                    if (switchId != null && count == 8 && id == switchId) add(name.substringBeforeLast('/') + "/0")
                                }
                            }
                        }.distinct().sortedBy { it.length }.filter { root ->
                            // No cross-user merging: every complete source root is a separate entry.
                            files.keys.any { it.startsWith("$root/") }
                        }
                        for (root in roots) {
                            val from = files.filterKeys { it.startsWith("$root/") }
                            val prefix = if (spot.folders.isNotEmpty()) root.substringAfterLast('/') + "/" else ""
                            val mapped = from.mapKeys { (name, _) -> prefix + name.removePrefix("$root/") }
                            add(entries, bindings, used, root, spot.format, q, spot, mapped, from.keys, SaveImportConfidence.TITLE_ID, spotIndex)
                        }
                        // A selected save folder itself, or a ZIP containing that one folder.
                        if (roots.isEmpty() && serial != null && source.nameWithoutExtension.lowercase().filter { it.isLetterOrDigit() }.startsWith(serial)) {
                            val prefix = if (spot.folders.isNotEmpty()) source.nameWithoutExtension + "/" else ""
                            val mapped = files.mapKeys { (name, _) -> prefix + name }
                            add(entries, bindings, used, "", spot.format, q, spot, mapped, files.keys, SaveImportConfidence.TITLE_ID, spotIndex)
                        }
                    } else {
                        for ((name, file) in files) {
                            val detected = fileFormat(name, q.platform) ?: continue
                            if (!plausible(file, detected)) continue
                            if (!SaveSlotFormats.compatible(detected, spot.format)) continue
                            val stem = File(name).nameWithoutExtension.removeSuffix("_1")
                            val serial = q.serial?.filter { it.isLetterOrDigit() }
                            val strong = serial != null && stem.filter { it.isLetterOrDigit() }.equals(serial, true)
                            val exact = TitleMatch.same(stem, q.title) || TitleMatch.same(stem, SaveAdapters.stem(q.romPath))
                            val confidence = if (strong) SaveImportConfidence.TITLE_ID else if (exact) SaveImportConfidence.EXACT_NAME else SaveImportConfidence.MANUAL
                            val canonical = canonicalFile(spot, detected, name) ?: continue
                            val group = if (detected == "n64.split") files.filterKeys { candidate ->
                                File(candidate).parent == File(name).parent && File(candidate).nameWithoutExtension == File(name).nameWithoutExtension && fileFormat(candidate, q.platform) == detected
                            } else mapOf(name to file)
                            val mapped = group.mapKeys { (path, _) -> canonicalFile(spot, detected, path) ?: canonical }
                            val key = if (detected == "n64.split") name.substringBeforeLast('.') else name
                            if (entries[key + "|" + detected]?.targets?.any { it.id.endsWith("|${q.game.id}|${q.emulatorId}|$spotIndex") } == true) continue
                            add(entries, bindings, used, key, detected, q, spot, mapped, group.keys, confidence, spotIndex)
                        }
                    }
                }
            }
            val plan = SaveImportPlan(id, source.name, entries.values.toList(), files.keys.filterNot { it in used }, files.values.sumOf { it.length() })
            sessions[id] = Session(plan, stage, bindings)
            return plan
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }

    private fun add(entries: MutableMap<String, SaveImportEntry>, bindings: MutableMap<String, Binding>, used: MutableSet<String>, source: String,
        format: String, query: SaveQuery, spot: SaveSpot, mapped: Map<String, File>, original: Set<String>, confidence: SaveImportConfidence, index: Int) {
        if (mapped.isEmpty() || mapped.keys.any { !SavePath.isSafe(it) }) return
        val key = "$source|$format"
        val targetId = "$key|${query.game.id}|${query.emulatorId}|$index"
        val target = SaveImportTarget(targetId, query, spot.kind, spot.format, spot.root ?: spot.files.joinToString { it.path }, format != spot.format, confidence)
        val old = entries[key]
        val targets = old?.targets.orEmpty() + target
        val confident = targets.filter { it.confidence != SaveImportConfidence.MANUAL }
        entries[key] = SaveImportEntry(key, source.ifEmpty { "Selected save folder" }, format, mapped.values.sumOf { it.length() }, targets,
            selectedTarget = confident.singleOrNull()?.id)
        bindings[targetId] = Binding(query, spot, mapped)
        // Unknown names remain visible for manual selection, but are still understood save bytes.
        used += original
    }

    internal fun binding(plan: SaveImportPlan, choice: SaveImportChoice): Binding {
        val session = sessions[plan.id] ?: error("This import preview expired. Choose the source again")
        require(session.plan == plan) { "The import preview changed" }
        require(session.plan.entries.any { it.id == choice.entry && it.targets.any { t -> t.id == choice.target } }) { "Choose a destination from the preview" }
        return session.bindings[choice.target] ?: error("That destination is no longer available")
    }

    /** Cancelling only removes Fuse's private staged copy, never the source. */
    fun discard(plan: SaveImportPlan) { sessions.remove(plan.id)?.folder?.deleteRecursively() }

    private fun canonicalFile(spot: SaveSpot, format: String, name: String): String? = when {
        format == "n64.split" -> "save.${name.substringAfterLast('.').lowercase()}"
        spot.files.size == 1 -> spot.files.single().name
        else -> spot.files.firstOrNull { File(it.path).name.equals(File(name).name, true) }?.name
    }

    private fun fileFormat(name: String, platform: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "srm" -> SaveAdapters.sramFormat(platform).takeIf { platform in SRAM_SYSTEMS || platform in setOf("psx", "n64") }
        "sav" -> "sram".takeIf { platform in SRAM_SYSTEMS }
        "dsv" -> "nds.dsv".takeIf { platform == "nds" }
        "mcd" -> "psx.mcd".takeIf { platform == "psx" }
        "ps2" -> "ps2.card".takeIf { platform == "ps2" }
        "eep", "mpk", "sra", "fla" -> "n64.split".takeIf { platform == "n64" }
        else -> null
    }

    private fun plausible(file: File, format: String): Boolean = when (format) {
        "sram", "nds.dsv" -> file.length().let { it in 128..(8L * 1024 * 1024) && (it and (it - 1) == 0L || format == "nds.dsv" && (it - 122) > 0 && ((it - 122) and (it - 123)) == 0L) }
        "psx.mcd" -> file.length() == 128L * 1024
        "retroarch.n64" -> file.length() == 0x48800L
        "ps2.card" -> file.length() in setOf(8L * 1024 * 1024, 8L * 1024 * 1024 + 512 * 1024)
        "n64.split" -> file.length() in setOf(512L, 2048L, 32768L, 131072L)
        else -> false
    }

    private fun stage(source: File, folder: File): Map<String, File> {
        var count = 0
        var total = 0L
        val names = HashSet<String>()
        val out = LinkedHashMap<String, File>()
        fun copy(name: String, size: Long, crc: Long? = null, input: () -> InputStream) {
            require(SavePath.isSafe(name)) { "The archive contains an unsafe path" }
            require(names.add(name.lowercase())) { "The archive contains duplicate save paths" }
            require(++count <= limits.files && size <= limits.fileBytes) { "The save source exceeds import limits" }
            val target = File(folder, name)
            require(target.canonicalPath.startsWith(folder.canonicalPath + File.separator)) { "Unsafe save path" }
            require(!target.exists()) { "Conflicting archive paths" }
            require(names.none { it != name.lowercase() && it.startsWith(name.lowercase() + "/") }) { "Conflicting archive paths" }
            target.parentFile.mkdirs()
            var bytes = 0L
            val checksum = CRC32()
            input().use { stream -> target.outputStream().use { dest ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    bytes += read; total += read
                    require(bytes <= limits.fileBytes && total <= limits.bytes) { "The save source exceeds import limits" }
                    dest.write(buffer, 0, read)
                    checksum.update(buffer, 0, read)
                }
            } }
            require(size < 0 || size == bytes) { "The source changed while being copied" }
            require(crc == null || crc < 0 || crc == checksum.value) { "The archive contains damaged save data" }
            out[name] = target
        }
        if (source.isDirectory) {
            // Never follow a symlink out of a user-selected tree, even to another readable file.
            var paths = 0
            source.walkTopDown().onEnter { d ->
                require(d.relativeTo(source).path.count { it == File.separatorChar } < 64) { "The save hierarchy is too deeply nested" }
                require(++paths <= limits.files) { "The save hierarchy has too many paths" }
                require(!Files.isSymbolicLink(d.toPath())) { "Symbolic links are not imported" }
                d == source || !SaveNoise.folder(d.name)
            }.forEach { f ->
                require(!Files.isSymbolicLink(f.toPath())) { "Symbolic links are not imported" }
                if (f.isFile && !SaveNoise.file(f.name)) copy(f.relativeTo(source).path.replace(File.separatorChar, '/'), f.length(), input = f::inputStream)
            }
        } else if (source.extension.equals("zip", true)) {
            ZipFile(source).use { zip ->
                val entries = zip.entries()
                var allEntries = 0
                while (entries.hasMoreElements()) {
                    require(++allEntries <= limits.files) { "The archive has too many entries" }
                    val entry = entries.nextElement()
                    val name = entry.name.removeSuffix("/")
                    require(SavePath.isSafe(name)) { "The archive contains an unsafe path" }
                    if (entry.isDirectory) {
                        require(names.add(name.lowercase())) { "The archive contains duplicate paths" }
                    } else copy(entry.name, entry.size, entry.crc) { zip.getInputStream(entry) }
                }
            }
        } else {
            require(source.isFile) { "Choose a save file, folder or ZIP" }
            copy(source.name, source.length(), input = source::inputStream)
        }
        require(out.isNotEmpty()) { "This source contains no readable save files" }
        return out
    }

    private companion object {
        val SRAM_SYSTEMS = setOf("nes", "snes", "gb", "gbc", "gba", "nds", "megadrive", "genesis", "mastersystem", "gamegear", "pce", "neogeo", "wonderswan", "wonderswancolor", "ngp", "ngpc")
    }
}
