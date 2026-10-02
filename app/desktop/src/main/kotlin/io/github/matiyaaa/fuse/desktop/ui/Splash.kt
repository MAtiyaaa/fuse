package io.github.matiyaaa.fuse.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The first frame: Fuse's mark on ink while the library opens. With [error], startup failed and the
 * reason is shown instead of an endless wait.
 */
@Composable
fun Splash(error: String? = null, detail: String? = null) {
    Box(Modifier.fillMaxSize().background(FuseBrand.Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            val glow = if (error == null) {
                val t = rememberInfiniteTransition(label = "splash")
                val v by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "spark")
                v
            } else {
                1f
            }
            Canvas(Modifier.size(72.dp).graphicsLayer { alpha = if (error == null) 0.75f + 0.25f * glow else 0.5f }) {
                drawFuseMark(size.minDimension, FuseBrand.Mark, FuseBrand.Spark.copy(alpha = glow))
            }
            if (error != null) {
                BasicText(
                    "Fuse could not start",
                    style = TextStyle(color = FuseBrand.Mark, fontSize = 22.sp, textAlign = TextAlign.Center),
                )
                BasicText(
                    error,
                    modifier = Modifier.widthIn(max = 640.dp).padding(horizontal = 24.dp),
                    style = TextStyle(color = FuseBrand.Mark.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center),
                )
                // The system's own words, small and last, for a bug report.
                if (detail != null && detail != error) {
                    BasicText(
                        detail,
                        modifier = Modifier.widthIn(max = 640.dp).padding(horizontal = 24.dp),
                        style = TextStyle(color = FuseBrand.Mark.copy(alpha = 0.4f), fontSize = 12.sp, textAlign = TextAlign.Center),
                    )
                }
            }
        }
    }
}
