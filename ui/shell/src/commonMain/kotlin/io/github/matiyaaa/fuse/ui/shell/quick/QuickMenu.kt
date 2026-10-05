package io.github.matiyaaa.fuse.ui.shell.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.BatteryGlyph
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.HintBar
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuRow
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.expandVertically
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
import io.github.matiyaaa.fuse.ui.fuseline.shrinkVertically
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.applyUpdate
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.sections
import io.github.matiyaaa.fuse.ui.shell.capture.rememberRecordingTime
import io.github.matiyaaa.fuse.ui.shell.components.ControlTile
import io.github.matiyaaa.fuse.ui.shell.components.ROW_CONTENT_START
import io.github.matiyaaa.fuse.ui.shell.components.batteryTimeText
import io.github.matiyaaa.fuse.ui.shell.components.rememberRowHighlight
import io.github.matiyaaa.fuse.ui.shell.home.switchHomeStyle
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import io.github.matiyaaa.fuse.ui.shell.music.MenuMusicRemote
import io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle
import io.github.matiyaaa.fuse.ui.shell.settings.next
import io.github.matiyaaa.fuse.ui.shell.settings.performanceLabel
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A tile; a [toggle] says On or Off under its name and is lit while [active]. [hold] is what holding
 * it does (A held, or a long press); without it a hold is an ordinary press.
 */
private data class QuickTile(
    val label: String,
    val icon: ImageVector,
    val active: Boolean = false,
    val detail: String? = null,
    val toggle: Boolean = false,
    val hold: (() -> Unit)? = null,
    val run: () -> Unit,
)

/** Where the controller is: a place in the grid, a row of the list under it, or a row of Add. */
private enum class QuickZone { GRID, LIST, ADD }

/** Where the scrolling column is, for placing the list's highlight in its coordinates. */
private class ListBase {
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
}

/** The Add tile's key among the grid's items while editing. */
private const val ADD_KEY = "add"

/**
 * The console-style quick menu (Start): a grid the user arranges (switches, sliders, Now playing,
 * the second screen's three ways) over a short list of Fuse's own actions.
 *
 * Editing (X, the pencil, or Edit quick menu): A picks an item up and the D-pad carries it, X
 * changes its size, Y takes it out, and the Add tile at the end puts things back. By touch or the
 * mouse an item is dragged where it should go, with its corner buttons for size and removal. Every
 * change is kept at once.
 */
@Composable
fun QuickMenu(app: AppState) {
    val open = app.quickMenuOpen
    val platform = app.platform
    val features = platform.features
    val prefs by app.store.prefs.collectAsState()
    val status by platform.status.collectAsState()
    val brightness by platform.quick.brightness.collectAsState()
    val volume by platform.quick.volume.collectAsState()
    val isHome = platform.homeRole?.isHome?.collectAsState()?.value ?: false
    val twoScreens = features.secondScreen
    val scope = rememberCoroutineScope()

    fun close() { app.quickMenuOpen = false }
    val updateAvailable by app.store.updates.available.collectAsState()
    val updateState by app.store.updates.state.collectAsState()
    val scan by app.store.sources.scan.collectAsState()
    val scanning = scan.phase == ScanPhase.DISCOVERING || scan.phase == ScanPhase.SCANNING || scan.phase == ScanPhase.SAVING
    val capture = app.capture
    val recordingTime = rememberRecordingTime(capture)
    val windows = platform.windowControls
    val sections = app.sections

    // The arrangement: what the user stored, or Fuse's own. While the menu is open, changes show at
    // once from here and are saved behind it.
    var working by remember { mutableStateOf<List<QuickSlot>?>(null) }
    val all = working ?: remember(prefs.quickMenu) { QuickLayout.decode(prefs.quickMenu) }
    fun available(id: QuickId): Boolean = when (id) {
        QuickId.WIFI -> features.wifiSettings
        QuickId.BLUETOOTH -> features.bluetoothSettings
        QuickId.CAPTURE -> capture != null
        QuickId.SECOND_SCREEN -> twoScreens
        QuickId.HIDE_SECOND -> twoScreens && !prefs.display.flipped
        QuickId.CARTRIDGE -> (prefs.cartridgeEnabled || sections.addons) && app.offers(Destination.CARTRIDGE)
        QuickId.BRIGHTNESS -> features.brightness && brightness != null
        QuickId.VOLUME -> features.volume && volume != null
        QuickId.FULLSCREEN -> windows != null
        else -> true
    }
    val visible = all.filter { available(it.id) }
    fun save(next: List<QuickSlot>) {
        working = next
        app.store.updatePrefs { it.copy(quickMenu = QuickLayout.encode(next)) }
    }
    // Moves read the arrangement as it is now, not as this composition saw it: a drag moves an item
    // several times between frames.
    fun liveAll(): List<QuickSlot> = working ?: QuickLayout.decode(app.store.prefs.value.quickMenu)
    fun moveVisible(from: Int, to: Int) {
        val now = liveAll()
        val shown = now.filter { available(it.id) }
        val a = shown.getOrNull(from) ?: return
        val b = shown.getOrNull(to) ?: return
        if (a == b) return
        save(QuickLayout.move(now, now.indexOf(a), now.indexOf(b)))
    }
    fun moveItem(id: QuickId, to: Int) {
        val from = liveAll().filter { available(it.id) }.indexOfFirst { it.id == id }
        if (from >= 0 && from != to) moveVisible(from, to)
    }

    // Where focus is, and what editing is doing.
    var zone by remember { mutableStateOf(QuickZone.GRID) }
    var cell by remember { mutableIntStateOf(0) }
    var listRow by remember { mutableIntStateOf(0) }
    var addRow by remember { mutableIntStateOf(0) }
    var anchor by remember { mutableStateOf<Float?>(null) }
    var editing by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Any?>(null) }

    // ---------------------------------------------------------------- what each item says and does

    val display = prefs.display
    val choice = when {
        display.flipped -> SecondScreenChoice.FLIPPED
        display.mode == DualScreenMode.OFF -> SecondScreenChoice.OFF
        else -> SecondScreenChoice.FUSE
    }
    fun chooseSecond(next: SecondScreenChoice) {
        if (next == choice) return
        // The windows trade places when the menus move between screens; the menu closes first.
        if ((next == SecondScreenChoice.FLIPPED) != (choice == SecondScreenChoice.FLIPPED)) close()
        app.store.updatePrefs { p ->
            val d = p.display
            val shown = if (d.mode == DualScreenMode.OFF) DualScreenMode.LIBRARY_COMPANION else d.mode
            p.copy(
                display = when (next) {
                    SecondScreenChoice.OFF -> d.copy(mode = DualScreenMode.OFF, flipped = false)
                    SecondScreenChoice.FUSE -> d.copy(mode = shown, flipped = false, secondScreenHidden = false)
                    SecondScreenChoice.FLIPPED -> d.copy(mode = shown, flipped = true, secondScreenHidden = false)
                },
            )
        }
        app.toasts.show(
            when (next) {
                SecondScreenChoice.OFF -> "Second screen is off"
                SecondScreenChoice.FUSE -> "Your games show on the screen below"
                SecondScreenChoice.FLIPPED -> "Your games show on the screen above"
            },
        )
    }

    // Now playing: Fuse Player's item while there is one, else the menu music.
    val session = if (io.github.matiyaaa.fuse.ui.player.FusePlayer.available) io.github.matiyaaa.fuse.ui.player.FusePlayer.session else null
    val media = session?.item
    val mediaPlaying = session?.engine?.state?.collectAsState()?.value?.playing == true
    var mediaSkips by remember { mutableIntStateOf(0) }
    var mediaDirection by remember { mutableIntStateOf(1) }
    val remote = MenuMusicRemote
    val music = prefs.music
    val track = BundledMusic.byId(remote.current)
    val now = if (media != null) {
        NowPlaying(media.title, media.subtitle ?: "Fuse Player", mediaPlaying, media = true, off = false, key = media.id, direction = mediaDirection, skips = mediaSkips)
    } else {
        NowPlaying(
            title = when {
                !music.enabled -> "Menu music is off"
                track != null -> track.title
                else -> music.songName ?: "Menu music"
            },
            subtitle = when {
                !music.enabled -> "Turn it on for a song under the menus"
                track != null -> "${BundledMusic.ARTIST} · ${track.album}"
                else -> "Your song"
            },
            playing = music.enabled && !remote.paused && remote.current != null,
            media = false,
            off = !music.enabled,
            key = remote.current,
            direction = remote.lastSkip,
            skips = remote.skipCount,
        )
    }
    fun skipSong(direction: Int) {
        if (media != null) {
            mediaDirection = direction
            mediaSkips++
            if (direction < 0) session.previous() else session.next()
        } else if (music.enabled) {
            remote.skip(direction)
        }
    }
    fun toggleSong() {
        when {
            media != null -> session.toggle()
            !music.enabled -> {
                remote.paused = false
                app.store.updatePrefs { it.copy(music = it.music.copy(enabled = true)) }
            }
            else -> remote.toggle()
        }
    }

    fun sliderValue(id: QuickId): Float = (if (id == QuickId.BRIGHTNESS) brightness else volume) ?: 0f
    fun setSlider(id: QuickId, v: Float) {
        val value = v.coerceIn(0f, 1f)
        if (id == QuickId.BRIGHTNESS) platform.quick.setBrightness(value) else platform.quick.setVolume(value)
    }

    fun tileFor(id: QuickId): QuickTile = when (id) {
        // Both open the system's panel, so they are never lit like a switch Fuse flips; they say how
        // things stand where the platform reports it.
        QuickId.WIFI -> QuickTile("Wi-Fi", if (status.wifi == ConnectionState.OFF) FuseIcons.WifiOff else FuseIcons.Wifi, detail = if (status.ethernet && status.wifi != ConnectionState.CONNECTED) "On a cable" else connectionText(status.wifi)) {
            platform.quick.openWifi()
        }
        QuickId.BLUETOOTH -> QuickTile("Bluetooth", if (status.bluetooth == ConnectionState.OFF) FuseIcons.BluetoothOff else FuseIcons.Bluetooth, detail = connectionText(status.bluetooth)) {
            platform.quick.openBluetooth()
        }
        // A screenshot three seconds after the menu closes; held, a recording after the same wait.
        QuickId.CAPTURE -> if (recordingTime != null) {
            QuickTile("Stop", FuseIcons.Square, active = true, detail = "$recordingTime recorded") { close(); capture?.stopRecording() }
        } else {
            QuickTile("Screenshot", FuseIcons.Camera, detail = "Hold to record", hold = { close(); capture?.toggleRecording(delayed = true) }) { close(); capture?.screenshot(delayed = true) }
        }
        QuickId.SECOND_SCREEN -> QuickTile("Second screen", FuseIcons.DualScreen, active = choice != SecondScreenChoice.OFF, detail = choice.label) {
            chooseSecond(SecondScreenChoice.entries[(choice.ordinal + 1) % SecondScreenChoice.entries.size])
        }
        QuickId.HIDE_SECOND -> {
            val hidden = display.secondScreenHidden
            QuickTile("Hide screen", FuseIcons.EyeOff, active = hidden, toggle = true) {
                app.store.updatePrefs { it.copy(display = it.display.copy(secondScreenHidden = !hidden)) }
                app.toasts.show(if (hidden) "The second screen is back" else "The second screen is hidden")
            }
        }
        QuickId.DISPLAY -> QuickTile("Display", FuseIcons.Monitor) { close(); app.go(Route.Settings("displays")) }
        QuickId.CONTROLLER -> QuickTile("Controller", FuseIcons.Gamepad) { close(); app.go(Route.Settings("inputs")) }
        QuickId.PERFORMANCE -> QuickTile("Performance", FuseIcons.Gauge, detail = performanceLabel(prefs.performance)) {
            val next = prefs.performance.next()
            app.store.updatePrefs { it.copy(performance = next) }
            // Say what changed, since most of it is felt rather than seen.
            app.toasts.show(io.github.matiyaaa.fuse.ui.shell.settings.performanceSummary(next, prefs.lowPower, platform.device, platform.host))
        }
        QuickId.LOW_POWER -> QuickTile("Low Power", FuseIcons.Leaf, active = prefs.lowPower, toggle = true) {
            val on = !prefs.lowPower
            app.store.updatePrefs { it.copy(lowPower = on) }
            app.toasts.show(if (on) "Low Power is on" else "Low Power is off")
        }
        // Games put on a card or drive while Fuse was open show up without a trip to Settings.
        QuickId.FIND_GAMES -> QuickTile(
            "Find games", FuseIcons.FolderSearch, active = scanning,
            detail = when {
                scanning -> "Looking"
                scan.phase == ScanPhase.DONE && scan.added > 0 -> "${scan.added} new"
                else -> "Scan folders"
            },
        ) {
            if (scanning) {
                app.toasts.show("Already looking for new games")
            } else {
                app.store.sources.rescan(ScanScope.QUICK)
                app.toasts.show("Looking for new games")
            }
        }
        QuickId.CARTRIDGE -> QuickTile(sections.label(Destination.CARTRIDGE), sections.icon(Destination.CARTRIDGE)) { close(); app.selectTab(Destination.CARTRIDGE) }
        QuickId.HOME_STYLE -> QuickTile("Home", if (prefs.home.mode == HomeMode.CHANNELS) FuseIcons.Grid else FuseIcons.Rows, detail = if (prefs.home.mode == HomeMode.CHANNELS) "Channels" else "Flow") {
            app.switchHomeStyle()
        }
        QuickId.SOUND -> {
            val soundOn = prefs.sound != io.github.matiyaaa.fuse.model.SoundProfile.OFF
            QuickTile("Sound", if (soundOn) FuseIcons.Volume else FuseIcons.VolumeOff, active = soundOn, toggle = true) {
                app.store.updatePrefs { it.copy(sound = if (it.sound == io.github.matiyaaa.fuse.model.SoundProfile.OFF) io.github.matiyaaa.fuse.model.SoundProfile.SOFT else io.github.matiyaaa.fuse.model.SoundProfile.OFF) }
            }
        }
        QuickId.SEARCH -> QuickTile("Search", FuseIcons.Search) { close(); app.go(Route.Search) }
        QuickId.THEMES -> QuickTile("Themes", FuseIcons.Palette, detail = prefs.theme.name) { close(); app.go(Route.Themes) }
        QuickId.PHONE_LINK -> QuickTile("Phone Link", FuseIcons.Smartphone, active = prefs.phoneLinkEnabled, detail = if (prefs.phoneLinkEnabled) "On" else "Off") { close(); app.go(Route.PhoneLink) }
        QuickId.PLAY_TIME -> QuickTile("Play time", FuseIcons.Hourglass) { close(); app.go(Route.PlayTime) }
        QuickId.STANDBY -> QuickTile("Standby", FuseIcons.Moon, detail = "Rest the screen") { close(); app.standby = true }
        QuickId.FRAME_TIMES -> QuickTile("Frame times", FuseIcons.Activity, active = prefs.performanceOverlay, toggle = true) {
            app.store.updatePrefs { it.copy(performanceOverlay = !it.performanceOverlay) }
        }
        QuickId.FULLSCREEN -> {
            val full = windows?.mode == WindowStyle.FULLSCREEN
            QuickTile("Full screen", if (full) FuseIcons.Minimize else FuseIcons.Maximize, active = full, toggle = true) {
                windows?.setMode(if (full) WindowStyle.WINDOWED else WindowStyle.FULLSCREEN)
            }
        }
        QuickId.MUSIC -> QuickTile(now.title, FuseIcons.Music, active = now.playing, detail = if (now.off) "Turn on" else "Tap to skip", hold = ::toggleSong) {
            if (now.off) toggleSong() else skipSong(1)
        }
        QuickId.BRIGHTNESS, QuickId.VOLUME -> QuickTile(id.title, iconFor(id), detail = "${(sliderValue(id) * 100).roundToInt()}%") { adjusting = !adjusting }
    }

    // ---------------------------------------------------------------- the list under the grid

    val actions = buildList {
        val release = updateAvailable
        if (release != null && !app.store.updates.inPlace) {
            add(MenuAction("update", "Get ${release.name}", FuseIcons.External, detail = "Opens its release page", onSelect = { close(); app.go(Route.Settings("about")) }))
        } else if (release != null) {
            val ready = updateState is UpdateState.Ready
            add(
                MenuAction(
                    "update", if (ready) "Restart and update" else "Update to ${release.name}", if (ready) FuseIcons.Refresh else FuseIcons.Download,
                    detail = when (val u = updateState) {
                        is UpdateState.Downloading -> "Downloading" + (u.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
                        is UpdateState.Ready -> "Downloaded and checked"
                        else -> "Download it in Settings, About"
                    },
                    onSelect = { close(); if (ready) app.applyUpdate() else app.go(Route.Settings("about")) },
                ),
            )
        }
        add(
            MenuAction(
                "fullscan", "Rescan every folder", FuseIcons.RefreshDot,
                detail = "Reads all your drives again, for games a quick look misses. Your edits are kept",
                onSelect = {
                    close()
                    app.store.sources.rescan(ScanScope.FULL)
                    app.toasts.show("Rescanning every folder in the background")
                },
            ),
        )
        add(MenuAction("edit", "Edit quick menu", FuseIcons.Pencil, detail = "Move, resize, add and take out", onSelect = { editing = true; zone = QuickZone.GRID; cell = 0 }))
        add(MenuAction("home", "Arrange Home", FuseIcons.Dashboard, onSelect = { close(); app.go(Route.Settings("home")) }))
        add(MenuAction("settings", "Settings", FuseIcons.Settings, trailing = Trailing.Chevron, onSelect = { close(); app.go(Route.Settings()) }))
        add(MenuAction("restart", "Restart Fuse", FuseIcons.Refresh, onSelect = { platform.restart() }))
        if (features.canExit && !isHome) {
            add(
                MenuAction("exit", "Exit Fuse", FuseIcons.LogOut, onSelect = {
                    close()
                    app.confirm = ConfirmSpec("Exit Fuse?", "Fuse closes. Your library and settings are kept.", "Exit") { platform.exit() }
                }),
            )
        }
    }

    // What Add offers: everything this device has that isn't in the menu, then putting it all back.
    val addable = QuickId.entries.filter { id -> available(id) && all.none { it.id == id } }
    fun addItem(id: QuickId) {
        save(all + QuickSlot(id, id.defaultSpan))
        zone = QuickZone.GRID
        cell = visible.size
        app.toasts.show("${id.title} is in the quick menu")
    }
    fun resetLayout() {
        save(QuickLayout.default)
        zone = QuickZone.GRID
        cell = 0
        app.toasts.show("The quick menu is as it came")
    }

    // The grid: the items, and the Add tile last while editing.
    val gridSpans = visible.map { it.span } + if (editing) listOf(1) else emptyList()
    val placed = QuickLayout.place(gridSpans)
    val cells = gridSpans.size
    cell = cell.coerceIn(0, (cells - 1).coerceAtLeast(0))
    listRow = listRow.coerceIn(0, (actions.size - 1).coerceAtLeast(0))
    addRow = addRow.coerceIn(0, addable.size)
    if (cells == 0 && zone == QuickZone.GRID && !editing) zone = QuickZone.LIST

    fun leaveEditing() {
        editing = false
        held = false
        dragging = null
        if (zone == QuickZone.ADD) zone = QuickZone.GRID
    }
    LaunchedEffect(open) {
        if (open) {
            // Each opening starts at the top, on the first item.
            zone = if (visible.isEmpty()) QuickZone.LIST else QuickZone.GRID
            cell = 0; listRow = 0; anchor = null
            platform.sounds.play(SoundCue.OPEN)
        } else {
            leaveEditing()
            adjusting = false
            working = null
        }
    }

    fun gridMove(dx: Int, dy: Int): NavResult {
        val here = placed.getOrNull(cell) ?: return NavResult.BLOCKED
        if (dy != 0 && anchor == null) anchor = QuickLayout.centre(here)
        if (dx != 0) anchor = null
        val target = QuickLayout.neighbour(placed, cell, dx, dy, anchor)
        if (target == null) {
            // Down from the last row goes on to the list (not while editing: it is put away).
            if (dy > 0 && !editing && actions.isNotEmpty()) { zone = QuickZone.LIST; listRow = 0; return NavResult.MOVED }
            if (held && dy > 0 && cell < visible.lastIndex) { moveVisible(cell, visible.lastIndex); cell = visible.lastIndex; return NavResult.MOVED }
            return NavResult.BLOCKED
        }
        if (held) {
            // Carried: it takes the place it moves to. The Add tile always stays last.
            val to = target.coerceAtMost(visible.lastIndex)
            if (to == cell) return NavResult.BLOCKED
            moveVisible(cell, to)
            cell = to
        } else {
            cell = target
        }
        return NavResult.MOVED
    }

    fun press(i: Int, hold: Boolean) {
        val slot = visible.getOrNull(i) ?: return
        when (slot.id.kind) {
            QuickKind.TILE -> tileFor(slot.id).let { t -> val h = t.hold; if (hold && h != null) h() else t.run() }
            QuickKind.CHOICE -> chooseSecond(SecondScreenChoice.entries[(choice.ordinal + 1) % SecondScreenChoice.entries.size])
            QuickKind.MUSIC -> if (slot.span == 1 && !hold && !now.off) skipSong(1) else toggleSong()
            QuickKind.SLIDER -> if (slot.span < QuickLayout.COLUMNS) adjusting = !adjusting
        }
    }

    if (open) {
        // Holding A turns into REORDER: the Screenshot tile records, Now playing pauses.
        InputLayer(priority = LayerPriority.OVERLAY, modal = true, longPress = true) { e ->
            val slot = visible.getOrNull(cell)
            when {
                // A slider being set: every direction moves it, A or B is done.
                adjusting && slot != null && slot.id.kind == QuickKind.SLIDER -> when (e.action) {
                    NavAction.UP, NavAction.RIGHT -> { setSlider(slot.id, sliderValue(slot.id) + 0.05f); NavResult.MOVED }
                    NavAction.DOWN, NavAction.LEFT -> { setSlider(slot.id, sliderValue(slot.id) - 0.05f); NavResult.MOVED }
                    NavAction.SELECT, NavAction.BACK, NavAction.REORDER -> { adjusting = false; NavResult.ACTIVATED }
                    NavAction.QUICK_MENU -> { close(); NavResult.CONSUMED }
                    else -> NavResult.CONSUMED
                }
                zone == QuickZone.ADD -> when (e.action) {
                    NavAction.UP -> if (addRow > 0) { addRow--; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.DOWN -> if (addRow < addable.size) { addRow++; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.SELECT -> { addable.getOrNull(addRow)?.let(::addItem) ?: resetLayout(); NavResult.ACTIVATED }
                    NavAction.BACK -> { zone = QuickZone.GRID; NavResult.CONSUMED }
                    NavAction.QUICK_MENU -> { close(); NavResult.CONSUMED }
                    else -> NavResult.CONSUMED
                }
                editing -> when (e.action) {
                    NavAction.BACK -> { if (held) held = false else leaveEditing(); NavResult.CONSUMED }
                    NavAction.QUICK_MENU -> { close(); NavResult.CONSUMED }
                    NavAction.UP -> gridMove(0, -1)
                    NavAction.DOWN -> gridMove(0, 1)
                    NavAction.LEFT -> gridMove(-1, 0)
                    NavAction.RIGHT -> gridMove(1, 0)
                    NavAction.SELECT, NavAction.REORDER -> {
                        if (cell >= visible.size) { zone = QuickZone.ADD; addRow = 0 } else held = !held
                        NavResult.ACTIVATED
                    }
                    NavAction.CONTEXT -> {
                        val s = visible.getOrNull(cell) ?: return@InputLayer NavResult.BLOCKED
                        if (s.id.spans.size < 2) return@InputLayer NavResult.BLOCKED
                        save(all.map { if (it.id == s.id) it.copy(span = s.id.nextSpan(s.span)) else it })
                        NavResult.ACTIVATED
                    }
                    NavAction.SEARCH -> {
                        val s = visible.getOrNull(cell) ?: return@InputLayer NavResult.BLOCKED
                        held = false
                        save(all.filter { it.id != s.id })
                        app.toasts.show("${s.id.title} is out. Add puts it back")
                        NavResult.ACTIVATED
                    }
                    else -> NavResult.CONSUMED
                }
                else -> when (e.action) {
                    NavAction.BACK, NavAction.QUICK_MENU -> { close(); platform.sounds.play(SoundCue.CLOSE); NavResult.CONSUMED }
                    NavAction.CONTEXT -> { editing = true; held = false; if (zone != QuickZone.GRID) { zone = QuickZone.GRID; cell = 0 }; NavResult.ACTIVATED }
                    NavAction.UP -> when (zone) {
                        QuickZone.LIST -> when {
                            listRow > 0 -> { listRow--; NavResult.MOVED }
                            cells > 0 -> {
                                // Back up into the grid, on its last row, under where focus left it.
                                zone = QuickZone.GRID
                                val last = placed.maxOf { it.row }
                                val x = anchor ?: 0.5f
                                cell = placed.filter { it.row == last }.minBy { kotlin.math.abs(QuickLayout.centre(it) - x) }.index
                                NavResult.MOVED
                            }
                            else -> NavResult.BLOCKED
                        }
                        else -> gridMove(0, -1)
                    }
                    NavAction.DOWN -> when (zone) {
                        QuickZone.LIST -> if (listRow < actions.lastIndex) { listRow++; NavResult.MOVED } else NavResult.BLOCKED
                        else -> gridMove(0, 1)
                    }
                    NavAction.LEFT, NavAction.RIGHT -> {
                        if (zone != QuickZone.GRID) return@InputLayer NavResult.BLOCKED
                        val d = if (e.action == NavAction.LEFT) -1 else 1
                        // A widget across the whole menu takes left and right for itself.
                        if (slot != null && slot.span == QuickLayout.COLUMNS) {
                            when (slot.id.kind) {
                                QuickKind.SLIDER -> { setSlider(slot.id, sliderValue(slot.id) + 0.05f * d); return@InputLayer NavResult.MOVED }
                                QuickKind.MUSIC -> { skipSong(d); return@InputLayer NavResult.ACTIVATED }
                                QuickKind.CHOICE -> {
                                    val next = (choice.ordinal + d).coerceIn(0, SecondScreenChoice.entries.lastIndex)
                                    if (next == choice.ordinal) return@InputLayer NavResult.BLOCKED
                                    chooseSecond(SecondScreenChoice.entries[next])
                                    return@InputLayer NavResult.MOVED
                                }
                                QuickKind.TILE -> Unit
                            }
                        }
                        gridMove(d, 0)
                    }
                    NavAction.SELECT, NavAction.REORDER -> {
                        when (zone) {
                            QuickZone.LIST -> actions.getOrNull(listRow)?.onSelect?.invoke()
                            else -> press(cell, hold = e.action == NavAction.REORDER)
                        }
                        NavResult.ACTIVATED
                    }
                    else -> NavResult.CONSUMED
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    val highlight = rememberRowHighlight()
    val listRequesters = remember(actions.size) { List(actions.size) { BringIntoViewRequester() } }
    val addRequesters = remember(addable.size) { List(addable.size + 1) { BringIntoViewRequester() } }
    val cellRequesters = remember { HashMap<Any, BringIntoViewRequester>() }
    val cellHeights = remember { HashMap<Any, Float>() }
    val scroll = rememberScrollState()
    val margin = with(LocalDensity.current) { Space.l.toPx() }
    val cellKeys = visible.map<QuickSlot, Any> { it.id } + if (editing) listOf<Any>(ADD_KEY) else emptyList()
    // The selection scrolls into view, so the stick and the list never drift apart.
    LaunchedEffect(zone, cell, listRow, addRow, open, editing) {
        if (!open) return@LaunchedEffect
        when (zone) {
            QuickZone.GRID -> if (placed.getOrNull(cell)?.row == 0) scroll.fuselineScrollTo(0) else cellKeys.getOrNull(cell)?.let { k ->
                // With room around it, so it never stops under the faded edge.
                cellRequesters[k]?.bringIntoView(Rect(0f, -margin, 1f, (cellHeights[k] ?: 0f) + margin))
            }
            QuickZone.LIST -> if (listRow == actions.lastIndex) {
                scroll.fuselineScrollTo(scroll.maxValue)
            } else {
                val h = highlight.bounds[listRow]?.let { it.second - it.first } ?: 0f
                listRequesters.getOrNull(listRow)?.bringIntoView(Rect(0f, -margin, 1f, h + margin))
            }
            QuickZone.ADD -> if (addRow == 0) scroll.fuselineScrollTo(0) else addRequesters.getOrNull(addRow)?.bringIntoView()
        }
    }

    val reveal = rememberReveal(open)
    val listBase = remember { ListBase() }
    highlight.Follow(highlight.bounds[listRow]?.takeIf { open && zone == QuickZone.LIST && !editing })

    Overlay(visible = open, onDismiss = ::close, edge = OverlayEdge.END) {
        val c = Fuse.colors
        val motion = Fuse.motion
        val time = rememberClockText(prefs.clock24h)
        BoxWithConstraints {
            // A side sheet the height of the screen; a narrow screen gives it all but a margin.
            val width = minOf(QUICK_MENU_WIDTH, maxWidth - Space.l)
            Panel(Modifier.width(width).fillMaxHeight().padding(vertical = Space.l).padding(end = Space.l)) {
                Column(Modifier.fillMaxSize()) {
                    // The clock and battery stay put (or, while editing, what editing is); the rest scrolls under.
                    Swap(
                        targetState = editing,
                        transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.fade(Durations.FAST)) },
                        modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.l).reveal(reveal, 0),
                        label = "quick header",
                    ) { edit ->
                        if (edit) {
                            EditHeader(adding = zone == QuickZone.ADD, onDone = { if (zone == QuickZone.ADD) zone = QuickZone.GRID else leaveEditing() })
                        } else {
                            QuickHeader(time, status, onEdit = { editing = true; zone = QuickZone.GRID; cell = 0 })
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    Box(Modifier.fillMaxWidth().padding(horizontal = Space.l).height(Size.divider).background(c.hairline))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .fadingEdges(scroll, top = Space.l, bottom = Space.xl)
                            .verticalScroll(scroll)
                            // Room for a lifted tile's ring and the edit badges at the top, the sheet's corner at the bottom.
                            .padding(horizontal = Space.l)
                            .padding(top = Space.l, bottom = Space.l)
                            .onPlaced { listBase.coords = it }
                            .then(highlight.drawModifier()),
                    ) {
                        if (zone == QuickZone.ADD) {
                            AddList(addable, addRow, addRequesters, onAdd = ::addItem, onReset = ::resetLayout)
                        } else {
                            QuickGrid(
                                slots = visible,
                                editing = editing,
                                placed = placed,
                                cellKeys = cellKeys,
                                cell = if (zone == QuickZone.GRID && open) cell else -1,
                                held = held,
                                dragging = dragging,
                                requesters = cellRequesters,
                                heights = cellHeights,
                                reveal = { m, row -> m.reveal(reveal, row + 1) },
                                onDrag = { key, start ->
                                    dragging = key
                                    if (start) { held = false; cell = cellKeys.indexOf(key).coerceAtLeast(0); zone = QuickZone.GRID }
                                },
                                onDragEnd = { dragging = null },
                                onDragOver = { key, to -> (key as? QuickId)?.let { moveItem(it, to); cell = to } },
                                onSelect = { i -> cell = i; zone = QuickZone.GRID },
                                onResize = { i ->
                                    val s = visible.getOrNull(i)
                                    if (s != null) save(all.map { if (it.id == s.id) it.copy(span = s.id.nextSpan(s.span)) else it })
                                },
                                onRemove = { i ->
                                    val s = visible.getOrNull(i)
                                    if (s != null) {
                                        save(all.filter { it.id != s.id })
                                        app.toasts.show("${s.id.title} is out. Add puts it back")
                                    }
                                },
                                onAdd = { zone = QuickZone.ADD; addRow = 0 },
                            ) { i, slot, selected, modifier ->
                                val pick = { cell = i; zone = QuickZone.GRID }
                                when {
                                    slot.id.kind == QuickKind.MUSIC -> MusicWidget(
                                        now, slot.span, selected, modifier,
                                        onPrevious = { pick(); skipSong(-1) },
                                        onToggle = { pick(); toggleSong() },
                                        onNext = { pick(); skipSong(1) },
                                    )
                                    slot.id.kind == QuickKind.CHOICE && slot.span == QuickLayout.COLUMNS -> ChoiceWidget(
                                        "Second screen", FuseIcons.DualScreen, SecondScreenChoice.entries.map { it.label }, choice.ordinal, choice.detail,
                                        selected, modifier,
                                    ) { k -> pick(); chooseSecond(SecondScreenChoice.entries[k]) }
                                    slot.id.kind == QuickKind.SLIDER && slot.span == QuickLayout.COLUMNS -> QuickCard(selected, modifier, accent = false) {
                                        io.github.matiyaaa.fuse.ui.designsystem.components.FillSlider(
                                            sliderValue(slot.id),
                                            onChange = { v -> pick(); setSlider(slot.id, v) },
                                            icon = sliderIcon(slot.id, sliderValue(slot.id)),
                                            label = slot.id.title,
                                            valueText = "${(sliderValue(slot.id) * 100).roundToInt()}%",
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    slot.id.kind == QuickKind.SLIDER -> TallSlider(
                                        sliderValue(slot.id), sliderIcon(slot.id, sliderValue(slot.id)), slot.id.title, selected, adjusting && selected, modifier,
                                        onPress = { pick(); adjusting = !adjusting },
                                    ) { v -> pick(); setSlider(slot.id, v) }
                                    else -> {
                                        val t = tileFor(slot.id)
                                        ControlTile(
                                            t.label, t.icon, selected = selected, modifier = modifier,
                                            active = t.active, detail = t.detail, toggle = t.toggle,
                                            onLongClick = t.hold?.let { hold -> { pick(); hold() } },
                                        ) { pick(); t.run() }
                                    }
                                }
                            }
                            // Fuse's own actions; put away while editing, so editing is about the grid alone.
                            Appear(
                                visible = !editing,
                                enter = fadeIn(motion.fade(Durations.BASE)) + expandVertically(motion.tween(Durations.BASE)),
                                exit = fadeOut(motion.fade(Durations.FAST)) + shrinkVertically(motion.tween(Durations.BASE)),
                                label = "quick list",
                            ) {
                                Column {
                                    Spacer(Modifier.height(Space.l))
                                    SectionLabel("Fuse", Modifier.padding(start = ROW_CONTENT_START).reveal(reveal, placed.maxOfOrNull { it.row + 2 } ?: 1))
                                    Spacer(Modifier.height(Space.s))
                                    actions.forEachIndexed { i, action ->
                                        if (i > 0) Spacer(Modifier.height(Space.xxs))
                                        MenuRow(
                                            action,
                                            selected = zone == QuickZone.LIST && i == listRow,
                                            modifier = Modifier
                                                .onPlaced { coords ->
                                                    // Relative to the scrolling column, where the highlight draws.
                                                    val y = listBase.coords?.takeIf { it.isAttached }?.localPositionOf(coords, Offset.Zero)?.y ?: coords.positionInParent().y
                                                    highlight.place(i, y, y + coords.size.height)
                                                }
                                                .bringIntoViewRequester(listRequesters[i])
                                                .reveal(reveal, (placed.maxOfOrNull { it.row + 2 } ?: 1) + i),
                                            highlight = false,
                                            onClick = { listRow = i; zone = QuickZone.LIST; action.onSelect() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // What the buttons do while editing; a quiet reminder of X otherwise.
                    val hints = when {
                        adjusting -> listOf(Hint(HintButton.DPAD, "Set"), Hint(HintButton.CONFIRM, "Done"))
                        zone == QuickZone.ADD -> listOf(Hint(HintButton.CONFIRM, "Add"), Hint(HintButton.BACK, "Back"))
                        editing && held -> listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Put down"))
                        editing -> listOf(Hint(HintButton.CONFIRM, "Move"), Hint(HintButton.OPTIONS, "Size"), Hint(HintButton.SEARCH, "Remove"))
                        else -> listOf(Hint(HintButton.OPTIONS, "Edit"), Hint(HintButton.BACK, "Close"))
                    }
                    HintBar(hints, Modifier.align(Alignment.End).padding(horizontal = Space.l, vertical = Space.m))
                }
            }
        }
    }
}

/**
 * The grid itself. Every item is laid out from the arrangement (no measuring of its neighbours), so
 * its place and size are known up front and glide when they change: an item moved, resized, added
 * or taken out, the others make room smoothly. While editing, items sit a touch smaller with their
 * corner buttons, and one being carried rides above the rest.
 */
@Composable
private fun QuickGrid(
    slots: List<QuickSlot>,
    editing: Boolean,
    placed: List<QuickPlaced>,
    cellKeys: List<Any>,
    cell: Int,
    held: Boolean,
    dragging: Any?,
    requesters: HashMap<Any, BringIntoViewRequester>,
    heights: HashMap<Any, Float>,
    reveal: (Modifier, Int) -> Modifier,
    onDrag: (key: Any, start: Boolean) -> Unit,
    onDragEnd: () -> Unit,
    onDragOver: (key: Any, to: Int) -> Unit,
    onSelect: (Int) -> Unit,
    onResize: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    item: @Composable (index: Int, slot: QuickSlot, selected: Boolean, modifier: Modifier) -> Unit,
) {
    val motion = Fuse.motion
    val density = LocalDensity.current
    val tileShape = io.github.matiyaaa.fuse.ui.shell.components.controlTileShape()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = TILE_GAP
        val colW = (maxWidth - gap * (QuickLayout.COLUMNS - 1)) / QuickLayout.COLUMNS
        val tileH = colW / TILE_ASPECT
        fun heightOf(i: Int): Dp {
            val slot = slots.getOrNull(i) ?: return tileH
            return if (slot.span < QuickLayout.COLUMNS) tileH else when (slot.id.kind) {
                QuickKind.TILE -> WIDE_TILE_HEIGHT
                QuickKind.SLIDER -> SLIDER_HEIGHT
                QuickKind.MUSIC -> MUSIC_HEIGHT
                QuickKind.CHOICE -> CHOICE_HEIGHT
            }
        }
        val rows = (placed.maxOfOrNull { it.row } ?: -1) + 1
        val rowHeights = List(rows) { r -> placed.filter { it.row == r }.maxOf { heightOf(it.index) } }
        val rowTops = rowHeights.runningFold(0.dp) { acc, h -> acc + h + gap }
        val total = if (rows == 0) 0.dp else rowTops[rows] - gap
        val spring = if (motion.reduced) io.github.matiyaaa.fuse.ui.fuseline.Snap() else spring(dampingRatio = 0.86f, stiffness = 520f)

        // The latest geometry for a drag under way, which outlives the composition it started in.
        val geo by rememberUpdatedState(Triple(placed, rowTops, with(density) { (colW + gap).toPx() }))
        val indexOfKey by rememberUpdatedState(cellKeys)
        val dragOver by rememberUpdatedState(onDragOver)
        val dragStart by rememberUpdatedState(onDrag)
        val dragEnd by rememberUpdatedState(onDragEnd)

        val height = remember { FuselineValue(with(density) { total.toPx() }) }
        LaunchedEffect(total) { height.animateTo(with(density) { total.toPx() }, spring) }
        Box(
            Modifier.fillMaxWidth().layout { m, c ->
                val h = height.value.roundToInt()
                val p = m.measure(c.copy(minHeight = h, maxHeight = h))
                layout(p.width, h) { p.place(0, 0) }
            },
        ) {
            placed.forEach { p ->
                val i = p.index
                val k = cellKeys.getOrNull(i) ?: return@forEach
                key(k) {
                    val x = with(density) { ((colW + gap) * p.column).toPx() }
                    val y = with(density) { rowTops[p.row].toPx() }
                    val w = with(density) { (colW * p.span + gap * (p.span - 1)).toPx() }
                    val h = with(density) { heightOf(i).toPx() }
                    val pos = remember { FuselineValue(Offset(x, y)) }
                    val size = remember { FuselineValue(androidx.compose.ui.geometry.Size(w, h)) }
                    val dragged = dragging == k
                    LaunchedEffect(x, y, dragged) { if (!dragged) pos.animateTo(Offset(x, y), spring) }
                    LaunchedEffect(w, h) { size.animateTo(androidx.compose.ui.geometry.Size(w, h), spring) }
                    val requester = remember { BringIntoViewRequester() }
                    requesters[k] = requester
                    heights[k] = h
                    val selected = i == cell
                    val carried = (held && selected) || dragged
                    val lift by fuselineFloat(
                        when {
                            carried -> 1f
                            editing -> -1f
                            else -> 0f
                        },
                        motion.focusSpring(),
                        label = "quick edit",
                    )
                    val scope = rememberCoroutineScope()
                    var dragAt by remember { mutableStateOf(Offset.Zero) }
                    Box(
                        Modifier
                            .zIndex(if (carried) 2f else if (selected) 1f else 0f)
                            .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
                            .layout { m, _ ->
                                val s = size.value
                                val pl = m.measure(Constraints.fixed(s.width.roundToInt().coerceAtLeast(0), s.height.roundToInt().coerceAtLeast(0)))
                                layout(pl.width, pl.height) { pl.place(0, 0) }
                            }
                            .bringIntoViewRequester(requester)
                            .then(reveal(Modifier, p.row))
                            .graphicsLayer {
                                // Editing sets everything back a little; the one carried rises.
                                val s = if (motion.reduced) 1f else 1f + (if (lift > 0f) 0.05f else 0.035f) * lift
                                scaleX = s
                                scaleY = s
                                if (lift > 0f) {
                                    shadowElevation = 14.dp.toPx() * lift
                                    shape = tileShape
                                }
                            }
                            .then(
                                if (!editing || k == ADD_KEY) Modifier else Modifier.pointerInput(k) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragAt = pos.value
                                            dragStart(k, true)
                                        },
                                        onDragEnd = { dragEnd() },
                                        onDragCancel = { dragEnd() },
                                    ) { change, amount ->
                                        change.consume()
                                        dragAt += amount
                                        scope.launch { pos.snapTo(dragAt) }
                                        // The place under the item's middle is where it goes.
                                        val (pl, tops, stride) = geo
                                        val s = size.value
                                        val cx = (dragAt.x + s.width / 2) / stride
                                        val cy = with(density) { (dragAt.y + s.height / 2).toDp() }
                                        val row = (tops.indexOfLast { it <= cy }).coerceAtLeast(0)
                                        val to = QuickLayout.at(pl.filter { it.index < indexOfKey.size && indexOfKey[it.index] != ADD_KEY }, cx, row)
                                        if (to != null && to != indexOfKey.indexOf(k)) dragOver(k, to)
                                    }
                                },
                            ),
                    ) {
                        if (k == ADD_KEY) {
                            AddTile(selected, Modifier.fillMaxSize(), onAdd)
                        } else {
                            val slot = slots[i]
                            item(i, slot, selected, Modifier.fillMaxSize())
                            if (editing) {
                                // Over the item while editing: a tap selects it, so nothing inside acts.
                                Box(Modifier.matchParentSize().pointerInput(k) { detectTapGestures { onSelect(indexOfKey.indexOf(k)) } })
                                EditBadge(FuseIcons.Minus, "Take out ${slot.id.title}", danger = true, modifier = Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp)) { onRemove(indexOfKey.indexOf(k)) }
                                if (slot.id.spans.size > 1) {
                                    EditBadge(FuseIcons.MoveDiagonal, "Change the size of ${slot.id.title}", modifier = Modifier.align(Alignment.BottomEnd).offset(8.dp, 8.dp)) { onResize(indexOfKey.indexOf(k)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The quick menu's sheet while picking something to put in. */
@Composable
private fun AddList(addable: List<QuickId>, row: Int, requesters: List<BringIntoViewRequester>, onAdd: (QuickId) -> Unit, onReset: () -> Unit) {
    Column {
        SectionLabel(if (addable.isEmpty()) "Everything is in" else "Add to the quick menu", Modifier.padding(start = ROW_CONTENT_START))
        Spacer(Modifier.height(Space.s))
        addable.forEachIndexed { i, id ->
            if (i > 0) Spacer(Modifier.height(Space.xxs))
            MenuRow(
                MenuAction("add.${id.name}", id.title, iconFor(id), detail = id.blurb, onSelect = { onAdd(id) }),
                selected = i == row,
                modifier = Modifier.bringIntoViewRequester(requesters[i]),
            )
        }
        Spacer(Modifier.height(Space.l))
        MenuRow(
            MenuAction("reset", "Put it back as it came", FuseIcons.RotateCcw, detail = "Fuse's own arrangement, with nothing taken out", onSelect = onReset),
            selected = row == addable.size,
            modifier = Modifier.bringIntoViewRequester(requesters[addable.size]),
        )
    }
}

/** While editing, the top line says so, with Done for touch. */
@Composable
private fun EditHeader(adding: Boolean, onDone: () -> Unit) {
    val c = Fuse.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FText(if (adding) "Add" else "Edit quick menu", Fuse.type.titleSmall, maxLines = 1)
            Spacer(Modifier.height(Space.xxs))
            FText(if (adding) "Pick something to put in" else "Drag to move. Corners resize and take out", Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.m))
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .clip(PillShape)
                .background(c.accent)
                .fuseClickable(shape = PillShape, role = Role.Button, onClick = onDone)
                .padding(horizontal = Space.l, vertical = Space.s),
            contentAlignment = Alignment.Center,
        ) {
            FText(if (adding) "Back" else "Done", Fuse.type.label, color = c.onAccent, maxLines = 1)
        }
    }
}

/** The sheet's width, the same as the context menu's side sheet; narrower screens give it all but a margin. */
private val QUICK_MENU_WIDTH = 420.dp

/** Between items, wide enough that a focused item's ring never meets its neighbours. */
private val TILE_GAP = Space.m

/** Control tiles are a little wider than tall, so two lines of text sit under the icon. */
private const val TILE_ASPECT = 1.1f

/** A switch across the whole menu: its icon beside its words. */
private val WIDE_TILE_HEIGHT = 64.dp

/** A slider across the whole menu. */
private val SLIDER_HEIGHT = 56.dp

/** Now playing across the whole menu. */
private val MUSIC_HEIGHT = 80.dp

/** The second screen's three ways across the whole menu. */
private val CHOICE_HEIGHT = 92.dp

/**
 * The quick menu's top line: the time large (with AM or PM set smaller beside it), the date under it,
 * and on the right the battery with what is left of it, and a pencil to edit the menu. Anything the
 * platform doesn't report is left out.
 */
@Composable
private fun QuickHeader(time: String, status: SystemStatus, onEdit: () -> Unit) {
    val c = Fuse.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            val suffix = time.substringAfterLast(' ', "").takeIf { it.isNotEmpty() && it.all(Char::isLetter) }
            Row {
                FText(if (suffix != null) time.substringBeforeLast(' ') else time, Fuse.type.numericLarge, maxLines = 1, modifier = Modifier.alignByBaseline())
                if (suffix != null) {
                    Spacer(Modifier.width(Space.xs + Space.xxs))
                    FText(suffix, Fuse.type.titleSmall, color = c.textMuted, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(Space.xxs))
            FText(formatDate(), Fuse.type.body, color = c.textMuted, maxLines = 1)
        }
        status.batteryPercent?.let { pct ->
            Spacer(Modifier.width(Space.m))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BatteryGlyph(pct, status.charging)
                    Spacer(Modifier.width(Space.s))
                    FText("$pct%", Fuse.type.numeric, maxLines = 1)
                }
                (batteryTimeText(status) ?: if (status.charging) "Charging" else null)?.let { state ->
                    Spacer(Modifier.height(Space.xxs))
                    FText(state, Fuse.type.body, color = c.textMuted, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(Space.m))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(c.text.copy(alpha = if (c.isDark) 0.07f else 0.055f))
                .fuseClickable(shape = CircleShape, role = Role.Button, onClick = onEdit)
                .semantics { contentDescription = "Edit quick menu" },
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(FuseIcons.Pencil, size = Size.iconS, tint = c.text.copy(alpha = 0.85f))
        }
    }
}

/** Brightness and volume show how high they are. */
private fun sliderIcon(id: QuickId, value: Float): ImageVector = when {
    id == QuickId.VOLUME && value <= 0.001f -> FuseIcons.VolumeOff
    id == QuickId.VOLUME -> FuseIcons.Volume
    value < 0.5f -> FuseIcons.SunDim
    else -> FuseIcons.Sun
}

/** Each item's icon, for Add's list. */
private fun iconFor(id: QuickId): ImageVector = when (id) {
    QuickId.WIFI -> FuseIcons.Wifi
    QuickId.BLUETOOTH -> FuseIcons.Bluetooth
    QuickId.CAPTURE -> FuseIcons.Camera
    QuickId.SECOND_SCREEN -> FuseIcons.DualScreen
    QuickId.HIDE_SECOND -> FuseIcons.EyeOff
    QuickId.DISPLAY -> FuseIcons.Monitor
    QuickId.CONTROLLER -> FuseIcons.Gamepad
    QuickId.PERFORMANCE -> FuseIcons.Gauge
    QuickId.LOW_POWER -> FuseIcons.Leaf
    QuickId.FIND_GAMES -> FuseIcons.FolderSearch
    QuickId.CARTRIDGE -> FuseIcons.Package
    QuickId.HOME_STYLE -> FuseIcons.Rows
    QuickId.SOUND -> FuseIcons.AudioLines
    QuickId.MUSIC -> FuseIcons.Music
    QuickId.BRIGHTNESS -> FuseIcons.Sun
    QuickId.VOLUME -> FuseIcons.Volume
    QuickId.SEARCH -> FuseIcons.Search
    QuickId.THEMES -> FuseIcons.Palette
    QuickId.PHONE_LINK -> FuseIcons.Smartphone
    QuickId.PLAY_TIME -> FuseIcons.Hourglass
    QuickId.STANDBY -> FuseIcons.Moon
    QuickId.FRAME_TIMES -> FuseIcons.Activity
    QuickId.FULLSCREEN -> FuseIcons.Maximize
}

/** How a connection stands, for a tile's state line; nothing when the platform doesn't say. */
private fun connectionText(state: ConnectionState): String? = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.ON -> "On"
    ConnectionState.OFF -> "Off"
    ConnectionState.UNKNOWN -> null
}

/** What the second screen says in Settings: Off, On (the selected game) or Companion. */
internal fun secondScreenText(mode: DualScreenMode): String = when (mode) {
    DualScreenMode.OFF -> "Off"
    DualScreenMode.LIBRARY_COMPANION -> "On"
    DualScreenMode.GAME_COMPANION -> "Companion"
    DualScreenMode.REVERSE -> "Games play there"
}

/** The next of Off, On and Companion. Playing on the second screen is only chosen in Settings. */
internal fun nextSecondScreen(mode: DualScreenMode): DualScreenMode = when (mode) {
    DualScreenMode.OFF -> DualScreenMode.LIBRARY_COMPANION
    DualScreenMode.LIBRARY_COMPANION -> DualScreenMode.GAME_COMPANION
    DualScreenMode.GAME_COMPANION, DualScreenMode.REVERSE -> DualScreenMode.OFF
}
