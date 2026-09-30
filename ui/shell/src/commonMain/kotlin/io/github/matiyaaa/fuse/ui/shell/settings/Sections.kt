package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.Support
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.integrations.KeyCheck
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtPack
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtStyle
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.label
import io.github.matiyaaa.fuse.ui.shell.home.title
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle
import io.github.matiyaaa.fuse.ui.shell.platform.WindowControls
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import io.github.matiyaaa.fuse.ui.shell.app.applyUpdate

private fun motionName(m: MotionProfile?) = when (m) {
    null -> "Theme default"
    MotionProfile.REDUCED -> "Reduced"
    MotionProfile.MINIMAL -> "Minimal"
    MotionProfile.STANDARD -> "Standard"
    MotionProfile.ENHANCED -> "Enhanced"
}

private fun layoutName(l: LibraryLayout) = when (l) {
    LibraryLayout.ICON -> "Icons"
    LibraryLayout.CAPSULE -> "Capsules"
    LibraryLayout.COVER_GRID -> "Cover grid"
    LibraryLayout.COMPACT_LIST -> "List"
}

@Composable
fun appearanceRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val set = app.store::updatePrefs
    return buildList {
        add(app.choiceRow("theme", "Theme", FuseIcons.Palette, p.themeId, ThemePresets.all.map { it.id to it.name }, optionDetail = { id -> ThemePresets.byId(id).tagline }) { v ->
            set { it.copy(themeId = v, crt = if (ThemePresets.byId(v).crt.enabled) it.crt.copy(enabled = true) else it.crt) }
        })
        add(app.choiceRow(
            "motion", "Motion", FuseIcons.Activity, p.motion, listOf(null, MotionProfile.REDUCED, MotionProfile.MINIMAL, MotionProfile.STANDARD, MotionProfile.ENHANCED).map { it to motionName(it) },
            detail = "Reduced keeps only short fades: no scaling, sliding or moving backgrounds",
        ) { v -> set { it.copy(motion = v) } })
        add(toggleRow("hero", "Background art", FuseIcons.Image, p.showHero, "The selected game's art lights the room") { v -> set { it.copy(showHero = v) } })
        add(toggleRow("logo", "Title logos", FuseIcons.Type, p.showLogo, "Show logo art instead of the written title when a game has one") { v -> set { it.copy(showLogo = v) } })
        add(app.percentRow("dim", "Background dimming", FuseIcons.Contrast, p.heroDim, "Darker keeps text readable over bright art") { v -> set { it.copy(heroDim = v) } })
        add(toggleRow("glass", "Glass panels", FuseIcons.Layers, p.glass.enabled, "Frosted, translucent menus") { v -> set { it.copy(glass = it.glass.copy(enabled = v)) } })
        if (p.glass.enabled) {
            add(app.percentRow("glass.opacity", "Panel opacity", FuseIcons.Layers, p.glass.surfaceOpacity) { v -> set { it.copy(glass = it.glass.copy(surfaceOpacity = v.coerceAtLeast(0.3f))) } })
            add(app.choiceRow("glass.blur", "Blur strength", FuseIcons.Aperture, p.glass.blur, listOf(0f, 12f, 24f, 36f, 48f).map { it to if (it == 0f) "Off" else "${it.toInt()}" }) { v -> set { it.copy(glass = it.glass.copy(blur = v)) } })
            add(app.percentRow("glass.hero", "Background brightness", FuseIcons.Sun, p.glass.heroBrightness) { v -> set { it.copy(glass = it.glass.copy(heroBrightness = v.coerceAtLeast(0.2f))) } })
            add(app.percentRow("glass.gradient", "Gradient strength", FuseIcons.Contrast, p.glass.gradientStrength) { v -> set { it.copy(glass = it.glass.copy(gradientStrength = v)) } })
        }
        add(toggleRow("crt", "CRT effect", FuseIcons.Tv, p.crt.enabled, "Scanlines and phosphor glow. Turns itself off in Low Power Mode") { v -> set { it.copy(crt = it.crt.copy(enabled = v)) } })
        if (p.crt.enabled) {
            add(app.percentRow("crt.scan", "Scanlines", FuseIcons.Rows, p.crt.scanlines) { v -> set { it.copy(crt = it.crt.copy(scanlines = v)) } })
            add(app.percentRow("crt.curve", "Curvature", FuseIcons.Aperture, p.crt.curvature) { v -> set { it.copy(crt = it.crt.copy(curvature = v)) } })
            add(app.percentRow("crt.bloom", "Bloom", FuseIcons.Sun, p.crt.bloom) { v -> set { it.copy(crt = it.crt.copy(bloom = v)) } })
            add(app.percentRow("crt.color", "Colour separation", FuseIcons.Palette, p.crt.chromatic) { v -> set { it.copy(crt = it.crt.copy(chromatic = v)) } })
            add(app.percentRow("crt.vignette", "Vignette", FuseIcons.Contrast, p.crt.vignette) { v -> set { it.copy(crt = it.crt.copy(vignette = v)) } })
        }
        add(toggleRow("contrast", "High contrast focus", FuseIcons.Accessibility, p.highContrastFocus, "Adds an outline to everything that's selected") { v -> set { it.copy(highContrastFocus = v) } })
    }
}

@Composable
fun homeRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val set = app.store::updatePrefs
    val homeRole = app.platform.homeRole
    val isHome = homeRole?.isHome?.collectAsState()?.value ?: false
    return buildList {
        if (homeRole != null) {
            add(MenuAction(
                "role", if (isHome) "Fuse is your Home screen" else "Use Fuse as my Home screen", FuseIcons.HomePlus,
                detail = if (isHome) "The Home button always brings you here" else "Optional. Fuse works the same as a normal app",
                trailing = Trailing.Switch(isHome),
                onSelect = { if (isHome) homeRole.openHomeSettings() else homeRole.request() },
            ))
        }
        add(app.choiceRow("mode", "Home style", FuseIcons.Dashboard, p.home.mode, listOf(HomeMode.FLOW to "Flow", HomeMode.CHANNELS to "Channels"), optionDetail = {
            if (it == HomeMode.FLOW) "A continuous dashboard of shelves" else "A board of tiles you arrange yourself"
        }) { v -> set { it.copy(home = it.home.copy(mode = v)) } })
        for (d in Destination.entries.filter { it != Destination.HOME && (it != Destination.CARTRIDGE || p.cartridgeEnabled) }) {
            val visible = d in p.destinations
            add(toggleRow("dest.$d", "${d.label()} in the top bar", FuseIcons.PanelsTop, visible) { v ->
                set { it.copy(destinations = if (v) (it.destinations + d).sortedBy { x -> Destination.entries.indexOf(x) } else it.destinations - d) }
            })
        }
        add(MenuAction("dest.order", "Section order", FuseIcons.MoveHorizontal, detail = p.destinations.joinToString("  ·  ") { it.label() }, trailing = Trailing.Chevron, onSelect = {
            app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                title = "Move a section earlier",
                options = p.destinations.drop(1).map { d ->
                    MenuAction("mv.$d", d.label(), null, onSelect = {
                        set { s ->
                            val list = s.destinations.toMutableList()
                            val i = list.indexOf(d)
                            if (i > 1) { list.removeAt(i); list.add(i - 1, d) }
                            s.copy(destinations = list)
                        }
                        app.choice = null
                    })
                },
            )
        }))
        for (w in p.home.widgets.sortedBy { it.order }) {
            add(toggleRow("w.${w.id}", w.kind.title(), widgetIcon(w.kind), w.visible) { v ->
                set { s -> s.copy(home = s.home.copy(widgets = s.home.widgets.map { if (it.id == w.id) it.copy(visible = v) else it })) }
            })
        }
        val missing = WidgetKind.entries.filter { k -> p.home.widgets.none { it.kind == k } }
        if (missing.isNotEmpty()) {
            add(MenuAction("w.add", "Add a widget", FuseIcons.Plus, trailing = Trailing.Chevron, onSelect = {
                app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                    title = "Add to Home",
                    options = missing.map { k ->
                        MenuAction("add.$k", k.title(), widgetIcon(k), onSelect = {
                            set { s ->
                                val order = (s.home.widgets.maxOfOrNull { it.order } ?: 0) + 1
                                s.copy(home = s.home.copy(widgets = s.home.widgets + io.github.matiyaaa.fuse.model.HomeWidget(k.name.lowercase(), k, order)))
                            }
                            app.choice = null
                        })
                    },
                )
            }))
        }
        add(toggleRow("clock24", "24-hour clock", FuseIcons.Clock, p.clock24h) { v -> set { it.copy(clock24h = v) } })
        add(toggleRow("wifi", "Show Wi-Fi in the status area", FuseIcons.Wifi, p.showWifi) { v -> set { it.copy(showWifi = v) } })
        add(toggleRow("bt", "Show Bluetooth in the status area", FuseIcons.Bluetooth, p.showBluetooth) { v -> set { it.copy(showBluetooth = v) } })
    }
}

private fun widgetIcon(k: WidgetKind) = when (k) {
    WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED -> FuseIcons.History
    WidgetKind.FAVORITES -> FuseIcons.Heart
    WidgetKind.RECENTLY_ADDED -> FuseIcons.Sparkles
    WidgetKind.PINNED_GAMES -> FuseIcons.Pin
    WidgetKind.PINNED_APPS -> FuseIcons.Smartphone
    WidgetKind.COLLECTIONS -> FuseIcons.Bookmark
    WidgetKind.SYSTEMS -> FuseIcons.Chip
    WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.ACHIEVEMENT_PROGRESS -> FuseIcons.Trophy
    WidgetKind.RECENTLY_MASTERED -> FuseIcons.Award
    WidgetKind.PLAYTIME_TOTAL, WidgetKind.PLAYTIME_WEEK, WidgetKind.MOST_PLAYED -> FuseIcons.Chart
    WidgetKind.CURRENT_GAME -> FuseIcons.Play
    WidgetKind.CARTRIDGE_DOWNLOADS -> FuseIcons.CloudDownload
    WidgetKind.STORAGE -> FuseIcons.HardDrive
    WidgetKind.CLOCK -> FuseIcons.Clock
}

@Composable
fun libraryRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val sources by app.store.sources.sources.collectAsState()
    val scan by app.store.sources.scan.collectAsState()
    return buildList {
        for (s in sources) {
            add(MenuAction("src.${s.id.value}", s.label, FuseIcons.Folder, detail = s.path, trailing = Trailing.Value("Remove"), onSelect = {
                app.confirm = ConfirmSpec(
                    "Stop using this folder?", "${s.path}\n\nFuse stops showing games from it. Nothing in the folder is changed, and your edits come back if you add it again.",
                    "Remove folder", destructive = true,
                ) { app.scope.launch { app.store.sources.remove(s) } }
            }))
        }
        add(MenuAction("src.add", "Add a games folder", FuseIcons.FolderSearch, detail = "A ROMs folder, a RomM library, or one system's folder", onSelect = {
            app.scope.launch {
                val path = app.platform.storage.pickFolder("Choose a games folder") ?: return@launch
                app.store.sources.add(path)
                app.store.sources.rescan()
            }
        }))
        add(MenuAction("scan", "Scan for changes", FuseIcons.Refresh, detail = "Only folders that changed. Also runs whenever you return to Fuse", trailing = Trailing.Value(scan.phase.name.lowercase().replaceFirstChar { it.uppercase() }), onSelect = {
            app.store.sources.rescan(ScanScope.QUICK); app.toasts.show("Scanning")
        }))
        add(app.confirmRow("fullscan", "Full rescan", FuseIcons.RefreshDot, "Rescan everything?", "Fuse reads every folder again. It can take a while on large libraries; you can keep using Fuse meanwhile. Your edits are kept.", "Rescan") {
            app.store.sources.rescan(ScanScope.FULL)
        })
        add(MenuAction("bios", "Check BIOS again", FuseIcons.Key, onSelect = { app.store.sources.refreshBios(); app.toasts.show("Checking BIOS files") }))
        add(app.choiceRow("layout", "Default view", FuseIcons.Grid, p.defaultLayout, LibraryLayout.entries.map { it to layoutName(it) }) { v -> app.store.updatePrefs { it.copy(defaultLayout = v) } })
        add(app.choiceRow(
            "select", "Selecting a game", FuseIcons.Play, p.openGamePage,
            listOf(false to "Plays it", true to "Opens its page"),
            detail = "What confirm (or a tap on a selected game) does. Play is always on the game's page",
        ) { v -> app.store.updatePrefs { it.copy(openGamePage = v) } })
        add(MenuAction(
            "clean", "Clean display names", FuseIcons.Wand,
            detail = "Hides tags like (USA) and [!] in titles. Files are never renamed",
            trailing = Trailing.Switch(p.cleanDisplayNames),
            onSelect = {
                if (p.cleanDisplayNames) {
                    app.scope.launch { app.store.library.applyCleanNames(false) }
                } else {
                    app.confirm = ConfirmSpec(
                        "Clean display names?",
                        "Fuse guesses which parts of a file name are tags (region, revision, dump codes) and hides them from titles. The guess can be wrong, for example with games whose real name contains brackets. Your files keep their names, custom titles always win, and you can undo this from here.",
                        "Preview and apply",
                    ) {
                        app.scope.launch {
                            val preview = app.store.library.previewCleanNames()
                            app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                                title = "${preview.size} titles will change",
                                message = preview.take(6).joinToString("\n") { (a, b) -> "$a  ->  $b" },
                                options = listOf(
                                    MenuAction("apply", "Apply", FuseIcons.Check, onSelect = {
                                        app.choice = null
                                        app.scope.launch { app.store.library.applyCleanNames(true); app.toasts.show("Titles cleaned. Undo from Library settings.") }
                                    }),
                                    MenuAction("cancel", "Cancel", FuseIcons.Close, onSelect = { app.choice = null }),
                                ),
                            )
                        }
                    }
                }
            },
        ))
        add(MenuAction("clean.undo", "Undo last name cleanup", FuseIcons.Undo, onSelect = {
            app.scope.launch { if (app.store.library.undoCleanNames()) app.toasts.show("Titles restored") else app.toasts.show("Nothing to undo") }
        }))
    }
}

@Composable
fun systemsRows(app: AppState): List<MenuAction> {
    val platforms by app.store.library.platforms.collectAsState()
    val prefs by app.store.prefs.collectAsState()
    val systems = platforms.filter { it.gameCount > 0 }
    val artProgress by app.store.media.systemArtProgress.collectAsState()
    return buildList {
        add(toggleRow("art.auto", "System art", FuseIcons.Image, prefs.systemArtAuto, "Logos, artwork and colours for each system from the Art Book Next pack, downloaded when a system has none") { v ->
            app.store.updatePrefs { it.copy(systemArtAuto = v) }
        })
        add(app.choiceRow(
            "art.style", "System art style", FuseIcons.Palette, prefs.systemArtStyle,
            SystemArtStyle.entries.map { it.name to it.displayName },
            detail = "Used the next time system art is downloaded",
        ) { v ->
            app.store.updatePrefs { it.copy(systemArtStyle = v) }
            app.confirm = ConfirmSpec("Download in this style now?", "Fuse downloads art for every system again in the new style. Art you chose yourself stays.", "Download") {
                app.store.media.downloadSystemArt()
                app.toasts.show("Downloading system art")
            }
        })
        val progress = artProgress
        add(MenuAction(
            "art.all", "Download system art for all systems", FuseIcons.CloudDownload,
            detail = when {
                progress == null -> "Fetches every system again in the chosen style. Art you chose yourself stays"
                !progress.finished -> "Working: ${progress.current ?: ""} (${progress.done + 1} of ${progress.total})"
                else -> "Done: ${progress.added} images for ${progress.total} systems"
            },
            onSelect = {
                app.store.media.downloadSystemArt()
                app.toasts.show("Downloading system art")
            },
        ))
        add(infoRow("art.credit", "Art Book Next", detail = SystemArtPack.ATTRIBUTION, icon = FuseIcons.Info))
        add(infoRow("order", "Arrange systems", detail = "Hold confirm on a system in Systems or on Home, then move it with the D-pad. The order is used everywhere"))
        if (prefs.systemOrder.isNotEmpty()) {
            add(MenuAction("order.reset", "Reset system order", FuseIcons.RotateCcw, detail = "Back to the order Fuse uses by default", onSelect = {
                app.store.updatePrefs { it.copy(systemOrder = emptyList()) }
                app.toasts.show("System order reset")
            }))
        }
        systems.forEach { p ->
            add(MenuAction(
                "sys.${p.platform.id}", p.platform.name, FuseIcons.Chip,
                detail = listOfNotNull("${p.gameCount} games", p.emulatorName ?: "No emulator").joinToString("  ·  "),
                trailing = Trailing.Chevron,
                onSelect = { app.go(Route.PlatformSettings(p.platform.id)) },
            ))
        }
        if (systems.isEmpty()) add(infoRow("none", "No systems yet", detail = "Systems appear once Fuse finds games for them"))
    }
}

@Composable
fun emulatorRows(app: AppState): List<MenuAction> {
    val installed by app.store.emulators.installed.collectAsState()
    return buildList {
        add(MenuAction("refresh", "Look for emulators again", FuseIcons.Refresh, detail = "Fuse also notices installs and removals on its own", onSelect = {
            app.store.emulators.refresh(); app.toasts.show("Looking for emulators")
        }))
        if (installed.isEmpty()) add(infoRow("none", "No emulators found", detail = "Install an emulator for a system and it appears here"))
        for (e in installed.sortedBy { it.name.lowercase() }) {
            val limits = app.store.emulators.limitations(e.id)
            add(MenuAction(
                "emu.${e.id}", e.name + (e.version?.let { "  $it" } ?: ""), FuseIcons.Joystick,
                detail = buildString {
                    append(e.platforms.joinToString(", ") { it.value.uppercase() })
                    append("  ·  found via ${e.detectedVia}")
                    if (e.isFamilyMatch) append("  ·  recognised as a variant")
                    if (limits.isNotEmpty()) append("\n" + limits.joinToString("\n"))
                },
                trailing = Trailing.Chevron,
                onSelect = { app.scope.launch { app.store.emulators.openEmulator(e.id) } },
            ))
        }
    }
}

@Composable
fun mediaRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val providers by app.store.media.providers.collectAsState()
    val checks by app.store.media.keyChecks.collectAsState()
    val stored by app.store.credentials.stored.collectAsState()
    val set = app.store::updatePrefs
    fun secretRow(key: String, label: String, detail: String) = app.textRow(
        "key.$key", label, FuseIcons.Key, if (key in stored) "Saved" else null, detail = detail, placeholder = "Paste or type",
    ) { v -> app.scope.launch { if (v.isBlank()) app.store.credentials.remove(key) else app.store.credentials.put(key, v) } }
    return buildList {
        add(MenuAction("order", "Source order", FuseIcons.Layers, detail = p.scraperOrder.joinToString("  ·  ") { it.displayName }, trailing = Trailing.Chevron, onSelect = {
            app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                title = "Move a source earlier",
                message = "Fuse asks sources in this order. RomM data from Cartridge comes first so nothing is scraped twice.",
                options = p.scraperOrder.drop(1).map { id ->
                    MenuAction("mv.$id", id.displayName, null, onSelect = {
                        set { s ->
                            val l = s.scraperOrder.toMutableList(); val i = l.indexOf(id)
                            if (i > 0) { l.removeAt(i); l.add(i - 1, id) }
                            s.copy(scraperOrder = l)
                        }
                        app.choice = null
                    })
                },
            )
        }))
        for (s in providers) {
            // Providers with a key show the result of the last real test, not just "a key is saved".
            val tested = s.id in checks
            val check = checks[s.id]
            val (value, icon, detail) = when {
                !s.configured -> Triple("Needs setup", FuseIcons.Alert, s.note)
                tested && check == null -> Triple("Checking", FuseIcons.Hourglass, "Testing the key with a real request")
                check is KeyCheck.Working -> Triple("Working", FuseIcons.CircleCheck, s.note ?: "The key was accepted")
                check is KeyCheck.Rejected -> Triple("Key rejected", FuseIcons.CircleX, check.reason)
                check is KeyCheck.Unreachable -> Triple("Offline", FuseIcons.WifiOff, "Couldn't reach it to test the key: ${check.reason}")
                check is KeyCheck.Failed -> Triple("Not confirmed", FuseIcons.Warning, check.reason)
                else -> Triple("Ready", FuseIcons.CircleCheck, s.note)
            }
            add(infoRow("prov.${s.id}", s.id.displayName, value, detail = detail, icon = icon))
        }
        if (checks.isNotEmpty() || stored.any { it.startsWith("sgdb.") || it.startsWith("igdb.") || it.startsWith("tgdb.") }) {
            add(MenuAction("test", "Test keys", FuseIcons.ShieldCheck, detail = "Checks every key with a real request and shows the result above", onSelect = {
                app.store.media.checkKeys()
                app.toasts.show("Testing keys")
            }))
        }
        add(secretRow("sgdb.apikey", "SteamGridDB API key", "Free from steamgriddb.com, Preferences, API. Stored encrypted on this device"))
        add(secretRow("igdb.clientId", "IGDB Client ID", "From your own Twitch developer app. IGDB doesn't allow apps to share one"))
        add(secretRow("igdb.clientSecret", "IGDB Client Secret", "Stored encrypted; never shown again"))
        add(secretRow("tgdb.apikey", "TheGamesDB API key", "Requested on the TheGamesDB forum"))
        add(secretRow("ss.user", "ScreenScraper username", "Optional. Your account raises your request limits"))
        add(secretRow("ss.password", "ScreenScraper password", "Stored encrypted"))
        add(app.choiceRow("lang", "Preferred language", FuseIcons.Globe, p.scraperLanguage, listOf("en" to "English", "fr" to "French", "de" to "German", "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese", "ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean")) { v -> set { it.copy(scraperLanguage = v) } })
        add(app.choiceRow("region", "Preferred region", FuseIcons.Map, p.scraperRegion, listOf("any" to "Any region", "us" to "USA", "eu" to "Europe", "jp" to "Japan", "wor" to "World")) { v -> set { it.copy(scraperRegion = v) } })
        add(app.choiceRow(
            "match", "Matching", FuseIcons.Target, p.matching,
            listOf(MatchStrictness.EXACT to "Exact", MatchStrictness.NORMAL to "Normal", MatchStrictness.AGGRESSIVE to "Aggressive"),
            detail = "How sure Fuse must be before it uses a match without asking",
            optionDetail = {
                when (it) {
                    MatchStrictness.EXACT -> "Only identical names on the same system"
                    MatchStrictness.NORMAL -> "Clear best matches; anything close is shown to you"
                    MatchStrictness.AGGRESSIVE -> "Accepts looser matches. Can pick the wrong game"
                }
            },
        ) { v ->
            if (v == MatchStrictness.AGGRESSIVE) {
                app.confirm = ConfirmSpec("Use aggressive matching?", "Fuse will accept looser matches without asking, so some games may get the wrong art or details. Custom art is never replaced.", "Use aggressive") {
                    set { it.copy(matching = v) }
                }
            } else set { it.copy(matching = v) }
        })
        add(MenuAction("fill", "Fill missing art", FuseIcons.Wand, detail = "Every game without an icon, cover, background or logo. Custom art is never replaced", onSelect = {
            app.store.media.fill(MediaFillMode.FILL_MISSING, setOf(MediaKind.ICON, MediaKind.BOXART, MediaKind.HERO, MediaKind.LOGO, MediaKind.GRID))
            app.toasts.show("Looking for missing art in the background")
        }))
        add(app.confirmRow("replace", "Replace all scraped art", FuseIcons.RotateCcw, "Replace scraped art?", "Fuse fetches art again for every game and replaces art it scraped before. Art you chose yourself stays.", "Replace") {
            app.store.media.fill(MediaFillMode.REPLACE_ALL, setOf(MediaKind.ICON, MediaKind.BOXART, MediaKind.HERO, MediaKind.LOGO, MediaKind.GRID))
        })
        add(toggleRow("video", "Video previews", FuseIcons.Film, p.videoPreview, if (app.platform.features.videoPreview) "After resting on a game, its art turns into a muted gameplay clip" else "Not available on this system yet", enabled = app.platform.features.videoPreview) { v -> set { it.copy(videoPreview = v) } })
        add(app.choiceRow("video.delay", "Preview delay", FuseIcons.Timer, p.videoDelaySeconds, listOf(5, 10, 15, 20, 30).map { it to "$it seconds" }) { v -> set { it.copy(videoDelaySeconds = v) } })
    }
}

@Composable
fun achievementRows(app: AppState): List<MenuAction> {
    val configured by app.store.achievements.configured.collectAsState()
    val feed by app.store.achievements.feed.collectAsState()
    return buildList {
        if (configured) {
            val u = feed?.user
            add(infoRow("user", u?.username ?: "Connected", u?.let { "${it.points} points" }, detail = u?.motto, icon = FuseIcons.User))
            add(MenuAction("refresh", "Refresh now", FuseIcons.Refresh, detail = "Fuse refreshes on its own and caches results", onSelect = { app.store.achievements.refresh(force = true) }))
            add(app.confirmRow("disconnect", "Disconnect", FuseIcons.Unplug, "Disconnect RetroAchievements?", "Your username and key are deleted from this device.", "Disconnect", destructive = true) {
                app.scope.launch { app.store.achievements.disconnect() }
            })
        } else {
            add(MenuAction("connect", "Connect RetroAchievements", FuseIcons.Trophy, detail = "Your username and Web API key (retroachievements.org, Settings, Keys). The key is stored encrypted", trailing = Trailing.Chevron, onSelect = {
                app.textInput = io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec("RetroAchievements username", "") { user ->
                    app.textInput = io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec("Web API key", "", "Paste your key") { key ->
                        app.scope.launch {
                            app.store.achievements.connect(user.trim(), key.trim())
                                .onSuccess { app.toasts.show("Connected as ${user.trim()}") }
                                .onFailure { app.toasts.show(it.message ?: "Couldn't connect") }
                        }
                    }
                }
            }))
        }
        add(infoRow("note", "Fuse shows achievements; emulators unlock them", detail = "Sign in to RetroAchievements inside your emulator (RetroArch, DuckStation, PPSSPP and others) for unlocks to count"))
    }
}

@Composable
fun cartridgeRows(app: AppState): List<MenuAction> {
    val s by app.store.cartridge.status.collectAsState()
    val p by app.store.prefs.collectAsState()
    return buildList {
        add(toggleRow(
            "enabled", "Cartridge support", FuseIcons.Plug, p.cartridgeEnabled,
            if (p.cartridgeEnabled) "The Cartridge tab, downloads, menu entries and status" else "Off: Fuse leaves Cartridge alone",
        ) { v -> app.store.updatePrefs { it.copy(cartridgeEnabled = v) } })
        if (!p.cartridgeEnabled) return@buildList
        add(infoRow("status", "Cartridge", if (!s.installed) "Not installed" else s.version ?: "Installed", icon = FuseIcons.CloudDownload,
            detail = when {
                !s.installed -> "Install it from the Cartridge tab"
                !s.bridge -> "Update to 0.9.10 or newer for direct links and live status"
                else -> "Linked. Downloads appear in Fuse automatically"
            }))
        if (s.installed) add(MenuAction("open", "Open Cartridge", FuseIcons.External, onSelect = { app.store.cartridge.open(CartridgeRoute.Home) }))
        add(toggleRow("auto", "Pick up new downloads on return", FuseIcons.Refresh, p.autoRefreshFromCartridge, "Rescans the folders Cartridge saved to when you come back") { v -> app.store.updatePrefs { it.copy(autoRefreshFromCartridge = v) } })
    }
}

/** Menu music and interface sounds, each with its own volume. */
@Composable
fun soundRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val music = p.music
    fun setMusic(change: (MusicPrefs) -> MusicPrefs) = app.store.updatePrefs { it.copy(music = change(it.music)) }
    return buildList {
        if (app.platform.music == null) {
            add(infoRow("music", "Menu music", "Not available", icon = FuseIcons.Music, detail = "This device can't play music in Fuse"))
        } else {
            add(toggleRow("music", "Menu music", FuseIcons.Music, music.enabled, if (music.songPath == null) "Choose a song below. Fuse comes without one" else "Plays in Fuse's menus and stops for games") { v -> setMusic { it.copy(enabled = v) } })
            add(app.percentRow("musicvolume", "Music volume", FuseIcons.Volume, music.volume, "Low sits nicely under the interface") { v -> setMusic { it.copy(volume = v) } })
            add(
                MenuAction(
                    "song", "Song", FuseIcons.Disc,
                    detail = "An audio file on this device, such as MP3. Fuse keeps its own copy",
                    trailing = Trailing.Value(music.songName ?: "None"),
                    onSelect = {
                        app.choice = ChoiceSpec(
                            title = "Menu music",
                            message = music.songName?.let { "Now playing: $it" } ?: "Pick a song from this device. It loops quietly in Fuse's menus.",
                            options = listOfNotNull(
                                MenuAction("pick", "Choose a file", FuseIcons.FolderOpen, detail = "MP3 works everywhere", onSelect = {
                                    app.choice = null
                                    app.scope.launch {
                                        val picked = app.platform.storage.pickAudio("Choose menu music") ?: return@launch
                                        setMusic { it.copy(enabled = true, songPath = picked.path, songName = picked.name) }
                                        app.toasts.show("Menu music: ${picked.name}")
                                    }
                                }),
                                if (music.songPath != null) MenuAction("none", "No song", FuseIcons.VolumeOff, onSelect = {
                                    app.choice = null
                                    setMusic { it.copy(songPath = null, songName = null) }
                                }) else null,
                            ),
                        )
                    },
                ),
            )
        }
        add(app.choiceRow("sound", "Interface sounds", FuseIcons.Bell, p.sound, listOf(SoundProfile.OFF to "Off", SoundProfile.SOFT to "Soft", SoundProfile.CLICK to "Crisp", SoundProfile.CHIME to "Chime")) { v -> app.store.updatePrefs { it.copy(sound = v) } })
        add(app.percentRow("volume", "Sound effects volume", FuseIcons.Volume, p.soundVolume, "Moving, confirming and going back") { v -> app.store.updatePrefs { it.copy(soundVolume = v) } })
    }
}

@Composable
fun inputRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val i = p.input
    fun setInput(t: (io.github.matiyaaa.fuse.model.InputProfile) -> io.github.matiyaaa.fuse.model.InputProfile) = app.store.updatePrefs { it.copy(input = t(it.input)) }
    return listOf(
        MenuAction("detect", "Detect my buttons", FuseIcons.ScanSearch, detail = "Press two buttons and Fuse sets the layout and confirm button for you", onSelect = { app.buttonDetect = true }),
        app.choiceRow(
            "layout", "Button layout", FuseIcons.Gamepad, i.glyphs,
            listOf(GlyphStyle.XBOX to "Xbox (A at the bottom)", GlyphStyle.NINTENDO to "Nintendo (A on the right)", GlyphStyle.PLAYSTATION to "PlayStation (shapes)"),
            detail = "The letters printed on your buttons. Hints use them",
        ) { v -> setInput { it.copy(glyphs = v) } },
        toggleRow("swap", "Swap confirm and back", FuseIcons.MoveHorizontal, i.swapConfirmBack, if (i.confirmOnRight) "Now: confirm is the right button" else "Now: confirm is the bottom button") { v -> setInput { it.copy(swapConfirmBack = v) } },
        toggleRow("keyhints", "Keyboard hints when typing", FuseIcons.Keyboard, i.autoGlyphs, "Show keyboard keys in hints after a keyboard key is used") { v -> setInput { it.copy(autoGlyphs = v) } },
        app.choiceRow("delay", "Repeat delay", FuseIcons.Timer, i.repeatDelayMs, listOf(180, 220, 280, 350, 450).map { it to "$it ms" }, detail = "How long a held direction waits before repeating") { v -> setInput { it.copy(repeatDelayMs = v) } },
        app.choiceRow("speed", "Repeat speed", FuseIcons.Zap, i.repeatIntervalMs, listOf(40 to "Fastest", 55 to "Fast", 70 to "Normal", 100 to "Relaxed", 140 to "Slow")) { v -> setInput { it.copy(repeatIntervalMs = v) } },
        toggleRow("accel", "Speed up while held", FuseIcons.Rocket, i.repeatAccelerate) { v -> setInput { it.copy(repeatAccelerate = v) } },
        app.percentRow("deadzone", "Stick deadzone", FuseIcons.Target, i.stickDeadzone) { v -> setInput { it.copy(stickDeadzone = v.coerceIn(0.05f, 0.6f)) } },
        app.percentRow("threshold", "Stick push to move", FuseIcons.Crosshair, i.navigationThreshold) { v -> setInput { it.copy(navigationThreshold = v.coerceIn(0.2f, 0.95f)) } },
        app.choiceRow("long", "Hold time", FuseIcons.Hand, i.longPressMs, listOf(400, 550, 700, 900).map { it to "$it ms" }, detail = "Holding confirm (or a long touch) arranges Home and opens options") { v -> setInput { it.copy(longPressMs = v) } },
        app.percentRow("vibration", "Vibration", FuseIcons.Vibrate, i.vibration) { v -> setInput { it.copy(vibration = v) } },
        MenuAction("mapping", "Button mapping and test", FuseIcons.Joystick, detail = if (i.remap.isEmpty()) "Standard layout. See what Fuse receives from each button" else "${i.remap.size} custom mappings", trailing = Trailing.Chevron, onSelect = { app.go(Route.Controls) }),
    )
}

@Composable
fun displayRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val displays by app.platform.displays.collectAsState()
    val log by app.platform.secondScreenLog.collectAsState()
    val d = p.display
    return buildList {
        app.platform.windowControls?.let { w ->
            add(app.choiceRow(
                "window", "Window", FuseIcons.Monitor, w.mode,
                listOf(WindowStyle.FULLSCREEN to "Full screen", WindowStyle.BORDERLESS to "Borderless", WindowStyle.WINDOWED to "Window"),
                detail = "F11 switches full screen at any time",
            ) { v -> w.setMode(v) })
            add(autostartRow(app, w))
        }
        if (!app.platform.features.secondScreen) {
            add(infoRow("none", "One screen", detail = "Second-screen options appear when a second display is connected"))
        }
        add(app.choiceRow(
            "mode", "Second screen", FuseIcons.DualScreen, d.mode,
            listOf(DualScreenMode.OFF to "Off", DualScreenMode.LIBRARY_COMPANION to "Show the selected game", DualScreenMode.GAME_COMPANION to "Companion while playing", DualScreenMode.REVERSE to "Play on the second screen"),
            optionDetail = {
                when (it) {
                    DualScreenMode.OFF -> "Leave the second screen alone"
                    DualScreenMode.LIBRARY_COMPANION -> "Art, logo and details of what you've selected"
                    DualScreenMode.GAME_COMPANION -> "Clock, battery, playtime and achievements beside the game"
                    DualScreenMode.REVERSE -> "Games open on the second screen when the emulator allows it"
                }
            },
        ) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(mode = v)) } })
        if (app.platform.features.secondScreen) {
            add(infoRow(
                "dual", "Games with two screens", icon = FuseIcons.DualScreen,
                detail = "DS, DSi, 3DS and Wii U games get the second screen: the companion steps aside while they run and comes back with Fuse. With Fuse as your Home app, Fuse is also the second screen's Home",
            ))
            add(MenuAction(
                "dual.log", "Second screen status", FuseIcons.Activity,
                detail = log.lastOrNull() ?: "Nothing has happened on the second screen yet",
                trailing = Trailing.Chevron,
                onSelect = {
                    app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                        title = "Second screen status",
                        message = log.takeLast(8).joinToString("\n").ifEmpty { "Nothing has happened on the second screen yet." },
                        options = listOf(MenuAction("dual.ok", "Close", FuseIcons.Check, onSelect = { app.choice = null })),
                    )
                },
            ))
        }
        add(toggleRow("perf", "Show performance on the second screen", FuseIcons.Activity, d.companionShowsPerformance, "Only values the system really reports; nothing is estimated") { v -> app.store.updatePrefs { it.copy(display = it.display.copy(companionShowsPerformance = v)) } })
        add(toggleRow("touch", "Touch controls on the second screen", FuseIcons.Hand, d.companionTouchControls) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(companionTouchControls = v)) } })
        for (disp in displays) {
            add(infoRow(
                "disp.${disp.id}", disp.name + if (disp.isPrimary) " (main)" else "",
                "${disp.widthPx}x${disp.heightPx}  ${disp.refreshRate.toInt()} Hz",
                detail = when (disp.canLaunchActivities) {
                    Support.YES -> "Games can be opened here"
                    Support.NO -> "This display can't run other apps"
                    Support.UNKNOWN -> if (disp.isPrimary) null else "Fuse checks when you first launch a game here"
                },
                icon = FuseIcons.Monitor,
            ))
        }
    }
}

@Composable
fun performanceRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val cap = app.platform.device
    return listOf(
        app.choiceRow(
            "profile", "Performance profile", FuseIcons.Gauge, p.performance,
            listOf(PerformanceProfile.AUTOMATIC to "Automatic", PerformanceProfile.LOW_POWER to "Low power", PerformanceProfile.BALANCED to "Balanced", PerformanceProfile.HIGH_QUALITY to "High quality"),
            detail = "Now: " + performanceSummary(p.performance, p.lowPower, cap, app.platform.host) +
                "\nAutomatic picks for this device: ${cap.tier.name.lowercase().replaceFirstChar { it.uppercase() }}",
        ) { v -> app.store.updatePrefs { it.copy(performance = v) } },
        toggleRow("low", "Low Power Mode", FuseIcons.Leaf, p.lowPower, "60 Hz, no video previews, blur, moving backgrounds or CRT; lighter artwork and a smaller image cache. Navigation stays quick") { v -> app.store.updatePrefs { it.copy(lowPower = v) } },
        toggleRow("overlay", "Performance overlay", FuseIcons.Activity, p.performanceOverlay, "Fuse's own frame rate, memory and temperatures, only as the system reports them. Other apps' frame rates can't be read") { v -> app.store.updatePrefs { it.copy(performanceOverlay = v) } },
        infoRow("cpu", "Processor", "${cap.cpuCores} cores", icon = FuseIcons.Chip),
        infoRow("ram", "Memory", "${(cap.totalRamMb / 1024.0 * 10).toInt() / 10.0} GB", icon = FuseIcons.Memory),
        infoRow("screen", "Screen", "${cap.screenWidthPx}x${cap.screenHeightPx}, up to ${cap.maxRefreshRate.toInt()} Hz", icon = FuseIcons.Monitor),
    )
}

@Composable
fun networkRows(app: AppState): List<MenuAction> = buildList {
    if (app.platform.features.wifiSettings) add(MenuAction("wifi", "Wi-Fi settings", FuseIcons.Wifi, trailing = Trailing.Chevron, onSelect = { app.platform.quick.openWifi() }))
    add(infoRow("where", "What Fuse connects to", detail = "Only services you set up: RetroAchievements, SteamGridDB, IGDB, TheGamesDB, ScreenScraper, libretro thumbnails, and GitHub to check for updates. Your library works fully offline", icon = FuseIcons.Globe))
}

@Composable
fun storageRows(app: AppState): List<MenuAction> {
    val storage by app.platform.storage.state.collectAsState()
    return buildList {
        add(MenuAction(
            "access", "File access", FuseIcons.HardDrive,
            detail = when (storage) {
                StorageState.GRANTED -> "Fuse can read your game folders"
                StorageState.LIMITED -> "Only folders you picked"
                StorageState.DENIED -> "Needed to read your games and hand them to emulators"
                StorageState.NOT_NEEDED -> "Nothing to allow on this system"
            },
            trailing = Trailing.Value(if (storage == StorageState.GRANTED || storage == StorageState.NOT_NEEDED) "Allowed" else "Allow"),
            onSelect = { app.platform.storage.request() },
        ))
        add(infoRow("readonly", "Fuse never changes your games", detail = "It only reads your folders. Nothing is moved, renamed or deleted, and playlists for multi-disc games are made in Fuse's own storage", icon = FuseIcons.ShieldCheck))
    }
}

@Composable
fun privacyRows(app: AppState): List<MenuAction> = listOf(
    infoRow("telemetry", "No telemetry", detail = "Fuse sends no analytics, crash reports or usage data", icon = FuseIcons.ShieldCheck),
    infoRow("uploads", "Nothing about your library leaves this device", detail = "Game names are only sent to the art and metadata sources you turn on, when looking up that game", icon = FuseIcons.Lock),
    infoRow("keys", "Keys are stored encrypted", detail = "API keys and passwords never appear in logs and are never shared with other apps", icon = FuseIcons.Key),
    app.confirmRow("wipe", "Delete all saved keys", FuseIcons.Trash, "Delete all saved keys?", "RetroAchievements and scraper keys are removed from this device.", "Delete keys", destructive = true) {
        app.scope.launch {
            for (k in app.store.credentials.stored.value) app.store.credentials.remove(k)
            app.store.achievements.disconnect()
        }
    },
)

@Composable
fun updateRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val available by app.store.updates.available.collectAsState()
    val state by app.store.updates.state.collectAsState()
    return buildList {
        add(infoRow("version", "Fuse", app.store.updates.currentVersion, icon = FuseIcons.Info))
        val r = available
        if (r != null) {
            val size = GitHubReleasesSize.of(r, app)
            when (val st = state) {
                is UpdateState.Downloading -> add(MenuAction(
                    "download", "Downloading ${r.name}", FuseIcons.Download,
                    detail = "You can keep using Fuse. Select to stop",
                    trailing = Trailing.Value(st.progress?.let { "${(it * 100).toInt()}%" } ?: "Starting"),
                    onSelect = { app.store.updates.cancelDownload() },
                ))
                is UpdateState.Ready, is UpdateState.Installing -> add(MenuAction(
                    "apply", "Restart and update", FuseIcons.Refresh,
                    detail = if (app.platform.host == io.github.matiyaaa.fuse.model.Host.ANDROID) "Android asks you to confirm, then Fuse starts again in ${r.name}" else "Fuse closes and starts again in ${r.name}",
                    trailing = Trailing.Value(if (st is UpdateState.Installing) "Installing" else "Ready"),
                    onSelect = { app.applyUpdate() },
                ))
                is UpdateState.Failed -> add(MenuAction(
                    "download", "Download ${r.name} again", FuseIcons.Download, detail = st.message,
                    onSelect = { app.store.updates.download(r) },
                ))
                UpdateState.Idle -> add(MenuAction(
                    "download", "Download ${r.name}", FuseIcons.Download,
                    detail = listOfNotNull(size, "Checked against the checksum GitHub publishes. Nothing installs until you choose Restart and update").joinToString(". "),
                    onSelect = { app.store.updates.download(r) },
                ))
            }
            if (r.notes.isNotBlank()) add(MenuAction("notes", "What's new in ${r.name}", FuseIcons.Sparkles, trailing = Trailing.Chevron, onSelect = {
                app.choice = ChoiceSpec(r.name, r.notes.lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim().take(1600), listOf(
                    MenuAction("ok", "Close", FuseIcons.Check, onSelect = { app.choice = null }),
                ))
            }))
        }
        add(MenuAction("check", "Check for updates", FuseIcons.Refresh, onSelect = {
            app.scope.launch { app.toasts.show(if (app.store.updates.check() != null) "An update is available" else "Fuse is up to date") }
        }))
        add(toggleRow("auto", "Check automatically", FuseIcons.Bell, p.checkForUpdates, "Once a day from GitHub Releases; nothing installs without you") { v -> app.store.updatePrefs { it.copy(checkForUpdates = v) } })
    }
}

@Composable
fun aboutRows(app: AppState): List<MenuAction> = listOfNotNull(
    app.platform.lastCrashReport()?.let { report -> crashRow(app, report) },
    infoRow("fuse", "Fuse ${app.store.updates.currentVersion}", detail = "A console-style home for your games. Free and open source (GPL-3.0-or-later)", icon = FuseIcons.Info),
    MenuAction("source", "Source code", FuseIcons.External, detail = "github.com/MAtiyaaa/fuse", onSelect = { app.platform.openUrl("https://github.com/MAtiyaaa/fuse") }),
    MenuAction("licences", "Open-source licences", FuseIcons.File, detail = "Fuse, its libraries, fonts and icons", trailing = Trailing.Chevron, onSelect = { app.go(Route.Licenses) }),
    MenuAction("setup", "Run setup again", FuseIcons.Sparkles, trailing = Trailing.Chevron, onSelect = { app.go(Route.Onboarding) }),
    infoRow("credits", "Made with", detail = "Kotlin, Compose Multiplatform, SQLDelight, Ktor, Coil. Icons: Lucide (ISC). Fonts: Sora and Manrope (SIL OFL). Emulator launch data: ES-DE (MIT) and Cartridge (MIT). Hashing rules: rcheevos (MIT)", icon = FuseIcons.Blocks),
    infoRow("trademarks", "Trademarks", detail = "Console and game names belong to their owners. Fuse ships no console artwork, sounds, BIOS or games", icon = FuseIcons.Tag),
)

@Composable
private fun autostartRow(app: AppState, w: WindowControls): MenuAction {
    var on by remember { mutableStateOf(w.isAutostart()) }
    return toggleRow(
        "autostart", "Start Fuse when you log in", FuseIcons.Power, on,
        if (w.autostartAvailable) "Adds Fuse to your desktop's startup applications" else "Available when Fuse runs from its AppImage or an installed package",
        enabled = w.autostartAvailable,
    ) { v ->
        w.setAutostart(v)
            .onSuccess { on = v }
            .onFailure { app.toasts.show(it.message ?: "Couldn't change the startup setting") }
    }
}

/** The last crash Fuse recorded: what happened, where, and a way to clear it once it's been read. */
private fun crashRow(app: AppState, report: String): MenuAction {
    val lines = report.lines().map { it.trim() }.filter { it.isNotEmpty() }
    // The exception line and the first frames that are Fuse's own say most.
    val cause = lines.firstOrNull { it.contains("Exception") || it.contains("Error") } ?: lines.firstOrNull().orEmpty()
    val frames = lines.filter { it.startsWith("at io.github.matiyaaa.fuse") }.take(4)
    val summary = (listOfNotNull(lines.firstOrNull { it.startsWith("Time:") }, cause) + frames).joinToString("\n")
    return MenuAction("crash", "Last crash report", FuseIcons.Warning, detail = cause.take(120), trailing = Trailing.Chevron, onSelect = {
        app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
            title = "Last crash report",
            message = summary,
            options = listOf(
                MenuAction("crash.clear", "Clear report", FuseIcons.Trash, detail = "Include this in a bug report first if you can", onSelect = {
                    app.platform.clearCrashReport()
                    app.choice = null
                    app.toasts.show("Crash report cleared")
                }),
                MenuAction("crash.keep", "Keep", FuseIcons.Check, onSelect = { app.choice = null }),
            ),
        )
    })
}

/** The download size of the update for this device, for the Updates row. */
private object GitHubReleasesSize {
    fun of(release: io.github.matiyaaa.fuse.model.ReleaseInfo, app: AppState): String? {
        val asset = release.assets.firstOrNull { a ->
            val n = a.name.lowercase()
            if (app.platform.host == io.github.matiyaaa.fuse.model.Host.ANDROID) n.endsWith(".apk") else n.endsWith(".appimage")
        } ?: return null
        if (asset.sizeBytes <= 0) return null
        return "${(asset.sizeBytes / 1_000_000.0).let { (it * 10).toInt() / 10.0 }} MB"
    }
}
