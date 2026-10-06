package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.ui.shell.systems.restoreSystemArt
import io.github.matiyaaa.fuse.ui.shell.systems.undoSystemArt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.integrations.KeyCheck
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtPack
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtStyle
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CrtSettings
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.GameArtStyle
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScreenRotation
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.Support
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.isRow
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ReorderEntry
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ReorderSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.addGame
import io.github.matiyaaa.fuse.ui.shell.app.applyUpdate
import io.github.matiyaaa.fuse.ui.shell.app.emulatorFoldersPicker
import io.github.matiyaaa.fuse.ui.shell.app.hasTwoScreens
import io.github.matiyaaa.fuse.ui.shell.app.locatePicker
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.offersToAdd
import io.github.matiyaaa.fuse.ui.shell.app.openStore
import io.github.matiyaaa.fuse.ui.shell.app.screenName
import io.github.matiyaaa.fuse.ui.shell.app.sections
import io.github.matiyaaa.fuse.ui.shell.app.showEmulator
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.home.title
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import io.github.matiyaaa.fuse.ui.shell.notes.openInstalledNotes
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.platform.WindowControls
import io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle
import io.github.matiyaaa.fuse.ui.shell.store.FillChoice
import io.github.matiyaaa.fuse.ui.shell.store.FillProgress
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import kotlinx.coroutines.launch

/** System art styles: the pack's sets, and panels from the system's own games. */
private val systemArtStyles: List<Pair<String, String>> = SystemArtStyle.entries.map { it.name to it.displayName } + ("GAMES" to "From your games")

private fun motionName(m: MotionProfile?) = when (m) {
    null -> "Automatic"
    MotionProfile.REDUCED -> "Reduced"
    MotionProfile.MINIMAL -> "Minimal"
    MotionProfile.STANDARD -> "Standard"
    MotionProfile.ENHANCED -> "Enhanced"
}

private fun layoutName(l: LibraryLayout) = when (l) {
    LibraryLayout.ICON -> "Grid"
    LibraryLayout.CAPSULE -> "Capsules"
    LibraryLayout.COVER_GRID -> "Cover grid"
    LibraryLayout.COMPACT_LIST -> "List"
}

@Composable
fun appearanceRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val set = app.store::updatePrefs
    return buildList {
        // No label: the theme card above heads these two.
        add(MenuAction(
            "theme", "Theme", FuseIcons.SwatchBook,
            detail = "Built-in themes and ones you add from a link or a file",
            trailing = Trailing.Value(p.theme.name),
            onSelect = { app.go(Route.Themes) },
        ))
        add(app.choiceRow(
            "art", "Game art", FuseIcons.GalleryThumbnails, p.gameArt,
            listOf(GameArtStyle.BOX_ART to "Box art", GameArtStyle.POSTER to "Posters"),
            detail = "How game tiles look on Home, in the Library and in Cartridge",
            optionDetail = {
                when (it) {
                    GameArtStyle.BOX_ART -> "Square art, as Fuse has always shown"
                    GameArtStyle.POSTER -> "Tall cover art, like a shelf of cases"
                }
            },
        ) { v -> set { it.copy(gameArt = v) } })
        labelled("Selected game") {
            add(toggleRow("hero", "Background art", FuseIcons.Image, p.showHero, "The selected game's art lights the room") { v -> set { it.copy(showHero = v) } })
            add(toggleRow("logo", "Title logos", FuseIcons.Type, p.showLogo, "Show logo art instead of the written title when a game has one") { v -> set { it.copy(showLogo = v) } })
            add(app.percentRow("dim", "Background dimming", FuseIcons.SunDim, p.heroDim, "Darker keeps text readable over bright art") { v -> set { it.copy(heroDim = v) } })
        }
        add(toggleRow("intro", "Startup animation", FuseIcons.Sparkles, p.startupAnimation, "Fuse's mark lights up when Fuse starts. Any button skips it") { v -> set { it.copy(startupAnimation = v) } })
        labelled("Effects") {
            add(toggleRow("glass", "Glass panels", FuseIcons.Layers, p.glass.enabled, "Frosted, translucent menus") { v -> set { it.copy(glass = it.glass.copy(enabled = v)) } })
            if (p.glass.enabled) {
                val g = p.glass
                val d = GlassSettings()
                val custom = g.surfaceOpacity != d.surfaceOpacity || g.blur != d.blur || g.heroBrightness != d.heroBrightness || g.gradientStrength != d.gradientStrength
                addAll(app.group("appearance.glass", "Glass tuning", FuseIcons.Sliders, summary = if (custom) "Custom" else "Default", detail = "Opacity, blur and how the art shows through") {
                    buildList {
                        add(app.percentRow("glass.opacity", "Panel opacity", FuseIcons.Droplet, g.surfaceOpacity) { v -> set { it.copy(glass = it.glass.copy(surfaceOpacity = v.coerceAtLeast(0.3f))) } })
                        add(app.choiceRow("glass.blur", "Blur strength", FuseIcons.Aperture, g.blur, listOf(0f, 12f, 24f, 36f, 48f).map { it to if (it == 0f) "Off" else "${it.toInt()}" }) { v -> set { it.copy(glass = it.glass.copy(blur = v)) } })
                        add(app.percentRow("glass.hero", "Background brightness", FuseIcons.Sun, g.heroBrightness) { v -> set { it.copy(glass = it.glass.copy(heroBrightness = v.coerceAtLeast(0.2f))) } })
                        add(app.percentRow("glass.gradient", "Gradient strength", FuseIcons.Blend, g.gradientStrength) { v -> set { it.copy(glass = it.glass.copy(gradientStrength = v)) } })
                        if (custom) add(defaultsRow("glass.reset") {
                            set { it.copy(glass = it.glass.copy(surfaceOpacity = d.surfaceOpacity, blur = d.blur, heroBrightness = d.heroBrightness, gradientStrength = d.gradientStrength)) }
                            app.toasts.show("Glass is back to its defaults")
                        })
                    }
                }.map { it.copy(indent = it.indent + 1) })
            }
            add(toggleRow("crt", "CRT effect", FuseIcons.Tv, p.crt.enabled, "Scanlines and phosphor glow. Turns itself off in Low Power Mode") { v -> set { it.copy(crt = it.crt.copy(enabled = v)) } })
            if (p.crt.enabled) {
                val c = p.crt
                val d = CrtSettings()
                val custom = c.copy(enabled = d.enabled) != d
                addAll(app.group("appearance.crt", "CRT tuning", FuseIcons.Sliders, summary = if (custom) "Custom" else "Default", detail = "Scanlines, curvature, bloom, colour and vignette") {
                    buildList {
                        add(app.percentRow("crt.scan", "Scanlines", FuseIcons.Rows, c.scanlines) { v -> set { it.copy(crt = it.crt.copy(scanlines = v)) } })
                        add(app.percentRow("crt.curve", "Curvature", FuseIcons.Corners, c.curvature) { v -> set { it.copy(crt = it.crt.copy(curvature = v)) } })
                        add(app.percentRow("crt.bloom", "Bloom", FuseIcons.Sparkle, c.bloom) { v -> set { it.copy(crt = it.crt.copy(bloom = v)) } })
                        add(app.percentRow("crt.color", "Colour separation", FuseIcons.Blend, c.chromatic) { v -> set { it.copy(crt = it.crt.copy(chromatic = v)) } })
                        add(app.percentRow("crt.vignette", "Vignette", FuseIcons.Contrast, c.vignette) { v -> set { it.copy(crt = it.crt.copy(vignette = v)) } })
                        if (custom) add(defaultsRow("crt.reset") {
                            set { it.copy(crt = d.copy(enabled = it.crt.enabled)) }
                            app.toasts.show("The CRT effect is back to its defaults")
                        })
                    }
                }.map { it.copy(indent = it.indent + 1) })
            }
        }
    }
}

/** Reading and moving around comfortably: text size, screen edges, motion and a stronger focus. */
@Composable
fun accessibilityRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val set = app.store::updatePrefs
    return buildList {
        labelled("Reading") {
            add(app.choiceRow(
                "textsize", "Text size", FuseIcons.TextSize, p.textScale,
                listOf(1f to "Default", 1.15f to "Large", 1.3f to "Extra large"),
                detail = "Larger text for reading from the sofa. Titles grow a little less",
            ) { v -> set { it.copy(textScale = v) } })
            add(app.choiceRow(
                "margin", "Screen edges", FuseIcons.Maximize, p.screenMargin,
                listOf(0 to "Use all of it", 2 to "2% in", 4 to "4% in", 6 to "6% in"),
                detail = "Keeps everything clear of edges a TV cuts off. The background still fills the screen",
            ) { v -> set { it.copy(screenMargin = v) } })
        }
        labelled("Motion and focus") {
            // Automatic is what this device's effects call for: the recommendation from setup, or the Performance choice.
            val automatic = io.github.matiyaaa.fuse.model.automaticMotion(
                p.theme.motion,
                io.github.matiyaaa.fuse.model.recommendedMotion(p.performance, app.platform.device, p.lowPower),
            )
            add(app.choiceRow(
                "motion", "Motion", FuseIcons.Activity, p.motion,
                listOf(null, MotionProfile.REDUCED, MotionProfile.MINIMAL, MotionProfile.STANDARD, MotionProfile.ENHANCED).map { it to if (it == null) "Automatic (${motionName(automatic)})" else motionName(it) },
                detail = "Automatic matches the effects this device runs. Reduced keeps only short fades: no scaling, sliding or moving backgrounds",
                optionDetail = { m ->
                    when (m) {
                        null -> "Follows this device's effects, from setup's recommendation or Performance"
                        MotionProfile.REDUCED -> "Short fades only"
                        MotionProfile.MINIMAL -> "Quick, light movement. Suits low-power devices"
                        MotionProfile.STANDARD -> "Fuse's full movement"
                        MotionProfile.ENHANCED -> "Richer springs and flourishes, for devices with room to spare"
                    }
                },
            ) { v -> set { it.copy(motion = v) } })
            add(toggleRow("contrast", "High contrast focus", FuseIcons.ScanEye, p.highContrastFocus, "Adds an outline to everything that's selected") { v -> set { it.copy(highContrastFocus = v) } })
        }
    }
}

@Composable
fun homeRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val set = app.store::updatePrefs
    val homeRole = app.platform.homeRole
    val isHome = homeRole?.isHome?.collectAsState()?.value ?: false
    return buildList {
        labelled("Home screen") {
            if (homeRole != null) {
                add(MenuAction(
                    "role", if (isHome) "Fuse is your Home screen" else "Use Fuse as my Home screen", FuseIcons.HomePlus,
                    detail = if (isHome) "The Home button always brings you here" else "Optional. Fuse works the same as a normal app",
                    trailing = Trailing.Switch(isHome),
                    onSelect = { if (isHome) homeRole.openHomeSettings() else homeRole.request() },
                ))
            }
            add(app.choiceRow("mode", "Home style", FuseIcons.Dashboard, p.home.mode, listOf(HomeMode.CHANNELS to "Fused (recommended)", HomeMode.FLOW to "Network"), optionDetail = {
                if (it == HomeMode.FLOW) "A continuous dashboard of shelves" else "A board of tiles you arrange yourself"
            }) { v -> set { it.copy(home = it.home.copy(mode = v)) } })
            add(toggleRow(
                "rememberplace", "Remember where you were", FuseIcons.Bookmark, p.rememberPlace,
                if (p.rememberPlace) "Each tab opens on the game or row you left it on" else "Each tab opens at its start",
            ) { v -> set { it.copy(rememberPlace = v) } })
            add(app.choiceRow(
                "standby", "Standby", FuseIcons.MoonStar, p.standbyMinutes,
                listOf(0 to "Never", 2 to "After 2 minutes", 5 to "After 5 minutes", 10 to "After 10 minutes", 30 to "After 30 minutes"),
                detail = "When nothing is pressed for a while, Fuse dims to a calm screen that moves, so an OLED screen never wears in. Any button or touch wakes it",
            ) { v -> set { it.copy(standbyMinutes = v) } })
        }
        labelled("Top bar") {
            val sections = app.sections
            val offered = Destination.entries.filter { sections.offersTab(it, app.offers(it), p) }
            addAll(app.group(
                "home.tabs", "Sections in the top bar", FuseIcons.PanelTop,
                summary = "${offered.count { it in p.destinations }} of ${offered.size}",
                detail = "Which sections have a tab. Home always does",
            ) {
                offered.map { d ->
                    toggleRow("dest.$d", sections.label(d), sections.icon(d), d in p.destinations) { v ->
                        set { it.copy(destinations = if (v) (it.destinations + d).sortedBy { x -> Destination.entries.indexOf(x) } else it.destinations - d) }
                    }
                }
            })
            val shownTabs = p.destinations.filter { app.offers(it) }
            add(MenuAction("dest.order", "Section order", FuseIcons.MoveHorizontal, detail = shownTabs.joinToString("  ·  ") { sections.label(it) }, trailing = Trailing.Chevron, onSelect = {
                app.reorder = ReorderSpec(
                    title = "Section order",
                    icon = FuseIcons.MoveHorizontal,
                    message = "The order of the top bar. Home always comes first. Drag a row by its grip, or press A to pick it up and move it with the D-pad",
                    entries = shownTabs.map { d -> ReorderEntry(d.name, sections.label(d), sections.icon(d), locked = d == Destination.HOME) },
                    onMoved = { keys ->
                        set { s ->
                            val moved = keys.mapNotNull { k -> Destination.entries.firstOrNull { it.name == k } }
                            // Sections hidden from this list (not offered here) keep their places after the rest.
                            s.copy(destinations = moved + s.destinations.filter { it !in moved })
                        }
                    },
                )
            }))
            if (app.store.apps.supported) {
                add(app.choiceRow(
                    "apps.filter", "Apps opens on", FuseIcons.Smartphone, p.appsFilter,
                    listOf(AppFilter.ALL to "All apps", AppFilter.PINNED to "Pinned", AppFilter.EMULATORS to "Emulators"),
                    detail = "The list the Apps tab shows first. It keeps your place when you come back",
                ) { v -> set { it.copy(appsFilter = v) } })
            }
            add(toggleRow("clock24", "24-hour clock", FuseIcons.Clock, p.clock24h) { v -> set { it.copy(clock24h = v) } })
            add(toggleRow("wifi", "Wi-Fi in the top bar", FuseIcons.Wifi, p.showWifi) { v -> set { it.copy(showWifi = v) } })
            add(toggleRow("bt", "Bluetooth in the top bar", FuseIcons.Bluetooth, p.showBluetooth) { v -> set { it.copy(showBluetooth = v) } })
        }
        if (p.home.mode == HomeMode.FLOW) {
            // Network is rows of games, systems and apps; the board's widgets don't appear in it.
            labelled("Rows") {
                val rows = p.home.widgets.filter { it.kind.isRow && app.offers(it.kind) }.sortedBy { it.order }
                for (w in rows) {
                    add(toggleRow("w.${w.id}", w.kind.title(), widgetIcon(w.kind), w.visible) { v ->
                        set { s -> s.copy(home = s.home.copy(widgets = s.home.widgets.map { if (it.id == w.id) it.copy(visible = v) else it })) }
                    })
                }
                if (rows.size > 1) {
                    add(MenuAction("rows.order", "Row order", FuseIcons.Rows, detail = rows.filter { it.visible }.joinToString("  ·  ") { it.kind.title() }, trailing = Trailing.Chevron, onSelect = {
                        app.reorder = ReorderSpec(
                            title = "Row order",
                            icon = FuseIcons.Rows,
                            message = "The order of Home's rows, top first. Hidden rows keep their place for when they come back",
                            entries = rows.map { w -> ReorderEntry(w.id, w.kind.title(), widgetIcon(w.kind), detail = if (w.visible) null else "Hidden") },
                            onMoved = { keys ->
                                set { s ->
                                    val byId = s.home.widgets.associateBy { it.id }
                                    val moved = keys.mapNotNull { byId[it] }
                                    val rest = s.home.widgets.filter { it.id !in keys }.sortedBy { it.order }
                                    s.copy(home = s.home.copy(widgets = (moved + rest).mapIndexed { i, w -> w.copy(order = i) }))
                                }
                            },
                        )
                    }))
                }
                val missing = WidgetKind.entries.filter { k -> k.isRow && app.offersToAdd(k) && p.home.widgets.none { it.kind == k } }
                if (missing.isNotEmpty()) {
                    add(MenuAction("w.add", "Add a row", FuseIcons.CirclePlus, trailing = Trailing.Chevron, onSelect = {
                        app.choice = ChoiceSpec(
                            title = "Add a row to Home",
                            icon = FuseIcons.CirclePlus,
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
            }
        } else {
            // The board's widgets: their sizes here for the controller; on Home, hold one to move or resize it.
            labelled("Widgets") {
                val board = p.home.boardWidgets().filter { app.offers(it.kind) }
                fun saveBoard(change: (List<io.github.matiyaaa.fuse.model.HomeWidget>) -> List<io.github.matiyaaa.fuse.model.HomeWidget>) =
                    set { s -> s.copy(home = s.home.copy(board = change(s.home.boardWidgets()).mapIndexed { i, w -> w.copy(order = i) })) }
                for (w in board) {
                    val size = w.boardSize
                    add(MenuAction(
                        "b.${w.id}", w.kind.title(), widgetIcon(w.kind),
                        trailing = Trailing.Value("${size.width} by ${size.height}"),
                        onSelect = {
                            app.choice = ChoiceSpec(
                                title = w.kind.title(),
                                icon = widgetIcon(w.kind),
                                message = "Its size on Home, in cells across by down. On Home you can also drag its corner",
                                options = BoardSizes.map { (bw, bh) ->
                                    MenuAction("size.$bw.$bh", "$bw by $bh", null, trailing = Trailing.Check(size.width == bw && size.height == bh), onSelect = {
                                        saveBoard { list -> list.map { if (it.id == w.id) it.copy(width = bw, height = bh) else it } }
                                        app.choice = null
                                    })
                                } + MenuAction("remove", "Remove from Home", FuseIcons.Minus, destructive = true, onSelect = {
                                    saveBoard { list -> list.filterNot { it.id == w.id } }
                                    app.choice = null
                                }),
                            )
                        },
                    ))
                }
                val missing = WidgetKind.entries.filter { k -> app.offersToAdd(k) && board.none { it.kind == k } }
                if (missing.isNotEmpty()) {
                    add(MenuAction("b.add", "Add a widget", FuseIcons.CirclePlus, detail = "It goes at the end of the board", trailing = Trailing.Chevron, onSelect = {
                        app.choice = ChoiceSpec(
                            title = "Add a widget",
                            icon = FuseIcons.CirclePlus,
                            options = missing.map { k ->
                                MenuAction("add.$k", k.title(), widgetIcon(k), onSelect = {
                                    saveBoard { list -> list + io.github.matiyaaa.fuse.model.HomeWidget(k.name.lowercase(), k, list.size) }
                                    app.choice = null
                                })
                            },
                        )
                    }))
                }
                add(MenuAction("b.reset", "Reset the board", FuseIcons.RotateCcw, detail = "Back to the widgets and sizes Home comes with, on this device", onSelect = {
                    app.confirm = ConfirmSpec(
                        title = "Reset the board?",
                        message = "Home on this device goes back to the widgets, sizes and order it came with. Network's rows stay as they are" +
                            (if (app.store.sync.inUse) ", and your Home on your other devices stays as it is." else ".") +
                            " Undo Home Reset brings this one back.",
                        confirmLabel = "Reset",
                    ) {
                        app.store.resetHome { it.copy(board = io.github.matiyaaa.fuse.model.HomeLayoutConfig.DefaultBoard) }
                        app.toasts.show("Board reset on this device")
                    }
                }))
                if (p.canUndoHomeReset) {
                    add(MenuAction("b.undo", "Undo Home Reset", FuseIcons.Undo, detail = "Home goes back as it was before the reset", onSelect = {
                        app.store.undoHomeReset()
                        app.toasts.show("Home is back as it was", icon = FuseIcons.Undo)
                    }))
                }
            }
        }
    }
}

/** The sizes a widget can take on the board, small to large. */
private val BoardSizes = listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 1 to 2, 2 to 2, 3 to 2, 4 to 2, 1 to 3, 2 to 3, 3 to 3, 4 to 3)

private fun widgetIcon(k: WidgetKind) = when (k) {
    WidgetKind.CONTINUE_PLAYING -> FuseIcons.CirclePlay
    WidgetKind.RECENTLY_PLAYED -> FuseIcons.History
    WidgetKind.FAVORITES -> FuseIcons.Heart
    WidgetKind.RECENTLY_ADDED -> FuseIcons.Sparkles
    WidgetKind.PINNED_GAMES -> FuseIcons.Pin
    WidgetKind.PINNED_APPS -> FuseIcons.Smartphone
    WidgetKind.COLLECTIONS -> FuseIcons.Bookmark
    WidgetKind.SYSTEMS -> FuseIcons.Chip
    WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS -> FuseIcons.Trophy
    WidgetKind.ACHIEVEMENT_PROGRESS -> FuseIcons.ChartPie
    WidgetKind.RECENTLY_MASTERED -> FuseIcons.Award
    WidgetKind.PLAYTIME_TOTAL -> FuseIcons.Hourglass
    WidgetKind.PLAYTIME_WEEK -> FuseIcons.CalendarClock
    WidgetKind.MOST_PLAYED -> FuseIcons.TrendingUp
    WidgetKind.CURRENT_GAME -> FuseIcons.Gamepad
    WidgetKind.CARTRIDGE_DOWNLOADS -> io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks.Cartridge
    WidgetKind.STORAGE -> FuseIcons.HardDrive
    WidgetKind.CLOCK -> FuseIcons.Clock
    WidgetKind.JELLYFIN_CONTINUE -> FuseIcons.MonitorPlay
    WidgetKind.JELLYFIN_NEXT_UP -> FuseIcons.SkipForward
    WidgetKind.JELLYFIN_RECENTLY_ADDED -> FuseIcons.Film
    WidgetKind.JELLYFIN_FAVORITES -> FuseIcons.Heart
    WidgetKind.JELLYFIN_MOVIES -> FuseIcons.Clapperboard
    WidgetKind.JELLYFIN_MUSIC -> FuseIcons.Disc
    WidgetKind.SYNC_STATUS -> FuseIcons.RefreshCcw
    WidgetKind.SYNC_DEVICES -> FuseIcons.MonitorSmartphone
}

@Composable
fun libraryRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val sources by app.store.sources.sources.collectAsState()
    val scan by app.store.sources.scan.collectAsState()
    return buildList {
        labelled("Game folders") {
            for (s in sources) {
                add(MenuAction("src.${s.id.value}", s.label, FuseIcons.Folder, detail = s.path, trailing = Trailing.Value("Remove"), onSelect = {
                    app.confirm = ConfirmSpec(
                        "Stop using this folder?", "${s.path}\n\nFuse stops showing games from it. Nothing in the folder is changed, and your edits come back if you add it again.",
                        "Remove folder", destructive = true,
                    ) { app.scope.launch { app.store.sources.remove(s) } }
                }))
            }
            add(MenuAction("src.add", "Add a games folder", FuseIcons.FolderPlus, detail = "A ROMs folder, a RomM library, or one system's folder", onSelect = {
                app.scope.launch {
                    val path = app.platform.storage.pickFolder("Choose a games folder") ?: return@launch
                    app.store.sources.add(path)
                    app.store.sources.rescan()
                }
            }))
            add(MenuAction(
                "add.game", "Add a game", FuseIcons.CirclePlus,
                detail = if (app.store.apps.gamesInLibrary) "An app on this device, an APK file, or a game file from anywhere" else "A game file from anywhere on this computer",
                trailing = Trailing.Chevron,
                onSelect = { app.addGame() },
            ))
        }
        app.platform.steam?.let { steam -> labelled("Steam") { addAll(steamRows(app, steam)) } }
        labelled("Scanning") {
            add(MenuAction("scan", "Scan for changes", FuseIcons.Refresh, detail = "Only folders that changed. Also runs whenever you return to Fuse", trailing = Trailing.Value(scan.phase.name.lowercase().replaceFirstChar { it.uppercase() }), onSelect = {
                app.store.sources.rescan(ScanScope.QUICK); app.toasts.show("Scanning")
            }))
            add(app.confirmRow("fullscan", "Full rescan", FuseIcons.RefreshDot, "Rescan everything?", "Fuse reads every folder again. It can take a while on large libraries; you can keep using Fuse meanwhile. Your edits are kept.", "Rescan", detail = "Every folder, read again. Your edits are kept") {
                app.store.sources.rescan(ScanScope.FULL)
            })
            add(MenuAction("bios", "Check BIOS again", FuseIcons.Key, detail = "After adding firmware to a BIOS folder", onSelect = { app.store.sources.refreshBios(); app.toasts.show("Checking BIOS files") }))
        }
        labelled("Browsing") {
            add(app.choiceRow("layout", "Default view", FuseIcons.Grid, p.defaultLayout, LibraryLayout.entries.map { it to layoutName(it) }, detail = "How the Library first shows your games") { v -> app.store.updatePrefs { it.copy(defaultLayout = v) } })
            add(app.choiceRow(
                "select", "Selecting a game", FuseIcons.Play, p.openGamePage,
                listOf(false to "Plays it", true to "Opens its page"),
                detail = "What confirm (or a tap on a selected game) does. Play is always on the game's page",
            ) { v -> app.store.updatePrefs { it.copy(openGamePage = v) } })
        }
        labelled("Collections") {
            add(toggleRow("collections", "Collections", FuseIcons.LibraryBig, p.collectionsEnabled, "Your own collections and the series Fuse finds. Off hides them everywhere; nothing is deleted") { v ->
                app.store.updatePrefs { it.copy(collectionsEnabled = v) }
            })
            add(toggleRow(
                "series", "Automatic series", FuseIcons.Sparkles, p.autoSeries,
                "A collection for each series, like Super Mario, from game details and shared titles. Kept up to date",
                enabled = p.collectionsEnabled,
            ) { v -> app.store.updatePrefs { it.copy(autoSeries = v) } })
            if (p.hiddenSeries.isNotEmpty() && p.collectionsEnabled) {
                add(MenuAction(
                    "series.hidden", "Hidden series", FuseIcons.EyeOff,
                    detail = "Series you hid or kept as your own. Bring one back to let Fuse make it again",
                    trailing = Trailing.Value(p.hiddenSeries.size.toString()),
                    onSelect = {
                        app.choice = ChoiceSpec(
                            title = "Hidden series",
                            icon = FuseIcons.EyeOff,
                            message = "Fuse makes these again when you bring them back.",
                            options = p.hiddenSeries.sorted().map { name ->
                                MenuAction("s.$name", name.replaceFirstChar { it.uppercase() }, FuseIcons.Eye, detail = "Bring back", onSelect = {
                                    app.store.updatePrefs { it.copy(hiddenSeries = it.hiddenSeries - name) }
                                    app.choice = null
                                    app.toasts.show("Fuse will make this series again")
                                })
                            } + MenuAction("all", "Bring all back", FuseIcons.Refresh, onSelect = {
                                app.store.updatePrefs { it.copy(hiddenSeries = emptyList()) }
                                app.choice = null
                            }),
                        )
                    },
                ))
            }
        }
        labelled("") {
            addAll(app.group(
                "library.names", "Display names", FuseIcons.Eraser,
                summary = if (p.cleanDisplayNames) "Cleaned" else "As in the files",
                detail = "Hide tags like (USA) and [!] from titles",
            ) {
                buildList {
                    add(MenuAction(
                        "clean", "Clean display names", FuseIcons.Eraser,
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
                                        app.choice = ChoiceSpec(
                                            title = "${preview.size} titles will change",
                                            icon = FuseIcons.Eraser,
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
            })
        }
    }
}

@Composable
fun systemsRows(app: AppState): List<MenuAction> {
    val platforms by app.store.library.platforms.collectAsState()
    val prefs by app.store.prefs.collectAsState()
    val systems = platforms.filter { it.gameCount > 0 }
    val artProgress by app.store.media.systemArtProgress.collectAsState()
    val emulators = emulatorRows(app)
    return buildList {
        // Each system's own settings first; how systems look and are ordered after.
        labelled("Your systems") {
            systems.forEach { p ->
                add(MenuAction(
                    "sys.${p.platform.id}", p.platform.name, FuseIcons.Chip,
                    detail = listOfNotNull("${p.gameCount} games", p.emulatorName ?: "No emulator").joinToString("  ·  "),
                    trailing = Trailing.Chevron,
                    onSelect = { app.go(Route.PlatformSettings(p.platform.id)) },
                ))
            }
            if (systems.isEmpty()) add(infoRow("none", "No systems yet", detail = "Systems appear once Fuse finds games for them", icon = FuseIcons.Chip))
        }
        addAll(emulators)
        labelled("Order and art") {
            add(MenuAction(
                "order.reset", "Reset system order", FuseIcons.RotateCcw,
                detail = if (prefs.systemOrder.isEmpty()) "Hold a system in Systems or on Home and drag it, or move it with the D-pad. The order is used everywhere" else "Back to the order Fuse uses by default",
                enabled = prefs.systemOrder.isNotEmpty(),
                onSelect = {
                    app.store.updatePrefs { it.copy(systemOrder = emptyList()) }
                    app.toasts.show("System order reset")
                },
            ))
            addAll(app.group(
                "systems.art", "System art", FuseIcons.Image,
                summary = if (prefs.systemArtAuto) systemArtStyles.firstOrNull { it.first == prefs.systemArtStyle }?.second else "Off",
                detail = "Logos, artwork and colours from the Art Book Next pack, or panels from your games' screenshots",
            ) {
                buildList {
                    add(toggleRow("art.auto", "Download system art", FuseIcons.Image, prefs.systemArtAuto, "Downloaded when a system has none") { v ->
                        app.store.updatePrefs { it.copy(systemArtAuto = v) }
                    })
                    add(app.choiceRow(
                        "art.style", "System art style", FuseIcons.Paintbrush, prefs.systemArtStyle,
                        systemArtStyles,
                        detail = "Used the next time system art is downloaded",
                        optionDetail = { v ->
                            if (v == "GAMES") "The pack's logos, with a screenshot of each system's own games as its panel, cut like the pack's"
                            else "Art Book Next's ${systemArtStyles.firstOrNull { it.first == v }?.second?.lowercase() ?: ""} panels"
                        },
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
                            progress == null -> "Every system again in the chosen style. Art you chose yourself stays"
                            !progress.finished -> "Working: ${progress.current ?: ""} (${progress.done + 1} of ${progress.total})"
                            else -> "Done: ${progress.added} images for ${progress.total} systems"
                        },
                        onSelect = {
                            app.store.media.downloadSystemArt()
                            app.toasts.show("Downloading system art")
                        },
                    ))
                    add(MenuAction(
                        "art.default", "Restore Fuse Default Art", FuseIcons.RotateCcw,
                        detail = "Every system shows Fuse's own icon, background and logo again; nothing is downloaded for them by itself",
                        onSelect = { app.restoreSystemArt(emptyList(), null) },
                    ))
                    app.artUndo?.let { u ->
                        add(MenuAction("art.undo", "Undo Restore Art", FuseIcons.Undo, detail = "Puts back the art ${u.count} systems had", onSelect = { app.undoSystemArt(u) }))
                    }
                    add(infoRow("art.credit", "Art Book Next", detail = SystemArtPack.ATTRIBUTION, icon = FuseIcons.Info))
                }
            })
        }
    }
}

/** The emulators Fuse found (each opens its own page), then how Fuse looks for them, folded. */
@Composable
fun emulatorRows(app: AppState): List<MenuAction> {
    val installed by app.store.emulators.installed.collectAsState()
    val folders by app.store.emulators.searchFolders.collectAsState()
    return buildList {
        labelled("Emulators") {
            if (installed.isEmpty()) add(infoRow("emu.none", "No emulators found", detail = "Install an emulator for a system and it appears here", icon = FuseIcons.SearchX))
            for (e in installed.sortedBy { it.name.lowercase() }) {
                val limits = app.store.emulators.limitations(e.id)
                add(MenuAction(
                    "emu.${e.id}", e.name + (e.version?.let { "  $it" } ?: ""), FuseIcons.Joystick,
                    detail = buildString {
                        val systems = when {
                            e.platforms.isEmpty() -> null
                            e.platforms.size > 8 -> "Any system"
                            else -> e.platforms.joinToString(", ") { it.value.uppercase() }
                        }
                        val found = when (e.detectedVia) {
                            "Built in" -> "built in"
                            "Located" -> "located by you"
                            else -> "found via ${e.detectedVia}"
                        }
                        append(listOfNotNull(systems, found).joinToString("  ·  "))
                        if (e.isFamilyMatch) append("  ·  recognised as a variant")
                        if (limits.isNotEmpty()) append("\n" + limits.joinToString("\n"))
                    },
                    trailing = Trailing.Chevron,
                    onSelect = { app.showEmulator(e.id) },
                ))
            }
            addAll(app.group(
                "systems.finding", "Finding emulators", FuseIcons.FolderSearch,
                summary = if (app.store.emulators.canLocate && folders.isNotEmpty()) "${folders.size} ${if (folders.size == 1) "folder" else "folders"}" else null,
                detail = "Look again, or show Fuse where one is",
            ) {
                buildList {
                    add(MenuAction("refresh", "Look for emulators again", FuseIcons.Refresh, detail = "Fuse also notices installs and removals on its own", onSelect = {
                        app.store.emulators.refresh(); app.toasts.show("Looking for emulators")
                    }))
                    if (app.store.emulators.canLocate) {
                        add(MenuAction("locate", "Locate an emulator", FuseIcons.Search, detail = "Show Fuse where one is when it wasn't found", trailing = Trailing.Chevron, onSelect = {
                            app.locatePicker()
                        }))
                        add(MenuAction(
                            "folders", "Emulator folders", FuseIcons.FolderOpen,
                            detail = if (folders.isEmpty()) "Add folders Fuse searches for emulators" else folders.joinToString("\n"),
                            trailing = Trailing.Value(if (folders.isEmpty()) "None" else "${folders.size}"),
                            onSelect = { app.emulatorFoldersPicker() },
                        ))
                    }
                }
            })
        }
    }
}

@Composable
fun mediaRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val providers by app.store.media.providers.collectAsState()
    val checks by app.store.media.keyChecks.collectAsState()
    val stored by app.store.credentials.stored.collectAsState()
    val fill by app.store.media.fillProgress.collectAsState()
    val set = app.store::updatePrefs
    fun secretRow(key: String, label: String, detail: String) = app.textRow(
        "key.$key", label, FuseIcons.Key, if (key in stored) "Saved" else null, detail = detail, placeholder = "Paste or type",
    ) { v -> app.scope.launch { if (v.isBlank()) app.store.credentials.remove(key) else app.store.credentials.put(key, v) } }
    // What a provider's last key test says, as its row's value, icon and note.
    fun providerRow(s: io.github.matiyaaa.fuse.model.ProviderStatus): MenuAction {
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
        return infoRow("prov.${s.id}", s.id.displayName, value, detail = detail, icon = icon)
    }
    // Each source's own keys sit right under it.
    fun keyRows(id: ScrapeProviderId): List<MenuAction> = when (id) {
        ScrapeProviderId.STEAMGRIDDB -> listOf(secretRow("sgdb.apikey", "SteamGridDB API key", "Free from steamgriddb.com, Preferences, API. Stored encrypted on this device"))
        ScrapeProviderId.IGDB -> listOf(
            secretRow("igdb.clientId", "IGDB Client ID", "From your own Twitch developer app. IGDB doesn't allow apps to share one"),
            secretRow("igdb.clientSecret", "IGDB Client Secret", "Stored encrypted; never shown again"),
        )
        ScrapeProviderId.THEGAMESDB -> listOf(secretRow("tgdb.apikey", "TheGamesDB API key", "Requested on the TheGamesDB forum"))
        ScrapeProviderId.SCREENSCRAPER -> listOf(
            secretRow("ss.user", "ScreenScraper username", "Optional. Your account raises your request limits"),
            secretRow("ss.password", "ScreenScraper password", "Stored encrypted"),
        )
        else -> emptyList()
    }
    val ready = providers.count { it.configured && checks[it.id] !is KeyCheck.Rejected }
    val needs = providers.filter { !it.configured || checks[it.id] is KeyCheck.Rejected }
    return buildList {
        // What you come here for first: filling art, and how it looks and behaves.
        labelled("Filling art") {
            addAll(fillRows(app, fill))
            add(MenuAction("fill", "Fill missing art", FuseIcons.Wand, detail = "Box art, icons, covers, banners, backgrounds, logos and screenshots for games without them. Custom art is never replaced", onSelect = {
                app.store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable)
                app.toasts.show("Looking for missing art. Progress shows here and in the top bar")
            }))
            add(MenuAction("fill.all", "Fill everything", FuseIcons.Sparkles, detail = "Every kind of art, screenshots and details (description, year, genres, series, rating) for every game, plus system art. Nothing you chose or edited is replaced", onSelect = {
                app.store.media.fillEverything()
                app.toasts.show("Filling art and details. Progress shows here and in the top bar")
            }))
            add(toggleRow(
                "fill.auto", "Find art by itself", FuseIcons.ScanSearch, p.autoFillArt,
                "After a scan or a new key, games missing art or details get them. When a source runs out of requests, the others take over",
            ) { v -> set { it.copy(autoFillArt = v) } })
        }
        labelled("Previews") {
            add(toggleRow("video", "Video previews", FuseIcons.ImagePlay, p.videoPreview, if (app.platform.features.videoPreview) "After resting on a game, its art turns into a muted gameplay clip" else "Not available on this system yet", enabled = app.platform.features.videoPreview) { v -> set { it.copy(videoPreview = v) } })
            if (p.videoPreview && app.platform.features.videoPreview) {
                add(app.choiceRow("video.delay", "Preview delay", FuseIcons.Timer, p.videoDelaySeconds, listOf(5, 10, 15, 20, 30).map { it to "$it seconds" }) { v -> set { it.copy(videoDelaySeconds = v) } }.copy(indent = 1))
            }
        }
        // Set once and rarely touched again: how sure a match must be, where art comes from, and the keys for it.
        labelled("Matching and sources") {
            val languages = listOf("en" to "English", "fr" to "French", "de" to "German", "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese", "ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean")
            val regions = listOf("any" to "Any region", "us" to "USA", "eu" to "Europe", "jp" to "Japan", "wor" to "World")
            addAll(app.group(
                "media.matching", "Matching", FuseIcons.Target,
                summary = listOfNotNull(
                    languages.firstOrNull { it.first == p.scraperLanguage }?.second,
                    regions.firstOrNull { it.first == p.scraperRegion }?.second,
                    p.matching.name.lowercase().replaceFirstChar { it.uppercase() },
                ).joinToString("  ·  "),
                detail = "Language, region and how sure Fuse must be",
            ) {
                buildList {
                    add(app.choiceRow("lang", "Preferred language", FuseIcons.Earth, p.scraperLanguage, languages) { v -> set { it.copy(scraperLanguage = v) } })
                    add(app.choiceRow("region", "Preferred region", FuseIcons.Map, p.scraperRegion, regions) { v -> set { it.copy(scraperRegion = v) } })
                    add(app.choiceRow(
                        "match", "Strictness", FuseIcons.Crosshair, p.matching,
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
                    add(app.confirmRow("replace", "Replace all scraped art", FuseIcons.RotateCcw, "Replace scraped art?", "Fuse fetches art again for every game and replaces art it scraped before. Art you chose yourself stays.", "Replace") {
                        app.store.media.fill(MediaFillMode.REPLACE_ALL, MediaKind.Fillable)
                    })
                }
            })
            addAll(app.group(
                "media.sources", "Sources and keys", FuseIcons.Database,
                summary = when {
                    providers.isEmpty() -> null
                    needs.isEmpty() -> "All $ready ready"
                    needs.size == 1 -> "$ready ready  ·  ${needs.single().id.displayName} needs a key"
                    else -> "$ready ready  ·  ${needs.size} need keys"
                },
                detail = "The order Fuse asks them in, each one's status and its keys",
            ) {
                buildList {
                    add(MenuAction("order", "Source order", FuseIcons.Layers, detail = p.scraperOrder.joinToString("  ·  ") { it.displayName }, trailing = Trailing.Chevron, onSelect = {
                        app.reorder = ReorderSpec(
                            title = "Source order",
                            icon = FuseIcons.Layers,
                            message = "Fuse asks sources in this order. RomM data from Cartridge comes first so nothing is scraped twice",
                            entries = p.scraperOrder.map { id -> ReorderEntry(id.name, id.displayName) },
                            onMoved = { keys ->
                                set { s ->
                                    val moved = keys.mapNotNull { k -> s.scraperOrder.firstOrNull { it.name == k } }
                                    s.copy(scraperOrder = moved + s.scraperOrder.filter { it !in moved })
                                }
                            },
                        )
                    }))
                    for (s in providers) {
                        add(providerRow(s))
                        addAll(keyRows(s.id).map { it.copy(indent = 1) })
                    }
                    // Keys for sources this build doesn't list still have a place.
                    val listed = providers.map { it.id }.toSet()
                    for (id in ScrapeProviderId.entries) if (id !in listed) addAll(keyRows(id))
                    if (checks.isNotEmpty() || stored.any { it.startsWith("sgdb.") || it.startsWith("igdb.") || it.startsWith("tgdb.") }) {
                        add(MenuAction("test", "Test keys", FuseIcons.ShieldCheck, detail = "Checks every key with a real request and shows the result on each source", onSelect = {
                            app.store.media.checkKeys()
                            app.toasts.show("Testing keys")
                        }))
                    }
                }
            })
        }
    }
}

/** The running or last fill: live progress (select to stop), then what it did and the games that need a choice. */
fun fillRows(app: AppState, fill: FillProgress?): List<MenuAction> {
    val f = fill ?: return emptyList()
    return buildList {
        if (!f.finished) {
            add(MenuAction(
                "fill.progress", if (f.automatic) "Finding art for your games" else "Filling art and details", FuseIcons.Wand,
                detail = listOfNotNull(f.current, "${f.added} ${if (f.added == 1) "image" else "images"} added", pausedNote(f), "Select to stop").joinToString("  ·  "),
                trailing = Trailing.Progress(f.fraction.takeIf { f.total > 0 }, "${f.done} of ${f.total}"),
                onSelect = { app.store.media.cancelFill() },
            ))
        } else {
            add(infoRow(
                "fill.result", if (f.cancelled) "Fill stopped" else "Fill finished",
                "${f.done} of ${f.total}",
                detail = fillSummary(f),
                icon = if (f.cancelled) FuseIcons.CircleX else FuseIcons.CircleCheck,
            ))
        }
        if (f.needsYou.isNotEmpty()) add(needsYouRow(app, f.needsYou))
    }
}

/** Which sources are resting while the fill carries on with the others, or null. */
fun pausedNote(f: FillProgress): String? = f.paused.takeIf { it.isNotEmpty() }?.let { names ->
    "${names.joinToString(" and ")} ${if (names.size == 1) "is" else "are"} out of requests for now, using the others"
}

/** "40 images added, details for 12 games" (or that nothing new was found). */
fun fillSummary(f: FillProgress): String {
    val parts = listOfNotNull(
        f.added.takeIf { it > 0 }?.let { "$it ${if (it == 1) "image" else "images"} added" },
        f.details.takeIf { it > 0 }?.let { "details for $it ${if (it == 1) "game" else "games"}" },
    )
    return if (parts.isEmpty()) "Nothing new was found" else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}

private fun needsYouRow(app: AppState, games: List<FillChoice>): MenuAction = MenuAction(
    "fill.needs", "${games.size} ${if (games.size == 1) "game needs" else "games need"} you", FuseIcons.FileQuestion,
    detail = "Several close matches. Pick the right game in Identify game",
    trailing = Trailing.Badge(games.size.toString()),
    onSelect = {
        app.choice = ChoiceSpec(
            title = "Pick the right game",
            message = "Fuse found several close matches for these. Choose one to see them all.",
            options = games.take(60).map { g ->
                MenuAction("needs.${g.game.value}", g.title, FuseIcons.Gamepad, trailing = Trailing.Chevron, onSelect = {
                    app.choice = null
                    app.go(Route.Media(MediaOwner.OfGame(g.game), g.title, identify = true))
                })
            },
        )
    },
)

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
                s.protocol >= 2 -> "Linked. Downloads, each game's progress and RomM's details appear in Fuse"
                else -> "Linked. Downloads appear in Fuse automatically"
            }))
        if (s.installed) add(MenuAction("open", "Open Cartridge", FuseIcons.External, onSelect = { app.store.cartridge.open(CartridgeRoute.Home) }))
        add(toggleRow("auto", "Pick up new downloads on return", FuseIcons.Refresh, p.autoRefreshFromCartridge, "Rescans the folders Cartridge saved to when you come back") { v -> app.store.updatePrefs { it.copy(autoRefreshFromCartridge = v) } })
        add(toggleRow(
            "romm", "Details and art from RomM", FuseIcons.Database, p.cartridgeRommDetails,
            if (s.installed && s.bridge && s.protocol < 2) "Needs a newer Cartridge. Games it downloaded then get RomM's description, genres, series, cover and logo"
            else "Games Cartridge downloaded get RomM's description, genres, series, cover and logo. Your own edits and picks stay",
        ) { v -> app.store.updatePrefs { it.copy(cartridgeRommDetails = v) } })
        add(MenuAction(
            "rommcheck", "Check RomM matches again", FuseIcons.ShieldCheck,
            detail = "Puts back the name, details and art of any game RomM mixed up with another",
            onSelect = {
                app.scope.launch {
                    val undone = app.store.cartridge.checkRommMatches()
                    app.toasts.show(
                        when (undone) {
                            0 -> "Every game with RomM's details is the right one"
                            1 -> "Put back one game RomM had mixed up"
                            else -> "Put back $undone games RomM had mixed up"
                        },
                    )
                }
            },
        ))
    }
}

/**
 * Steam on a computer: its installed games in the library (found on every drive, or in a folder the
 * user picks), and Fuse in Steam's library for Game Mode.
 */
private fun steamRows(app: AppState, steam: io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration): List<MenuAction> {
    fun addFound(games: List<io.github.matiyaaa.fuse.library.steam.SteamGame>) {
        if (games.isEmpty()) {
            app.toasts.show("No Steam games found there")
            return
        }
        app.confirm = ConfirmSpec(
            "Add ${games.size} Steam ${if (games.size == 1) "game" else "games"}?",
            games.take(6).joinToString("\n") { it.name } + if (games.size > 6) "\nand ${games.size - 6} more" else "",
            "Add them",
        ) {
            app.scope.launch {
                val n = app.store.sources.addSteamGames(games)
                app.toasts.show(if (n == 1) "1 Steam game added" else "$n Steam games added")
            }
        }
    }
    return listOf(
        MenuAction(
            "steam.find", "Find Steam games", FuseIcons.FolderSearch,
            detail = "Every game Steam has installed, on any drive. They start through Steam",
            onSelect = { app.scope.launch { addFound(app.store.sources.findSteamGames()) } },
        ),
        MenuAction(
            "steam.folder", "Add a Steam library folder", FuseIcons.FolderPlus,
            detail = "A drive or folder Steam keeps games in that Fuse didn't find",
            onSelect = {
                app.scope.launch {
                    val path = app.platform.storage.pickFolder("Choose a Steam library folder") ?: return@launch
                    addFound(app.store.sources.findSteamGames(path))
                }
            },
        ),
        MenuAction(
            "steam.add", if (steam.gameMode) "Fuse in Game Mode" else "Add Fuse to Steam", FuseIcons.Gamepad,
            detail = if (steam.gameMode) "Fuse is running in Game Mode now. Cartridge opens inside it, over Fuse" else "So SteamOS's Game Mode can start Fuse, full screen. Close Steam first",
            enabled = !steam.gameMode,
            onSelect = {
                app.scope.launch {
                    steam.addFuse()
                        .onSuccess { app.toasts.show(it) }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't add Fuse to Steam", io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.ERROR) }
                }
            },
        ),
    )
}

/** Menu music and interface sounds, each with its own volume. */
@Composable
fun soundRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val music = p.music
    fun setMusic(change: (MusicPrefs) -> MusicPrefs) = app.store.updatePrefs { it.copy(music = change(it.music)) }
    return buildList {
        labelled("Menu music") {
            if (app.platform.music == null) {
                add(infoRow("music", "Menu music", "Not available", icon = FuseIcons.Music, detail = "This device can't play music in Fuse"))
            } else {
                add(toggleRow("music", "Menu music", FuseIcons.Music, music.enabled, "Plays in Fuse's menus and stops for games") { v -> setMusic { it.copy(enabled = v) } })
                add(app.percentRow("musicvolume", "Music volume", FuseIcons.Volume, music.volume, "Low sits nicely under the interface") { v -> setMusic { it.copy(volume = v) } })
                add(songRow(app, music, ::setMusic))
                add(toggleRow("shuffle", "Shuffle", FuseIcons.Shuffle, music.shuffle, if (music.shuffle) "A different song each time one ends, from both albums" else "Plays every song in a random order instead of repeating one") { v -> setMusic { it.copy(enabled = true, shuffle = v) } })
                add(infoRow("credit", "Music by ${BundledMusic.ARTIST}", detail = "Fuse's songs are from the albums ${BundledMusic.ALBUM} and ${BundledMusic.ALBUM_TWO}. First-time setup plays ${BundledMusic.byId(BundledMusic.ONBOARDING)?.title}", icon = FuseIcons.Heart))
            }
        }
        labelled("Interface sounds") {
            add(app.choiceRow("sound", "Interface sounds", FuseIcons.AudioLines, p.sound, listOf(SoundProfile.OFF to "Off", SoundProfile.SOFT to "Soft", SoundProfile.CLICK to "Crisp", SoundProfile.CHIME to "Chime")) { v -> app.store.updatePrefs { it.copy(sound = v) } })
            add(app.percentRow("volume", "Sound effects volume", FuseIcons.Volume, p.soundVolume, "Moving, confirming and going back") { v -> app.store.updatePrefs { it.copy(soundVolume = v) } })
        }
    }
}

/** The menu song: one of the album's songs, or the user's own. Picking one plays it straight away. */
private fun songRow(app: AppState, music: MusicPrefs, setMusic: ((MusicPrefs) -> MusicPrefs) -> Unit): MenuAction {
    val own = music.track == BundledMusic.OWN_SONG
    val current = when {
        music.shuffle -> "Shuffle"
        own -> music.songName ?: "Your song"
        else -> BundledMusic.byId(music.track)?.title ?: "None"
    }
    fun pickFile() {
        app.choice = null
        app.scope.launch {
            val picked = app.platform.storage.pickAudio("Choose menu music") ?: return@launch
            setMusic { it.copy(enabled = true, songPath = picked.path, songName = picked.name, track = BundledMusic.OWN_SONG, shuffle = false) }
            app.toasts.show("Menu music: ${picked.name}")
        }
    }
    return MenuAction(
        "song", "Song", FuseIcons.Disc,
        detail = when {
            music.shuffle -> "Every song by ${BundledMusic.ARTIST}, in a random order"
            own -> "Your own song. Fuse keeps its own copy"
            else -> "${BundledMusic.ARTIST}, ${BundledMusic.byId(music.track)?.album ?: BundledMusic.ALBUM}"
        },
        trailing = Trailing.Value(current),
        onSelect = {
            app.choice = ChoiceSpec(
                title = "Menu music",
                icon = FuseIcons.Music,
                message = "${BundledMusic.CREDIT}, or a song of your own. The song you pick plays straight away.",
                options = listOf(
                    MenuAction(
                        "shuffle", "Shuffle", FuseIcons.Shuffle,
                        detail = "Every song from both albums, a new one each time one ends",
                        trailing = Trailing.Check(music.shuffle),
                        onSelect = {
                            app.choice = null
                            setMusic { it.copy(enabled = true, shuffle = true) }
                        },
                    ),
                ) + BundledMusic.tracks.map { t ->
                    MenuAction(
                        "t.${t.id}", t.title, FuseIcons.Music,
                        detail = if (t.id == BundledMusic.MENU_DEFAULT) "Fuse's default" else null,
                        trailing = Trailing.Check(!music.shuffle && !own && music.track == t.id),
                        section = t.album,
                        onSelect = {
                            app.choice = null
                            setMusic { it.copy(enabled = true, track = t.id, shuffle = false) }
                        },
                    )
                } + listOfNotNull(
                    music.songPath?.let { path ->
                        MenuAction("own", music.songName ?: "Your song", FuseIcons.FolderOpen, detail = "Your own song", trailing = Trailing.Check(!music.shuffle && own), section = "Your music", onSelect = {
                            app.choice = null
                            setMusic { it.copy(enabled = true, track = BundledMusic.OWN_SONG, songPath = path, shuffle = false) }
                        })
                    },
                    MenuAction("pick", if (music.songPath == null) "Choose your own song" else "Choose another song", FuseIcons.FileUp, detail = "An audio file on this device. MP3 works everywhere", section = "Your music", onSelect = ::pickFile),
                ),
            )
        },
    )
}

@Composable
fun inputRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val i = p.input
    fun setInput(t: (io.github.matiyaaa.fuse.model.InputProfile) -> io.github.matiyaaa.fuse.model.InputProfile) = app.store.updatePrefs { it.copy(input = t(it.input)) }
    return buildList {
        labelled("Buttons") {
            add(MenuAction("detect", "Detect my buttons", FuseIcons.ScanSearch, detail = "Press two buttons and Fuse sets the layout and confirm button for you", onSelect = { app.buttonDetect = true }))
            add(app.choiceRow(
                "layout", "Button layout", FuseIcons.Gamepad, i.glyphs,
                listOf(GlyphStyle.XBOX to "Xbox (A at the bottom)", GlyphStyle.NINTENDO to "Nintendo (A on the right)", GlyphStyle.PLAYSTATION to "PlayStation (shapes)"),
                detail = "The letters printed on your buttons. Hints use them",
            ) { v -> setInput { it.copy(glyphs = v) } })
            add(toggleRow("swap", "Swap confirm and back", FuseIcons.Swap, i.swapConfirmBack, if (i.confirmOnRight) "Now: confirm is the right button" else "Now: confirm is the bottom button") { v -> setInput { it.copy(swapConfirmBack = v) } })
            add(toggleRow("shoulders", "Swap bumpers and triggers", FuseIcons.Swap, i.swapShoulders, if (i.swapShoulders) "Now: L2 and R2 switch tabs, L1 and R1 turn widgets" else "Now: L1 and R1 switch tabs, L2 and R2 turn widgets") { v -> setInput { it.copy(swapShoulders = v) } })
            add(toggleRow("keyhints", "Keyboard hints when typing", FuseIcons.Keyboard, i.autoGlyphs, "Show keyboard keys in hints after a keyboard key is used") { v -> setInput { it.copy(autoGlyphs = v) } })
        }
        labelled("Mapping") {
            add(MenuAction("mapping", "Button mapping and test", FuseIcons.Joystick, detail = if (i.remap.isEmpty()) "Standard layout. See what Fuse receives from each button" else "${i.remap.size} custom mappings", trailing = Trailing.Chevron, onSelect = { app.go(Route.Controls) }))
        }
        labelled("Feel") {
            val d = io.github.matiyaaa.fuse.model.InputProfile()
            val changed = listOf(
                i.repeatDelayMs != d.repeatDelayMs, i.repeatIntervalMs != d.repeatIntervalMs, i.repeatAccelerate != d.repeatAccelerate,
                i.stickDeadzone != d.stickDeadzone, i.navigationThreshold != d.navigationThreshold, i.longPressMs != d.longPressMs, i.vibration != d.vibration,
            ).count { it }
            addAll(app.group(
                "inputs.feel", "Repeat and sticks", FuseIcons.SlidersVertical,
                summary = when (changed) {
                    0 -> "Default"
                    1 -> "1 changed"
                    else -> "$changed changed"
                },
                detail = "Holding a direction, stick deadzone, hold time and vibration",
            ) {
                buildList {
                    add(app.choiceRow("delay", "Repeat delay", FuseIcons.Timer, i.repeatDelayMs, listOf(180, 220, 280, 350, 450).map { it to "$it ms" }, detail = "How long a held direction waits before repeating") { v -> setInput { it.copy(repeatDelayMs = v) } })
                    add(app.choiceRow("speed", "Repeat speed", FuseIcons.Zap, i.repeatIntervalMs, listOf(40 to "Fastest", 55 to "Fast", 70 to "Normal", 100 to "Relaxed", 140 to "Slow")) { v -> setInput { it.copy(repeatIntervalMs = v) } })
                    add(toggleRow("accel", "Speed up while held", FuseIcons.Rocket, i.repeatAccelerate) { v -> setInput { it.copy(repeatAccelerate = v) } })
                    add(app.percentRow("deadzone", "Stick deadzone", FuseIcons.Target, i.stickDeadzone) { v -> setInput { it.copy(stickDeadzone = v.coerceIn(0.05f, 0.6f)) } })
                    add(app.percentRow("threshold", "Stick push to move", FuseIcons.Crosshair, i.navigationThreshold) { v -> setInput { it.copy(navigationThreshold = v.coerceIn(0.2f, 0.95f)) } })
                    add(app.choiceRow("long", "Hold time", FuseIcons.Hand, i.longPressMs, listOf(400, 550, 700, 900).map { it to "$it ms" }, detail = "Holding confirm (or a long touch) arranges Home and opens options") { v -> setInput { it.copy(longPressMs = v) } })
                    add(app.percentRow("vibration", "Vibration", FuseIcons.Vibrate, i.vibration) { v -> setInput { it.copy(vibration = v) } })
                    if (changed > 0) add(defaultsRow("feel.reset") {
                        setInput {
                            it.copy(
                                repeatDelayMs = d.repeatDelayMs, repeatIntervalMs = d.repeatIntervalMs, repeatAccelerate = d.repeatAccelerate,
                                stickDeadzone = d.stickDeadzone, navigationThreshold = d.navigationThreshold, longPressMs = d.longPressMs, vibration = d.vibration,
                            )
                        }
                        app.toasts.show("Repeat and sticks are back to their defaults")
                    })
                }
            })
            addAll(captureRows(app, p))
        }
    }
}

/** Screenshots and recordings, where Fuse can capture its screen. */
private fun captureRows(app: AppState, p: io.github.matiyaaa.fuse.ui.shell.store.UiPrefs): List<MenuAction> {
    val capture = app.capture ?: return emptyList()
    val (pictures, videos) = capture.places
    return app.group(
        "capture", "Screenshots and recordings", FuseIcons.Camera,
        summary = if (p.captureCombo) "L3 + R3" else "Quick menu only",
        detail = "From the quick menu, or by pressing both sticks in",
    ) {
        listOf(
            toggleRow(
                "capture.combo", "L3 + R3", FuseIcons.Gamepad, p.captureCombo,
                "Press both sticks in for a screenshot; hold them to start or stop a recording",
            ) { v -> app.store.updatePrefs { it.copy(captureCombo = v) } },
            toggleRow(
                "capture.sound", "Record sound", FuseIcons.Mic, p.captureSound,
                "Recordings include Fuse's music. Button sounds are never recorded",
            ) { v -> app.store.updatePrefs { it.copy(captureSound = v) } },
            infoRow("capture.where", "Where they go", detail = "Screenshots in $pictures, recordings in $videos. Only Fuse's own screen is captured", icon = FuseIcons.FolderOpen),
        )
    }
}

@Composable
fun displayRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val log by app.platform.secondScreenLog.collectAsState()
    val d = p.display
    return buildList {
        labelled("This screen") {
            if (app.platform.features.rotation) {
                add(app.choiceRow(
                    "rotation", "Rotation", FuseIcons.RotateCw, d.rotation,
                    listOf(
                        ScreenRotation.AUTO to "Automatic",
                        ScreenRotation.LANDSCAPE to "Landscape",
                        ScreenRotation.PORTRAIT to "Portrait",
                        ScreenRotation.ANY to "Any way",
                        ScreenRotation.SYSTEM to "Like Android",
                    ),
                    detail = "Turned over by accident, Fuse turns back with the device, even with rotation locked",
                    optionDetail = {
                        when (it) {
                            ScreenRotation.AUTO -> "Landscape either way up on a handheld or a device with two screens; like Android elsewhere"
                            ScreenRotation.LANDSCAPE -> "Wide, either way up, by the sensor"
                            ScreenRotation.PORTRAIT -> "Tall, either way up, by the sensor"
                            ScreenRotation.ANY -> "Every way the device is held"
                            ScreenRotation.SYSTEM -> "Follows Android's own rotation and its lock"
                        }
                    },
                ) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(rotation = v)) } })
            }
            app.platform.windowControls?.let { w ->
                add(app.choiceRow(
                    "window", "Window", FuseIcons.Monitor, w.mode,
                    listOf(WindowStyle.FULLSCREEN to "Full screen", WindowStyle.BORDERLESS to "Borderless", WindowStyle.WINDOWED to "Window"),
                    detail = "F11 switches full screen at any time",
                ) { v -> w.setMode(v) })
                add(autostartRow(app, w))
            }
        }
        // Every second-screen option, for a device that has one.
        val second = buildList {
            add(app.choiceRow(
                "flipped", "Which way round", FuseIcons.Swap, d.flipped,
                listOf(false to "Menus on top", true to "Menus below"),
                optionDetail = {
                    if (it) "Fuse on the touch screen, and the game you're on large on the main screen, like a 3DS" else "Fuse on the main screen, the second screen beside it"
                },
            ) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(flipped = v)) } })
            add(app.choiceRow(
                "mode", "Second screen", FuseIcons.DualScreen, d.mode,
                // Playing on the second screen is now "Games open on"; the old choice stays listed only while it's set.
                listOfNotNull(
                    DualScreenMode.OFF to "Off", DualScreenMode.LIBRARY_COMPANION to "Show the selected game", DualScreenMode.GAME_COMPANION to "Companion while playing",
                    (DualScreenMode.REVERSE to "Play on the second screen").takeIf { d.mode == DualScreenMode.REVERSE },
                ),
                optionDetail = {
                    when (it) {
                        DualScreenMode.OFF -> "Leave the second screen alone"
                        DualScreenMode.LIBRARY_COMPANION -> "Art, logo and details of what you've selected, and the game you're playing"
                        DualScreenMode.GAME_COMPANION -> "Clock, battery, playtime and achievements beside the game"
                        DualScreenMode.REVERSE -> "Games open on the second screen when the emulator allows it"
                    }
                },
            ) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(mode = v)) } })
            if (app.hasTwoScreens) {
                val games by remember { app.store.settings.observe(ScopedSettings.LaunchScreen, null, null) }.collectAsState(null)
                val screens = LaunchDisplay.entries.map { it to screenName(it) }
                add(app.choiceRow(
                    "games.screen", "Games open on", FuseIcons.PanelTop, games?.value ?: LaunchDisplay.ASK, screens,
                    detail = "A game or system can have its own: game options, Screen, or the system's settings",
                    optionDetail = { if (it == LaunchDisplay.ASK) "Pick when a game starts, and remember it for the game or its system if you like" else null },
                ) { v -> app.scope.launch { app.store.settings.set(ScopedSettings.LaunchScreen, ScopeRef.Global, v) } })
                add(app.choiceRow(
                    "apps.screen", "Apps open on", FuseIcons.Smartphone, d.appScreen, screens,
                    detail = if (d.appScreens.isEmpty()) "An app can have its own: app options, Screen" else "${d.appScreens.size} ${if (d.appScreens.size == 1) "app has" else "apps have"} a screen of their own",
                ) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(appScreen = v)) } })
            }
            if (app.platform.features.secondScreen) {
                add(infoRow(
                    "dual", "Games with two screens", icon = FuseIcons.DualScreen,
                    detail = "The companion stays while games and apps run. DS, DSi, 3DS and Wii U games get the second screen: the companion steps aside while they run and comes back with Fuse. With Fuse as your Home app, Fuse is also the second screen's Home",
                ))
                add(MenuAction(
                    "dual.log", "Second screen status", FuseIcons.Activity,
                    detail = log.lastOrNull() ?: "Nothing has happened on the second screen yet",
                    trailing = Trailing.Chevron,
                    onSelect = {
                        app.choice = ChoiceSpec(
                            title = "Second screen status",
                            icon = FuseIcons.Activity,
                            message = log.takeLast(8).joinToString("\n").ifEmpty { "Nothing has happened on the second screen yet." },
                            options = listOf(MenuAction("dual.ok", "Close", FuseIcons.Check, onSelect = { app.choice = null })),
                        )
                    },
                ))
            }
            add(toggleRow(
                "hide", "Hide the second screen", FuseIcons.EyeOff, d.secondScreenHidden,
                if (d.flipped) "Not while the menus are on it" else "Dark, showing nothing, until you show it again here, in the quick menu, or with two double taps on it",
                enabled = !d.flipped,
            ) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(secondScreenHidden = v)) } })
            add(toggleRow("bg", "Same background as the main screen", FuseIcons.Image, d.companionFollowsBackground, "Its scene or picture behind what the second screen shows") { v -> app.store.updatePrefs { it.copy(display = it.display.copy(companionFollowsBackground = v)) } })
            add(toggleRow("perf", "Show performance on the second screen", FuseIcons.ChartLine, d.companionShowsPerformance, "Only values the system really reports; nothing is estimated") { v -> app.store.updatePrefs { it.copy(display = it.display.copy(companionShowsPerformance = v)) } })
            add(toggleRow("touch", "Touch controls on the second screen", FuseIcons.Hand, d.companionTouchControls) { v -> app.store.updatePrefs { it.copy(display = it.display.copy(companionTouchControls = v)) } })
        }
        // Only a device with a second screen mentions one.
        if (app.platform.features.secondScreen) labelled("Second screen") { addAll(second) }
    }
}

@Composable
fun performanceRows(app: AppState): List<MenuAction> {
    val drawing = app.platform.drawing.value
    val p by app.store.prefs.collectAsState()
    val displays by app.platform.displays.collectAsState()
    val cap = app.platform.device
    return buildList {
        labelled("Performance") {
            add(app.choiceRow(
                "profile", "Performance profile", FuseIcons.Gauge, p.performance,
                listOf(PerformanceProfile.AUTOMATIC to "Automatic", PerformanceProfile.LOW_POWER to "Low power", PerformanceProfile.BALANCED to "Balanced", PerformanceProfile.HIGH_QUALITY to "High quality"),
                detail = "Now: " + performanceSummary(p.performance, p.lowPower, cap, app.platform.host) +
                    "\nAutomatic picks for this device: ${cap.tier.name.lowercase().replaceFirstChar { it.uppercase() }}",
            ) { v -> app.store.updatePrefs { it.copy(performance = v) } })
            add(toggleRow("low", "Low Power Mode", FuseIcons.Leaf, p.lowPower, "60 Hz, no video previews, blur, moving backgrounds or CRT; lighter artwork and a smaller image cache. Navigation stays quick") { v -> app.store.updatePrefs { it.copy(lowPower = v) } })
            add(toggleRow("overlay", "Performance overlay", FuseIcons.ChartLine, p.performanceOverlay, "Fuse's own frame rate, memory and temperatures, only as the system reports them. Other apps' frame rates can't be read") { v -> app.store.updatePrefs { it.copy(performanceOverlay = v) } })
        }
        labelled("") {
            addAll(app.group(
                "displays.device", "About this device", FuseIcons.Laptop,
                summary = "${cap.cpuCores} cores  ·  ${(cap.totalRamMb / 1024.0 * 10).toInt() / 10.0} GB",
                detail = "Processor, memory and the displays Fuse can see",
            ) {
                buildList {
                    add(infoRow("cpu", "Processor", "${cap.cpuCores} cores", icon = FuseIcons.Chip))
                    add(infoRow("ram", "Memory", "${(cap.totalRamMb / 1024.0 * 10).toInt() / 10.0} GB", icon = FuseIcons.Memory))
                    add(infoRow("screen", "Screen", "${cap.screenWidthPx}x${cap.screenHeightPx}, up to ${cap.maxRefreshRate.toInt()} Hz", icon = FuseIcons.Monitor))
                    if (drawing != null) {
                        add(infoRow(
                            "drawing", "Drawn with", drawing.name, icon = FuseIcons.Gauge,
                            detail = if (drawing.gpu) null else "Fuse couldn't use the graphics card here, so it keeps motion light and effects simple. Updating the graphics driver usually fixes this",
                        ))
                    }
                    for (disp in displays) {
                        add(infoRow(
                            "disp.${disp.id}", disp.name + if (disp.isPrimary) " (main)" else "",
                            "${disp.widthPx}x${disp.heightPx}  ${disp.refreshRate.toInt()} Hz",
                            detail = when (disp.canLaunchActivities) {
                                Support.YES -> "Games can be opened here"
                                Support.NO -> "This display can't run other apps"
                                Support.UNKNOWN -> if (disp.isPrimary) null else "Fuse checks when you first launch a game here"
                            },
                            icon = FuseIcons.MonitorCheck,
                        ))
                    }
                }
            })
        }
    }
}

/** The screens Fuse is on, which way round it turns, and how hard it works, with what the device is at the end. */
@Composable
fun displayAndPerformanceRows(app: AppState): List<MenuAction> {
    val screens = displayRows(app)
    val performance = performanceRows(app)
    return buildList {
        under("This screen", "screen", screens)
        under("Performance", "perf", performance)
    }
}

@Composable
fun networkRows(app: AppState): List<MenuAction> = buildList {
    if (app.platform.features.wifiSettings) add(MenuAction("wifi", "Wi-Fi settings", FuseIcons.Wifi, trailing = Trailing.Chevron, onSelect = { app.platform.quick.openWifi() }))
    add(infoRow("where", "What Fuse connects to", detail = "Only services you set up: RetroAchievements, SteamGridDB, IGDB, TheGamesDB, ScreenScraper, libretro thumbnails, GitHub to check for updates, and rpcs3.net when you ask how a PS3 game runs. Your library works fully offline", icon = FuseIcons.Globe))
}

/** The services Fuse signs in to or pairs with: RetroAchievements and phones. Cartridge is in Addons. */
@Composable
fun accountsRows(app: AppState): List<MenuAction> {
    val achievements = achievementRows(app)
    val phone = phoneLinkRows(app)
    return buildList {
        under("RetroAchievements", "ra", achievements)
        under("Phone Link", "phone", phone)
    }
}

/** Where games live and what Fuse may do with them, then backing up and restoring. */
@Composable
fun storageAndBackupRows(app: AppState): List<MenuAction> {
    val files = storageRows(app)
    val backups = backupRows(app)
    return buildList {
        under("Files and drives", "files", files)
        under("Backups", "backup", backups.map { if (it.section == "Privacy") it.copy(section = null) else it })
    }
}

@Composable
fun storageRows(app: AppState): List<MenuAction> {
    val storage by app.platform.storage.state.collectAsState()
    val usage by app.store.storage.usage.collectAsState()
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
        // What the last measurement found, when there is one; otherwise what the page is for.
        val measured = usage?.takeIf { it.finished && it.games.isNotEmpty() }
        add(MenuAction(
            "space", "Games and space", FuseIcons.ChartPie,
            detail = measured?.let { u -> "${u.games.size} games take ${bytesText(u.games.sumOf { it.bytes })}. See each drive, and delete games you're done with" }
                ?: "What each game takes on each drive, and deleting games you're done with",
            trailing = Trailing.Chevron,
            onSelect = { app.go(Route.Storage) },
        ))
        // Films and shows kept from Jellyfin: their own page, since they move and go as a group.
        if (app.store.offlineMedia.supported && app.store.prefs.value.jellyfin.enabled) {
            val kept by app.store.offlineMedia.entries.collectAsState()
            add(MenuAction(
                "offline", "Films and shows downloaded", FuseIcons.Clapperboard,
                detail = if (kept.isEmpty()) "Nothing kept yet. Download from Jellyfin to watch without the server"
                else "${kept.size} kept, ${bytesText(kept.filter { it.here }.sumOf { it.meta.sizeBytes })}. Play, delete or move them to another drive",
                trailing = Trailing.Chevron,
                onSelect = { app.go(Route.OfflineMedia) },
            ))
        }
        add(infoRow("readonly", "Fuse only changes your games when you ask", detail = "It reads your folders. Files are only deleted when you delete games in Games and space, after you confirm. Playlists for multi-disc games are made in Fuse's own storage", icon = FuseIcons.ShieldCheck))
    }
}

@Composable
fun privacyRows(app: AppState): List<MenuAction> = listOf(
    infoRow("telemetry", "No telemetry", detail = "Fuse sends no analytics, crash reports or usage data", icon = FuseIcons.ShieldCheck),
    infoRow("uploads", "Nothing about your library leaves this device", detail = "Game names are only sent to the art and metadata sources you turn on, when looking up that game", icon = FuseIcons.Lock),
    infoRow("keys", "Keys are stored encrypted", detail = "API keys and passwords never appear in logs and are never shared with other apps", icon = FuseIcons.LockKeyhole),
    app.confirmRow("wipe", "Delete all saved keys", FuseIcons.Trash, "Delete all saved keys?", "RetroAchievements and scraper keys are removed from this device.", "Delete keys", destructive = true) {
        app.scope.launch {
            for (k in app.store.credentials.stored.value) app.store.credentials.remove(k)
            app.store.achievements.disconnect()
        }
    }.copy(section = ""),
)

private const val FUSE_RELEASES = "https://github.com/MAtiyaaa/fuse/releases/latest"

@Composable
fun updateRows(app: AppState): List<MenuAction> {
    val p by app.store.prefs.collectAsState()
    val available by app.store.updates.available.collectAsState()
    val state by app.store.updates.state.collectAsState()
    return buildList {
        add(infoRow("version", "Fuse", app.store.updates.currentVersion, icon = FuseIcons.Info))
        val r = available
        if (r != null && !app.store.updates.inPlace) {
            // Windows and macOS install Fuse themselves: the release page has the installer.
            add(MenuAction(
                "get", "Get ${r.name}", FuseIcons.External,
                detail = "Opens its release page. Install it over this one; your library and settings stay",
                trailing = Trailing.Chevron,
                onSelect = { app.platform.openUrl(r.htmlUrl.ifBlank { FUSE_RELEASES }) },
            ))
        } else if (r != null) {
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
                val name = io.github.matiyaaa.fuse.ui.shell.notes.releaseNameOf(r.notes) ?: r.name.substringAfter(" - ", "").ifBlank { null }
                app.go(Route.ReleaseNotes(r.tag.removePrefix("v"), name, r.notes, installed = false))
            }))
        }
        add(MenuAction(
            "installed-notes", "What's new in this version", FuseIcons.Sparkles,
            detail = "Fuse ${app.store.updates.currentVersion}: what it brought, on a page of its own",
            trailing = Trailing.Chevron,
            onSelect = { app.openInstalledNotes() },
        ))
        add(MenuAction("check", "Check for updates", FuseIcons.Refresh, onSelect = {
            app.scope.launch { app.toasts.show(if (app.store.updates.check() != null) "An update is available" else "Fuse is up to date") }
        }))
        add(toggleRow("auto", "Check automatically", FuseIcons.Bell, p.checkForUpdates, "Once a day from GitHub Releases; nothing installs without you") { v -> app.store.updatePrefs { it.copy(checkForUpdates = v) } })
    }
}

/**
 * What Fuse is and keeping it current: the version (five taps for developer options), updates,
 * privacy and what Fuse connects to, setup and licences, then the credits folded away.
 */
@Composable
fun aboutRows(app: AppState): List<MenuAction> = buildList {
    val updates = updateRows(app).filter { it.id != "version" }
    val privacy = privacyRows(app)
    val network = networkRows(app)
    add(MenuAction(
        "fuse", "Fuse ${app.store.updates.currentVersion}", FuseIcons.Info,
        detail = "A console-style home for your games. Free and open source (GPL-3.0-or-later)",
        // Five taps turn on the developer options, for this launch only.
        onSelect = {
            val wasOn = app.dev.enabled
            val left = app.dev.tap()
            when {
                !wasOn && app.dev.enabled -> app.toasts.show("Developer options are on until Fuse closes", ToastKind.SUCCESS)
                left in 1..3 -> app.toasts.show(if (left == 1) "One more tap for developer options" else "$left more taps for developer options")
            }
        },
    ))
    under("Updates", "update", updates)
    // What Fuse keeps to itself and what it connects to, with deleting keys set apart after both.
    under("Privacy and network", "privacy", privacy.filter { it.section == null })
    under("Privacy and network", "net", network)
    under("", "privacy", privacy.filter { it.section != null })
    labelled("Fuse") {
        add(MenuAction("setup", "Run setup again", FuseIcons.Sparkles, detail = "Games, emulators, controller, Home and theme, one step at a time", trailing = Trailing.Chevron, onSelect = { app.go(Route.Onboarding) }))
        add(MenuAction("licences", "Open-source licences", FuseIcons.FileText, detail = "Fuse, its libraries, fonts and icons", trailing = Trailing.Chevron, onSelect = { app.go(Route.Licenses) }))
        addAll(app.group("about.credits", "Credits and links", FuseIcons.Heart, detail = "Who Fuse is built on, and where it lives") {
            listOf(
                // Fuse's own systems first: they are what Fuse is made of.
                infoRow("credits.sync", "Fuse Sync by Fuse", detail = "Fuse's own sync: saves, play time, library and settings on every device, from a host of your own", icon = FuseIcons.RefreshCcw),
                infoRow("credits.player", "Fuse Player by Fuse", detail = "Fuse's own video and music player: Media3 on Android, FFmpeg on computers, every subtitle drawn by Fuse", icon = FuseIcons.Clapperboard),
                infoRow("credits.fuseline", "Fuseline by Fuse", detail = "Fuse's own animation engine: every movement in Fuse, at about a tenth of Compose's cost", icon = FuseIcons.Waves),
                infoRow("credits", "Made with", detail = "Kotlin, Compose Multiplatform, SQLDelight, Ktor, Coil. Video: Media3 (Apache 2.0) and FFmpeg (GPL). Icons: Lucide (ISC). Fonts: Sora and Manrope (SIL OFL). Systems and emulator launch data: ES-DE (MIT), RomM and Cartridge (MIT). Hashing rules: rcheevos (MIT). Music: boipurple", icon = FuseIcons.Blocks),
                MenuAction(
                    "cartridge.credit", "Cartridge by abdu2304", FuseIcons.CloudDownload,
                    detail = "The RomM companion Fuse pairs with. github.com/abdu2304/cartridge",
                    trailing = Trailing.Chevron,
                    onSelect = { app.platform.openUrl("https://github.com/abdu2304/cartridge") },
                ),
                infoRow("trademarks", "Trademarks", detail = "Console and game names belong to their owners. Fuse ships no console artwork, sounds, BIOS or games", icon = FuseIcons.Tag),
                MenuAction("website", "Website", FuseIcons.Globe, detail = "matiyaaa.github.io/fuse: downloads and themes", trailing = Trailing.Chevron, onSelect = { app.platform.openUrl("https://matiyaaa.github.io/fuse/") }),
                MenuAction("source", "Source code", FuseIcons.External, detail = "github.com/MAtiyaaa/fuse", trailing = Trailing.Chevron, onSelect = { app.platform.openUrl("https://github.com/MAtiyaaa/fuse") }),
            )
        })
    }
    if (app.dev.enabled) {
        labelled("Developer") {
            add(MenuAction(
                "dev.setup", "Replay setup", FuseIcons.RotateCcw,
                detail = "A rehearsal: every step shows, but nothing is added, removed or kept",
                trailing = Trailing.Chevron,
                onSelect = {
                    app.dev.rehearsalPrefs = app.store.prefs.value
                    app.go(Route.Onboarding)
                    // From the beginning, opening and all.
                    app.setupOpening = true
                },
            ))
            add(MenuAction(
                "dev.opening", "Play setup opening", FuseIcons.Flame,
                detail = "The longer animation setup opens with on the first start",
                onSelect = { app.setupOpening = true },
            ))
            // Profile arrivals, as the person playing (or a sample when nobody is): nothing changes.
            fun arriving() = app.syncProfile ?: io.github.matiyaaa.fuse.sync.ProfileInfo("dev.sample", "Mo", "cat", protected = false, createdAt = 0)
            add(MenuAction(
                "dev.arrival", "Play profile switch", FuseIcons.Users,
                detail = "The animation when someone becomes the one playing",
                onSelect = { app.arrivalGrand = false; app.profileArrival = arriving() },
            ))
            add(MenuAction(
                "dev.firstArrival", "Play first profile welcome", FuseIcons.UserPlus,
                detail = "The fuse that burns in when a profile is made, as in setup",
                onSelect = { app.arrivalGrand = true; app.arrivalMade = true; app.profileArrival = arriving() },
            ))
            add(MenuAction(
                "dev.firstSignIn", "Play first sign-in welcome", FuseIcons.UserRound,
                detail = "The same welcome, the first time someone plays on this device",
                onSelect = { app.arrivalGrand = true; app.arrivalMade = false; app.profileArrival = arriving() },
            ))
            add(MenuAction(
                "dev.intro", "Play startup animation", FuseIcons.Sparkles,
                detail = "Plays it now, as when Fuse starts",
                onSelect = { app.intro = true },
            ))
            if (app.store.appStore.supported) {
                add(MenuAction(
                    "dev.store", "Restart Store setup", FuseIcons.Store,
                    detail = "Forgets the chosen edition and opens the Store on its first page",
                    trailing = Trailing.Chevron,
                    onSelect = {
                        app.store.updatePrefs { it.copy(storeVariant = null) }
                        app.openStore()
                    },
                ))
            }
            add(toggleRow("dev.skip", "Skip required setup steps", FuseIcons.ChevronsRight, app.dev.skipRequired) { app.dev.skipRequired = it })
            add(toggleRow("dev.frames", "Frame-time overlay", FuseIcons.Activity, app.dev.frameGraph) { app.dev.frameGraph = it })
            add(MenuAction("dev.off", "Turn off developer options", FuseIcons.Power, onSelect = {
                app.dev.enabled = false
                app.dev.taps = 0
                app.dev.skipRequired = false
                app.dev.frameGraph = false
            }))
        }
    }
    // The last crash, if there was one, at the very end: worth finding, never in the way.
    app.platform.lastCrashReport()?.let { report -> labelled("Last crash") { add(crashRow(app, report)) } }
    labelled("Start over") { add(eraseRow(app)) }
}

/**
 * Erase Fuse, the last row of About: asks twice, says plainly what goes and what stays, lets go of
 * Fuse Sync first (a host is deleted, a device forgets its host), then Fuse starts again as new.
 */
private fun eraseRow(app: AppState): MenuAction = MenuAction(
    "erase", "Erase Fuse", FuseIcons.Trash, destructive = true,
    detail = "Start over as new. Your games, emulators and their saves stay",
    onSelect = {
        val host = app.store.prefs.value.sync.role == "HOST"
        app.confirm = ConfirmSpec(
            "Erase Fuse?",
            "Fuse's library, settings, themes, Home, profiles, play time, art and caches are erased" +
                (if (host) ", and the Fuse Sync host on this computer with everyone's saves and profiles." else if (app.store.sync.inUse) ", and this device leaves Fuse Sync." else ".") +
                " Game files, emulators and the saves in the emulators' folders stay exactly where they are.",
            "Continue", destructive = true,
        ) {
            app.confirm = ConfirmSpec(
                "Erase everything for good?",
                "This can't be undone. Fuse closes and opens again as if it were new.",
                "Erase Fuse", destructive = true,
            ) {
                app.scope.launch {
                    val sync = app.store.sync.service
                    if (sync != null) {
                        if (host) runCatching { sync.deleteHost() }
                        runCatching { sync.setEnabled(false, keepProfiles = false) }
                    }
                    // Saved keys too, wherever the system keeps them.
                    for (k in app.store.credentials.stored.value) runCatching { app.store.credentials.remove(k) }
                    if (!app.platform.eraseAndRestart()) app.toasts.show("Fuse can't erase itself here", ToastKind.ERROR)
                }
            }
        }
    },
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
        app.choice = ChoiceSpec(
            title = "Last crash report",
            icon = FuseIcons.Warning,
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
