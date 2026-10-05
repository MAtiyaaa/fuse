package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuRow
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.animate
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.BrandArt
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.store.SuggestedSource
import kotlinx.coroutines.launch

/** Builds the setup steps for this platform; steps for features the platform lacks are left out. */
@Composable
fun rememberSteps(app: AppState, state: OnboardingState): List<Step> {
    val store = app.store
    val platform = app.platform
    val prefs by store.prefs.collectAsState()
    val storage by platform.storage.state.collectAsState()
    val isHome = platform.homeRole?.isHome?.collectAsState()?.value ?: false
    val cartridge by store.cartridge.status.collectAsState()
    val installed by store.emulators.installed.collectAsState()
    val platforms by store.library.platforms.collectAsState()
    val scan by store.sources.scan.collectAsState()
    val sources by store.sources.sources.collectAsState()
    val raConfigured by store.achievements.configured.collectAsState()
    val jellyfinState = store.jellyfin?.state?.collectAsState()?.value
    val syncthingState = store.syncthing?.state?.collectAsState()?.value
    val secrets by store.credentials.stored.collectAsState()
    val displays by platform.displays.collectAsState()
    val suggestions = remember { mutableStateListOf<SuggestedSource>() }
    val chosen = remember { mutableStateListOf<String>() }
    var suggestionsLoaded by remember { mutableStateOf(false) }
    val suggestionSel = remember { LinearSelection() }
    val next = state::next
    // Replayed as a rehearsal (developer options), steps change nothing outside preferences, and
    // those are put back when it ends.
    val live = !app.dev.rehearsing
    val desktop = platform.host != io.github.matiyaaa.fuse.model.Host.ANDROID

    LaunchedEffect(storage) {
        if (storage == StorageState.GRANTED || storage == StorageState.NOT_NEEDED) {
            val found = store.sources.suggestions()
            suggestions.clear()
            suggestions.addAll(found)
            if (chosen.isEmpty()) chosen.addAll(found.filter { f -> sources.none { it.path == f.path } }.take(3).map { it.path })
            suggestionsLoaded = true
        }
    }

    // Steam on a computer: what is installed, on every drive, all ticked to start with.
    val steamGames = remember { mutableStateListOf<io.github.matiyaaa.fuse.library.steam.SteamGame>() }
    val steamChosen = remember { mutableStateListOf<Long>() }
    var steamLoaded by remember { mutableStateOf(!desktop) }
    var steamAdded by remember { mutableStateOf<Int?>(null) }
    val steamSel = remember { LinearSelection() }
    LaunchedEffect(desktop) {
        if (!desktop) return@LaunchedEffect
        val found = runCatching { store.sources.findSteamGames() }.getOrDefault(emptyList())
        steamGames.clear()
        steamGames.addAll(found)
        steamChosen.clear()
        steamChosen.addAll(found.map { it.appId })
        steamLoaded = true
    }

    val cap = platform.device
    val scanning = scan.phase == ScanPhase.DISCOVERING || scan.phase == ScanPhase.SCANNING || scan.phase == ScanPhase.SAVING
    val withGames = platforms.filter { it.gameCount > 0 }

    return buildList {
        // ------------------------------------------------------------------------------- start
        add(Step(
            "welcome", "Welcome", "Welcome to Fuse",
            "Your games, emulators and apps in one place, made for a controller. Setup takes about a minute; you can change anything later.",
            icon = FuseIcons.Sparkles, chapter = Chapters.START,
            actions = listOf(StepAction("Begin", primary = true, run = next)),
            content = { Ignition() },
        ))
        add(Step(
            "device", "This device", "Tuned for this device",
            "Fuse looked at this device to pick how rich the effects can be without slowing anything down. You can change it at the end.",
            icon = FuseIcons.Gauge, chapter = Chapters.START,
            actions = listOf(StepAction("Continue", primary = true, run = next)),
            content = { DeviceCard(cap) },
        ))
        val role = platform.homeRole
        if (role != null) add(Step(
            "home", "Home screen", if (isHome) "Fuse is your Home screen" else "Make Fuse your Home screen?",
            if (isHome) "The Home button brings you back here, and your handheld starts straight into Fuse."
            else "Then pressing Home comes back to Fuse and the device starts into it. It's optional: Fuse works the same as a normal app, and you can change this anytime.",
            optional = true, icon = FuseIcons.Home, chapter = Chapters.START,
            actions = if (isHome) listOf(StepAction("Continue", primary = true, run = next))
            else listOf(StepAction("Use Fuse as Home", primary = true) { role.request() }, StepAction("Not now", run = next)),
        ))

        // ------------------------------------------------------------------------------- games
        add(Step(
            "storage", "Your games", "Let Fuse see your games",
            when (storage) {
                StorageState.GRANTED, StorageState.NOT_NEEDED -> "Access granted. Fuse only reads your folders: it never moves, renames or deletes anything."
                else -> "Fuse needs to read your game folders and hand games to your emulators. Android calls this \"All files access\". Fuse only reads: nothing is moved, renamed or deleted."
            },
            icon = FuseIcons.FolderOpen, chapter = Chapters.GAMES,
            actions = if (storage == StorageState.GRANTED || storage == StorageState.NOT_NEEDED) listOf(StepAction("Continue", primary = true, run = next))
            else listOf(StepAction("Allow access", primary = true) { platform.storage.request() }, StepAction("Check again") { platform.storage.refresh() }),
        ))
        val pickFolder: () -> Unit = {
            app.scope.launch {
                val path = platform.storage.pickFolder("Choose a games folder") ?: return@launch
                if (suggestions.none { it.path == path }) suggestions.add(SuggestedSource(path, path.substringAfterLast('/'), LibrarySourceKind.ROMS_ROOT, 0))
                if (path !in chosen) chosen.add(path)
            }
        }
        val useChosen: () -> Unit = {
            if (live) {
                app.scope.launch {
                    for (path in chosen.toList()) {
                        val kind = suggestions.firstOrNull { it.path == path }?.kind ?: LibrarySourceKind.ROMS_ROOT
                        store.sources.add(path, kind)
                    }
                    store.sources.rescan()
                }
            }
            next()
        }
        add(Step(
            "libraries", "Libraries", if (scanning) "Finding your games" else "Where are your games?",
            when {
                scanning -> "${scan.gamesFound} games so far. Keep going; this carries on in the background."
                suggestions.isEmpty() && suggestionsLoaded -> "Fuse didn't find a games folder on its own. Add yours: a ROMs folder, a RomM library, or a single system's folder. You can also do this later in Settings, Library."
                else -> "These look like game folders. Pick the ones to use; RomM layouts (roms/<system>) and ES-DE layouts (ROMs/<system>) are both understood."
            },
            icon = FuseIcons.Library, chapter = Chapters.GAMES,
            footnote = if (suggestions.isNotEmpty()) "X ticks or unticks the highlighted folder" else null,
            onInput = { e ->
                when (e.action) {
                    NavAction.UP, NavAction.DOWN -> suggestionSel.move(e.action, suggestions.size, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
                    NavAction.CONTEXT -> {
                        suggestions.getOrNull(suggestionSel.index)?.let { s -> if (s.path in chosen) chosen.remove(s.path) else chosen.add(s.path) }
                        NavResult.ACTIVATED
                    }
                    else -> NavResult.IGNORED
                }
            },
            // Only buttons that can do something: nothing found and nothing added means adding one,
            // or leaving it for later.
            actions = when {
                chosen.isNotEmpty() -> listOf(StepAction(if (chosen.size == 1) "Use this folder" else "Use these ${chosen.size}", primary = true, run = useChosen), StepAction("Add a folder", run = pickFolder))
                sources.isNotEmpty() -> listOf(StepAction("Continue", primary = true, run = next), StepAction("Add a folder", run = pickFolder))
                else -> listOf(StepAction("Add a folder", primary = true, run = pickFolder), StepAction("Later", run = next))
            },
            content = {
                when {
                    !suggestionsLoaded -> Spinner()
                    suggestions.isEmpty() -> FolderLayouts()
                    else -> Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                        suggestions.forEachIndexed { i, s ->
                            MenuRow(
                                MenuAction(
                                    s.path, s.label, FuseIcons.Folder,
                                    detail = "${s.path}${if (s.platformsFound > 0) "  ·  ${s.platformsFound} ${if (s.platformsFound == 1) "system" else "systems"}" else ""}",
                                    trailing = Trailing.Check(s.path in chosen),
                                ),
                                selected = i == suggestionSel.index,
                                onClick = { suggestionSel.index = i; if (s.path in chosen) chosen.remove(s.path) else chosen.add(s.path) },
                            )
                        }
                        if (scanning) ProgressBar(null, Modifier.fillMaxWidth().padding(top = Space.m))
                    }
                }
            },
        ))
        if (desktop) {
            val picked = steamGames.filter { it.appId in steamChosen }
            val drives = steamGames.map { it.library }.distinct().size
            val pickSteam: () -> Unit = {
                app.scope.launch {
                    val path = platform.storage.pickFolder("Choose a Steam library folder") ?: return@launch
                    val found = store.sources.findSteamGames(path)
                    val fresh = found.filter { f -> steamGames.none { it.appId == f.appId } }
                    steamGames.addAll(fresh)
                    steamChosen.addAll(fresh.map { it.appId })
                    if (fresh.isEmpty()) app.toasts.show("No Steam games in that folder. Pick the folder holding steamapps")
                }
            }
            add(Step(
                "steam", "Steam", when {
                    steamAdded != null -> "Your Steam games are in"
                    !steamLoaded -> "Looking for Steam games"
                    steamGames.isEmpty() -> "No Steam games found"
                    else -> "Play your Steam games here too?"
                },
                when {
                    steamAdded != null -> "${steamAdded} games are in your library under Steam. They start through Steam, and their art fills in like everything else."
                    !steamLoaded -> "Fuse is reading Steam's library folders on every drive."
                    steamGames.isEmpty() -> "Fuse looked where Steam keeps its games, on every drive. If yours live somewhere else, choose that Steam library folder, or skip this."
                    else -> "Fuse found ${steamGames.size} installed games${if (drives > 1) " across $drives drives" else ""}. Add them and they sit next to everything else, each one starting through Steam. Fuse never changes Steam's files."
                },
                optional = true, icon = FuseIcons.Gamepad, chapter = Chapters.GAMES,
                footnote = if (steamGames.isNotEmpty() && steamAdded == null) "X ticks or unticks the highlighted game" else null,
                onInput = { e ->
                    when (e.action) {
                        NavAction.UP, NavAction.DOWN -> steamSel.move(e.action, steamGames.size, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
                        NavAction.CONTEXT -> {
                            steamGames.getOrNull(steamSel.index)?.let { g -> if (g.appId in steamChosen) steamChosen.remove(g.appId) else steamChosen.add(g.appId) }
                            NavResult.ACTIVATED
                        }
                        else -> NavResult.IGNORED
                    }
                },
                actions = when {
                    steamAdded != null -> listOf(StepAction("Continue", primary = true, run = next))
                    steamGames.isEmpty() -> listOf(StepAction("Choose a Steam folder", primary = true, run = pickSteam), StepAction("Skip", run = next))
                    else -> listOf(
                        StepAction(if (picked.size == 1) "Add 1 game" else "Add ${picked.size} games", primary = true, enabled = picked.isNotEmpty(), note = "Tick at least one game to add") {
                            if (!live) { next(); return@StepAction }
                            app.scope.launch {
                                steamAdded = store.sources.addSteamGames(picked)
                                next()
                            }
                        },
                        StepAction("Another folder", run = pickSteam),
                        StepAction("No thanks", run = next),
                    )
                },
                content = {
                    when {
                        !steamLoaded -> Spinner()
                        steamGames.isEmpty() -> StepEmblem(FuseIcons.FolderSearch)
                        else -> SteamList(steamGames, steamChosen, steamSel)
                    }
                },
            ))
        }
        add(Step(
            "emulators", "Emulators", if (installed.isEmpty()) "No emulators yet" else "${installed.size} emulators found",
            if (installed.isEmpty()) "Install emulators for your systems whenever you like. Fuse notices them automatically and picks the best one for each system."
            else "Fuse picked one for each system. You can choose another per system, or per game, from their options.",
            icon = FuseIcons.Chip, chapter = Chapters.GAMES,
            actions = listOf(StepAction("Continue", primary = true, run = next), StepAction("Look again") { store.emulators.refresh() }),
            content = {
                if (withGames.isEmpty() && installed.isEmpty()) {
                    StepEmblem(FuseIcons.Chip)
                } else {
                    StatusList(
                        if (withGames.isNotEmpty()) withGames.take(8).map { p -> Triple(p.emulatorInstalled, p.platform.shortName, p.emulatorName ?: "Nothing installed") }
                        else installed.take(8).map { Triple(true, it.name, "Ready") },
                    )
                }
            },
        ))
        val needsBios = withGames.filter { it.bios.state != BiosState.NOT_REQUIRED }
        add(Step(
            "bios", "BIOS", if (needsBios.isEmpty()) "No BIOS needed so far" else "BIOS check",
            "Some systems need firmware you dump from your own console. Fuse checks your BIOS folders; when an emulator keeps it in its own storage, Fuse can't look and says so instead of guessing.",
            icon = FuseIcons.Memory, chapter = Chapters.GAMES,
            actions = listOf(StepAction("Continue", primary = true, run = next), StepAction("Check again") { store.sources.refreshBios() }),
            content = {
                if (needsBios.isEmpty()) {
                    StepEmblem(FuseIcons.ShieldCheck)
                } else {
                    StatusList(needsBios.take(8).map { p ->
                        Triple(
                            when (p.bios.state) { BiosState.READY -> true; BiosState.MISSING, BiosState.PARTIAL -> false; else -> null },
                            p.platform.shortName,
                            when (p.bios.state) { BiosState.READY -> "Ready"; BiosState.PARTIAL -> "Partly found"; BiosState.MISSING -> "Missing"; else -> "Check inside the emulator" },
                        )
                    })
                }
            },
        ))

        // ----------------------------------------------------------------------------- connect
        val cartridgeHere = platform.features.cartridge
        add(Step(
            "cartridge", "RomM and Cartridge", when {
                !cartridgeHere -> "Cartridge runs on Android and Linux"
                cartridge.installed && cartridge.bridge -> "Cartridge is linked"
                cartridge.installed -> "Cartridge found"
                else -> "Get games from your RomM server"
            },
            when {
                !cartridgeHere -> "Cartridge, the companion that downloads games from your RomM server, isn't made for this system. Add your RomM library folder in Settings, Library, and Fuse reads it as it is."
                cartridge.installed && cartridge.bridge -> "Downloads from Cartridge appear in Fuse on their own. Open it anytime from the Cartridge tab."
                cartridge.installed -> "This Cartridge opens from Fuse. Version 0.9.10 or newer adds live download status and direct links."
                else -> "Cartridge is a companion app that downloads games from your RomM server into the right folders. Install it now or later from the Cartridge tab."
            },
            optional = true, icon = io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks.Cartridge, chapter = Chapters.CONNECT,
            actions = when {
                !cartridgeHere -> listOf(
                    StepAction("Install Cartridge", enabled = false, note = "Cartridge isn't available on Windows or macOS") {},
                    StepAction("Continue", primary = true, run = next),
                )
                cartridge.installed -> listOf(StepAction("Continue", primary = true, run = next))
                else -> listOf(
                    StepAction("Install Cartridge", primary = true) {
                        app.scope.launch {
                            val release = store.cartridge.latestRelease()
                            if (release == null) {
                                app.toasts.show("Couldn't reach GitHub. You can install Cartridge later from its tab.")
                            } else {
                                app.confirm = ConfirmSpec(
                                    "Install Cartridge ${release.tag.removePrefix("v")}?",
                                    "Fuse downloads the official release from GitHub and hands it to your system's installer, where you confirm it.",
                                    "Download and install",
                                ) { if (live) app.scope.launch { store.cartridge.install(release) } }
                            }
                        }
                    },
                    StepAction("Skip", run = next),
                )
            },
        ))
        // Jellyfin: what it is, for anyone who hasn't met it, and signing in right here.
        val jellyfinAccount = jellyfinState?.account?.takeIf { prefs.jellyfin.enabled }
        add(Step(
            "jellyfin", "Films and shows", if (jellyfinAccount != null) "Jellyfin is connected" else "Your films, here too",
            if (jellyfinAccount != null) {
                "Signed in as ${jellyfinAccount.userName ?: "you"}. Your films, shows and music are in Addons, and Fuse Player plays them, with subtitles, resume and the next episode on its own."
            } else {
                "Jellyfin is a free media server you run at home, on a computer or a NAS. It keeps your films, shows and music in one library you can reach from anywhere. Connect it and Fuse plays them with its own player. No server? Skip this, and add one later in Settings, Addons."
            },
            optional = true, icon = FuseIcons.Clapperboard, chapter = Chapters.CONNECT,
            actions = if (jellyfinAccount != null) {
                listOf(StepAction("Continue", primary = true, run = next))
            } else {
                listOf(
                    StepAction("Connect Jellyfin", primary = true) { connectJellyfin(app, live) { if (state.index < state.total - 1) next() } },
                    StepAction("Skip", run = next),
                )
            },
            content = { JellyfinStage(connected = jellyfinAccount != null, server = jellyfinAccount?.serverName) },
        ))
        // Keeping saves in step: Fuse Sync (the one Fuse recommends), Syncthing for people who run it, or neither.
        val syncthing = store.syncthing
        val sync = store.sync.service
        if (sync != null || syncthing != null) {
            val syncOn = prefs.sync.enabled && prefs.sync.role.isNotEmpty()
            val syncthingOn = syncthingState != null && syncthingState !is io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.Off
            fun setUp(host: Boolean) {
                if (!live) { next(); return }
                app.scope.launch { store.sync.setEnabled(true) }
                app.go(Route.SyncSetup(host))
            }
            fun useFuseSync() {
                if (sync == null) return
                if (!sync.canHost) { setUp(host = false); return }
                app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                    title = "Fuse Sync", icon = FuseIcons.RefreshCcw,
                    message = "One computer at home keeps everything; every other device connects to it.",
                    options = listOf(
                        MenuAction("host", "Make This the Host", FuseIcons.Server, detail = "This device keeps everyone's saves and settings", onSelect = { app.choice = null; setUp(host = true) }),
                        MenuAction("connect", "Connect to a Host", FuseIcons.Link2, detail = "Another device already keeps them", onSelect = { app.choice = null; setUp(host = false) }),
                    ),
                )
            }
            fun useSyncthing() {
                if (syncthing == null) return
                if (!live) { next(); return }
                io.github.matiyaaa.fuse.ui.shell.sync.useSyncthing(app, syncthing, true)
                app.go(Route.SyncthingSettings)
            }
            add(Step(
                "sync", "Every device",
                when {
                    syncOn -> "Fuse Sync is on"
                    syncthingOn -> "Syncthing is on"
                    else -> "Play on, anywhere"
                },
                when {
                    syncOn -> "Your saves, play time, favourites and settings stay the same on every device, kept by ${prefs.sync.hostName.ifBlank { "your host" }}."
                    syncthingOn -> "Fuse shares your emulators' save folders through Syncthing, brings in the newest save before a game, and asks when two devices both played."
                    syncthing == null -> "Fuse Sync by Fuse keeps your saves, play time, favourites and settings the same on every device you play on, from a computer of your own at home. Stop on the PC, carry on on the handheld. One device? Skip this; it waits in Settings, Addons."
                    else -> "Stop on the PC, carry on on the handheld. Fuse Sync is Fuse's own, and the one we recommend: it knows each game, adds up play time and keeps a profile for each person. Already run Syncthing? Fuse can use it for your save folders instead. One device? Skip; both wait in Settings, Addons."
                },
                optional = true, icon = FuseIcons.RefreshCcw, chapter = Chapters.CONNECT,
                actions = if (syncOn || syncthingOn) {
                    listOf(StepAction("Continue", primary = true, run = next))
                } else if (syncthing == null) {
                    listOfNotNull(
                        StepAction("Make This the Host", primary = true) { setUp(host = true) }.takeIf { sync?.canHost == true },
                        StepAction("Connect to a Host", primary = sync?.canHost != true) { setUp(host = false) },
                        StepAction("Skip", run = next),
                    )
                } else {
                    listOfNotNull(
                        StepAction("Use Fuse Sync", primary = true) { useFuseSync() }.takeIf { sync != null },
                        StepAction("Use Syncthing", primary = sync == null) { useSyncthing() },
                        StepAction("Skip", run = next),
                    )
                },
                content = {
                    if (syncthing == null) SyncStage(on = syncOn) else SyncChoiceStage(fuseSync = syncOn, syncthing = syncthingOn, hasFuseSync = sync != null)
                },
            ))
        }
        add(Step(
            "ra", "Achievements", if (raConfigured) "RetroAchievements connected" else "Show your achievements?",
            if (raConfigured) "Recent unlocks and progress appear on Home and on each game's page."
            else "Connect RetroAchievements with your username and Web API key (retroachievements.org, Settings, Keys) to see progress in Fuse. The key is stored encrypted on this device.",
            optional = true, icon = FuseIcons.Trophy, chapter = Chapters.CONNECT,
            actions = if (raConfigured) listOf(StepAction("Continue", primary = true, run = next)) else listOf(
                StepAction("Connect", primary = true) {
                    app.textInput = TextInputSpec("RetroAchievements username", "") { user ->
                        app.textInput = TextInputSpec("Web API key", "", "Paste your key") { key ->
                            app.scope.launch {
                                if (live) store.achievements.connect(user.trim(), key.trim())
                                    .onSuccess { app.toasts.show("Connected as ${user.trim()}") }
                                    .onFailure { app.toasts.show(it.message ?: "Couldn't connect") }
                            }
                        }
                    }
                },
                StepAction("Skip", run = next),
            ),
        ))
        add(Step(
            "art", "Artwork", if ("sgdb.apikey" in secrets) "SteamGridDB is ready" else "Better artwork",
            "Fuse uses art from RomM (through Cartridge) and your folders first. A free SteamGridDB key adds box art, covers, backgrounds, logos and icons for everything else. More sources are in Settings, Art and details.",
            optional = true, icon = FuseIcons.Images, chapter = Chapters.CONNECT,
            actions = listOf(
                StepAction(if ("sgdb.apikey" in secrets) "Continue" else "Add SteamGridDB key", primary = true) {
                    if ("sgdb.apikey" in secrets) next() else app.textInput = TextInputSpec("SteamGridDB API key", "", "From steamgriddb.com, Preferences, API") { key ->
                        if (key.isNotBlank() && live) app.scope.launch { store.credentials.put("sgdb.apikey", key.trim()) }
                    }
                },
                StepAction("Skip", run = next),
            ),
        ))

        // ------------------------------------------------------------------------- make it yours
        // Only where there is a second screen: which of the two holds the menus.
        if (platform.features.secondScreen) add(Step(
            "displays", "Two screens", "Which way round?",
            "Menus on top keeps Fuse on the main screen, with the chosen game shown below. Menus below puts Fuse on the touch screen, like a 3DS, and gives the main screen to the game you are on. Settings, Display can change it later.",
            icon = FuseIcons.DualScreen, chapter = Chapters.YOURS,
            actions = listOf(
                StepAction("Menus on top", primary = !prefs.display.flipped && prefs.display.mode != DualScreenMode.OFF) {
                    store.updatePrefs { it.copy(display = it.display.copy(flipped = false, mode = if (it.display.mode == DualScreenMode.OFF) DualScreenMode.LIBRARY_COMPANION else it.display.mode)) }
                    next()
                },
                StepAction("Menus below", primary = prefs.display.flipped) {
                    store.updatePrefs { it.copy(display = it.display.copy(flipped = true, mode = if (it.display.mode == DualScreenMode.OFF) DualScreenMode.LIBRARY_COMPANION else it.display.mode)) }
                    next()
                },
                StepAction("One screen", primary = !prefs.display.flipped && prefs.display.mode == DualScreenMode.OFF) {
                    store.updatePrefs { it.copy(display = it.display.copy(flipped = false, mode = DualScreenMode.OFF)) }
                    next()
                },
            ),
            content = { WhichWayPreview(state.button) },
        ))
        add(Step(
            "controller", "Controller", "Which button confirms?",
            "Detect your buttons so Fuse knows how your pad is labelled and which button confirms. It takes two presses.",
            icon = FuseIcons.Gamepad, chapter = Chapters.YOURS,
            actions = listOf(
                StepAction("Detect my buttons", primary = true) { app.buttonDetect = true },
                StepAction("Continue", run = next),
            ),
            content = { ControllerTest(prefs.input.glyphs == io.github.matiyaaa.fuse.model.GlyphStyle.NINTENDO) },
        ))
        add(Step(
            "launch", "Choosing a game", "When you choose a game",
            "Play it straight away, or open its page first, with its art, details, achievements and time played, and Play one press away. The other way is always in the game's options.",
            icon = FuseIcons.CirclePlay, chapter = Chapters.YOURS,
            actions = listOf(
                StepAction("Play straight away", primary = !prefs.openGamePage) { store.updatePrefs { it.copy(openGamePage = false) }; next() },
                StepAction("Show its page first", primary = prefs.openGamePage) { store.updatePrefs { it.copy(openGamePage = true) }; next() },
            ),
            content = { LaunchStylePreview(pagesFirst = state.button == 1) },
        ))
        add(Step(
            "homestyle", "Home", "Pick a Home style",
            "Flow is a continuous dashboard of shelves. Channels is a board of tiles you arrange yourself. Either can be rearranged by holding confirm.",
            icon = FuseIcons.Dashboard, chapter = Chapters.YOURS,
            actions = listOf(
                StepAction("Flow", primary = prefs.home.mode == HomeMode.FLOW) { store.updatePrefs { it.copy(home = it.home.copy(mode = HomeMode.FLOW)) }; next() },
                StepAction("Channels", primary = prefs.home.mode == HomeMode.CHANNELS) { store.updatePrefs { it.copy(home = it.home.copy(mode = HomeMode.CHANNELS)) }; next() },
            ),
            content = { HomeStylePreview(if (state.button == 1) HomeMode.CHANNELS else HomeMode.FLOW) },
        ))
        val themes = ThemePresets.all
        val themeIndex = themes.indexOfFirst { it.id == prefs.themeId }.coerceAtLeast(0)
        add(Step(
            "theme", "Look", themes[themeIndex].name,
            themes[themeIndex].tagline + ". Use up and down to try themes; they apply right away.",
            icon = FuseIcons.Palette, chapter = Chapters.YOURS,
            onInput = { e ->
                when (e.action) {
                    NavAction.UP, NavAction.DOWN -> {
                        val nextIndex = (themeIndex + if (e.action == NavAction.DOWN) 1 else -1 + themes.size) % themes.size
                        store.updatePrefs { it.withTheme(themes[nextIndex]) }
                        NavResult.MOVED
                    }
                    else -> NavResult.IGNORED
                }
            },
            footnote = "Up and down try the next theme",
            actions = listOf(StepAction("Use ${themes[themeIndex].name}", primary = true, run = next)),
            content = { ThemePreview(themeIndex) },
        ))
        add(Step(
            "performance", "Performance", "Effects and battery",
            "Automatic suits this device. Low Power keeps navigation just as quick but skips video, blur and moving backgrounds.",
            icon = FuseIcons.Leaf, chapter = Chapters.YOURS,
            actions = listOf(
                StepAction("Automatic", primary = prefs.performance == PerformanceProfile.AUTOMATIC) { store.updatePrefs { it.copy(performance = PerformanceProfile.AUTOMATIC) }; next() },
                StepAction("Low power", primary = prefs.performance == PerformanceProfile.LOW_POWER) { store.updatePrefs { it.copy(performance = PerformanceProfile.LOW_POWER) }; next() },
                StepAction("High quality", primary = prefs.performance == PerformanceProfile.HIGH_QUALITY) { store.updatePrefs { it.copy(performance = PerformanceProfile.HIGH_QUALITY) }; next() },
            ),
            content = { PerformancePreview(state.button) },
        ))

        // ------------------------------------------------------------------------------- ready
        val total = withGames.sumOf { it.gameCount }
        add(Step(
            "done", "Ready", "You're all set",
            if (total > 0) "$total games across ${withGames.size} systems, ready to play. Press Start anytime for quick settings." else "Fuse keeps looking for games in the background. Press Start anytime for quick settings.",
            icon = FuseIcons.Rocket, chapter = Chapters.READY,
            actions = listOf(StepAction(if (live) "Start playing" else "End the rehearsal", primary = true) {
                if (live) {
                    store.updatePrefs { it.copy(onboardingDone = true) }
                    app.navigator.replace(Route.Root(io.github.matiyaaa.fuse.model.Destination.HOME))
                } else {
                    app.endRehearsal()
                }
            }),
            content = { Ignition(lit = true) },
        ))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FText(label, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.width(120.dp))
        FText(value, Fuse.type.bodyStrong, maxLines = 1)
    }
}

/** What Fuse read about the device, on a panel, with the effects it recommends. */
@Composable
private fun DeviceCard(cap: io.github.matiyaaa.fuse.model.CapabilityProfile) {
    Panel(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Fact("Processor", "${cap.cpuCores} cores")
            Fact("Memory", "${(cap.totalRamMb / 1024.0 * 10).toInt() / 10.0} GB")
            Fact("Screen", "${cap.screenWidthPx} x ${cap.screenHeightPx}, ${cap.maxRefreshRate.toInt()} Hz")
            Fact("Displays", "${cap.displayCount}")
            Spacer(Modifier.height(Space.s))
            Chip("Recommended: ${cap.tier.name.lowercase().replaceFirstChar { it.uppercase() }} effects", icon = FuseIcons.Gauge, color = Fuse.colors.accent)
        }
    }
}

/** Systems with a light for each: ready (green), missing (red) or unknown (grey), and a word. */
@Composable
private fun StatusList(rows: List<Triple<Boolean?, String, String>>) {
    Panel(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.xl, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for ((ok, name, word) in rows) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    StatusDot(ok)
                    FText(name, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.width(88.dp))
                    FText(word, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 2)
                }
            }
        }
    }
}

/**
 * No games folder found: the two layouts Fuse understands, drawn as small folder trees, so it is
 * clear what to point it at.
 */
@Composable
private fun FolderLayouts() {
    val c = Fuse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.l), modifier = Modifier.widthIn(max = 560.dp)) {
        for ((title, lines) in listOf(
            "RomM" to listOf("library", "roms", "snes", "psx", "gba"),
            "ES-DE" to listOf("ROMs", "snes", "psx", "gba"),
        )) {
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FText(title, Fuse.type.overline, color = c.accent, maxLines = 1)
                    Spacer(Modifier.height(Space.xs))
                    lines.forEachIndexed { i, name ->
                        val depth = when {
                            title == "RomM" && i >= 2 -> 2
                            i >= 1 -> 1
                            else -> 0
                        }
                        Row(Modifier.padding(start = 14.dp * depth), verticalAlignment = Alignment.CenterVertically) {
                            FuseIcon(if (depth == 2 || (title == "ES-DE" && depth == 1)) FuseIcons.Folder else FuseIcons.FolderOpen, size = Size.iconS, tint = if (depth == 0) c.text else c.textMuted)
                            Spacer(Modifier.width(Space.s))
                            FText(name, Fuse.type.label, color = if (depth == 0) c.text else c.textMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** The Steam games Fuse found: a tick each, the highlighted one followed, and where they are. */
@Composable
private fun SteamList(games: List<io.github.matiyaaa.fuse.library.steam.SteamGame>, chosen: List<Long>, sel: LinearSelection) {
    Panel(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 460.dp)) {
        io.github.matiyaaa.fuse.ui.designsystem.components.MenuList(
            games.map { g ->
                MenuAction(
                    "steam.${g.appId}", g.name, FuseIcons.Gamepad,
                    detail = listOfNotNull(
                        g.library.substringAfterLast('/').ifBlank { g.library },
                        g.sizeBytes.takeIf { it > 0 }?.let { io.github.matiyaaa.fuse.ui.shell.home.bytesText(it) },
                    ).joinToString("  ·  "),
                    trailing = Trailing.Check(g.appId in chosen),
                )
            },
            sel,
            fill = false,
            modifier = Modifier.padding(Space.s),
        )
    }
}

/**
 * Fuse's own mark drawn in, from the brand art itself ([BrandArt]): the squircle frame traces itself,
 * the fuse line runs into it and the spark lights with its glow, exactly as the logo is. [lit] shows
 * it finished.
 */
@Composable
internal fun Ignition(lit: Boolean = false) {
    val c = Fuse.colors
    val trace = remember { FuselineValue(if (lit) 1f else 0f) }
    val spark = remember { FuselineValue(if (lit) 1f else 0f) }
    LaunchedEffect(Unit) {
        trace.animateTo(1f, tween(1100, easing = Curves.Standard))
        spark.animateTo(1f, tween(420, easing = Curves.Enter))
    }
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val unit = size.width / BrandArt.MARK
            withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
                val t = trace.value
                // The frame traces itself over the first two thirds, the fuse line over the last half.
                val frame = PathMeasure().apply { setPath(BrandArt.frame, false) }
                val drawnFrame = Path()
                frame.getSegment(0f, frame.length * (t / 0.66f).coerceIn(0f, 1f), drawnFrame, true)
                drawPath(drawnFrame, c.text, style = Stroke(BrandArt.MARK_STROKE))
                val fuse = PathMeasure().apply { setPath(BrandArt.fuse, false) }
                val drawnFuse = Path()
                fuse.getSegment(0f, fuse.length * ((t - 0.5f) * 2f).coerceIn(0f, 1f), drawnFuse, true)
                drawPath(drawnFuse, c.text, style = Stroke(BrandArt.MARK_STROKE, cap = StrokeCap.Round))
                val s = spark.value
                if (s > 0f) {
                    // A flash a little larger than the glow, settling to the logo's own spark.
                    val flash = (1f - s) * 0.8f
                    drawCircle(BrandArt.glow(c.accent), radius = BrandArt.GLOW_R * (1f + flash), center = BrandArt.SPARK, alpha = s)
                    drawCircle(BrandArt.core(c.accent), radius = BrandArt.CORE_R * s, center = BrandArt.SPARK)
                }
            }
        }
    }
}

/**
 * Live view of what the controller sends, so users can confirm their layout, on a whole controller
 * drawn in the theme ([PadArt]) whose every button lights while it is held. [nintendoKeys] places
 * the face buttons where a pad that sends Nintendo keycodes has them (A on the right).
 */
@Composable
internal fun ControllerTest(nintendoKeys: Boolean = false) {
    val router = LocalInputRouter.current
    val pressed = remember { mutableStateListOf<PadButton>() }
    var last by remember { mutableStateOf<PadButton?>(null) }
    DisposableEffect(router) {
        router.rawListener = { b, down ->
            if (down) { if (b !in pressed) pressed.add(b); last = b } else pressed.remove(b)
        }
        onDispose { router.rawListener = null }
    }
    val c = Fuse.colors
    val style = Fuse.glyphs.style
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PadArt(pressed.toSet(), style, nintendoKeys, Modifier.width(380.dp))
        Spacer(Modifier.height(Space.m))
        FText(last?.let { "Last: ${it.name.replace('_', ' ').lowercase()}" } ?: "Press any button", Fuse.type.label, color = c.textMuted)
        FText(if (nintendoKeys) "Laid out as a Nintendo pad" else "Laid out as an Xbox pad", Fuse.type.caption, color = c.textFaint)
    }
}

/**
 * A small picture of a screen, for setup's choices: a rounded frame in the theme's raised surface
 * with [draw] inside it. The one being chosen is lit (an accent edge, full strength, a touch larger)
 * and the others step back, so moving between the buttons shows what each one means.
 */
@Composable
private fun MiniScreen(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    aspect: Float = 16f / 10f,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(lit: Float) -> Unit,
) {
    val c = Fuse.colors
    val lit by io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat(if (active) 1f else 0f, Fuse.motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.BASE), label = "mini")
    val shape = RoundedCornerShape(Fuse.geometry.panel.coerceAtMost(18.dp))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .graphicsLayer {
                    val s = 0.94f + 0.06f * lit
                    scaleX = s
                    scaleY = s
                    alpha = 0.5f + 0.5f * lit
                }
                .clip(shape)
                .background(c.surfaceRaised)
                .border(if (active) 2.dp else 1.dp, androidx.compose.ui.graphics.lerp(c.hairline, c.accent, lit), shape),
        ) {
            Canvas(Modifier.matchParentSize().padding(Space.m)) { draw(lit) }
        }
        Spacer(Modifier.height(Space.m))
        // Two lines where the stage is narrow (a phone held sideways), centred under its picture.
        FText(label, Fuse.type.bodyStrong, color = if (active) c.text else c.textMuted, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * One moment of a looping demonstration, 0..1, running only where motion is welcome; held still it
 * rests at [still], the moment that tells the story best.
 */
@Composable
private fun loopClock(ms: Int, still: Float = 0.8f): Float {
    if (!Fuse.motion.ambient) return still
    return io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock(label = "loop").animateFloat(
        0f, 1f, io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable(tween(ms, easing = io.github.matiyaaa.fuse.ui.fuseline.Curves.Linear)), label = "t",
    ).value
}

private fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

/** A row of game tiles along the bottom of a mini screen, the [chosen] one lifted and lit. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.miniTiles(c: io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors, chosen: Int, lift: Float, y: Float = size.height * 0.5f, n: Int = 5) {
    val gap = size.width * 0.03f
    val w = (size.width - gap * (n - 1)) / n
    val h = w * 1.25f
    for (i in 0 until n) {
        val up = if (i == chosen) lift * h * 0.08f else 0f
        drawRoundRect(
            if (i == chosen) androidx.compose.ui.graphics.lerp(c.text.copy(alpha = 0.2f), c.accent, lift) else c.text.copy(alpha = 0.16f),
            Offset(i * (w + gap), y - up), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(w * 0.16f),
        )
    }
}

/**
 * The two ways a game can open, played out side by side: a tile is chosen and pressed, then either
 * the game fills the screen at once, or its page comes first (cover, title, Play), and Play is
 * pressed there. The way the highlighted button picks is lit.
 */
@Composable
private fun LaunchStylePreview(pagesFirst: Boolean) {
    val c = Fuse.colors
    val t = loopClock(3_600)
    Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        MiniScreen("Straight into the game", active = !pagesFirst, modifier = Modifier.weight(1f)) {
            val choose = phase(t, 0.05f, 0.25f)
            val play = phase(t, 0.35f, 0.6f)
            // The menu: a title bar and tiles.
            drawRoundRect(c.text.copy(alpha = 0.5f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.35f, size.height * 0.07f), androidx.compose.ui.geometry.CornerRadius(size.height))
            miniTiles(c, chosen = 1, lift = choose, y = size.height * 0.32f)
            // The press: a ring out from the tile.
            if (play > 0f && play < 1f) {
                val gap = size.width * 0.03f
                val w = (size.width - gap * 4) / 5
                val center = Offset(w + gap + w / 2, size.height * 0.32f + w * 0.62f)
                drawCircle(c.accent.copy(alpha = 0.6f * (1f - play)), radius = w * (0.4f + play), center = center, style = Stroke(2.dp.toPx()))
            }
            // The game, filling the screen.
            val fill = phase(t, 0.5f, 0.68f) * (1f - phase(t, 0.94f, 1f))
            if (fill > 0f) {
                val inset = (1f - fill) * size.minDimension * 0.3f
                drawRoundRect(
                    Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.9f), c.accent.copy(alpha = 0.35f), c.ink)),
                    Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), alpha = fill,
                )
                // Hills and a sun: a game, abstractly.
                val ground = Path().apply {
                    moveTo(inset, size.height - inset)
                    lineTo(inset, size.height * 0.72f)
                    quadraticTo(size.width * 0.3f, size.height * 0.5f, size.width * 0.55f, size.height * 0.7f)
                    quadraticTo(size.width * 0.8f, size.height * 0.86f, size.width - inset, size.height * 0.62f)
                    lineTo(size.width - inset, size.height - inset)
                    close()
                }
                drawPath(ground, c.ink.copy(alpha = 0.7f * fill))
                drawCircle(Color.White.copy(alpha = 0.85f * fill), radius = size.minDimension * 0.08f, center = Offset(size.width * 0.72f, size.height * 0.3f))
            }
        }
        MiniScreen("The game's page first", active = pagesFirst, modifier = Modifier.weight(1f)) {
            val choose = phase(t, 0.05f, 0.25f)
            val page = phase(t, 0.32f, 0.48f) * (1f - phase(t, 0.94f, 1f))
            val press = phase(t, 0.62f, 0.8f)
            if (page < 1f) {
                drawRoundRect(c.text.copy(alpha = 0.5f * (1f - page)), Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.35f, size.height * 0.07f), androidx.compose.ui.geometry.CornerRadius(size.height))
                miniTiles(c, chosen = 1, lift = choose * (1f - page), y = size.height * 0.32f)
            }
            if (page > 0f) {
                val dy = (1f - page) * size.height * 0.08f
                // Cover, title, a meta line, a description and the Play button.
                drawRoundRect(c.accent.copy(alpha = 0.75f * page), Offset(0f, dy), androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.72f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                val x = size.width * 0.36f
                drawRoundRect(c.text.copy(alpha = 0.85f * page), Offset(x, dy + size.height * 0.04f), androidx.compose.ui.geometry.Size(size.width * 0.46f, size.height * 0.09f), androidx.compose.ui.geometry.CornerRadius(size.height))
                drawRoundRect(c.text.copy(alpha = 0.35f * page), Offset(x, dy + size.height * 0.2f), androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.05f), androidx.compose.ui.geometry.CornerRadius(size.height))
                for (i in 0 until 3) {
                    drawRoundRect(c.text.copy(alpha = 0.2f * page), Offset(x, dy + size.height * (0.32f + 0.08f * i)), androidx.compose.ui.geometry.Size(size.width * (0.58f - 0.1f * (i % 2)), size.height * 0.035f), androidx.compose.ui.geometry.CornerRadius(size.height))
                }
                val pill = androidx.compose.ui.geometry.Size(size.width * 0.24f, size.height * 0.13f)
                val at = Offset(x, dy + size.height * 0.6f)
                val pressed = if (press > 0f && press < 1f) 1f - 0.08f * kotlin.math.sin(press * kotlin.math.PI.toFloat()) else 1f
                drawRoundRect(
                    c.accent.copy(alpha = page), Offset(at.x + pill.width * (1f - pressed) / 2, at.y + pill.height * (1f - pressed) / 2),
                    androidx.compose.ui.geometry.Size(pill.width * pressed, pill.height * pressed), androidx.compose.ui.geometry.CornerRadius(size.height),
                )
                // A small play triangle on it.
                val tri = Path().apply {
                    val cx = at.x + pill.width * 0.5f
                    val cy = at.y + pill.height * 0.5f
                    val r = pill.height * 0.22f
                    moveTo(cx - r * 0.7f, cy - r)
                    lineTo(cx + r, cy)
                    lineTo(cx - r * 0.7f, cy + r)
                    close()
                }
                drawPath(tri, c.onAccent.copy(alpha = page))
                if (press > 0f && press < 1f) {
                    drawCircle(c.accent.copy(alpha = 0.6f * (1f - press)), radius = pill.width * (0.5f + press * 0.6f), center = Offset(at.x + pill.width / 2, at.y + pill.height / 2), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

/** Flow (shelves) and Channels (a board of tiles), the highlighted one lit. */
@Composable
private fun HomeStylePreview(mode: HomeMode) {
    val c = Fuse.colors
    // Channels as Home really lays it out: the default board through the board's own layout.
    val board = remember {
        val widgets = io.github.matiyaaa.fuse.model.HomeLayoutConfig.DefaultBoard
        io.github.matiyaaa.fuse.ui.shell.home.BoardGrid.layout(
            widgets.map { io.github.matiyaaa.fuse.ui.shell.home.BoardGrid.Item(it.id, it.boardSize, null) }, 4,
        )
    }
    Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        for (m in HomeMode.entries) {
            MiniScreen(if (m == HomeMode.FLOW) "Flow" else "Channels", active = m == mode, modifier = Modifier.weight(1f)) { lit ->
                val quiet = c.text.copy(alpha = 0.16f + 0.06f * lit)
                val strong = c.text.copy(alpha = 0.55f + 0.3f * lit)
                val accent = c.accent.copy(alpha = 0.55f + 0.4f * lit)
                val w = size.width
                val h = size.height
                val r = androidx.compose.ui.geometry.CornerRadius(w * 0.018f)
                fun box(x: Float, y: Float, bw: Float, bh: Float, color: Color) =
                    drawRoundRect(color, Offset(x, y), androidx.compose.ui.geometry.Size(bw, bh), r)
                if (m == HomeMode.FLOW) {
                    // The stage: the system's dot and name, the game's title large, its line of facts.
                    drawCircle(accent, radius = h * 0.018f, center = Offset(w * 0.05f, h * 0.1f))
                    box(w * 0.08f, h * 0.085f, w * 0.1f, h * 0.03f, quiet)
                    box(w * 0.04f, h * 0.15f, w * 0.42f, h * 0.085f, strong)
                    box(w * 0.04f, h * 0.27f, w * 0.18f, h * 0.035f, quiet)
                    box(w * 0.24f, h * 0.265f, w * 0.1f, h * 0.045f, quiet)
                    // Continue playing: wide cards, the first chosen with the spark bar under it.
                    box(w * 0.04f, h * 0.37f, w * 0.2f, h * 0.025f, quiet)
                    val cw = w * 0.24f
                    for (i in 0 until 4) {
                        val x = w * 0.04f + i * (cw + w * 0.025f)
                        box(x, h * 0.42f, cw, h * 0.22f, if (i == 0) accent else quiet)
                    }
                    box(w * 0.04f + cw * 0.38f, h * 0.665f, cw * 0.24f, h * 0.012f, accent)
                    // Systems: a row of square tiles.
                    box(w * 0.04f, h * 0.72f, w * 0.12f, h * 0.025f, quiet)
                    val sw = w * 0.11f
                    for (i in 0 until 8) box(w * 0.04f + i * (sw + w * 0.012f), h * 0.77f, sw, h * 0.2f, quiet)
                } else {
                    // Channels: the board, each widget its real size and place.
                    val cols = board.columns
                    val rows = board.rows.coerceAtLeast(1)
                    val gap = w * 0.02f
                    val left = w * 0.04f
                    val top = h * 0.06f
                    val cellW = (w - left * 2 - gap * (cols - 1)) / cols
                    val cellH = ((h - top * 2 - gap * (rows - 1)) / rows).coerceAtMost(cellW * 0.62f)
                    for ((id, rect) in board.rects) {
                        val x = left + rect.column * (cellW + gap)
                        val y = top + rect.row * (cellH + gap)
                        val bw = cellW * rect.width + gap * (rect.width - 1)
                        val bh = cellH * rect.height + gap * (rect.height - 1)
                        box(x, y, bw, bh, if (id == board.ids.first()) accent else quiet)
                        // A label line in each, as widgets carry their names.
                        box(x + bw * 0.08f, y + bh - bh * 0.22f, bw * 0.4f, bh * 0.08f, strong.copy(alpha = strong.alpha * 0.5f))
                    }
                }
            }
        }
    }
}

/**
 * A dual-screen handheld, drawn plainly, with what each screen holds for the highlighted choice:
 * Fuse's menus (a top line and a row of tiles) and the showcase of the game they are on (its art,
 * name and facts). Choosing the other way round slides the two past each other through the hinge,
 * and One screen leaves the lower screen dark.
 */
@Composable
private fun WhichWayPreview(button: Int) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val choice = button.coerceIn(0, 2)
    val swap by fuselineFloat(if (choice == 1) 1f else 0f, motion.tween(Durations.SLOW, Curves.Standard), label = "whichWay")
    val lit by fuselineFloat(if (choice == 2) 0f else 1f, motion.fade(Durations.BASE), label = "secondLit")
    val labels = listOf("Menus on top, the game below", "Menus below, the game on top", "Only the main screen")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(280.dp).aspectRatio(0.8f)) {
            val w = size.width
            val h = size.height
            val body = c.text.copy(alpha = 0.1f)
            val corner = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
            val hinge = h * 0.02f
            // The two halves: a wide main screen on top, the smaller touch screen below.
            drawRoundRect(body, Offset.Zero, androidx.compose.ui.geometry.Size(w, h * 0.5f - hinge), corner)
            drawRoundRect(body, Offset(w * 0.04f, h * 0.5f + hinge), androidx.compose.ui.geometry.Size(w * 0.92f, h * 0.5f - hinge), corner)
            drawRoundRect(c.text.copy(alpha = 0.16f), Offset(w * 0.08f, h * 0.5f - hinge), androidx.compose.ui.geometry.Size(w * 0.84f, hinge * 2), androidx.compose.ui.geometry.CornerRadius(hinge))
            val pad = w * 0.06f
            val top = androidx.compose.ui.geometry.Rect(pad, pad, w - pad, h * 0.5f - hinge - pad)
            val bottomW = top.height * 1.2f
            val bottomTop = h * 0.5f + hinge + pad
            val bottom = androidx.compose.ui.geometry.Rect(w / 2 - bottomW / 2, bottomTop, w / 2 + bottomW / 2, h - pad)
            val screenCorner = androidx.compose.ui.geometry.CornerRadius(w * 0.025f)
            drawRoundRect(c.ink, top.topLeft, top.size, screenCorner)
            drawRoundRect(c.ink, bottom.topLeft, bottom.size, screenCorner)
            // Where each picture is now: the menus from the top screen to the bottom one as the
            // choice turns, the showcase the other way.
            val menus = lerpRect(top, bottom, swap)
            val show = lerpRect(bottom, top, swap)
            for (screen in listOf(top, bottom)) {
                clipRect(screen.left, screen.top, screen.right, screen.bottom) {
                    val showAlpha = if (screen == bottom) lit else 1f
                    drawShowcase(show, c, showAlpha)
                    drawMenus(menus, c)
                }
            }
            // One screen: the lower screen is off.
            if (lit < 1f) drawRoundRect(Color.Black.copy(alpha = (1f - lit) * 0.92f), bottom.topLeft, bottom.size, screenCorner)
        }
        Spacer(Modifier.height(Space.m))
        FText(labels[choice], Fuse.type.bodyStrong, maxLines = 1)
    }
}

private fun lerpRect(a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect, t: Float) = androidx.compose.ui.geometry.Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)

/** Fuse's menus in miniature: the top line, a row of tiles with one chosen, and a shelf under it. */
private fun DrawScope.drawMenus(r: androidx.compose.ui.geometry.Rect, c: io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors) {
    val round = androidx.compose.ui.geometry.CornerRadius(r.height)
    val u = r.width / 20f
    for (i in 0 until 4) {
        drawRoundRect(
            if (i == 0) c.text.copy(alpha = 0.8f) else c.text.copy(alpha = 0.25f),
            Offset(r.left + u * (1.2f + i * 2.6f), r.top + r.height * 0.1f),
            androidx.compose.ui.geometry.Size(u * 2f, r.height * 0.05f),
            round,
        )
    }
    drawRoundRect(c.accent, Offset(r.left + u * 1.2f, r.top + r.height * 0.18f), androidx.compose.ui.geometry.Size(u * 2f, r.height * 0.015f), round)
    val tile = (r.width - u * 2.4f) / 5.6f
    for (i in 0 until 5) {
        val chosen = i == 1
        val lift = if (chosen) tile * 0.08f else 0f
        drawRoundRect(
            if (chosen) c.accent else c.text.copy(alpha = 0.2f),
            Offset(r.left + u * 1.2f + i * tile * 1.12f, r.top + r.height * 0.32f - lift),
            androidx.compose.ui.geometry.Size(tile, tile * 1.3f),
            androidx.compose.ui.geometry.CornerRadius(tile * 0.14f),
        )
    }
    for (i in 0 until 7) {
        drawRoundRect(
            c.text.copy(alpha = 0.12f),
            Offset(r.left + u * 1.2f + i * tile * 0.8f, r.top + r.height * 0.32f + tile * 1.45f),
            androidx.compose.ui.geometry.Size(tile * 0.7f, tile * 0.7f),
            androidx.compose.ui.geometry.CornerRadius(tile * 0.12f),
        )
    }
}

/** The chosen game shown large: its art, its name, its facts and the time played. */
private fun DrawScope.drawShowcase(r: androidx.compose.ui.geometry.Rect, c: io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors, alpha: Float) {
    if (alpha <= 0f) return
    drawRect(
        Brush.linearGradient(listOf(c.accent.copy(alpha = 0.75f * alpha), c.accent.copy(alpha = 0.18f * alpha)), start = r.topLeft, end = r.bottomRight),
        r.topLeft, r.size,
    )
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.ink.copy(alpha = 0.85f * alpha)), startY = r.top + r.height * 0.3f, endY = r.bottom), r.topLeft, r.size)
    val round = androidx.compose.ui.geometry.CornerRadius(r.height)
    val x = r.left + r.width * 0.07f
    drawRoundRect(c.text.copy(alpha = 0.95f * alpha), Offset(x, r.top + r.height * 0.52f), androidx.compose.ui.geometry.Size(r.width * 0.46f, r.height * 0.1f), round)
    for (i in 0 until 3) {
        drawRoundRect(c.text.copy(alpha = 0.3f * alpha), Offset(x + i * r.width * 0.15f, r.top + r.height * 0.69f), androidx.compose.ui.geometry.Size(r.width * 0.12f, r.height * 0.06f), round)
    }
    drawRoundRect(c.text.copy(alpha = 0.16f * alpha), Offset(x, r.top + r.height * 0.83f), androidx.compose.ui.geometry.Size(r.width * 0.86f, r.height * 0.035f), round)
    drawRoundRect(c.accent.copy(alpha = alpha), Offset(x, r.top + r.height * 0.83f), androidx.compose.ui.geometry.Size(r.width * 0.5f, r.height * 0.035f), round)
}

/**
 * What each performance choice looks like: the same screen with its light and motion. Automatic
 * has some glow, Low power is flat and still, High quality glows and drifts.
 */
@Composable
private fun PerformancePreview(button: Int) {
    val c = Fuse.colors
    val t = loopClock(6_000)
    val choice = button.coerceIn(0, 2)
    val richness = listOf(0.6f, 0f, 1f)[choice]
    val label = listOf("Automatic: rich where it is cheap", "Low power: flat, still and quick", "High quality: every effect on")[choice]
    MiniScreen(label, active = true, modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
        if (richness > 0f) {
            val drift = kotlin.math.sin(t * 2 * kotlin.math.PI.toFloat()) * richness
            val a = Offset(size.width * (0.25f + 0.1f * drift), size.height * 0.8f)
            drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.55f * richness), Color.Transparent), center = a, radius = size.width * 0.55f), radius = size.width * 0.55f, center = a)
            if (richness > 0.8f) {
                for (i in 0 until 14) {
                    val x = ((i * 0.137f + t * (0.2f + i % 3 * 0.1f)) % 1f) * size.width
                    val y = size.height * (0.15f + (i * 0.29f) % 0.7f)
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = 1.5.dp.toPx(), center = Offset(x, y))
                }
            }
        }
        drawRoundRect(c.text.copy(alpha = 0.6f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.4f, size.height * 0.08f), androidx.compose.ui.geometry.CornerRadius(size.height))
        miniTiles(c, chosen = 0, lift = 1f, y = size.height * 0.4f, n = 7)
    }
}

@Composable
private fun ThemePreview(index: Int) {
    // The same live picture as Settings, Themes, drawn in the theme's own colours and shapes.
    io.github.matiyaaa.fuse.ui.shell.settings.ThemePreview(
        ThemePresets.all[index],
        animate = true,
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(Fuse.geometry.panel)),
    )
}

private fun Modifier.matchParentSizeSafe(): Modifier = this.then(Modifier.fillMaxWidth().heightIn(min = 1.dp).aspectRatio(16f / 10f))

/**
 * Ends a replayed setup (developer options): the preferences go back to how they were before it
 * started, and Fuse returns to where the rehearsal began.
 */
fun AppState.endRehearsal() {
    val before = dev.rehearsalPrefs ?: return
    dev.rehearsalPrefs = null
    store.updatePrefs { before }
    back()
    toasts.show("Setup replayed. Nothing was changed")
}
