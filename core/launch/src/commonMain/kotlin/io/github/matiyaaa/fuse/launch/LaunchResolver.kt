package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.android.AndroidIntentPlan
import io.github.matiyaaa.fuse.launch.android.AndroidIntentPlanner
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.model.AppGames
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.ShortcutFormat
import kotlinx.serialization.Serializable

/**
 * Settings the caller already resolved through Global -> Platform -> Game scopes, plus per-launch
 * inputs. Blank strings count as unset.
 */
data class ScopedLaunchChoice(
    /** ScopedSettings.RetroArchCore. */
    val core: String? = null,
    /** ScopedSettings.GenerateM3u. */
    val generateM3u: Boolean = true,
    /** ScopedSettings.LaunchScreen. */
    val display: LaunchDisplay = LaunchDisplay.PRIMARY,
    /** Android display id for [LaunchDisplay.SECONDARY], when the device allows it. */
    val displayId: Int? = null,
    /** Content of the game's id file, when [IdFiles.needsContent] said to read it. */
    val injectedText: String? = null,
    /** Start by title id instead of the file where the emulator allows both (for example Eden). */
    val preferTitleId: Boolean = false,
    /** Linux: RetroArch core file found by [io.github.matiyaaa.fuse.launch.linux.LinuxDetector.findRetroArchCore]. */
    val corePath: String? = null,
    /** Linux: home directory for `~` expansion. */
    val homeDir: String? = null,
)

/** A disc playlist Fuse should write to its cache. Entries are absolute paths, one per line. */
@Serializable
data class PlaylistRequest(val gameId: GameId, val fileName: String, val discPaths: List<String>) {
    /** The `.m3u` text to write. */
    val content: String get() = discPaths.joinToString("\n", postfix = "\n")
}

/** Writes [PlaylistRequest.content] into Fuse's cache and returns the file's absolute path (null on failure). */
fun interface PlaylistGenerator {
    fun generate(request: PlaylistRequest): String?
}

/** Where the chosen emulator came from. */
enum class ChoiceSource { GAME, PLATFORM, PRIORITY, NONE }

/** An adapter together with the install that runs it. */
data class LaunchCandidate(val adapter: EmulatorAdapter, val installed: InstalledEmulator)

/** Result of target resolution for one adapter. */
sealed interface TargetResult {
    data class Ok(val target: LaunchTarget, val playlist: PlaylistRequest? = null) : TargetResult
    data class Failed(val reason: String) : TargetResult
}

/**
 * The launch decision for one game: the adapter, the effective target, the plan, and what else could
 * run it.
 */
data class ResolvedLaunch(
    val adapter: EmulatorAdapter?,
    val installed: InstalledEmulator?,
    val source: ChoiceSource,
    val target: LaunchTarget?,
    val plan: LaunchPlan,
    /** The full intent for Android intent plans (int extras, activity checks). Launch from this on Android. */
    val androidIntent: AndroidIntentPlan? = null,
    /** Other installed adapters for this platform, in priority order. */
    val alternatives: List<LaunchCandidate> = emptyList(),
    /** Set when the target is a playlist Fuse generated. */
    val playlistRequest: PlaylistRequest? = null,
    /** Why earlier choices were skipped, for the UI. */
    val notes: List<String> = emptyList(),
) {
    /** True when the plan really starts the game (not open-app-only, not unsupported). */
    val startsGame: Boolean get() = plan is LaunchPlan.AndroidIntent || plan is LaunchPlan.Command
}

/**
 * Chooses the emulator for a game and resolves what it is given.
 *
 * Order: the game's override, then the platform's emulator, then the first installed adapter in the
 * platform's priority order ([EmulatorPriority]) that can really start the game; an adapter that only
 * opens its app is used only when nothing else can start it. An explicit game override is honoured
 * even when it can't start the game (the plan then says why); a platform choice that can't open this
 * particular game falls through, with a note.
 */
class LaunchResolver(
    private val registry: AdapterRegistry = AdapterRegistry.Default,
    private val playlists: PlaylistGenerator? = null,
) {
    fun resolve(
        game: Game,
        platformDefaultEmulator: EmulatorId?,
        installed: List<InstalledEmulator>,
        host: Host,
        scopedChoice: ScopedLaunchChoice = ScopedLaunchChoice(),
    ): ResolvedLaunch = Session(game, installed, host, scopedChoice).resolve(platformDefaultEmulator)

    /** DLC/update handling for [adapter]; see [ContentPlanner]. */
    fun contentPlan(game: Game, adapter: EmulatorAdapter): ContentPlan = ContentPlanner.plan(game, adapter)

    /** Installed adapters for the game's platform, in priority order. */
    fun candidates(game: Game, installed: List<InstalledEmulator>, host: Host): List<LaunchCandidate> =
        Session(game, installed, host, ScopedLaunchChoice()).candidates

    /**
     * What [adapter] would be given for [game]:
     * - REQUIRED title-id adapters get the id file, or a [LaunchTarget.TitleId] from the file name's serial;
     * - PREFERRED ones (and OPTIONAL ones when [ScopedLaunchChoice.preferTitleId]) use the serial when there is one;
     * - multi-disc games without an `.m3u` get a generated [LaunchTarget.Playlist] when the adapter reads
     *   playlists, [ScopedLaunchChoice.generateM3u] is on and a [PlaylistGenerator] is set, else disc 1;
     * - folder games follow [FolderSupport]: DIRECTORY gives the folder (or an accepted file inside a
     *   multi-file folder), RESOLVES_FILE gives the game's launch file, NONE only a file inside the folder.
     */
    fun targetFor(game: Game, adapter: EmulatorAdapter, choice: ScopedLaunchChoice = ScopedLaunchChoice(), host: Host = adapter.host): TargetResult =
        targetFor(game, adapter, choice, host) { req -> playlists?.generate(req) }

    private fun targetFor(
        game: Game,
        adapter: EmulatorAdapter,
        choice: ScopedLaunchChoice,
        host: Host,
        generate: (PlaylistRequest) -> String?,
    ): TargetResult {
        // An installed app played as a game starts as the app.
        game.appId?.let { return TargetResult.Ok(LaunchTarget.App(it)) }
        val loc = game.location
        val ext = Paths.extension(loc.launchPath)
        val isIdFile = loc.kind == LocationKind.FILE && ext in adapter.idFileExtensions
        val serial = game.tags.serial?.trim()?.ifEmpty { null }

        // A package (.pkg, a Vita .vpk or .zip) is installed into the emulator, never started: what
        // was installed starts by its title id, which the scanner read from the package.
        if (serial != null && adapter.titleIdMode != TitleIdMode.NONE && InstallOnlyFiles.matches(game.platformId, loc.launchPath)) {
            return TargetResult.Ok(LaunchTarget.TitleId(serial))
        }
        if (!isIdFile) {
            when (adapter.titleIdMode) {
                TitleIdMode.REQUIRED -> return serial?.let { TargetResult.Ok(LaunchTarget.TitleId(it)) }
                    ?: TargetResult.Failed(
                        "${adapter.name} starts installed games by title id. Add a " +
                            "${adapter.idFileExtensions.firstOrNull()?.let { ".$it file" } ?: "id file"} containing the id, " +
                            "or keep the id in the file name.",
                    )
                TitleIdMode.PREFERRED -> if (serial != null) return TargetResult.Ok(LaunchTarget.TitleId(serial))
                TitleIdMode.OPTIONAL -> if (choice.preferTitleId && serial != null) return TargetResult.Ok(LaunchTarget.TitleId(serial))
                TitleIdMode.NONE -> Unit
            }
        }
        if (isIdFile) return TargetResult.Ok(fileTarget(loc.launchPath, host, choice.injectedText))

        val discs = game.discs.sortedBy { it.number }
        if (discs.size > 1 && ext != "m3u") {
            if (adapter.capabilities.playlists && choice.generateM3u) {
                val request = PlaylistRequest(game.id, playlistName(game), discs.map { it.path })
                generate(request)?.let { return TargetResult.Ok(LaunchTarget.Playlist(it, generated = true), request) }
            }
            return TargetResult.Ok(LaunchTarget.File(discs.first().path))
        }

        if (loc.kind == LocationKind.FOLDER) {
            val hasFile = loc.launchPath != loc.path
            val folderMessage = "${adapter.name} can't open a folder game; pick a file with Folder Behaviour"
            return when (adapter.capabilities.folders) {
                FolderSupport.DIRECTORY -> {
                    val inner = fileTarget(loc.launchPath, host, choice.injectedText)
                    if (hasFile && loc.interpretation != FolderInterpretation.FOLDER_IS_GAME && adapter.accepts(inner, game.platformId)) {
                        TargetResult.Ok(inner)
                    } else {
                        TargetResult.Ok(LaunchTarget.Directory(loc.path))
                    }
                }
                FolderSupport.RESOLVES_FILE ->
                    if (hasFile) TargetResult.Ok(fileTarget(loc.launchPath, host, choice.injectedText)) else TargetResult.Failed(folderMessage)
                FolderSupport.NONE ->
                    if (Paths.isInside(loc.launchPath, loc.path)) {
                        TargetResult.Ok(fileTarget(loc.launchPath, host, choice.injectedText))
                    } else {
                        TargetResult.Failed(folderMessage)
                    }
            }
        }
        return TargetResult.Ok(fileTarget(loc.launchPath, host, choice.injectedText))
    }

    private fun fileTarget(path: String, host: Host, injected: String?): LaunchTarget {
        val ext = Paths.extension(path)
        return when {
            ext == "desktop" -> {
                val format = ShortcutParser.detectFormat(path, injected)
                // On Android a .desktop game is a Winlator export even when Fuse hasn't read it.
                val fixed = if (host == Host.ANDROID && injected == null) ShortcutFormat.WINLATOR_DESKTOP else format
                LaunchTarget.Shortcut(path, fixed)
            }
            ext in IdFiles.storeSources -> LaunchTarget.Shortcut(path, ShortcutFormat.GAMENATIVE)
            else -> LaunchTarget.File(path)
        }
    }

    private fun playlistName(game: Game): String {
        val safe = game.displayTitle.map { if (it in "/\\:*?\"<>|" || it.code < 32) '_' else it }.joinToString("").trim()
        return (safe.ifEmpty { "game-${game.id.value}" }) + ".m3u"
    }

    private inner class Session(
        val game: Game,
        val installed: List<InstalledEmulator>,
        val host: Host,
        val choice: ScopedLaunchChoice,
    ) {
        private val generated = HashMap<PlaylistRequest, String?>()
        private val notes = ArrayList<String>()

        private fun installedFor(adapter: EmulatorAdapter): InstalledEmulator? =
            installed.firstOrNull { it.id == adapter.id && it.host == host }
                ?: if (adapter.builtIn) {
                    InstalledEmulator(
                        id = adapter.id, name = adapter.name, host = host, appId = "builtin",
                        platforms = adapter.platforms, detectedVia = "Built in",
                    )
                } else {
                    null
                }

        // An installed app played as a game is started as the app, whichever system it is filed under.
        val candidates: List<LaunchCandidate> = (if (game.appId != null) registry.forPlatform(AppGames.PLATFORM, host) else registry.forPlatform(game.platformId, host))
            .mapNotNull { a -> installedFor(a)?.let { LaunchCandidate(a, it) } }

        private fun candidate(id: EmulatorId): LaunchCandidate? =
            registry[id]?.takeIf { it.host == host }?.let { a -> installedFor(a)?.let { LaunchCandidate(a, it) } }

        private fun nameOf(id: EmulatorId) = registry[id]?.name ?: id.value

        fun resolve(platformDefault: EmulatorId?): ResolvedLaunch {
            if (game.appId != null) return resolveCandidates(platformDefault = null)
            game.emulatorOverride?.let { id ->
                candidate(id)?.let { return attempt(it, ChoiceSource.GAME) }
                notes += "This game's emulator (${nameOf(id)}) isn't installed."
            }
            return resolveCandidates(platformDefault)
        }

        private fun resolveCandidates(platformDefault: EmulatorId?): ResolvedLaunch {
            var triedPlatform: EmulatorId? = null
            platformDefault?.let { id ->
                val c = candidate(id)
                if (c == null) {
                    notes += "The platform's emulator (${nameOf(id)}) isn't installed."
                } else {
                    triedPlatform = id
                    val r = attempt(c, ChoiceSource.PLATFORM)
                    val plan = r.plan
                    if (plan !is LaunchPlan.Unsupported) return r
                    notes += "${c.adapter.name} can't open this game: ${plan.reason}"
                }
            }
            var openOnly: ResolvedLaunch? = null
            var firstUnsupported: ResolvedLaunch? = null
            for (c in candidates) {
                if (c.adapter.id == triedPlatform) continue
                val r = attempt(c, ChoiceSource.PRIORITY)
                when (r.plan) {
                    is LaunchPlan.AndroidIntent, is LaunchPlan.Command -> return r.copy(notes = notes.toList())
                    is LaunchPlan.OpenAppOnly -> if (openOnly == null) openOnly = r
                    is LaunchPlan.Unsupported -> if (firstUnsupported == null) firstUnsupported = r
                }
            }
            return (openOnly ?: firstUnsupported)?.copy(notes = notes.toList()) ?: ResolvedLaunch(
                adapter = null, installed = null, source = ChoiceSource.NONE, target = null,
                plan = LaunchPlan.Unsupported(EmulatorId("none"), "No installed emulator can run ${game.platformId} games."),
                alternatives = candidates, notes = notes.toList(),
            )
        }

        private fun attempt(c: LaunchCandidate, source: ChoiceSource): ResolvedLaunch {
            val others = candidates.filter { it.adapter.id != c.adapter.id }
            val t = targetFor(game, c.adapter, choice, host) { req -> generated.getOrPut(req) { playlists?.generate(req) } }
            if (t is TargetResult.Failed) {
                return ResolvedLaunch(
                    c.adapter, c.installed, source, target = null, plan = LaunchPlan.Unsupported(c.adapter.id, t.reason),
                    alternatives = others, notes = notes.toList(),
                )
            }
            t as TargetResult.Ok
            val options = LaunchOptions(
                core = choice.core?.takeIf { it.isNotBlank() },
                corePath = choice.corePath,
                displayId = choice.displayId,
                secondaryDisplay = choice.display == LaunchDisplay.SECONDARY,
                generatedPlaylistPath = (t.target as? LaunchTarget.Playlist)?.takeIf { it.generated }?.path,
                injectedText = choice.injectedText,
                homeDir = choice.homeDir,
            )
            val request = LaunchRequest(game, c.installed, t.target, options)
            val adapter = c.adapter
            val (plan, intent) = if (adapter is AndroidIntentPlanner) {
                adapter.planAndroid(request).let { it.plan to it.intent }
            } else {
                adapter.plan(request) to null
            }
            return ResolvedLaunch(
                adapter, c.installed, source, t.target, plan, intent,
                alternatives = others, playlistRequest = t.playlist, notes = notes.toList(),
            )
        }
    }
}

/**
 * Files an emulator installs rather than starts: PlayStation 3 and Vita packages, and the Vita's
 * `.vpk`/`.zip` dumps. Once installed, the game starts by its title id.
 */
object InstallOnlyFiles {
    fun matches(platform: io.github.matiyaaa.fuse.model.PlatformId, path: String): Boolean {
        val ext = Paths.extension(path).lowercase()
        return when (platform.value) {
            "ps3" -> ext == "pkg"
            "psvita" -> ext == "pkg" || ext == "vpk" || ext == "zip"
            else -> false
        }
    }
}
