package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
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
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
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
    val secrets by store.credentials.stored.collectAsState()
    val displays by platform.displays.collectAsState()
    val suggestions = remember { mutableStateListOf<SuggestedSource>() }
    val chosen = remember { mutableStateListOf<String>() }
    var suggestionsLoaded by remember { mutableStateOf(false) }
    val suggestionSel = remember { LinearSelection() }
    val next = state::next

    LaunchedEffect(storage) {
        if (storage == StorageState.GRANTED || storage == StorageState.NOT_NEEDED) {
            val found = store.sources.suggestions()
            suggestions.clear()
            suggestions.addAll(found)
            if (chosen.isEmpty()) chosen.addAll(found.filter { f -> sources.none { it.path == f.path } }.take(3).map { it.path })
            suggestionsLoaded = true
        }
    }

    val cap = platform.device
    val scanning = scan.phase == ScanPhase.DISCOVERING || scan.phase == ScanPhase.SCANNING || scan.phase == ScanPhase.SAVING
    val withGames = platforms.filter { it.gameCount > 0 }

    return buildList {
        add(Step(
            "welcome", "Welcome", "Welcome to Fuse",
            "Your games, emulators and apps in one place, made for a controller. Setup takes about a minute; you can change anything later.",
            actions = listOf(StepAction("Begin", primary = true, run = next)),
            content = { Ignition() },
        ))
        add(Step(
            "device", "This device", "Tuned for this device",
            "Fuse looked at this device to pick how rich the effects can be without slowing anything down. You can change it at the end.",
            actions = listOf(StepAction("Continue", primary = true, run = next)),
            content = {
                Panel(Modifier.widthIn(max = 460.dp)) {
                    Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Fact("Processor", "${cap.cpuCores} cores")
                        Fact("Memory", "${(cap.totalRamMb / 1024.0 * 10).toInt() / 10.0} GB")
                        Fact("Screen", "${cap.screenWidthPx} x ${cap.screenHeightPx}, ${cap.maxRefreshRate.toInt()} Hz")
                        Fact("Displays", "${cap.displayCount}")
                        Spacer(Modifier.height(Space.s))
                        Chip("Recommended: ${cap.tier.name.lowercase().replaceFirstChar { it.uppercase() }} effects", icon = FuseIcons.Gauge, color = Fuse.colors.accent)
                    }
                }
            },
        ))
        val role = platform.homeRole
        if (role != null) add(Step(
            "home", "Home screen", if (isHome) "Fuse is your Home screen" else "Make Fuse your Home screen?",
            if (isHome) "The Home button brings you back here, and your handheld starts straight into Fuse."
            else "Then pressing Home comes back to Fuse and the device starts into it. It's optional: Fuse works the same as a normal app, and you can change this anytime.",
            optional = true,
            actions = if (isHome) listOf(StepAction("Continue", primary = true, run = next))
            else listOf(StepAction("Use Fuse as Home", primary = true) { role.request() }, StepAction("Not now", run = next)),
        ))
        add(Step(
            "storage", "Your games", "Let Fuse see your games",
            when (storage) {
                StorageState.GRANTED, StorageState.NOT_NEEDED -> "Access granted. Fuse only reads your folders: it never moves, renames or deletes anything."
                else -> "Fuse needs to read your game folders and hand games to your emulators. Android calls this \"All files access\". Fuse only reads: nothing is moved, renamed or deleted."
            },
            actions = if (storage == StorageState.GRANTED || storage == StorageState.NOT_NEEDED) listOf(StepAction("Continue", primary = true, run = next))
            else listOf(StepAction("Allow access", primary = true) { platform.storage.request() }, StepAction("Check again") { platform.storage.refresh() }),
        ))
        add(Step(
            "libraries", "Libraries", if (scanning) "Finding your games" else "Where are your games?",
            when {
                scanning -> "${scan.gamesFound} games so far. Keep going; this carries on in the background."
                suggestions.isEmpty() && suggestionsLoaded -> "Fuse didn't find a games folder on its own. Add yours: a ROMs folder, a RomM library, or a single system's folder."
                else -> "These look like game folders. Pick the ones to use; RomM layouts (roms/<system>) and ES-DE layouts (ROMs/<system>) are both understood."
            },
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
            actions = listOf(
                StepAction(if (chosen.isEmpty() && sources.isNotEmpty()) "Continue" else "Use these", primary = true, enabled = chosen.isNotEmpty() || sources.isNotEmpty()) {
                    app.scope.launch {
                        for (path in chosen.toList()) {
                            val kind = suggestions.firstOrNull { it.path == path }?.kind ?: LibrarySourceKind.ROMS_ROOT
                            store.sources.add(path, kind)
                        }
                        store.sources.rescan()
                    }
                    next()
                },
                StepAction("Add a folder") {
                    app.scope.launch {
                        val path = platform.storage.pickFolder("Choose a games folder") ?: return@launch
                        if (suggestions.none { it.path == path }) suggestions.add(SuggestedSource(path, path.substringAfterLast('/'), LibrarySourceKind.ROMS_ROOT, 0))
                        if (path !in chosen) chosen.add(path)
                    }
                },
            ),
            content = {
                Column(Modifier.widthIn(max = 520.dp)) {
                    if (!suggestionsLoaded) Spinner()
                    suggestions.forEachIndexed { i, s ->
                        MenuRow(
                            MenuAction(
                                s.path, s.label, FuseIcons.Folder,
                                detail = "${s.path}${if (s.platformsFound > 0) "  ·  ${s.platformsFound} systems" else ""}",
                                trailing = Trailing.Check(s.path in chosen),
                            ),
                            selected = i == suggestionSel.index,
                            onClick = { suggestionSel.index = i; if (s.path in chosen) chosen.remove(s.path) else chosen.add(s.path) },
                        )
                    }
                    if (suggestions.isNotEmpty()) FText("X toggles a folder", Fuse.type.caption, color = Fuse.colors.textFaint, modifier = Modifier.padding(Space.m))
                    if (scanning) ProgressBar(null, Modifier.fillMaxWidth().padding(top = Space.m))
                }
            },
        ))
        add(Step(
            "cartridge", "RomM and Cartridge", when {
                cartridge.installed && cartridge.bridge -> "Cartridge is linked"
                cartridge.installed -> "Cartridge found"
                else -> "Get games from your RomM server"
            },
            when {
                cartridge.installed && cartridge.bridge -> "Downloads from Cartridge appear in Fuse on their own. Open it anytime from the Cartridge tab."
                cartridge.installed -> "This Cartridge opens from Fuse. Version 0.9.10 or newer adds live download status and direct links."
                else -> "Cartridge is a companion app that downloads games from your RomM server into the right folders. Install it now or later from the Cartridge tab."
            },
            optional = true,
            actions = if (cartridge.installed) listOf(StepAction("Continue", primary = true, run = next))
            else listOf(
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
                            ) { app.scope.launch { store.cartridge.install(release) } }
                        }
                    }
                },
                StepAction("Skip", run = next),
            ),
        ))
        add(Step(
            "emulators", "Emulators", if (installed.isEmpty()) "No emulators yet" else "${installed.size} emulators found",
            if (installed.isEmpty()) "Install emulators for your systems whenever you like. Fuse notices them automatically and picks the best one for each system."
            else "Fuse picked one for each system. You can choose another per system, or per game, from their options.",
            actions = listOf(StepAction("Continue", primary = true, run = next), StepAction("Look again") { store.emulators.refresh() }),
            content = {
                Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    for (p in withGames.take(8)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            StatusDot(p.emulatorInstalled)
                            FText(p.platform.shortName, Fuse.type.bodyStrong, modifier = Modifier.width(72.dp))
                            FText(p.emulatorName ?: "Nothing installed", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                        }
                    }
                    if (withGames.isEmpty()) installed.take(8).forEach { FText(it.name, Fuse.type.body, color = Fuse.colors.textMuted) }
                }
            },
        ))
        val needsBios = withGames.filter { it.bios.state != BiosState.NOT_REQUIRED }
        add(Step(
            "bios", "BIOS", if (needsBios.isEmpty()) "No BIOS needed so far" else "BIOS check",
            "Some systems need firmware you dump from your own console. Fuse checks your BIOS folders; when an emulator keeps it in its own storage, Fuse can't look and says so instead of guessing.",
            actions = listOf(StepAction("Continue", primary = true, run = next), StepAction("Check again") { store.sources.refreshBios() }),
            content = {
                Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    for (p in needsBios.take(8)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            StatusDot(when (p.bios.state) { BiosState.READY -> true; BiosState.MISSING, BiosState.PARTIAL -> false; else -> null })
                            FText(p.platform.shortName, Fuse.type.bodyStrong, modifier = Modifier.width(72.dp))
                            FText(
                                when (p.bios.state) { BiosState.READY -> "Ready"; BiosState.PARTIAL -> "Partly found"; BiosState.MISSING -> "Missing"; else -> "Check inside the emulator" },
                                Fuse.type.body, color = Fuse.colors.textMuted,
                            )
                        }
                    }
                }
            },
        ))
        add(Step(
            "ra", "Achievements", if (raConfigured) "RetroAchievements connected" else "Show your achievements?",
            if (raConfigured) "Recent unlocks and progress appear on Home and on each game's page."
            else "Connect RetroAchievements with your username and Web API key (retroachievements.org, Settings, Keys) to see progress in Fuse. The key is stored encrypted on this device.",
            optional = true,
            actions = if (raConfigured) listOf(StepAction("Continue", primary = true, run = next)) else listOf(
                StepAction("Connect", primary = true) {
                    app.textInput = TextInputSpec("RetroAchievements username", "") { user ->
                        app.textInput = TextInputSpec("Web API key", "", "Paste your key") { key ->
                            app.scope.launch {
                                store.achievements.connect(user.trim(), key.trim())
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
            "Fuse uses art from RomM (through Cartridge) and your folders first. A free SteamGridDB key adds covers, backgrounds, logos and icons for everything else. More sources are in Settings, Media and Scraping.",
            optional = true,
            actions = listOf(
                StepAction(if ("sgdb.apikey" in secrets) "Continue" else "Add SteamGridDB key", primary = true) {
                    if ("sgdb.apikey" in secrets) next() else app.textInput = TextInputSpec("SteamGridDB API key", "", "From steamgriddb.com, Preferences, API") { key ->
                        if (key.isNotBlank()) app.scope.launch { store.credentials.put("sgdb.apikey", key.trim()) }
                    }
                },
                StepAction("Skip", run = next),
            ),
        ))
        if (platform.features.secondScreen || displays.size > 1) add(Step(
            "displays", "Displays", "Two screens",
            "Choose what the second screen does. Games can also open on either screen when the device and emulator allow it.",
            actions = listOf(
                StepAction("Show the selected game", primary = prefs.display.mode == DualScreenMode.LIBRARY_COMPANION) { store.updatePrefs { it.copy(display = it.display.copy(mode = DualScreenMode.LIBRARY_COMPANION)) }; next() },
                StepAction("Companion while playing") { store.updatePrefs { it.copy(display = it.display.copy(mode = DualScreenMode.GAME_COMPANION)) }; next() },
                StepAction("Off") { store.updatePrefs { it.copy(display = it.display.copy(mode = DualScreenMode.OFF)) }; next() },
            ),
        ))
        add(Step(
            "controller", "Controller", "Which button confirms?",
            "Detect your buttons so Fuse knows how your pad is labelled and which button confirms. It takes two presses.",
            actions = listOf(
                StepAction("Detect my buttons", primary = true) { app.buttonDetect = true },
                StepAction("Continue", run = next),
            ),
            content = { ControllerTest(prefs.input.glyphs == io.github.matiyaaa.fuse.model.GlyphStyle.NINTENDO) },
        ))
        add(Step(
            "homestyle", "Home", "Pick a Home style",
            "Flow is a continuous dashboard of shelves. Channels is a board of tiles you arrange yourself. Either can be rearranged by holding confirm.",
            actions = listOf(
                StepAction("Flow", primary = prefs.home.mode == HomeMode.FLOW) { store.updatePrefs { it.copy(home = it.home.copy(mode = HomeMode.FLOW)) }; next() },
                StepAction("Channels", primary = prefs.home.mode == HomeMode.CHANNELS) { store.updatePrefs { it.copy(home = it.home.copy(mode = HomeMode.CHANNELS)) }; next() },
            ),
            content = { HomeStylePreview(prefs.home.mode) },
        ))
        val themes = ThemePresets.all
        val themeIndex = themes.indexOfFirst { it.id == prefs.themeId }.coerceAtLeast(0)
        add(Step(
            "theme", "Look", themes[themeIndex].name,
            themes[themeIndex].tagline + ". Use up and down to try themes; they apply right away.",
            onInput = { e ->
                when (e.action) {
                    NavAction.UP, NavAction.DOWN -> {
                        val nextIndex = (themeIndex + if (e.action == NavAction.DOWN) 1 else -1 + themes.size) % themes.size
                        store.updatePrefs { it.copy(themeId = themes[nextIndex].id) }
                        NavResult.MOVED
                    }
                    else -> NavResult.IGNORED
                }
            },
            actions = listOf(StepAction("Use ${themes[themeIndex].name}", primary = true, run = next)),
            content = { ThemePreview(themeIndex) },
        ))
        add(Step(
            "performance", "Performance", "Effects and battery",
            "Automatic suits this device. Low Power keeps navigation just as quick but skips video, blur and moving backgrounds.",
            actions = listOf(
                StepAction("Automatic", primary = prefs.performance == PerformanceProfile.AUTOMATIC) { store.updatePrefs { it.copy(performance = PerformanceProfile.AUTOMATIC) }; next() },
                StepAction("Low power") { store.updatePrefs { it.copy(performance = PerformanceProfile.LOW_POWER) }; next() },
                StepAction("High quality") { store.updatePrefs { it.copy(performance = PerformanceProfile.HIGH_QUALITY) }; next() },
            ),
        ))
        val total = withGames.sumOf { it.gameCount }
        add(Step(
            "done", "Ready", "You're all set",
            if (total > 0) "$total games across ${withGames.size} systems, ready to play. Press Start anytime for quick settings." else "Fuse keeps looking for games in the background. Press Start anytime for quick settings.",
            actions = listOf(StepAction("Start playing", primary = true) {
                store.updatePrefs { it.copy(onboardingDone = true) }
                app.navigator.replace(Route.Root(io.github.matiyaaa.fuse.model.Destination.HOME))
            }),
            content = { Ignition(lit = true) },
        ))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        FText(label, Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.width(120.dp))
        FText(value, Fuse.type.bodyStrong)
    }
}

/** The Fuse mark drawn in: the frame traces itself, the fuse line runs into it and the spark lights. */
@Composable
private fun Ignition(lit: Boolean = false) {
    val c = Fuse.colors
    val trace = remember { Animatable(if (lit) 1f else 0f) }
    val spark = remember { Animatable(if (lit) 1f else 0f) }
    LaunchedEffect(Unit) {
        trace.animateTo(1f, tween(1100))
        spark.animateTo(1f, tween(420))
    }
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val w = size.width
            val stroke = w * 0.06f
            val frame = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(stroke, stroke, w - stroke, w - stroke, androidx.compose.ui.geometry.CornerRadius(w * 0.26f)))
            }
            val measure = PathMeasure().apply { setPath(frame, false) }
            val traced = Path()
            measure.getSegment(0f, measure.length * trace.value, traced, true)
            drawPath(traced, c.text, style = Stroke(stroke, cap = StrokeCap.Round))
            val fuse = Path().apply {
                moveTo(w * 0.28f, w * 0.7f)
                cubicTo(w * 0.42f, w * 0.7f, w * 0.44f, w * 0.34f, w * 0.64f, w * 0.34f)
            }
            val fm = PathMeasure().apply { setPath(fuse, false) }
            val drawn = Path()
            fm.getSegment(0f, fm.length * ((trace.value - 0.5f) * 2f).coerceIn(0f, 1f), drawn, true)
            drawPath(drawn, c.text, style = Stroke(stroke, cap = StrokeCap.Round))
            val s = spark.value
            if (s > 0f) {
                val center = Offset(w * 0.7f, w * 0.3f)
                drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.9f * s), Color.Transparent), center = center, radius = w * 0.35f * s), radius = w * 0.35f * s, center = center)
                drawCircle(Color.White.copy(alpha = s), radius = w * 0.045f, center = center)
            }
        }
    }
}

/**
 * Live view of what the controller sends, so users can confirm their layout. [nintendoKeys] places
 * the keycodes where a pad that sends Nintendo keycodes has them (A on the right).
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
    @Composable
    fun Key(b: PadButton, label: String) {
        val on = b in pressed
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(if (on) c.accent else c.text.copy(alpha = 0.08f))
                .border(1.dp, c.text.copy(alpha = 0.2f), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center,
        ) { FText(label, Fuse.type.bodyStrong, color = if (on) c.onAccent else c.text) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
            Key(PadButton.L1, "L"); Key(PadButton.R1, "R")
        }
        if (nintendoKeys) {
            Key(PadButton.X, "X")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x4)) { Key(PadButton.Y, "Y"); Key(PadButton.A, "A") }
            Key(PadButton.B, "B")
        } else {
            Key(PadButton.Y, "Y")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x4)) { Key(PadButton.X, "X"); Key(PadButton.B, "B") }
            Key(PadButton.A, "A")
        }
        Spacer(Modifier.height(Space.m))
        FText(last?.let { "Last: ${it.name.replace('_', ' ').lowercase()}" } ?: "Press any button", Fuse.type.label, color = c.textMuted)
        FText(if (nintendoKeys) "Laid out as a Nintendo pad" else "Laid out as an Xbox pad", Fuse.type.caption, color = c.textFaint)
    }
}

@Composable
private fun HomeStylePreview(mode: HomeMode) {
    val c = Fuse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        for (m in HomeMode.entries) {
            val active = m == mode
            Column(Modifier.width(220.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(Fuse.geometry.panel))
                        .background(c.surfaceRaised).border(if (active) 2.dp else 1.dp, if (active) c.accent else c.hairline, RoundedCornerShape(Fuse.geometry.panel))
                        .padding(Space.m),
                ) {
                    Canvas(Modifier.matchParentSizeSafe()) {
                        val t = c.text.copy(alpha = 0.25f)
                        if (m == HomeMode.FLOW) {
                            drawRoundRect(t, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.5f, size.height * 0.16f), androidx.compose.ui.geometry.CornerRadius(4f))
                            for (row in 0..1) for (i in 0..4) {
                                val w = size.width / 5.6f
                                drawRoundRect(t, Offset(i * (w + 6f), size.height * (0.35f + row * 0.34f)), androidx.compose.ui.geometry.Size(w, size.height * 0.26f), androidx.compose.ui.geometry.CornerRadius(6f))
                            }
                        } else {
                            for (row in 0..2) for (i in 0..3) {
                                val w = (size.width - 18f) / 4f
                                drawRoundRect(t, Offset(i * (w + 6f), row * (size.height / 3f)), androidx.compose.ui.geometry.Size(if (i == 0 && row == 0) w * 2 + 6f else w, size.height / 3f - 6f), androidx.compose.ui.geometry.CornerRadius(8f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Space.s))
                FText(if (m == HomeMode.FLOW) "Flow" else "Channels", Fuse.type.bodyStrong, color = if (active) c.text else c.textMuted)
            }
        }
    }
}

@Composable
private fun ThemePreview(index: Int) {
    val theme = ThemePresets.all[index]
    Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(Fuse.geometry.panel))) {
        AmbientBackground(theme.background, theme.palette.accent.toColor(), Modifier.matchParentSizeSafe())
        Column(Modifier.align(Alignment.BottomStart).padding(Space.l)) {
            FText(theme.name, Fuse.type.display, color = theme.palette.textPrimary.toColor())
            FText(theme.tagline, Fuse.type.body, color = theme.palette.textSecondary.toColor())
            Spacer(Modifier.height(Space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (col in listOf(theme.palette.accent, theme.palette.surface, theme.palette.surfaceRaised, theme.palette.textPrimary)) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(col.toColor()))
                }
            }
        }
    }
}

private fun Modifier.matchParentSizeSafe(): Modifier = this.then(Modifier.fillMaxWidth().heightIn(min = 1.dp).aspectRatio(16f / 10f))
