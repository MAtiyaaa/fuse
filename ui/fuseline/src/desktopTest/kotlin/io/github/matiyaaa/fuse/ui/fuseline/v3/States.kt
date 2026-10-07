package io.github.matiyaaa.fuse.ui.fuseline.v3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A value that follows [targetValue]: whenever the target changes it moves there under
 * [animationSpec], taking over from the move under way. The first composition starts at the
 * target. Read it with `by`, like any state; [finishedListener] hears each arrival.
 */
@Composable
fun <T> fuselineValueAsState(
    targetValue: T,
    converter: Converter<T>,
    animationSpec: Motion = Spring(),
    threshold: Float = converter.threshold,
    label: String = "FuselineValue",
    finishedListener: ((T) -> Unit)? = null,
): State<T> = rememberFollowing(targetValue, converter, animationSpec, threshold, label, finishedListener).asState()

/**
 * The [FuselineValue] behind [fuselineValueAsState]: it follows [targetValue], and a motion under way
 * takes each new target in place ([FuselineValue.retarget]), carrying on from where it is and how fast
 * it is going, whatever kind of motion it is.
 */
@Composable
fun <T> rememberFollowing(
    targetValue: T,
    converter: Converter<T>,
    animationSpec: Motion = Spring(),
    threshold: Float = converter.threshold,
    label: String = "FuselineValue",
    finishedListener: ((T) -> Unit)? = null,
): FuselineValue<T> {
    val value = remember { FuselineValue(targetValue, converter, threshold, label) }
    val spec by rememberUpdatedState(animationSpec)
    val listener by rememberUpdatedState(finishedListener)
    // Only the newest target matters: targets set faster than frames are skipped.
    val targets = remember { Channel<T>(Channel.CONFLATED) }
    SideEffect { targets.trySend(targetValue) }
    LaunchedEffect(targets) {
        for (target in targets) {
            val newest = targets.tryReceive().getOrNull() ?: target
            // A motion under way takes the new target in place: no new move, no jolt.
            val motion = spec
            if (newest != value.targetValue && value.retarget(newest, motion)) continue
            launch {
                if (newest != value.targetValue) {
                    value.animateTo(newest, spec)
                    listener?.invoke(value.value)
                }
            }
        }
    }
    return value
}

/** A float that follows [targetValue] (see [fuselineValueAsState]), read without boxing. */
@Composable
fun fuselineFloat(
    targetValue: Float,
    animationSpec: Motion = Spring(threshold = 0.01f),
    visibilityThreshold: Float = 0.01f,
    label: String = "FuselineFloat",
    finishedListener: ((Float) -> Unit)? = null,
): FloatState = rememberFollowing(targetValue, FloatConverter, animationSpec, visibilityThreshold, label, finishedListener).asFloatState()

/** A colour that follows [targetValue], blending through Oklab (see [ColorConverter]). */
@Composable
fun fuselineColor(
    targetValue: Color,
    animationSpec: Motion = Spring(),
    label: String = "FuselineColor",
    finishedListener: ((Color) -> Unit)? = null,
): State<Color> = fuselineValueAsState(targetValue, ColorConverter, animationSpec, ColorConverter.threshold, label, finishedListener)

/** A length that follows [targetValue]. */
@Composable
fun fuselineDp(
    targetValue: Dp,
    animationSpec: Motion = Spring(),
    label: String = "FuselineDp",
    finishedListener: ((Dp) -> Unit)? = null,
): State<Dp> = fuselineValueAsState(targetValue, DpConverter, animationSpec, DpConverter.threshold, label, finishedListener)

/** A whole number that follows [targetValue], counting through the numbers between. */
@Composable
fun fuselineInt(
    targetValue: Int,
    animationSpec: Motion = Spring(),
    label: String = "FuselineInt",
    finishedListener: ((Int) -> Unit)? = null,
): State<Int> = fuselineValueAsState(targetValue, IntConverter, animationSpec, IntConverter.threshold, label, finishedListener)

/** A position that follows [targetValue]. */
@Composable
fun fuselineOffset(
    targetValue: Offset,
    animationSpec: Motion = Spring(),
    label: String = "FuselineOffset",
): State<Offset> = fuselineValueAsState(targetValue, OffsetConverter, animationSpec, OffsetConverter.threshold, label)

/** A whole-pixel position that follows [targetValue]. */
@Composable
fun fuselineIntOffset(
    targetValue: IntOffset,
    animationSpec: Motion = Spring(),
    label: String = "FuselineIntOffset",
): State<IntOffset> = fuselineValueAsState(targetValue, IntOffsetConverter, animationSpec, IntOffsetConverter.threshold, label)

/** A whole-pixel size that follows [targetValue]. */
@Composable
fun fuselineIntSize(
    targetValue: IntSize,
    animationSpec: Motion = Spring(),
    label: String = "FuselineIntSize",
): State<IntSize> = fuselineValueAsState(targetValue, IntSizeConverter, animationSpec, IntSizeConverter.threshold, label)

/** A remembered [FuselineValue] starting at [initialValue]. */
@Composable
fun rememberFuselineValue(initialValue: Float): FuselineValue<Float> = remember { FuselineValue(initialValue) }
