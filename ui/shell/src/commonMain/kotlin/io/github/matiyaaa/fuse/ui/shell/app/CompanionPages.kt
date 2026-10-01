package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.SliderBar
import io.github.matiyaaa.fuse.ui.designsystem.components.Toggle
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Room at the top of a page for the status line, and at the bottom for the page dots. */
private val PageTop = 56.dp
private val PageBottom = 44.dp

/**
 * The second page: what the device is doing, from what Fuse can really measure. Battery, the
 * processor's temperature and thermal state, memory, Fuse's own frame rate while it is in front,
 * Wi-Fi, the library drive's free space and the game being played. Anything the device doesn't
 * report is left out rather than shown as zero.
 */
@Composable
internal fun StatusPage(store: FuseStore, platform: PlatformUi, status: SystemStatus) {
    val metrics by platform.performance.collectAsState()
    val home by store.library.home.collectAsState()
    fun metric(vararg keys: String): PerformanceMetric? = keys.firstNotNullOfOrNull { k -> metrics.firstOrNull { it.key == k } }
    val battery = status.batteryPercent
    val batteryTemp = metric("battery_temp")
    val cpuTemp = metric("cpu_temp", "cpu-temp")
    val thermal = metric("thermal")
    val cpuLoad = metric("cpu")
    val memory = metric("memory")
    val fps = metric("fuse_fps")
    val storage = home.storage
    val playing = home.playtime.currentGame
    val since = home.playtime.currentSince
    var now by remember { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            delay(15_000)
        }
    }

    val tiles = buildList {
        if (battery != null) {
            add(
                Stat(
                    icon = if (status.charging) FuseIcons.BatteryCharging else if (battery <= 15) FuseIcons.BatteryLow else FuseIcons.Battery,
                    label = "Battery",
                    value = "$battery%",
                    detail = listOfNotNull(if (status.charging) "Charging" else "On battery", batteryTemp?.value).joinToString("  ·  "),
                    fraction = battery / 100f,
                    warn = battery <= 15 && !status.charging,
                ),
            )
        }
        if (cpuTemp != null || thermal != null || cpuLoad != null) {
            // The temperature leads when there is one; how busy it is and how warm it runs follow.
            val lead = cpuTemp ?: cpuLoad ?: thermal!!
            val rest = listOfNotNull(
                cpuLoad?.takeIf { it !== lead }?.let { "${it.value} busy" },
                thermal?.takeIf { it !== lead }?.let { "Running ${it.value.lowercase()}" },
            )
            add(
                Stat(
                    icon = FuseIcons.Chip,
                    label = "Processor",
                    value = lead.value,
                    detail = rest.joinToString("  ·  ").ifEmpty { if (lead === cpuTemp) "Temperature" else lead.label },
                    fraction = cpuLoad?.fraction ?: thermal?.fraction ?: cpuTemp?.fraction,
                    warn = (thermal?.fraction ?: 0f) >= 0.5f,
                ),
            )
        }
        if (memory != null) add(Stat(FuseIcons.Memory, "Memory", memory.value.substringBefore(" / ").ifEmpty { memory.value }, memory.value.substringAfter(" / ", "").takeIf { it.isNotEmpty() }?.let { "of $it" } ?: memory.label, memory.fraction))
        if (fps != null) add(Stat(FuseIcons.Gauge, "Fuse frame rate", fps.value, "While Fuse is on the main screen", fps.fraction))
        add(
            Stat(
                icon = if (status.wifi == ConnectionState.CONNECTED) FuseIcons.Wifi else FuseIcons.WifiOff,
                label = "Wi-Fi",
                value = when (status.wifi) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.ON -> "Not connected"
                    ConnectionState.OFF -> "Off"
                    ConnectionState.UNKNOWN -> "Unknown"
                },
                detail = status.wifiStrength?.let { signalWords(it) } ?: if (status.network == ConnectionState.CONNECTED) "Online" else "Offline",
                fraction = status.wifiStrength?.let { (it.coerceIn(0, 4)) / 4f },
            ),
        )
        if (storage != null && storage.totalBytes > 0) {
            add(Stat(FuseIcons.HardDrive, "Storage", "${bytesText(storage.freeBytes)} free", "of ${bytesText(storage.totalBytes)}  ·  ${storage.label}", 1f - storage.freeBytes.toFloat() / storage.totalBytes))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth > 560.dp) 3 else 2
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Space.l, end = Space.l, top = PageTop, bottom = PageBottom),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            SectionLabel("Status")
            // The game being played comes first, across the page.
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatIcon(FuseIcons.Timer, if (playing != null) Fuse.colors.accent else Fuse.colors.textMuted)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(if (playing != null) "Playing ${playing.title}" else "No game running", Fuse.type.bodyStrong, maxLines = 1)
                        FText(
                            if (playing != null && since != null) "${playtimeText(((now - since) / 1000).coerceAtLeast(0))} this session  ·  ${playtimeText(playing.playSeconds)} in all"
                            else "Start a game and its time shows here",
                            Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                        )
                    }
                }
            }
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { StatTile(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class Stat(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val detail: String,
    val fraction: Float? = null,
    val warn: Boolean = false,
)

private fun signalWords(bars: Int): String = when {
    bars >= 4 -> "Excellent signal"
    bars == 3 -> "Good signal"
    bars == 2 -> "Fair signal"
    else -> "Weak signal"
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Fuse.colors
    Box(
        modifier
            .clip(RoundedCornerShape(Fuse.geometry.panel))
            .background(c.surfaceRaised.copy(alpha = 0.72f))
            .border(1.dp, c.text.copy(alpha = 0.06f), RoundedCornerShape(Fuse.geometry.panel))
            .padding(Space.m),
    ) { content() }
}

@Composable
private fun StatIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(32.dp).clip(PillShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        FuseIcon(icon, size = 16.dp, tint = tint)
    }
}

@Composable
private fun StatTile(stat: Stat, modifier: Modifier) {
    val c = Fuse.colors
    val tint = if (stat.warn) c.warning else c.text
    Card(modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(stat.icon, size = 15.dp, tint = if (stat.warn) c.warning else c.textMuted)
                Spacer(Modifier.width(Space.s))
                FText(stat.label.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
            }
            Spacer(Modifier.height(Space.s))
            FText(stat.value, Fuse.type.title, color = tint, maxLines = 1)
            FText(stat.detail, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            stat.fraction?.let {
                Spacer(Modifier.height(Space.s))
                ProgressBar(it.coerceIn(0f, 1f), Modifier.fillMaxWidth(), height = 4.dp)
            }
        }
    }
}

/** Which screen the brightness slider sets. */
private enum class BrightnessTarget(val label: String) { MAIN("Main"), THIS("This screen"), BOTH("Both") }

/** Remembered while Fuse runs, so coming back to the page keeps the choice. */
private object CompanionControls {
    var target by mutableStateOf(BrightnessTarget.BOTH)
}

/**
 * The third page: brightness for the main screen, this screen or both, and sound: the device's
 * volume, the menu music and the interface sounds. Sliders follow a finger and show their value.
 */
@Composable
internal fun ControlsPage(store: FuseStore, platform: PlatformUi) {
    val quick = platform.quick
    val prefs by store.prefs.collectAsState()
    val main by quick.brightness.collectAsState()
    val second by quick.secondBrightness.collectAsState()
    val system by quick.systemBrightness.collectAsState()
    val volume by quick.volume.collectAsState()
    val c = Fuse.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Space.l, end = Space.l, top = PageTop, bottom = PageBottom),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        SectionLabel("Controls")
        if (platform.features.brightness && main != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    // Without a second-screen window, only the main screen can be set.
                    val targets = if (second != null) BrightnessTarget.entries else listOf(BrightnessTarget.MAIN)
                    val target = CompanionControls.target.takeIf { it in targets } ?: targets.first()
                    val value = when (target) {
                        BrightnessTarget.THIS -> second ?: 0f
                        else -> main ?: 0f
                    }
                    ControlHeader(FuseIcons.Sun, "Brightness", "${(value * 100).roundToInt()}%")
                    if (targets.size > 1) Segmented(targets.map { it.label }, targets.indexOf(target)) { CompanionControls.target = targets[it] }
                    SliderBar(value, selected = true, modifier = Modifier.fillMaxWidth().height(36.dp), onChange = { v ->
                        when (target) {
                            BrightnessTarget.MAIN -> quick.setBrightness(v)
                            BrightnessTarget.THIS -> quick.setSecondBrightness(v)
                            BrightnessTarget.BOTH -> {
                                quick.setBrightness(v)
                                quick.setSecondBrightness(v)
                            }
                        }
                    })
                    if (!system && quick.canAskSystemBrightness && target != BrightnessTarget.THIS) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(Fuse.geometry.control))
                                .clickable(remember { MutableInteractionSource() }, null) { quick.askSystemBrightness() }
                                .padding(vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FText("The main screen dims inside Fuse only. Allow system brightness", Fuse.type.caption, color = c.accent, maxLines = 2, modifier = Modifier.weight(1f))
                            FuseIcon(FuseIcons.ChevronRight, size = 14.dp, tint = c.accent)
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                val device = volume
                if (platform.features.volume && device != null) {
                    SoundRow(FuseIcons.Volume, "Volume", device, enabled = true) { quick.setVolume(it) }
                }
                val music = prefs.music
                SoundRow(
                    FuseIcons.Music, "Menu music", music.volume, enabled = music.enabled,
                    off = !music.enabled, onToggle = { store.updatePrefs { p -> p.copy(music = p.music.copy(enabled = !p.music.enabled)) } },
                ) { v -> store.updatePrefs { p -> p.copy(music = p.music.copy(volume = v)) } }
                val soundsOn = prefs.sound != SoundProfile.OFF
                SoundRow(
                    FuseIcons.Bell, "Interface sounds", prefs.soundVolume, enabled = soundsOn,
                    off = !soundsOn, onToggle = { store.updatePrefs { p -> p.copy(sound = if (p.sound == SoundProfile.OFF) SoundProfile.SOFT else SoundProfile.OFF) } },
                ) { v -> store.updatePrefs { p -> p.copy(soundVolume = v) } }
            }
        }
    }
}

@Composable
private fun ControlHeader(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatIcon(icon, Fuse.colors.text)
        Spacer(Modifier.width(Space.m))
        FText(label, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f))
        FText(value, Fuse.type.numeric, color = Fuse.colors.textMuted, maxLines = 1)
    }
}

/** A sound level with its slider; [onToggle] adds an On/Off pill for sounds that can be switched off. */
@Composable
private fun SoundRow(
    icon: ImageVector,
    label: String,
    value: Float,
    enabled: Boolean,
    off: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatIcon(icon, if (enabled) Fuse.colors.text else Fuse.colors.textMuted)
            Spacer(Modifier.width(Space.m))
            FText(label, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f))
            if (onToggle != null) {
                OnOffPill(!off, onToggle)
                Spacer(Modifier.width(Space.s))
            }
            FText(if (off) "Off" else "${(value * 100).roundToInt()}%", Fuse.type.numeric, color = Fuse.colors.textMuted, maxLines = 1)
        }
        SliderBar(value, selected = enabled, modifier = Modifier.fillMaxWidth().height(36.dp), onChange = onChange, enabled = enabled)
    }
}

@Composable
private fun OnOffPill(on: Boolean, onClick: () -> Unit) {
    Toggle(
        on,
        Modifier
            .clip(PillShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { selected = on },
    )
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
                    .heightIn(min = 34.dp)
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
