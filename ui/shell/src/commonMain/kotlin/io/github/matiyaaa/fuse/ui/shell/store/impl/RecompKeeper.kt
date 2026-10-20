package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.settings.InstalledPort
import io.github.matiyaaa.fuse.launch.ports.NativePorts
import io.github.matiyaaa.fuse.launch.ports.PortAdapter
import io.github.matiyaaa.fuse.launch.ports.PortLauncher
import io.github.matiyaaa.fuse.launch.ports.PortLayout
import io.github.matiyaaa.fuse.launch.ports.RecompChoice
import io.github.matiyaaa.fuse.launch.ports.RecompSetting
import io.github.matiyaaa.fuse.launch.ports.RecompSettings
import io.github.matiyaaa.fuse.launch.ports.PsPorts
import io.github.matiyaaa.fuse.launch.ports.Recomps
import io.github.matiyaaa.fuse.library.identity.Ids
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.FoundRecomp
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.PortFiles
import io.github.matiyaaa.fuse.ui.shell.store.RecompFiles
import io.github.matiyaaa.fuse.ui.shell.store.RecompMod
import io.github.matiyaaa.fuse.ui.shell.store.RecompPlace
import io.github.matiyaaa.fuse.ui.shell.store.RecompRelease
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Recomps for Fuse Ports: the ones the person installed themselves (found by [files]) offered to
 * play a game beside its emulator, and every recomp's own folder (Fuse's copy's home or the
 * person's install's) for its mods, graphics settings and controls profiles. A game played with
 * the person's own recomp stays one entry: Fuse writes how it starts as a port's launcher and keeps
 * that launcher's own entry hidden, and its saves are the recomp's, kept in step with every
 * device's copy of the same recomp (Fuse Sync's `port.<id>`).
 */
internal class RecompKeeper(
    private val ctx: StoreContext,
    private val ports: PortFiles,
    private val files: RecompFiles?,
    private val library: () -> LibraryOps,
    private val installed: () -> Map<String, InstalledPort>,
    private val playWith: (GameId) -> String?,
    private val setPlayWith: suspend (GameId, String?) -> Unit,
) {
    @kotlin.concurrent.Volatile private var found: List<FoundRecomp>? = null
    @kotlin.concurrent.Volatile private var foundAt = 0L

    /** The recomp each ROM is made into, by its path and size, so a ROM is read once. */
    private val made = HashMap<String, String>()
    private val none = HashSet<String>()

    suspend fun yours(fresh: Boolean = false): List<FoundRecomp> {
        val f = files ?: return emptyList()
        val now = Clock.System.now().toEpochMilliseconds()
        found?.takeIf { !fresh && now - foundAt < FIND_EVERY_MS }?.let { return it }
        return runCatching { f.find() }.getOrDefault(emptyList()).also { found = it; foundAt = now }
    }

    suspend fun yoursFor(game: GameId): List<FoundRecomp> {
        val g = runCatching { ctx.data.games.get(game) }.getOrNull() ?: return emptyList()
        if (g.platformId == PortAdapter.PLATFORM) return emptyList()
        val mine = yours(fresh = true)
        if (mine.isEmpty()) return emptyList()
        val fromRom = madeFrom(g)
        val name = TitleText.normalize(g.displayTitle)
        // A PlayStation dump is known by its title id, from its own files or its folder's name.
        val titleId = if (PsPorts.all.any { it.system == g.platformId.value }) {
            Ids.psId(g.tags.serial.orEmpty()) ?: Ids.psId(g.location.path)
        } else {
            null
        }
        return mine.filter { f ->
            val ps = PsPorts.byId(f.id)
            if (ps != null) return@filter ps.system == g.platformId.value && (titleId?.let { it in ps.titleIds } ?: (TitleText.normalize(ps.game) == name))
            val r = Recomps.byId(f.id) ?: return@filter false
            r.id == fromRom || (fromRom == null && r.system == g.platformId.value && TitleText.normalize(r.game) == name)
        }
    }

    /** The recomp [g]'s ROM is made into, by its hash; null when it is none of them or can't be read. */
    private suspend fun madeFrom(g: Game): String? {
        if (Recomps.all.none { it.system == g.platformId.value }) return null
        val path = g.location.launchPath
        val st = runCatching { ctx.services.fs.stat(path) }.getOrNull()?.takeIf { !it.isDirectory && it.sizeBytes in 1..MAX_ROM } ?: return null
        val key = "$path|${st.sizeBytes}"
        made[key]?.let { return it }
        if (key in none) return null
        val bytes = runCatching { ctx.services.fs.readBytes(path, 0, st.sizeBytes.toInt()) }.getOrNull()
        val id = bytes?.let { Recomps.of(it)?.id }
        if (id == null) none += key else made[key] = id
        return id
    }

    suspend fun playWithYours(game: GameId, recomp: FoundRecomp, layout: (String) -> PortLayout): String? {
        val name = NativePorts.name(recomp.id) ?: return "Fuse doesn't know this port."
        val g = runCatching { ctx.data.games.get(game) }.getOrNull() ?: return "That game isn't in the library any more."
        // A PlayStation port is handed the dump's own folder (the one holding eboot.bin).
        val launch = g.location.launchPath.replace('\\', '/')
        val gameFolder = if (launch.substringAfterLast('/').equals("eboot.bin", ignoreCase = true)) launch.substringBeforeLast('/') else g.location.path.replace('\\', '/')
        val l = layout(yoursId(recomp.id))
        if (!ports.makeFolder(l.root)) return "Fuse couldn't make a place for how it starts."
        val launcher = PortLauncher(
            port = recomp.id, version = YOURS, argv = recomp.argv.map { it.replace(PsPorts.GAME, gameFolder) }, cwd = recomp.cwd,
            saves = recomp.saves ?: "${recomp.folder}/${Recomps.SAVES}",
            saveFiles = recomp.saveFiles,
        )
        if (!ports.writeText(l.launcher, launcher.encode())) return "Fuse couldn't write how $name starts."
        val id = runCatching { ctx.data.games.idByPath(l.launcher) }.getOrNull()
            ?: library().addGameFile(l.launcher, PortAdapter.PLATFORM, title = NativePorts.game(recomp.id) ?: name)
            ?: return "Fuse couldn't add $name to the library."
        // One entry: the game chosen is the one shown; this one only says how it starts.
        library().setHidden(id, true)
        setPlayWith(game, "$YOURS_PREFIX${recomp.id}")
        return null
    }

    /** The library game Play starts for [game] when it plays with the person's own recomp. */
    suspend fun yoursTarget(game: GameId, layout: (String) -> PortLayout): GameId? {
        val with = playWith(game)?.takeIf { it.startsWith(YOURS_PREFIX) } ?: return null
        return runCatching { ctx.data.games.idByPath(layout(yoursId(with.removePrefix(YOURS_PREFIX))).launcher) }.getOrNull()?.takeIf { it != game }
    }

    suspend fun placeFor(game: GameId, layout: (String) -> PortLayout): RecompPlace? {
        val g = runCatching { ctx.data.games.get(game) }.getOrNull() ?: return null
        if (g.platformId == PortAdapter.PLATFORM) {
            // A port's own entry: its launcher says which recomp and whose copy.
            val text = runCatching { ctx.services.fs.readText(g.location.path, MAX_LAUNCHER) }.getOrNull() ?: return null
            val l = PortLauncher.decode(text) ?: return null
            return place(l.port, yours = l.version == YOURS, layout)
        }
        val with = playWith(game) ?: return null
        return if (with.startsWith(YOURS_PREFIX)) place(with.removePrefix(YOURS_PREFIX), yours = true, layout) else place(with, yours = false, layout)
    }

    private suspend fun place(id: String, yours: Boolean, layout: (String) -> PortLayout): RecompPlace? {
        val r = Recomps.byId(id) ?: return null
        if (!yours) {
            val i = installed()[id] ?: return null
            // Fuse's own copy keeps its files in its home (APP_FOLDER_PATH, as its entry in the list says).
            return RecompPlace(r, layout(id).home, null, i.port.version)
        }
        val f = yours().firstOrNull { it.id == id }
        val folder = f?.folder ?: run {
            // Not where it was found before: its folder is still the one its launcher names.
            val text = runCatching { ctx.services.fs.readText(layout(yoursId(id)).launcher, MAX_LAUNCHER) }.getOrNull()
            text?.let { PortLauncher.decode(it) }?.saves?.removeSuffix("/${Recomps.SAVES}")
        } ?: return null
        return RecompPlace(r, folder, f, f?.version)
    }

    suspend fun mods(place: RecompPlace): List<RecompMod> {
        val listed = files?.list("${place.folder}/${Recomps.MODS}") ?: return emptyList()
        return listed.filter { Recomps.isMod(it.first) }
            .map { (name, size) -> RecompMod(name, texturePack = name.lowercase().endsWith(".rtz"), size = size) }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun addMod(place: RecompPlace, file: String): String? {
        val f = files ?: return "Mods are added from Fuse on a computer."
        val name = file.replace('\\', '/').substringAfterLast('/')
        if (!Recomps.canAdd(name)) return "That isn't a mod: ${place.recomp.name} takes .nrm mods, .rtz texture packs, or a zip with them inside."
        return f.addMods(file, "${place.folder}/${Recomps.MODS}").fold(
            onSuccess = { added -> if (added.isEmpty()) "There's no mod or texture pack in that zip." else null },
            onFailure = { it.message ?: "Fuse couldn't add that mod." },
        )
    }

    suspend fun removeMod(place: RecompPlace, name: String): Boolean {
        if ('/' in name || '\\' in name || !Recomps.isMod(name)) return false
        return files?.remove("${place.folder}/${Recomps.MODS}/$name") == true
    }

    suspend fun graphics(place: RecompPlace): List<Pair<RecompSetting, RecompChoice?>> {
        val text = files?.readText("${place.folder}/${Recomps.GRAPHICS}")
        return RecompSettings.graphics(place.recomp).map { it to RecompSettings.current(text, it) }
    }

    suspend fun setGraphic(place: RecompPlace, key: String, choice: RecompChoice): Boolean {
        val f = files ?: return false
        if (RecompSettings.graphics(place.recomp).none { s -> s.key == key && choice in s.choices }) return false
        val path = "${place.folder}/${Recomps.GRAPHICS}"
        val next = RecompSettings.with(f.readText(path), key, choice.value) ?: return false
        return f.writeText(path, next)
    }

    private fun profileFolder(place: RecompPlace) = "${files?.profiles?.trimEnd('/')}/${place.recomp.id}"

    suspend fun profiles(place: RecompPlace): List<String> {
        val f = files ?: return emptyList()
        return f.list(profileFolder(place)).orEmpty().map { it.first }.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sortedBy { it.lowercase() }
    }

    suspend fun saveProfile(place: RecompPlace, name: String): String? {
        val f = files ?: return "Profiles are kept from Fuse on a computer."
        val clean = profileName(name) ?: return "Give it a name of letters, numbers and spaces."
        val controls = f.readText("${place.folder}/${Recomps.CONTROLS}")
        if (!RecompSettings.isControls(controls)) return "${place.recomp.name} hasn't saved its controls yet. Start it once and set them up, then keep them here."
        return if (f.writeText("${profileFolder(place)}/$clean.json", controls!!)) null else "Fuse couldn't keep that profile."
    }

    suspend fun useProfile(place: RecompPlace, name: String): String? {
        val f = files ?: return "Profiles are kept from Fuse on a computer."
        val clean = profileName(name) ?: return "There's no profile by that name."
        val text = f.readText("${profileFolder(place)}/$clean.json")
        if (!RecompSettings.isControls(text)) return "That profile can't be read."
        return if (f.writeText("${place.folder}/${Recomps.CONTROLS}", text!!)) null else "Fuse couldn't put those controls in place."
    }

    suspend fun removeProfile(place: RecompPlace, name: String): Boolean {
        val clean = profileName(name) ?: return false
        return files?.remove("${profileFolder(place)}/$clean.json") == true
    }

    suspend fun release(place: RecompPlace): RecompRelease? = runCatching {
        val response = ctx.services.http.get("https://api.github.com/repos/${place.recomp.repo}/releases/latest") { header("User-Agent", "Fuse") }
        if (response.status.value !in 200..299) return null
        val o = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val url = o["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://github.com/") }
            ?: "https://github.com/${place.recomp.repo}/releases"
        RecompRelease(tag, url, place.version?.let { Recomps.newer(tag, it) })
    }.getOrNull()

    suspend fun updateYours(place: RecompPlace): String? {
        val f = files ?: return "Only a Flatpak is updated from Fuse."
        val y = place.yours ?: return "Fuse's own copy is updated in Addons, Ports."
        if (y.how != FLATPAK) return "Update ${place.recomp.name} where you got it: its releases page has the new ${y.how.lowercase()}."
        return f.updateFlatpak(y.path).also { if (it == null) yours(fresh = true) }
    }

    companion object {
        /** A launcher Fuse wrote for the person's own install, not for its own copy. */
        const val YOURS = "yours"

        /** How Play With names the person's own install of a recomp: `yours:<id>`. */
        const val YOURS_PREFIX = "yours:"

        const val FLATPAK = "Flatpak"

        /** The port folder holding the launcher for the person's own install of [id]. */
        fun yoursId(id: String) = "yours-$id"

        /** A profile's name as kept: letters, digits, spaces, dashes and underscores, up to 40. */
        fun profileName(name: String): String? =
            name.trim().takeIf { it.isNotEmpty() && it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' } }

        /** How long a look for the person's recomps is believed. */
        private const val FIND_EVERY_MS = 30_000L

        /** An N64 cartridge holds 64 MB at most; anything larger isn't one. */
        private const val MAX_ROM = 128L * 1024 * 1024
        private const val MAX_LAUNCHER = 64 * 1024
    }
}
