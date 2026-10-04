package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Watches for Fuse being left alone and puts up [StandbyScreen] after the user's Standby time, but
 * never while a game runs or starts, during setup, or while the startup animation plays.
 */
@Composable
internal fun StandbyWatch(app: AppState, router: io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter, minutes: Int, busy: () -> Boolean) {
    LaunchedEffect(minutes) {
        if (minutes <= 0) return@LaunchedEffect
        val limit = minutes * 60_000L
        while (true) {
            delay(CHECK_MS)
            val idle = kotlin.time.Clock.System.now().toEpochMilliseconds() - router.lastActivityAt
            if (idle >= limit && !app.standby && !busy()) app.standby = true
        }
    }
}

/**
 * Fuse's standby: the screen goes nearly black, with the clock and Fuse's mark small and dim, and
 * the two drift to a new place every little while, so nothing sits still long enough to wear an OLED
 * screen in. Any button, key or touch wakes it, and only wakes it: that press does nothing else.
 * Waking plays the startup animation when it is on, as though Fuse had just been switched on.
 */
@Composable
internal fun StandbyScreen(clock24h: Boolean, onWake: () -> Unit) {
    val router = LocalInputRouter.current
    val fade = remember { FuselineValue(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_MS, easing = Curves.Linear)) }
    fun wake() {
        router.touched()
        onWake()
    }
    InputLayer(priority = LayerPriority.SYSTEM, modal = true) { _ ->
        wake()
        NavResult.CONSUMED
    }
    // Where the clock sits now, as a share of the free room; it moves on a timer.
    var spot by remember { mutableStateOf(0.5f to 0.45f) }
    val move = remember { FuselineValue(1f) }
    LaunchedEffect(Unit) {
        val random = Random(kotlin.time.Clock.System.now().toEpochMilliseconds())
        while (true) {
            delay(DRIFT_MS)
            move.animateTo(0f, tween(SHIFT_MS))
            spot = (0.1f + random.nextFloat() * 0.8f) to (0.15f + random.nextFloat() * 0.7f)
            move.animateTo(1f, tween(SHIFT_MS))
        }
    }
    val time = rememberClockText(clock24h)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Color.Black)
            .semantics { contentDescription = "Standby. Press any button to wake Fuse" }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.any { it.pressed }) wake()
                    }
                }
            },
    ) {
        // The block takes the size its text needs (a 12-hour clock in a large size is wide on a
        // phone) and sits at [spot] within whatever room is left around it.
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            ((constraints.maxWidth - placeable.width).coerceAtLeast(0) * spot.first).toInt(),
                            ((constraints.maxHeight - placeable.height).coerceAtLeast(0) * spot.second).toInt(),
                        )
                    }
                }
                .graphicsLayer { alpha = 0.55f * move.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            FText(time, Fuse.type.numericLarge.copy(fontSize = Fuse.type.numericLarge.fontSize * 1.6f), color = Color.White, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseMark(Modifier.size(18.dp), color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.width(Space.s))
                FText("Press any button", Fuse.type.caption, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

private const val CHECK_MS = 10_000L
private const val FADE_MS = 1_400
private const val DRIFT_MS = 24_000L
private const val SHIFT_MS = 900
