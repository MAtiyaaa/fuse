package io.github.matiyaaa.fuse.ui.shell.quick

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PerformanceProfile
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
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.model.Destination

private data class QuickTile(val label: String, val icon: ImageVector, val active: Boolean = false, val detail: String? = null, val run: () -> Unit)

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

    val tiles = buildList {
        if (features.wifiSettings) add(QuickTile("Wi-Fi", FuseIcons.Wifi) { platform.quick.openWifi() })
        if (features.bluetoothSettings) add(QuickTile("Bluetooth", FuseIcons.Bluetooth) { platform.quick.openBluetooth() })
        add(QuickTile("Display", FuseIcons.Monitor) { close(); app.go(Route.Settings("displays")) })
        add(QuickTile("Controller", FuseIcons.Gamepad) { close(); app.go(Route.Settings("inputs")) })
        add(QuickTile(
            "Performance", FuseIcons.Gauge,
            detail = when (prefs.performance) {
                PerformanceProfile.AUTOMATIC -> "Automatic"
                PerformanceProfile.LOW_POWER -> "Low power"
                PerformanceProfile.BALANCED -> "Balanced"
                PerformanceProfile.HIGH_QUALITY -> "High quality"
            },
        ) {
            val next = PerformanceProfile.entries[(prefs.performance.ordinal + 1) % PerformanceProfile.entries.size]
            app.store.updatePrefs { it.copy(performance = next) }
            // Say what changed, since most of it is felt rather than seen.
            app.toasts.show(io.github.matiyaaa.fuse.ui.shell.settings.performanceSummary(next, prefs.lowPower, platform.device, platform.host))
        })
        add(QuickTile("Low Power", FuseIcons.Leaf, active = prefs.lowPower) { app.store.updatePrefs { it.copy(lowPower = !it.lowPower) } })
        add(QuickTile("Cartridge", FuseIcons.CloudDownload) { close(); app.selectTab(Destination.CARTRIDGE) })
        add(QuickTile("Search", FuseIcons.Search) { close(); app.go(Route.Search) })
        add(QuickTile("Sound", FuseIcons.Volume, active = prefs.sound != io.github.matiyaaa.fuse.model.SoundProfile.OFF) {
            app.store.updatePrefs { it.copy(sound = if (it.sound == io.github.matiyaaa.fuse.model.SoundProfile.OFF) io.github.matiyaaa.fuse.model.SoundProfile.SOFT else io.github.matiyaaa.fuse.model.SoundProfile.OFF) }
        })
    }
    val rows = buildList {
        tiles.chunked(3).forEach { add(QuickRow.Tiles(it)) }
        if (features.brightness) brightness?.let { add(QuickRow.Slider("Brightness", FuseIcons.Sun, it) { v -> platform.quick.setBrightness(v) }) }
        if (features.volume) volume?.let { add(QuickRow.Slider("Volume", FuseIcons.Volume, it) { v -> platform.quick.setVolume(v) }) }
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
        InputLayer(priority = LayerPriority.OVERLAY, modal = true) { e ->
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
                NavAction.SELECT -> {
                    when (r) {
                        is QuickRow.Tiles -> r.tiles.getOrNull(col)?.run?.invoke()
                        is QuickRow.Item -> r.action.onSelect()
                        is QuickRow.Slider -> Unit
                    }
                    NavResult.ACTIVATED
                }
                else -> NavResult.CONSUMED
            }
        }
    }

    Overlay(visible = open, onDismiss = ::close, edge = OverlayEdge.END) {
        val c = Fuse.colors
        val time = rememberClockText(prefs.clock24h)
        Panel(Modifier.width(400.dp).fillMaxHeight().padding(vertical = Space.l).padding(end = Space.l)) {
            Column(Modifier.padding(Space.l).verticalScroll(rememberScrollState())) {
                FText(time, Fuse.type.numericLarge)
                FText(formatDate(), Fuse.type.body, color = c.textMuted)
                Spacer(Modifier.height(Space.m))
                status.batteryPercent?.let { pct ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        BatteryGlyph(pct, status.charging)
                        FText("$pct%${if (status.charging) "  ·  Charging" else ""}", Fuse.type.label, color = c.textMuted)
                    }
                }
                Spacer(Modifier.height(Space.l))
                rows.forEachIndexed { i, r ->
                  Column(Modifier.bringIntoViewRequester(requesters[i])) {
                    when (r) {
                        is QuickRow.Tiles -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                r.tiles.forEachIndexed { j, t ->
                                    QuickTileView(t, selected = i == row && j == col, modifier = Modifier.weight(1f)) { row = i; col = j; t.run() }
                                }
                                repeat(3 - r.tiles.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(Space.s))
                        }
                        is QuickRow.Slider -> {
                            if (i > 0 && rows[i - 1] is QuickRow.Tiles) Spacer(Modifier.height(Space.m))
                            val selected = i == row
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control))
                                    .background(if (selected) c.text.copy(alpha = 0.08f) else Color.Transparent)
                                    .padding(horizontal = Space.m, vertical = Space.s),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FuseIcon(r.icon, tint = c.textMuted)
                                Spacer(Modifier.width(Space.m))
                                SliderBar(r.value, selected, Modifier.weight(1f))
                            }
                        }
                        is QuickRow.Item -> {
                            if (i > 0 && rows[i - 1] !is QuickRow.Item) {
                                Spacer(Modifier.height(Space.l))
                                SectionLabel("Fuse")
                                Spacer(Modifier.height(Space.s))
                            }
                            MenuRow(r.action, selected = i == row)
                        }
                    }
                  }
                }
            }
        }
    }
}

@Composable
private fun QuickTileView(tile: QuickTile, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by animateColorAsState(
        when {
            selected -> c.text
            tile.active -> c.accentSoft
            else -> c.text.copy(alpha = 0.07f)
        },
        Fuse.motion.tween(Durations.FAST),
        label = "qt",
    )
    val fg = if (selected) c.ink else if (tile.active) c.accent else c.text
    Box(
        modifier
            .aspectRatio(1.1f)
            .clip(RoundedCornerShape(Fuse.geometry.panel))
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(Space.m),
    ) {
        FuseIcon(tile.icon, tint = fg, modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart)) {
            FText(tile.label, Fuse.type.label, color = fg, maxLines = 1)
            tile.detail?.let { FText(it, Fuse.type.caption, color = fg.copy(alpha = 0.7f), maxLines = 1) }
        }
    }
}
