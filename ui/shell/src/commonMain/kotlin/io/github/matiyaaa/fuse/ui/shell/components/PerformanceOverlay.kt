package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * Real metrics only, as the platform reports them (Fuse's own frame rate, memory, temperatures).
 * When the platform reports nothing, the overlay says so instead of showing made-up numbers.
 */
@Composable
fun PerformanceOverlay(metrics: List<PerformanceMetric>, modifier: Modifier = Modifier) {
    Panel(modifier.widthIn(min = 180.dp, max = 260.dp)) {
        Column(Modifier.padding(horizontal = Space.m, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (metrics.isEmpty()) {
                FText("No performance data from this device", Fuse.type.caption, color = Fuse.colors.textMuted)
            }
            for (m in metrics) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FText(m.label, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Space.s))
                    FText(m.value, Fuse.type.numeric, maxLines = 1)
                }
                m.fraction?.let { ProgressBar(it, height = 2.dp) }
            }
        }
    }
}
