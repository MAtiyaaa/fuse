package io.github.matiyaaa.fuse.ui.shell.quick

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.BatteryGlyph
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuRow
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.SliderBar
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.applyUpdate
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.capture.rememberRecordingTime
import io.github.matiyaaa.fuse.ui.shell.components.ControlTile
import io.github.matiyaaa.fuse.ui.shell.components.ROW_CONTENT_START
import io.github.matiyaaa.fuse.ui.shell.components.rememberRowHighlight
import io.github.matiyaaa.fuse.ui.shell.components.batteryTimeText
import io.github.matiyaaa.fuse.ui.shell.components.controlWellShape
import io.github.matiyaaa.fuse.ui.shell.home.switchHomeStyle
import io.github.matiyaaa.fuse.ui.shell.settings.next
import io.github.matiyaaa.fuse.ui.shell.settings.performanceLabel
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import kotlin.math.roundToInt

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

private sealed interface QuickRow {
    data class Tiles(val tiles: List<QuickTile>) : QuickRow
    data class Slider(val label: String, val icon: ImageVector, val value: Float, val set: (Float) -> Unit) : QuickRow
    data class Item(val action: MenuAction) : QuickRow
}

/**
 * The console-style quick menu (Start). Wi-Fi and Bluetooth open the system panels because apps
 * can't switch them directly on current Android; brightness and volume act immediately.
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
    var row by remember { mutableIntStateOf(0) }
    var col by remember { mutableIntStateOf(0) }

    fun close() { app.quickMenuOpen = false }
    val updateAvailable by app.store.updates.available.collectAsState()
    val updateState by app.store.updates.state.collectAsState()
    val scan by app.store.sources.scan.collectAsState()
    val scanning = scan.phase == ScanPhase.DISCOVERING || scan.phase == ScanPhase.SCANNING || scan.phase == ScanPhase.SAVING
    val capture = app.capture
    val recordingTime = rememberRecordingTime(capture)

    val tiles = buildList {
        // Both open the system's panel; the tile says how things stand where the platform reports it.
        if (features.wifiSettings) {
            add(QuickTile(
                "Wi-Fi", if (status.wifi == ConnectionState.OFF) FuseIcons.WifiOff else FuseIcons.Wifi,
                active = status.wifi == ConnectionState.CONNECTED, detail = connectionText(status.wifi),
            ) { platform.quick.openWifi() })
        }
        if (features.bluetoothSettings) {
            add(QuickTile(
                "Bluetooth", if (status.bluetooth == ConnectionState.OFF) FuseIcons.BluetoothOff else FuseIcons.Bluetooth,
                active = status.bluetooth == ConnectionState.CONNECTED || status.bluetooth == ConnectionState.ON,
                detail = connectionText(status.bluetooth),
            ) { platform.quick.openBluetooth() })
        }
        // A screenshot three seconds after the menu closes; held, a recording after the same wait.
        if (capture != null) {
            if (recordingTime != null) {
                add(QuickTile("Stop", FuseIcons.Square, active = true, detail = "$recordingTime recorded") { close(); capture.stopRecording() })
            } else {
                add(QuickTile(
                    "Screenshot", FuseIcons.Camera, detail = "Hold to record",
                    hold = { close(); capture.toggleRecording(delayed = true) },
                ) { close(); capture.screenshot(delayed = true) })
            }
        }
        add(QuickTile("Display", FuseIcons.Monitor) { close(); app.go(Route.Settings("displays")) })
        add(QuickTile("Controller", FuseIcons.Gamepad) { close(); app.go(Route.Settings("inputs")) })
        add(QuickTile("Performance", FuseIcons.Gauge, detail = performanceLabel(prefs.performance)) {
            val next = prefs.performance.next()
            app.store.updatePrefs { it.copy(performance = next) }
            // Say what changed, since most of it is felt rather than seen.
            app.toasts.show(io.github.matiyaaa.fuse.ui.shell.settings.performanceSummary(next, prefs.lowPower, platform.device, platform.host))
        })
        add(QuickTile("Low Power", FuseIcons.Leaf, active = prefs.lowPower, toggle = true) {
            val on = !prefs.lowPower
            app.store.updatePrefs { it.copy(lowPower = on) }
            app.toasts.show(if (on) "Low Power is on" else "Low Power is off")
        })
        // Games put on a card or drive while Fuse was open show up without a trip to Settings.
        add(QuickTile(
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
        })
        if (prefs.cartridgeEnabled && app.offers(Destination.CARTRIDGE)) add(QuickTile("Cartridge", FuseIcons.CloudDownload) { close(); app.selectTab(Destination.CARTRIDGE) })
        // Search has its own button in the top line; Home's style is one press here.
        add(QuickTile("Home", if (prefs.home.mode == HomeMode.CHANNELS) FuseIcons.Grid else FuseIcons.Rows, detail = if (prefs.home.mode == HomeMode.CHANNELS) "Channels" else "Flow") {
            app.switchHomeStyle()
        })
        val soundOn = prefs.sound != io.github.matiyaaa.fuse.model.SoundProfile.OFF
        add(QuickTile("Sound", if (soundOn) FuseIcons.Volume else FuseIcons.VolumeOff, active = soundOn, toggle = true) {
            app.store.updatePrefs { it.copy(sound = if (it.sound == io.github.matiyaaa.fuse.model.SoundProfile.OFF) io.github.matiyaaa.fuse.model.SoundProfile.SOFT else io.github.matiyaaa.fuse.model.SoundProfile.OFF) }
        })
    }
    val rows = buildList {
        tiles.chunked(3).forEach { add(QuickRow.Tiles(it)) }
        if (features.brightness) brightness?.let { add(QuickRow.Slider("Brightness", FuseIcons.Sun, it) { v -> platform.quick.setBrightness(v) }) }
        if (features.volume) volume?.let { add(QuickRow.Slider("Volume", FuseIcons.Volume, it) { v -> platform.quick.setVolume(v) }) }
        val release = updateAvailable
        if (release != null && !app.store.updates.inPlace) {
            add(QuickRow.Item(MenuAction(
                "update", "Get ${release.name}", FuseIcons.External, detail = "Opens its release page",
                onSelect = { close(); app.go(Route.Settings("updates")) },
            )))
        } else if (release != null) {
            val ready = updateState is UpdateState.Ready
            add(QuickRow.Item(MenuAction(
                "update", if (ready) "Restart and update" else "Update to ${release.name}", if (ready) FuseIcons.Refresh else FuseIcons.Download,
                detail = when (val u = updateState) {
                    is UpdateState.Downloading -> "Downloading" + (u.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
                    is UpdateState.Ready -> "Downloaded and checked"
                    else -> "Download it in Settings, Updates"
                },
                onSelect = { close(); if (ready) app.applyUpdate() else app.go(Route.Settings("updates")) },
            )))
        }
        add(QuickRow.Item(MenuAction(
            "fullscan", "Rescan every folder", FuseIcons.RefreshDot,
            detail = "Reads all your drives again, for games a quick look misses. Your edits are kept",
            onSelect = {
                close()
                app.store.sources.rescan(ScanScope.FULL)
                app.toasts.show("Rescanning every folder in the background")
            },
        )))
        add(QuickRow.Item(MenuAction("home", "Arrange Home", FuseIcons.Dashboard, onSelect = { close(); app.go(Route.Settings("home")) })))
        add(QuickRow.Item(MenuAction("settings", "Settings", FuseIcons.Settings, trailing = Trailing.Chevron, onSelect = { close(); app.go(Route.Settings()) })))
        add(QuickRow.Item(MenuAction("restart", "Restart Fuse", FuseIcons.Refresh, onSelect = { platform.restart() })))
        if (features.canExit && !isHome) {
            add(QuickRow.Item(MenuAction("exit", "Exit Fuse", FuseIcons.LogOut, onSelect = {
                close()
                app.confirm = ConfirmSpec("Exit Fuse?", "Fuse closes. Your library and settings are kept.", "Exit") { platform.exit() }
            })))
        }
    }
    row = row.coerceIn(0, (rows.size - 1).coerceAtLeast(0))

    LaunchedEffect(open) {
        if (open) { row = 0; col = 0; platform.sounds.play(SoundCue.OPEN) }
    }
    // The selected row scrolls into view, so the stick and the list never drift apart.
    val requesters = remember(rows.size) { List(rows.size) { BringIntoViewRequester() } }
    LaunchedEffect(row, open) { if (open) requesters.getOrNull(row)?.bringIntoView() }

    if (open) {
        // Holding A turns into REORDER: the Screenshot tile records, everything else just runs.
        InputLayer(priority = LayerPriority.OVERLAY, modal = true, longPress = true) { e ->
            val r = rows.getOrNull(row) ?: return@InputLayer NavResult.IGNORED
            when (e.action) {
                NavAction.BACK, NavAction.QUICK_MENU -> { close(); platform.sounds.play(SoundCue.CLOSE); NavResult.CONSUMED }
                NavAction.UP -> if (row > 0) { row--; col = col.coerceAtMost(((rows[row] as? QuickRow.Tiles)?.tiles?.size ?: 1) - 1); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (row < rows.lastIndex) { row++; col = col.coerceAtMost(((rows[row] as? QuickRow.Tiles)?.tiles?.size ?: 1) - 1); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.LEFT, NavAction.RIGHT -> when (r) {
                    is QuickRow.Tiles -> {
                        val next = col + if (e.action == NavAction.LEFT) -1 else 1
                        if (next in r.tiles.indices) { col = next; NavResult.MOVED } else NavResult.BLOCKED
                    }
                    is QuickRow.Slider -> { r.set((r.value + if (e.action == NavAction.LEFT) -0.05f else 0.05f).coerceIn(0f, 1f)); NavResult.MOVED }
                    is QuickRow.Item -> NavResult.BLOCKED
                }
                NavAction.SELECT, NavAction.REORDER -> {
                    when (r) {
                        is QuickRow.Tiles -> r.tiles.getOrNull(col)?.let { t ->
                            val hold = t.hold
                            if (e.action == NavAction.REORDER && hold != null) hold() else t.run()
                        }
                        is QuickRow.Item -> r.action.onSelect()
                        is QuickRow.Slider -> Unit
                    }
                    NavResult.ACTIVATED
                }
                else -> NavResult.CONSUMED
            }
        }
    }

    val reveal = rememberReveal(open)
    val highlight = rememberRowHighlight()
    val target = highlight.bounds[row]?.takeIf { open && rows.getOrNull(row) !is QuickRow.Tiles }
    highlight.Follow(target)

    Overlay(visible = open, onDismiss = ::close, edge = OverlayEdge.END) {
        val c = Fuse.colors
        val time = rememberClockText(prefs.clock24h)
        val scroll = rememberScrollState()
        BoxWithConstraints {
            // A side sheet the height of the screen; a narrow screen gives it all but a margin.
            val width = minOf(QUICK_MENU_WIDTH, maxWidth - Space.l)
            Panel(Modifier.width(width).fillMaxHeight().padding(vertical = Space.l).padding(end = Space.l)) {
                Column(Modifier.fillMaxSize()) {
                    // The clock and battery stay put; the controls scroll under them.
                    QuickHeader(time, status, Modifier.padding(start = Space.l, end = Space.l, top = Space.l).reveal(reveal, 0))
                    Spacer(Modifier.height(Space.m))
                    Box(Modifier.fillMaxWidth().padding(horizontal = Space.l).height(Size.divider).background(c.hairline))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .fadingEdges(scroll, top = Space.l, bottom = Space.xl)
                            .verticalScroll(scroll)
                            // Room for a lifted tile's ring at the top and the sheet's corner at the bottom.
                            .padding(horizontal = Space.l)
                            .padding(top = Space.l, bottom = Space.l)
                            .then(highlight.drawModifier()),
                    ) {
                        rows.forEachIndexed { i, r ->
                            val prev = rows.getOrNull(i - 1)
                            when {
                                prev == null -> Unit
                                r is QuickRow.Tiles -> Spacer(Modifier.height(TILE_GAP))
                                r is QuickRow.Slider && prev is QuickRow.Tiles -> Spacer(Modifier.height(Space.l))
                                r is QuickRow.Item && prev !is QuickRow.Item -> {
                                    Spacer(Modifier.height(Space.l))
                                    SectionLabel("Fuse", Modifier.padding(start = ROW_CONTENT_START).reveal(reveal, i + 1))
                                    Spacer(Modifier.height(Space.s))
                                }
                                else -> Spacer(Modifier.height(Space.xxs))
                            }
                            val placed = Modifier
                                .bringIntoViewRequester(requesters[i])
                                .reveal(reveal, i + 1)
                                .onPlaced { coords ->
                                    // Where the row sits in the list, for the highlight gliding between rows.
                                    val y = coords.positionInParent().y
                                    highlight.place(i, y, y + coords.size.height)
                                }
                            when (r) {
                                is QuickRow.Tiles -> Row(placed.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                                    r.tiles.forEachIndexed { j, t ->
                                        ControlTile(
                                            t.label, t.icon, selected = i == row && j == col,
                                            modifier = Modifier.weight(1f).aspectRatio(TILE_ASPECT),
                                            active = t.active, detail = t.detail, toggle = t.toggle,
                                            onLongClick = t.hold?.let { hold -> { row = i; col = j; hold() } },
                                        ) { row = i; col = j; t.run() }
                                    }
                                    repeat(3 - r.tiles.size) { Spacer(Modifier.weight(1f)) }
                                }
                                is QuickRow.Slider -> SliderRow(r, selected = i == row, placed) { v -> row = i; r.set(v) }
                                is QuickRow.Item -> MenuRow(r.action, selected = i == row, modifier = placed, highlight = false, onClick = { row = i; r.action.onSelect() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The sheet's width, the same as the context menu's side sheet; narrower screens give it all but a margin. */
private val QUICK_MENU_WIDTH = 420.dp

/** Between control tiles, wide enough that a focused tile's ring never meets its neighbours. */
private val TILE_GAP = Space.m

/** Control tiles are a little wider than tall, so two lines of text sit under the icon. */
private const val TILE_ASPECT = 1.1f

/**
 * The quick menu's top line: the time large (with AM or PM set smaller beside it), the date under it,
 * and on the right the battery with what is left of it. Anything the platform doesn't report is left
 * out.
 */
@Composable
private fun QuickHeader(time: String, status: SystemStatus, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
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
    }
}

/**
 * Brightness or volume: the same anatomy as a menu row (the selection bar's room, an icon in a well,
 * then the slider and its value), so the gliding highlight sits on it like on any row. Dragging the
 * bar sets the value by touch.
 */
@Composable
private fun SliderRow(r: QuickRow.Slider, selected: Boolean, modifier: Modifier, onChange: (Float) -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Size.row).padding(end = Space.l).semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(ROW_CONTENT_START))
        val icon = when {
            r.icon == FuseIcons.Volume && r.value <= 0.001f -> FuseIcons.VolumeOff
            r.icon == FuseIcons.Sun && r.value < 0.5f -> FuseIcons.SunDim
            else -> r.icon
        }
        RowWell(icon, selected)
        Spacer(Modifier.width(Space.m))
        SliderBar(r.value, selected, Modifier.weight(1f), onChange = onChange, valueText = "${(r.value * 100).roundToInt()}%")
    }
}

/** A row's icon in a quiet well, the same size and shape as a [MenuRow]'s. */
@Composable
private fun RowWell(icon: ImageVector, selected: Boolean) {
    val c = Fuse.colors
    val fill by animateColorAsState(
        c.text.copy(alpha = if (selected) (if (c.isDark) 0.13f else 0.1f) else (if (c.isDark) 0.07f else 0.055f)),
        Fuse.motion.tween(Durations.FAST),
        label = "well",
    )
    Box(Modifier.size(Size.iconXL).clip(controlWellShape()).background(fill), contentAlignment = Alignment.Center) {
        FuseIcon(icon, size = Size.glyphS, tint = if (selected) c.text else c.text.copy(alpha = 0.82f))
    }
}

/** How a connection stands, for a tile's state line; nothing when the platform doesn't say. */
private fun connectionText(state: ConnectionState): String? = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.ON -> "On"
    ConnectionState.OFF -> "Off"
    ConnectionState.UNKNOWN -> null
}
