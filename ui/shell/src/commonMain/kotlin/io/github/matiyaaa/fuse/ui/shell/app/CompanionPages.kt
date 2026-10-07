package io.github.matiyaaa.fuse.ui.shell.app

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
import androidx.compose.foundation.layout.aspectRatio
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
import io.github.matiyaaa.fuse.ui.designsystem.effects.background
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
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
 * The second page, the device at a glance, from what Fuse can really measure: the battery as a
 * large ring with how long it lasts (or takes to fill), then slim meters for the processor,
 * memory, the library drive and Wi-Fi, and the game being played while there is one. Wider than
 * tall (a two-screen handheld's lower screen), the battery stands beside the meters. Anything the
 * device doesn't report is left out rather than shown as zero.
 */
@Composable
internal fun StatusPage(store: FuseStore, platform: PlatformUi, status: SystemStatus) {
    val metrics by platform.performance.collectAsState()
    val home by store.homeFeed.collectAsState()
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
        val wide = maxWidth > maxHeight * 1.1f
        val meters: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                gauges.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { MeterTile(it, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (playing != null) SessionStrip(playing, since, now, Modifier.fillMaxWidth().height(56.dp))
            }
        }
        if (wide) {
            Row(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                BatteryCard(status, batteryTemp, Modifier.weight(0.9f).fillMaxHeight())
                meters(Modifier.weight(1.25f).fillMaxHeight())
            }
        } else {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                BatteryCard(status, batteryTemp, Modifier.fillMaxWidth().weight(1f))
                meters(Modifier.fillMaxWidth().weight(1.15f))
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
 * The battery: a ring as large as the card allows with the level in its middle, and beside (or
 * under) it whether it is charging, how long it lasts or takes to fill, and how warm it is. A
 * machine on mains power says so.
 */
@Composable
private fun BatteryCard(status: SystemStatus, temperature: String?, modifier: Modifier) {
    val c = Fuse.colors
    val pct = status.batteryPercent
    CompanionCard(modifier, padding = Space.m) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (pct == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        FuseIcon(FuseIcons.Plug, size = 24.dp, tint = c.textMuted)
                    }
                    FText("On mains power", Fuse.type.titleSmall, maxLines = 1)
                    FText("This machine has no battery", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
                return@BoxWithConstraints
            }
            val low = pct <= 15 && !status.charging
            val lit = status.charging || status.batteryFull
            val tint = when {
                lit -> c.success
                low -> c.danger
                else -> c.accent
            }
            val time = batteryTimeText(status)
            val stateWord = when {
                status.batteryFull -> "Charged"
                status.charging -> "Charging"
                else -> "On battery"
            }
            // Ring beside the words where the card is wide, above them where it is tall.
            val sideBySide = maxWidth > maxHeight * 1.35f
            val ring = if (sideBySide) minOf(maxHeight, maxWidth * 0.45f) else minOf(maxWidth * 0.7f, maxHeight * 0.62f)
            val words = @Composable {
                Column(horizontalAlignment = if (sideBySide) Alignment.Start else Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (status.charging) FuseIcon(FuseIcons.Zap, size = 14.dp, tint = tint) else Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
                        FText(stateWord.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                    }
                    FText(time ?: if (status.charging) "Working out the time" else "Time left shows soon", Fuse.type.titleSmall, color = if (lit) c.success else c.text, maxLines = 1)
                    temperature?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 1) }
                }
            }
            val dial = @Composable {
                Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                    ProgressRing(pct / 100f, size = ring, stroke = (ring.value * 0.075f).coerceIn(6f, 14f).dp, color = tint)
                    Row(verticalAlignment = Alignment.Bottom) {
                        val big = (ring.value * 0.3f).coerceIn(28f, 64f).sp
                        FText("$pct", Fuse.type.numericLarge.copy(fontSize = big, lineHeight = big * 1.05f), color = if (low) c.danger else c.text, maxLines = 1)
                        FText("%", Fuse.type.titleSmall, color = c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 1.dp, bottom = (big.value * 0.16f).dp))
                    }
                }
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    dial()
                    words()
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically)) {
                    dial()
                    words()
                }
            }
        }
    }
}

/** The game being played: its art, its name and how long this session has run. */
@Composable
private fun SessionStrip(playing: GameCard, since: Long?, now: Long, modifier: Modifier) {
    val c = Fuse.colors
    CompanionCard(modifier, padding = Space.s) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            SquareGameArt(
                playing.art,
                Modifier.fillMaxHeight().aspectRatio(1f).clip(SquircleShape.fraction(0.24f)),
                fallback = { GeneratedArt(playing.title, playing.accent.toColor(), slot = ArtSlot.ICON) },
            )
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(playing.title, Fuse.type.bodyStrong, maxLines = 1)
                val session = since?.let { playtimeText(((now - it) / 1000).coerceAtLeast(0)) }
                FText(listOfNotNull(session?.let { "$it this session" }, "${playtimeText(playing.playSeconds)} in all").joinToString("  ·  "), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            Box(Modifier.padding(end = Space.s).size(8.dp).clip(CircleShape).background(c.accent))
        }
    }
}

/** One measure: its icon and name, the value large, a slim meter, and a line of detail. */
@Composable
private fun MeterTile(g: Gauge, modifier: Modifier) {
    val c = Fuse.colors
    val tint = if (g.warn) c.warning else c.accent
    CompanionCard(modifier, padding = Space.m) {
        // A tall tile gives the reading a larger size, so it fills the tile rather than floating in it.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val big = maxHeight > 120.dp && maxWidth > 150.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(
                        when (g.kind) {
                            GaugeKind.PROCESSOR -> FuseIcons.Chip
                            GaugeKind.MEMORY -> FuseIcons.Memory
                            GaugeKind.STORAGE -> FuseIcons.HardDrive
                            GaugeKind.WIFI -> if (g.fraction == 0f) FuseIcons.WifiOff else FuseIcons.Wifi
                        },
                        size = 14.dp,
                        tint = tint,
                    )
                    Spacer(Modifier.width(6.dp))
                    FText(g.label.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FText(g.value, if (big) Fuse.type.title else Fuse.type.bodyStrong, color = if (g.warn) c.warning else c.text, maxLines = 1)
                    val f = (g.fraction ?: 0f).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(if (big) 6.dp else 4.dp).clip(PillShape).background(c.text.copy(alpha = 0.1f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceAtLeast(0.02f)).clip(PillShape).background(tint))
                    }
                    g.detail?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 1) }
                }
            }
        }
    }
}

/** Which screen the brightness slider sets. */
internal enum class BrightnessTarget(val label: String) { MAIN("Main"), THIS("This screen"), BOTH("Both") }

/** Which sound the volume slider sets. */
internal enum class VolumeTarget(val label: String, val chip: String = label) { DEVICE("Device"), MUSIC("Menu music", "Music"), SOUNDS("Sounds") }

/** Remembered while Fuse runs, so coming back to the page keeps the choices. */
internal object CompanionControls {
    var brightness by mutableStateOf(BrightnessTarget.BOTH)
    var volume by mutableStateOf(VolumeTarget.DEVICE)

    /** The second screen is blacked out until it is touched. */
    var screenOff by mutableStateOf(false)
}

/**
 * The third page, the device's controls: brightness and volume as sliders, each with small chips
 * for what it sets (the main screen, this one or both; the device, the menu music or the sounds),
 * then round toggles, the way a phone's control centre has them: Low Power, Performance, Find
 * games (Hide while Fuse is away), menu music, sounds and turning this screen off. Wider than tall,
 * the sliders stand beside the toggles. It fits without scrolling.
 */
@Composable
internal fun ControlsPage(store: FuseStore, platform: PlatformUi, onHide: (() -> Unit)? = null) {
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
        val wide = maxWidth > maxHeight * 1.1f
        val sliders: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically)) {
                if (platform.features.brightness && main != null) {
                    // Without a second-screen window, only the main screen can be set.
                    val targets = if (second != null) BrightnessTarget.entries else listOf(BrightnessTarget.MAIN)
                    val target = CompanionControls.brightness.takeIf { it in targets } ?: targets.first()
                    val value = if (target == BrightnessTarget.THIS) second ?: 0f else main ?: 0f
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        if (targets.size > 1) TargetChips(targets.map { it.label }, targets.indexOf(target)) { CompanionControls.brightness = targets[it] }
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
                            modifier = Modifier.fillMaxWidth().height(SLIDER),
                        )
                        if (!system && quick.canAskSystemBrightness && target != BrightnessTarget.THIS) {
                            Row(
                                Modifier.clip(RoundedCornerShape(Fuse.geometry.control)).clickable(remember { MutableInteractionSource() }, null) { quick.askSystemBrightness() },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FText("Dims inside Fuse only. Allow system brightness", Fuse.type.caption, color = c.accent, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                FuseIcon(FuseIcons.ChevronRight, size = 14.dp, tint = c.accent)
                            }
                        }
                    }
                }
                val device = volume.takeIf { platform.features.volume }
                val targets = VolumeTarget.entries.filter { it != VolumeTarget.DEVICE || device != null }
                val target = CompanionControls.volume.takeIf { it in targets } ?: targets.first()
                val (value, on) = when (target) {
                    VolumeTarget.DEVICE -> (device ?: 0f) to true
                    VolumeTarget.MUSIC -> prefs.music.volume to prefs.music.enabled
                    VolumeTarget.SOUNDS -> prefs.soundVolume to (prefs.sound != SoundProfile.OFF)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    TargetChips(targets.map { it.chip }, targets.indexOf(target)) { CompanionControls.volume = targets[it] }
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
                        modifier = Modifier.fillMaxWidth().height(SLIDER),
                        enabled = on,
                        dragText = { "${(it * 100).roundToInt()}%" },
                    )
                }
            }
        }
        val toggles: @Composable (Modifier) -> Unit = { m ->
            val orbs = listOf<@Composable (Modifier) -> Unit>(
                { mm -> ControlOrb("Low Power", FuseIcons.Leaf, prefs.lowPower, if (prefs.lowPower) "On" else "Off", mm) { store.updatePrefs { it.copy(lowPower = !it.lowPower) } } },
                { mm -> ControlOrb("Performance", FuseIcons.Gauge, false, performanceLabel(prefs.performance), mm) { store.updatePrefs { it.copy(performance = it.performance.next()) } } },
                { mm ->
                    if (onHide != null) {
                        // Fuse is in the background: the second screen can be given to something else.
                        ControlOrb("Hide", FuseIcons.EyeOff, false, "Until Fuse is back", mm) { onHide() }
                    } else {
                        ControlOrb(
                            "Find games", FuseIcons.FolderSearch, scanning,
                            when {
                                scanning -> "Looking"
                                scan.phase == ScanPhase.DONE && scan.added > 0 -> "${scan.added} new"
                                else -> "Scan folders"
                            },
                            mm,
                        ) { if (!scanning) store.sources.rescan(ScanScope.QUICK) }
                    }
                },
                { mm -> ControlOrb("Music", FuseIcons.Music, prefs.music.enabled, if (prefs.music.enabled) "On" else "Off", mm) { store.updatePrefs { p -> p.copy(music = p.music.copy(enabled = !p.music.enabled)) } } },
                { mm -> ControlOrb("Sounds", FuseIcons.Bell, prefs.sound != SoundProfile.OFF, if (prefs.sound != SoundProfile.OFF) "On" else "Off", mm) { store.updatePrefs { p -> p.copy(sound = if (p.sound == SoundProfile.OFF) SoundProfile.SOFT else SoundProfile.OFF) } } },
                { mm -> ControlOrb("Screen off", FuseIcons.Moon, false, "Tap to wake", mm) { CompanionControls.screenOff = true } },
            )
            // Three to a row where the page is tall, two where they stand beside the sliders.
            Column(m, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                orbs.chunked(if (wide) 2 else 3).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { orb -> orb(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                sliders(Modifier.weight(1f).fillMaxHeight())
                toggles(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                sliders(Modifier.fillMaxWidth())
                toggles(Modifier.fillMaxWidth().weight(1f).heightIn(max = 260.dp))
            }
        }
    }
}

/** How tall the brightness and volume sliders are. */
private val SLIDER = 52.dp

/** What a slider sets, as small chips above it; the chosen one is lit. */
@Composable
private fun TargetChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Fuse.colors
    // A segmented track: the choices share its width evenly, so every name fits however narrow.
    Row(
        Modifier.fillMaxWidth().clip(PillShape).background(c.text.copy(alpha = 0.05f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by fuselineColor(if (on) c.text.copy(alpha = 0.14f) else Color.Transparent, Fuse.motion.tween(Durations.FAST), label = "chip")
            val fg by fuselineColor(if (on) c.text else c.textMuted, Fuse.motion.tween(Durations.FAST), label = "chipText")
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(PillShape)
                    .background({ bg })
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                    .semantics { this.selected = on }
                    .padding(horizontal = Space.xs, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                FText(label, Fuse.type.label, color = { fg }, maxLines = 1)
            }
        }
    }
}

/**
 * A control-centre toggle: a round button lit in the accent while on, its name under it and its
 * state in small letters. The whole tile takes the touch.
 */
@Composable
private fun ControlOrb(label: String, icon: ImageVector, on: Boolean, state: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val disc by fuselineColor(if (on) c.accent else c.text.copy(alpha = 0.1f), Fuse.motion.tween(Durations.FAST), label = "orb")
    val glyph by fuselineColor(if (on) c.onAccent else c.text, Fuse.motion.tween(Durations.FAST), label = "orbIcon")
    CompanionCard(modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick), padding = Space.s) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val orb = minOf(maxHeight * 0.48f, maxWidth * 0.5f, 52.dp).coerceAtLeast(30.dp)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(orb).clip(CircleShape).background({ disc }), contentAlignment = Alignment.Center) {
                    FuseIcon(icon, tint = { glyph }, size = orb * 0.42f)
                }
                FText(label, Fuse.type.label, maxLines = 1)
                FText(state, Fuse.type.caption, color = if (on) c.accent else c.textMuted, maxLines = 1)
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
