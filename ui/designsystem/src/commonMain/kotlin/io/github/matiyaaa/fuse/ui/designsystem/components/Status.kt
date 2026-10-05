package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.tween

/**
 * The status cluster at the top right: connectivity, battery and time on one baseline, drawn in
 * Fuse's own glyphs so it belongs to the interface instead of looking like a phone status bar.
 * Anything the platform doesn't report is simply left out.
 */
@Composable
fun StatusCluster(
    status: SystemStatus,
    time: String,
    modifier: Modifier = Modifier,
    showWifi: Boolean = true,
    showBluetooth: Boolean = false,
    /** Drawn tighter, with smaller figures, when something else shares the end of the line. */
    compact: Boolean = false,
) {
    val c = Fuse.colors
    val numeric = if (compact) Fuse.type.numericSmall else Fuse.type.numeric
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBluetooth && status.bluetooth == ConnectionState.CONNECTED) {
            BluetoothGlyph(c.textMuted)
        }
        // A cable stands in for Wi-Fi when Wi-Fi isn't what connects the device.
        if (showWifi && status.ethernet && status.wifi != ConnectionState.CONNECTED) {
            EthernetGlyph()
        } else if (showWifi && status.wifi != ConnectionState.UNKNOWN) {
            WifiGlyph(if (status.wifi == ConnectionState.CONNECTED) status.wifiStrength ?: 3 else 0, status.wifi == ConnectionState.CONNECTED)
        }
        status.batteryPercent?.let { pct ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) Space.xs else Space.s), verticalAlignment = Alignment.CenterVertically) {
                BatteryGlyph(pct, status.charging)
                FText("$pct%", numeric, color = c.textMuted)
            }
        }
        FText(time, if (compact) Fuse.type.numeric else Fuse.type.numeric.copy(fontSize = Fuse.type.numeric.fontSize * 1.15f), color = c.text)
    }
}

/**
 * Battery drawn as an outline with a level fill that eases to each new reading; a bolt when
 * charging, the danger colour when low. Its shapes are built once per size.
 *
 * Plugging in plays a short flourish: the fill rises from empty to the level in green, the bolt
 * springs in, and a ring of light breathes out from the outline and fades. It plays only on the
 * change, never when Fuse starts already charging, and not under Reduced motion.
 */
@Composable
fun BatteryGlyph(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val low = percent <= 15 && !charging
    val reduced = Fuse.motion.reduced
    val plug = remember { FuselineValue(1f) }
    val was = remember { booleanArrayOf(charging) }
    LaunchedEffect(charging) {
        val plugged = charging && !was[0] && !reduced
        was[0] = charging
        if (plugged) {
            plug.snapTo(0f)
            plug.animateTo(1f, tween(PLUG_MS, easing = Curves.Linear))
        } else {
            // Unplugged mid-flourish: the flourish that was cut short must not stay on screen.
            plug.snapTo(1f)
        }
    }
    val glow = c.success
    val fillColor by fuselineColor(
        when {
            charging -> c.success
            low -> c.danger
            else -> c.text
        },
        Fuse.motion.tween(Durations.BASE),
        label = "batteryColor",
    )
    val level by fuselineFloat(percent.coerceIn(0, 100) / 100f, Fuse.motion.tween(Durations.DELIBERATE), label = "batteryLevel")
    val outline = c.text.copy(alpha = 0.55f)
    val boltColor = c.ink
    Spacer(
        modifier.size(width = 26.dp, height = 13.dp).drawWithCache {
            val stroke = 1.4.dp.toPx()
            val nub = 2.dp.toPx()
            val body = Size(size.width - nub - stroke, size.height - stroke)
            val r = CornerRadius(3.dp.toPx())
            val inset = stroke + 1.2.dp.toPx()
            val fullW = body.width - inset * 2 + stroke
            val cx = body.width / 2 + stroke / 2
            val h = size.height
            val bolt = Path().apply {
                moveTo(cx + h * 0.08f, h * 0.12f)
                lineTo(cx - h * 0.22f, h * 0.56f)
                lineTo(cx, h * 0.56f)
                lineTo(cx - h * 0.08f, h * 0.88f)
                lineTo(cx + h * 0.22f, h * 0.44f)
                lineTo(cx, h * 0.44f)
                close()
            }
            val outlineStroke = Stroke(stroke)
            onDrawBehind {
                val p = plug.value
                // The ring of light: out from the outline and gone by the end.
                if (p < 1f) {
                    val grow = 5.dp.toPx() * easeOut(p)
                    drawRoundRect(
                        glow.copy(alpha = 0.55f * (1f - p)),
                        Offset(stroke / 2 - grow, stroke / 2 - grow),
                        Size(body.width + grow * 2, body.height + grow * 2),
                        CornerRadius(r.x + grow),
                        style = Stroke(stroke * (1.6f - p)),
                    )
                }
                drawRoundRect(outline, Offset(stroke / 2, stroke / 2), body, r, style = outlineStroke)
                drawRoundRect(outline, Offset(size.width - nub, size.height * 0.32f), Size(nub, size.height * 0.36f), CornerRadius(nub))
                // The fill rises from empty over the first half of the flourish.
                val rise = if (p < 1f) easeOut((p / 0.55f).coerceIn(0f, 1f)) else 1f
                drawRoundRect(fillColor, Offset(inset, inset), Size((fullW * level * rise).coerceAtLeast(1.5f), size.height - inset * 2), CornerRadius(1.5.dp.toPx()))
                if (charging) {
                    // The bolt springs in, overshooting a little, once the fill is on its way.
                    val pop = if (p < 1f) backOut(((p - 0.25f) / 0.45f).coerceIn(0f, 1f)) else 1f
                    if (pop > 0f) scale(pop, pivot = Offset(cx, h / 2)) { drawPath(bolt, boltColor) }
                }
            }
        },
    )
}

/**
 * The battery drawn large, for status pages: an outline with a nub, the level filling it (animated),
 * a bolt while charging and a check once full. Green while charging or full, red at 15% or less on
 * battery, otherwise the text colour. While charging a soft light runs along the fill. It fills the
 * size it is given; about 2:1 looks right.
 */
@Composable
fun BatteryCapsule(percent: Int, charging: Boolean, modifier: Modifier = Modifier, full: Boolean = false) {
    val c = Fuse.colors
    val low = percent <= 15 && !charging
    val fill = when {
        charging || full -> c.success
        low -> c.danger
        else -> c.text.copy(alpha = 0.92f)
    }
    val level by fuselineFloat(percent.coerceIn(0, 100) / 100f, Fuse.motion.tween(Durations.DELIBERATE), label = "battery")
    val sweep = if (charging && !full && !Fuse.motion.reduced && Fuse.quality.animatedBackground) {
        rememberLoopClock(label = "charge").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1800, easing = Curves.Linear), RepeatMode.Restart), label = "sweep",
        )
    } else {
        null
    }
    val outline = c.text.copy(alpha = 0.32f)
    val ink = c.ink
    val text = c.text
    Spacer(
        modifier.drawWithCache {
            val stroke = (size.height * 0.045f).coerceIn(2.dp.toPx(), 3.dp.toPx())
            val nubW = size.width * 0.05f
            val gap = stroke
            val body = Size(size.width - nubW - gap - stroke, size.height - stroke)
            val radius = body.height * 0.24f
            val inset = stroke + size.height * 0.07f
            val inner = Size(body.width + stroke - inset * 2, size.height - inset * 2)
            val innerRadius = CornerRadius((radius - inset + stroke).coerceAtLeast(2.dp.toPx()))
            val cx = stroke / 2 + body.width / 2
            val cy = size.height / 2
            val h = inner.height * 0.62f
            val bolt = Path().apply {
                moveTo(cx + h * 0.10f, cy - h * 0.5f)
                lineTo(cx - h * 0.30f, cy + h * 0.08f)
                lineTo(cx - h * 0.02f, cy + h * 0.08f)
                lineTo(cx - h * 0.12f, cy + h * 0.5f)
                lineTo(cx + h * 0.30f, cy - h * 0.08f)
                lineTo(cx + h * 0.02f, cy - h * 0.08f)
                close()
            }
            val check = Path().apply {
                moveTo(cx - h * 0.34f, cy + h * 0.02f)
                lineTo(cx - h * 0.08f, cy + h * 0.28f)
                lineTo(cx + h * 0.38f, cy - h * 0.26f)
            }
            val outlineStroke = Stroke(stroke)
            val checkStroke = Stroke(h * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val fillBrush = Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.78f)), startY = inset, endY = inset + inner.height)
            val band = inner.width * 0.35f
            // The charging light, built once at x = 0 and moved by translation while it runs.
            val sweepBrush = Brush.horizontalGradient(
                listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0f)),
                startX = 0f,
                endX = band,
            )
            onDrawBehind {
                drawRoundRect(outline, Offset(stroke / 2, stroke / 2), body, CornerRadius(radius), style = outlineStroke)
                drawRoundRect(outline, Offset(size.width - nubW, size.height * 0.32f), Size(nubW, size.height * 0.36f), CornerRadius(nubW * 0.6f))
                val w = (inner.width * level).coerceAtLeast(if (percent > 0) innerRadius.x * 1.2f else 0f)
                if (w > 0f) {
                    drawRoundRect(fillBrush, Offset(inset, inset), Size(w, inner.height), innerRadius)
                    if (sweep != null) {
                        val x = inset - band + (w + band) * sweep.value
                        clipRect(inset, inset, inset + w, inset + inner.height) {
                            translate(left = x) { drawRect(sweepBrush, Offset(0f, inset), Size(band, inner.height)) }
                        }
                    }
                }
                // The mark sits over the fill in ink, or in the text colour on the empty part.
                val markColor = if (cx < inset + w) ink else text
                if (charging && !full) drawPath(bolt, markColor) else if (full) drawPath(check, markColor, style = checkStroke)
            }
        },
    )
}

/** Wi-Fi strength 0..4 as three arcs and a dot; unlit arcs stay faintly visible. */
@Composable
fun WifiGlyph(level: Int, connected: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val lit = if (!connected) 0 else (level.coerceIn(0, 4) * 3 + 3) / 4
    val on = c.text.copy(alpha = 0.9f)
    val off = c.text.copy(alpha = 0.2f)
    Spacer(
        modifier.size(18.dp).drawWithCache {
            val stroke = 1.8.dp.toPx()
            val center = Offset(size.width / 2, size.height * 0.86f)
            val arcStroke = Stroke(stroke, cap = StrokeCap.Round)
            onDrawBehind {
                for (i in 1..3) {
                    val radius = size.width * (0.18f + 0.26f * i) / 1.2f
                    drawArc(
                        color = if (i <= lit) on else off,
                        startAngle = 225f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = arcStroke,
                    )
                }
                drawCircle(if (connected) c.text else c.text.copy(alpha = 0.3f), radius = stroke * 0.9f, center = center)
                if (!connected) {
                    drawLine(c.text.copy(alpha = 0.7f), Offset(size.width * 0.15f, size.height * 0.15f), Offset(size.width * 0.85f, size.height * 0.85f), stroke, StrokeCap.Round)
                }
            }
        },
    )
}

/** How long the plug-in flourish on the battery runs. */
private const val PLUG_MS = 1_100

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

/** Eases past the end and settles back, for a shape that springs into place. */
private fun backOut(t: Float): Float {
    if (t <= 0f) return 0f
    val k = 1.9f
    val u = t - 1f
    return 1f + (k + 1f) * u * u * u + k * u * u
}

/**
 * A network cable's plug, seen end on: the body with its latch below and three contacts, drawn
 * in the same weight as the Wi-Fi arcs it stands in for.
 */
@Composable
fun EthernetGlyph(modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val on = c.text.copy(alpha = 0.9f)
    Spacer(
        modifier.size(18.dp).drawWithCache {
            val stroke = 1.8.dp.toPx()
            val w = size.width
            val h = size.height
            val body = Path().apply {
                moveTo(w * 0.16f, h * 0.22f)
                lineTo(w * 0.84f, h * 0.22f)
                lineTo(w * 0.84f, h * 0.68f)
                lineTo(w * 0.66f, h * 0.68f)
                lineTo(w * 0.66f, h * 0.84f)
                lineTo(w * 0.34f, h * 0.84f)
                lineTo(w * 0.34f, h * 0.68f)
                lineTo(w * 0.16f, h * 0.68f)
                close()
            }
            val line = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                drawPath(body, on, style = line)
                for (i in 0 until 3) {
                    val x = w * (0.36f + 0.14f * i)
                    drawLine(on, Offset(x, h * 0.34f), Offset(x, h * 0.46f), stroke * 0.8f, StrokeCap.Round)
                }
            }
        },
    )
}

@Composable
private fun BluetoothGlyph(color: Color) {
    Spacer(
        Modifier.size(14.dp).drawWithCache {
            val w = size.width
            val h = size.height
            val p = Path().apply {
                moveTo(w * 0.25f, h * 0.28f)
                lineTo(w * 0.75f, h * 0.7f)
                lineTo(w * 0.5f, h * 0.92f)
                lineTo(w * 0.5f, h * 0.08f)
                lineTo(w * 0.75f, h * 0.3f)
                lineTo(w * 0.25f, h * 0.72f)
            }
            val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind { drawPath(p, color, style = stroke) }
        },
    )
}
