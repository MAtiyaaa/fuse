package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

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
) {
    val c = Fuse.colors
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBluetooth && status.bluetooth == ConnectionState.CONNECTED) {
            BluetoothGlyph(c.textMuted)
        }
        if (showWifi && status.wifi != ConnectionState.UNKNOWN) {
            WifiGlyph(if (status.wifi == ConnectionState.CONNECTED) status.wifiStrength ?: 3 else 0, status.wifi == ConnectionState.CONNECTED)
        }
        status.batteryPercent?.let { pct ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                BatteryGlyph(pct, status.charging)
                FText("$pct%", Fuse.type.numeric, color = c.textMuted)
            }
        }
        FText(time, Fuse.type.numeric.copy(fontSize = Fuse.type.numeric.fontSize * 1.15f), color = c.text)
    }
}

/** Battery drawn as an outline with a level fill; a bolt when charging, warning colour when low. */
@Composable
fun BatteryGlyph(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val low = percent <= 15 && !charging
    val fillColor = when {
        charging -> c.success
        low -> c.danger
        else -> c.text
    }
    Canvas(modifier.size(width = 26.dp, height = 13.dp)) {
        val stroke = 1.4.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub - stroke, size.height - stroke)
        val r = CornerRadius(3.dp.toPx())
        drawRoundRect(c.text.copy(alpha = 0.55f), Offset(stroke / 2, stroke / 2), body, r, style = Stroke(stroke))
        drawRoundRect(c.text.copy(alpha = 0.55f), Offset(size.width - nub, size.height * 0.32f), Size(nub, size.height * 0.36f), CornerRadius(nub))
        val inset = stroke + 1.2.dp.toPx()
        val fullW = body.width - inset * 2 + stroke
        val w = fullW * (percent.coerceIn(0, 100) / 100f)
        drawRoundRect(fillColor, Offset(inset, inset), Size(w.coerceAtLeast(1.5f), size.height - inset * 2), CornerRadius(1.5.dp.toPx()))
        if (charging) {
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
            drawPath(bolt, c.ink)
        }
    }
}

/** Wi-Fi strength 0..4 as three arcs and a dot; unlit arcs stay faintly visible. */
@Composable
fun WifiGlyph(level: Int, connected: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Canvas(modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val center = Offset(size.width / 2, size.height * 0.86f)
        val lit = if (!connected) 0 else (level.coerceIn(0, 4) * 3 + 3) / 4
        for (i in 1..3) {
            val radius = size.width * (0.18f + 0.26f * i) / 1.2f
            drawArc(
                color = if (i <= lit) c.text.copy(alpha = 0.9f) else c.text.copy(alpha = 0.2f),
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(if (connected) c.text else c.text.copy(alpha = 0.3f), radius = stroke * 0.9f, center = center)
        if (!connected) {
            drawLine(c.text.copy(alpha = 0.7f), Offset(size.width * 0.15f, size.height * 0.15f), Offset(size.width * 0.85f, size.height * 0.85f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun BluetoothGlyph(color: Color) {
    Canvas(Modifier.size(14.dp)) {
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
        drawPath(p, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
