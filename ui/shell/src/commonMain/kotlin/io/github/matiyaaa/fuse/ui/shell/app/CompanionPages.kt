package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.BatteryCapsule
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FillSlider
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.components.ControlTile
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.batteryTimeText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.settings.next
import io.github.matiyaaa.fuse.ui.shell.settings.performanceLabel
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The second screen's top line (page title and status) and bottom line (page dots), kept clear on every page. */
internal val CompanionTopBar = 52.dp
internal val CompanionDotsBar = 36.dp

/** Pages wider than this lay their cards out in rows (a bigger second screen or an external display). */
private val WidePage = 600.dp

/**
 * The second page: what the device is doing, from what Fuse can really measure, laid out to fit
 * the screen without scrolling. The battery leads (with how long it lasts or takes to fill), then
 * the game being played, then rings for the processor, memory, the library drive and Wi-Fi.
 * Anything the device doesn't report is left out rather than shown as zero.
 */
@Composable
internal fun StatusPage(store: FuseStore, platform: PlatformUi, status: SystemStatus) {
    val metrics by platform.performance.collectAsState()
    val home by store.library.home.collectAsState()
    val gauges = remember(metrics, status, home.storage) { statusGauges(metrics, status, home.storage) }
    val batteryTemp = metrics.firstOrNull { it.key == "battery_temp" }?.value
    val playing = home.playtime.currentGame
    val since = home.playtime.currentSince
    var now by remember { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            delay(15_000)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = Space.l, end = Space.l, top = CompanionTopBar, bottom = CompanionDotsBar)) {
        if (maxWidth < WidePage) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                BatteryHero(status, batteryTemp, Modifier.fillMaxWidth().weight(1f))
                SessionCard(playing, since, now, Modifier.fillMaxWidth().height(64.dp))
                gauges.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { GaugeTile(it, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(Modifier.fillMaxWidth().height(196.dp), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    BatteryHero(status, batteryTemp, Modifier.weight(1.3f).fillMaxHeight(), large = true)
                    SessionCard(playing, since, now, Modifier.weight(1f).fillMaxHeight(), large = true)
                }
                Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    gauges.forEach { GaugeTile(it, Modifier.weight(1f).fillMaxHeight(), ring = 56.dp) }
                }
            }
        }
    }
}

/** A card on the second screen's pages: raised, softly lit from the top, with a hairline edge. */
@Composable
internal fun CompanionCard(modifier: Modifier = Modifier, padding: Dp = Space.m, content: @Composable BoxScope.() -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.surfaceRaised.copy(alpha = 0.82f), c.surfaceRaised.copy(alpha = 0.64f))))
            .border(1.dp, c.text.copy(alpha = 0.07f), shape)
            .padding(padding),
        content = content,
    )
}

/**
 * The battery, large: the level in big figures, how long it lasts or takes to fill, and the drawn
 * battery beside it. Machines without a battery say so.
 */
@Composable
private fun BatteryHero(status: SystemStatus, temperature: String?, modifier: Modifier, large: Boolean = false) {
    val c = Fuse.colors
    val pct = status.batteryPercent
    CompanionCard(modifier, padding = Space.l) {
        if (pct == null) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    FuseIcon(FuseIcons.Plug, tint = c.textMuted)
                }
                Spacer(Modifier.width(Space.m))
                Column {
                    FText("No battery", Fuse.type.titleSmall, maxLines = 1)
                    FText("Running on mains power", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
            return@CompanionCard
        }
        val low = pct <= 15 && !status.charging
        val lit = status.charging || status.batteryFull
        val stateColor = when {
            lit -> c.success
            low -> c.danger
            else -> c.textMuted
        }
        val time = batteryTimeText(status)
        val stateWord = when {
            status.batteryFull -> "Plugged in"
            status.charging -> "Charging"
            else -> "On battery"
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(stateColor))
                    FText("BATTERY", Fuse.type.overline, color = c.textMuted, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    val size = if (large) 64.sp else 54.sp
                    FText("$pct", Fuse.type.numericLarge.copy(fontSize = size, lineHeight = size * 1.08f), color = if (low) c.danger else c.text, maxLines = 1)
                    FText("%", Fuse.type.title, color = c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 2.dp, bottom = if (large) 12.dp else 10.dp))
                }
                FText(time ?: stateWord, Fuse.type.titleSmall, color = if (lit) c.success else c.text, maxLines = 1)
                val caption = listOfNotNull(
                    if (time != null) stateWord else if (!status.charging) "Time left shows after a few minutes" else null,
                    temperature,
                ).joinToString("  ·  ")
                if (caption.isNotEmpty()) FText(caption, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            Spacer(Modifier.width(Space.l))
            BatteryCapsule(
                pct, status.charging,
                Modifier.size(width = if (large) 132.dp else 108.dp, height = if (large) 64.dp else 54.dp),
                full = status.batteryFull,
            )
        }
    }
}

/** The game being played, with its art and how long this session has run; otherwise a quiet note. */
@Composable
private fun SessionCard(playing: GameCard?, since: Long?, now: Long, modifier: Modifier, large: Boolean = false) {
    val c = Fuse.colors
    CompanionCard(modifier, padding = Space.m) {
        val art = if (large) 76.dp else 40.dp
        val inner: @Composable () -> Unit = {
            if (playing != null) {
                SquareGameArt(
                    playing.art,
                    Modifier.size(art).clip(SquircleShape.fraction(0.24f)),
                    fallback = { GeneratedArt(playing.title, playing.accent.toColor(), slot = ArtSlot.ICON) },
                )
            } else {
                Box(Modifier.size(art).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    FuseIcon(FuseIcons.Timer, tint = c.textMuted, size = if (large) 28.dp else 18.dp)
                }
            }
        }
        val session = if (playing != null && since != null) playtimeText(((now - since) / 1000).coerceAtLeast(0)) else null
        if (large) {
            Column(Modifier.align(Alignment.CenterStart)) {
                inner()
                Spacer(Modifier.height(Space.m))
                FText(if (playing != null) "NOW PLAYING" else "PLAY TIME", Fuse.type.overline, color = if (playing != null) c.accent else c.textMuted, maxLines = 1)
                FText(playing?.title ?: "No game running", Fuse.type.titleSmall, maxLines = 1)
                FText(
                    if (playing != null) listOfNotNull(session?.let { "$it this session" }, "${playtimeText(playing.playSeconds)} in all").joinToString("  ·  ")
                    else "Start a game and its time shows here",
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
            }
        } else {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                inner()
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(playing?.title ?: "No game running", Fuse.type.bodyStrong, maxLines = 1)
                    FText(
                        if (playing != null) listOfNotNull("Playing", session?.let { "$it this session" }, "${playtimeText(playing.playSeconds)} in all").joinToString("  ·  ")
                        else "Start a game and its time shows here",
                        Fuse.type.caption, color = c.textMuted, maxLines = 1,
                    )
                }
                if (playing != null) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
            }
        }
    }
}

/** A ring with its value inside (or its icon), and the label, value and detail beside it. */
@Composable
private fun GaugeTile(g: Gauge, modifier: Modifier, ring: Dp = 48.dp) {
    val c = Fuse.colors
    val tint = if (g.warn) c.warning else c.accent
    CompanionCard(modifier, padding = Space.m) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                ProgressRing(g.fraction ?: 0f, size = ring, stroke = 5.dp, color = tint)
                if (g.centre != null) {
                    FText(g.centre, Fuse.type.label.copy(fontSize = if (ring > 50.dp) 14.sp else 12.sp), maxLines = 1)
                } else {
                    FuseIcon(
                        when (g.kind) {
                            GaugeKind.PROCESSOR -> FuseIcons.Chip
                            GaugeKind.MEMORY -> FuseIcons.Memory
                            GaugeKind.STORAGE -> FuseIcons.HardDrive
                            GaugeKind.WIFI -> if (g.fraction == 0f) FuseIcons.WifiOff else FuseIcons.Wifi
                        },
                        size = 18.dp,
                        tint = c.text,
                    )
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(g.label.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                FText(g.value, Fuse.type.bodyStrong, color = if (g.warn) c.warning else c.text, maxLines = 1)
                g.detail?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 1) }
            }
        }
    }
}

/** Which screen the brightness slider sets. */
internal enum class BrightnessTarget(val label: String) { MAIN("Main"), THIS("This screen"), BOTH("Both") }

/** Which sound the volume slider sets. */
internal enum class VolumeTarget(val label: String) { DEVICE("Device"), MUSIC("Menu music"), SOUNDS("Sounds") }

/** Remembered while Fuse runs, so coming back to the page keeps the choices. */
internal object CompanionControls {
    var brightness by mutableStateOf(BrightnessTarget.BOTH)
    var volume by mutableStateOf(VolumeTarget.DEVICE)

    /** The second screen is blacked out until it is touched. */
    var screenOff by mutableStateOf(false)
}

/**
 * The third page: two large sliders, brightness (the main screen, this one, or both) and volume
 * (the device, the menu music or the interface sounds), then tiles for Low Power, Performance,
 * Find games, menu music, sounds and turning this screen off. It fits without scrolling.
 */
@Composable
internal fun ControlsPage(store: FuseStore, platform: PlatformUi) {
    val quick = platform.quick
    val prefs by store.prefs.collectAsState()
    val main by quick.brightness.collectAsState()
    val second by quick.secondBrightness.collectAsState()
    val system by quick.systemBrightness.collectAsState()
    val volume by quick.volume.collectAsState()
    val scan by store.sources.scan.collectAsState()
    val scanning = scan.phase == ScanPhase.DISCOVERING || scan.phase == ScanPhase.SCANNING || scan.phase == ScanPhase.SAVING
    val c = Fuse.colors
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = Space.l, end = Space.l, top = CompanionTopBar, bottom = CompanionDotsBar)) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            if (platform.features.brightness && main != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    // Without a second-screen window, only the main screen can be set.
                    val targets = if (second != null) BrightnessTarget.entries else listOf(BrightnessTarget.MAIN)
                    val target = CompanionControls.brightness.takeIf { it in targets } ?: targets.first()
                    val value = if (target == BrightnessTarget.THIS) second ?: 0f else main ?: 0f
                    if (targets.size > 1) Segmented(targets.map { it.label }, targets.indexOf(target)) { CompanionControls.brightness = targets[it] }
                    FillSlider(
                        value,
                        onChange = { v ->
                            when (target) {
                                BrightnessTarget.MAIN -> quick.setBrightness(v)
                                BrightnessTarget.THIS -> quick.setSecondBrightness(v)
                                BrightnessTarget.BOTH -> {
                                    quick.setBrightness(v)
                                    quick.setSecondBrightness(v)
                                }
                            }
                        },
                        icon = FuseIcons.Sun,
                        label = "Brightness",
                        valueText = "${(value * 100).roundToInt()}%",
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                    )
                    if (!system && quick.canAskSystemBrightness && target != BrightnessTarget.THIS) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(Fuse.geometry.control))
                                .clickable(remember { MutableInteractionSource() }, null) { quick.askSystemBrightness() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FText("The main screen dims inside Fuse only. Allow system brightness", Fuse.type.caption, color = c.accent, maxLines = 1, modifier = Modifier.weight(1f))
                            FuseIcon(FuseIcons.ChevronRight, size = 14.dp, tint = c.accent)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                val device = volume.takeIf { platform.features.volume }
                val targets = VolumeTarget.entries.filter { it != VolumeTarget.DEVICE || device != null }
                val target = CompanionControls.volume.takeIf { it in targets } ?: targets.first()
                Segmented(targets.map { it.label }, targets.indexOf(target)) { CompanionControls.volume = targets[it] }
                val (value, on) = when (target) {
                    VolumeTarget.DEVICE -> (device ?: 0f) to true
                    VolumeTarget.MUSIC -> prefs.music.volume to prefs.music.enabled
                    VolumeTarget.SOUNDS -> prefs.soundVolume to (prefs.sound != SoundProfile.OFF)
                }
                FillSlider(
                    value,
                    onChange = { v ->
                        when (target) {
                            VolumeTarget.DEVICE -> quick.setVolume(v)
                            VolumeTarget.MUSIC -> store.updatePrefs { p -> p.copy(music = p.music.copy(volume = v)) }
                            VolumeTarget.SOUNDS -> store.updatePrefs { p -> p.copy(soundVolume = v) }
                        }
                    },
                    icon = when (target) {
                        VolumeTarget.DEVICE -> if (value <= 0f) FuseIcons.VolumeOff else FuseIcons.Volume
                        VolumeTarget.MUSIC -> FuseIcons.Music
                        VolumeTarget.SOUNDS -> FuseIcons.Bell
                    },
                    label = if (target == VolumeTarget.DEVICE) "Volume" else target.label,
                    valueText = if (on) "${(value * 100).roundToInt()}%" else "Off",
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    enabled = on,
                )
            }
            val tiles = listOf<@Composable (Modifier) -> Unit>(
                { m ->
                    ControlTile("Low Power", FuseIcons.Leaf, selected = false, modifier = m, active = prefs.lowPower, toggle = true, compact = true) {
                        store.updatePrefs { it.copy(lowPower = !it.lowPower) }
                    }
                },
                { m ->
                    ControlTile("Performance", FuseIcons.Gauge, selected = false, modifier = m, detail = performanceLabel(prefs.performance), compact = true) {
                        store.updatePrefs { it.copy(performance = it.performance.next()) }
                    }
                },
                { m ->
                    ControlTile(
                        "Find games", FuseIcons.FolderSearch, selected = false, modifier = m, active = scanning, compact = true,
                        detail = when {
                            scanning -> "Looking"
                            scan.phase == ScanPhase.DONE && scan.added > 0 -> "${scan.added} new"
                            else -> "Scan folders"
                        },
                    ) { if (!scanning) store.sources.rescan(ScanScope.QUICK) }
                },
                { m ->
                    ControlTile("Menu music", FuseIcons.Music, selected = false, modifier = m, active = prefs.music.enabled, toggle = true, compact = true) {
                        store.updatePrefs { p -> p.copy(music = p.music.copy(enabled = !p.music.enabled)) }
                    }
                },
                { m ->
                    ControlTile("Sounds", FuseIcons.Bell, selected = false, modifier = m, active = prefs.sound != SoundProfile.OFF, toggle = true, compact = true) {
                        store.updatePrefs { p -> p.copy(sound = if (p.sound == SoundProfile.OFF) SoundProfile.SOFT else SoundProfile.OFF) }
                    }
                },
                { m ->
                    ControlTile("Screen off", FuseIcons.Moon, selected = false, modifier = m, detail = "Tap to wake", compact = true) {
                        CompanionControls.screenOff = true
                    }
                },
            )
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f).heightIn(max = 96.dp), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
    }
}

/** A row of choices in one pill; the chosen one is filled. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Fuse.colors
    Row(
        Modifier.fillMaxWidth().clip(PillShape).background(c.text.copy(alpha = 0.08f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.text else Color.Transparent, Fuse.motion.tween(Durations.FAST), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(PillShape)
                    .background(bg)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                FText(label, Fuse.type.label, color = if (on) c.ink else c.text, maxLines = 1)
            }
        }
    }
}

/** A round icon button for the second screen's top line (close, back): a 48 dp target around a 36 dp disc. */
@Composable
internal fun CompanionRoundButton(icon: ImageVector, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(
        Modifier.size(48.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.text.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = 18.dp, tint = c.text)
        }
    }
}
