package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * Real metrics only, as the platform reports them (Fuse's own frame rate, memory, temperatures).
 * When the platform reports nothing, the overlay says so instead of showing made-up numbers.
 *
 * A compact card of fixed width, so a value that ticks never makes it jump: each metric is a quiet
 * label and its value in tabular figures, and a hairline meter under it where the platform gives a
 * fraction. A meter turns to the warning colour from three quarters and to danger from nine tenths,
 * so a hot or full device stands out at a glance.
 */
@Composable
fun PerformanceOverlay(metrics: List<PerformanceMetric>, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Panel(modifier.width(OVERLAY_WIDTH)) {
        Column(
            Modifier.padding(horizontal = Space.m, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (metrics.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.Gauge, size = Size.iconS, tint = c.textMuted)
                    Spacer(Modifier.width(Space.s))
                    FText("No performance data from this device", Fuse.type.caption, color = c.textMuted, maxLines = 2)
                }
            }
            for (m in metrics) MetricRow(m)
        }
    }
}

@Composable
private fun MetricRow(m: PerformanceMetric) {
    val c = Fuse.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText(m.label, Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            FText(m.value, Fuse.type.numericSmall, color = c.text, maxLines = 1)
        }
        m.fraction?.let { f ->
            val color = when {
                f >= 0.9f -> c.danger
                f >= 0.75f -> c.warning
                else -> c.accent
            }
            ProgressBar(f.coerceIn(0f, 1f), Modifier.fillMaxWidth(), color = color, height = Space.xxs)
        }
    }
}

/** Wide enough for "5.2 / 16.0 GB" beside "CPU temperature". */
private val OVERLAY_WIDTH = 224.dp
