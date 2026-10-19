package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.components.batteryTimeText
import io.github.matiyaaa.fuse.ui.shell.platform.PowerHold
import io.github.matiyaaa.fuse.ui.shell.platform.RefreshFact
import kotlin.math.roundToInt

/**
 * System health's Power and heat: the battery, how warm the device is, the rate Fuse asks of the
 * screen, whether it lets the device sleep, and Low Power Mode a press away. Only what the device
 * can measure is shown; a row it can't fill is left out rather than guessed.
 */
@Composable
fun powerRows(app: AppState, section: String? = null): List<MenuAction> {
    val status by app.platform.status.collectAsState()
    val metrics by app.platform.performance.collectAsState()
    val power by app.platform.power.collectAsState()
    val prefs by app.store.prefs.collectAsState()
    fun metric(vararg keys: String) = metrics.firstOrNull { it.key in keys }
    val thermal = metric("thermal")
    val cpu = metric("cpu_temp", "cpu-temp")
    val batteryTemp = metric("battery_temp")
    return buildList {
        status.batteryPercent?.let { percent ->
            val time = batteryTimeText(status)
            add(MenuAction(
                "power.battery", "Battery",
                when {
                    status.charging || status.batteryFull -> FuseIcons.BatteryCharging
                    percent <= LOW_BATTERY -> FuseIcons.BatteryLow
                    percent < FULL_BATTERY -> FuseIcons.BatteryMedium
                    else -> FuseIcons.BatteryFull
                },
                detail = listOfNotNull(time, batteryTemp?.let { "the battery is at ${it.value}" })
                    .joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null },
                trailing = Trailing.Value(if (status.charging && !status.batteryFull) "$percent%, charging" else "$percent%"),
                section = section,
            ))
        }
        if (thermal != null || cpu != null) {
            val state = thermal?.value
            val level = thermal?.fraction ?: 0f
            val hot = level >= HOT_FRACTION
            add(MenuAction(
                "power.heat", "Heat", if (hot) FuseIcons.Flame else FuseIcons.Thermometer,
                detail = listOfNotNull(
                    when {
                        thermal == null -> null
                        level >= SLOWING_FRACTION -> "The system is slowing the processor to cool it down. Low Power Mode makes Fuse lighter on it; select to open it"
                        hot -> "Hot: the system may start slowing the processor soon. Low Power Mode makes Fuse lighter on it; select to open it"
                        level > 0f -> "Warm, as a handheld gets while it works. Nothing has been slowed down"
                        else -> "Cool. Nothing is being slowed down"
                    },
                    cpu?.let { "processor at ${it.value}" },
                ).joinToString(", ").replaceFirstChar { it.uppercase() },
                trailing = Trailing.Value(state ?: cpu!!.value),
                section = section,
                onSelect = { if (hot) app.openSettings("displays", row = LOW_POWER) },
            ))
        }
        power.refresh?.let { r ->
            val full = r.fullHz?.let { "${it.roundToInt()} Hz" }
            val calm = r.calmHz?.let { "${it.roundToInt()} Hz" } ?: "about 60 Hz"
            add(MenuAction(
                "power.screen", "Screen", FuseIcons.Gauge,
                detail = when (r.state) {
                    RefreshFact.State.FULL -> "At its full rate while you use Fuse. After ten seconds without a press, while only the background moves, Fuse asks for $calm"
                    RefreshFact.State.CALM -> "At $calm while only the background moves. Your next press brings ${full ?: "the full rate"} back before anything moves"
                    RefreshFact.State.RESTING -> "Nothing moves, so Fuse asks the screen for nothing and it can slow right down"
                },
                trailing = Trailing.Value(when (r.state) {
                    RefreshFact.State.FULL -> full ?: "Full rate"
                    RefreshFact.State.CALM -> calm.replaceFirstChar { it.uppercase() }
                    RefreshFact.State.RESTING -> "Resting"
                }),
                section = section,
            ))
        }
        if (power.pausesAsleep || power.holding.isNotEmpty()) {
            val what = buildList {
                if (PowerHold.TRANSFERS in power.holding) add("a download or upload")
                if (PowerHold.ART in power.holding) add("finding art and details")
            }
            add(MenuAction(
                "power.sleep", "Sleep", if (what.isEmpty()) FuseIcons.MoonStar else FuseIcons.Zap,
                detail = if (what.isEmpty()) {
                    "With the screen off the device sleeps. Fuse asks nothing of the network meanwhile and catches up as the screen comes back"
                } else {
                    "Kept awake for ${what.joinToString(" and ")}, so it carries on with the screen off. The device may sleep again a few seconds after it finishes"
                },
                trailing = Trailing.Value(if (what.isEmpty()) "Allowed" else "Kept awake"),
                section = section,
            ))
        }
        // Low Power Mode lives in Displays; here it is one press away, never a second switch for it.
        add(MenuAction(
            "power.low", LOW_POWER, FuseIcons.Leaf,
            detail = "No blur, video previews or moving backgrounds, and lighter artwork. Select to change it in Display",
            trailing = Trailing.Value(if (prefs.lowPower) "On" else "Off"),
            section = section,
            onSelect = { app.openSettings("displays", row = LOW_POWER) },
        ))
    }
}

/** The row in Displays that Power and heat leads to. */
private const val LOW_POWER = "Low Power Mode"

/** At or under this the battery shows as low. */
private const val LOW_BATTERY = 20

/** At or over this it shows as full. */
private const val FULL_BATTERY = 90

/** The thermal state's share of its scale (Android's 0 to 6) from "Hot", and from "Throttling". */
private const val HOT_FRACTION = 0.3f
private const val SLOWING_FRACTION = 0.5f
