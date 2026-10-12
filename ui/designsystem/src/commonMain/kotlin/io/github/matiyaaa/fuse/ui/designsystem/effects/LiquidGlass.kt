package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * What floating glass shows through: the screen without its menus, sheets, dialogs and notices,
 * recorded as it is drawn. It is recorded only while some glass shows it ([readers]); otherwise the
 * screen draws exactly as before. Floating things ([glassFloating]) are left out of the recording,
 * so glass never shows itself.
 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    /** Where the recorded screen sits in the window. */
    internal var origin: Offset = Offset.Zero

    /** Moves on when the recording is new enough to show, so glass over it draws again. */
    internal val frames = mutableIntStateOf(0)

    /** How many glass panels show the screen now. */
    internal var readers by mutableIntStateOf(0)

    /** Whether the layer holds the screen as it last drew. */
    internal var recorded = false

    /** True while the screen is being recorded: floating things draw nothing then. */
    internal var recording = false

    /** When [frames] last moved on. */
    internal var shownAt: TimeSource.Monotonic.ValueTimeMark? = null
}

/** The screen floating glass shows through; null where there is none (a second screen's own window). */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** True inside something floating over the screen ([glassFloating]): its panels may be liquid glass. */
val LocalGlassFloating = staticCompositionLocalOf { false }

/**
 * Marks something floating over the screen (a menu and its scrim, a sheet, a notice): it is left out
 * of what glass shows, and the panels in it are liquid glass. Inside, provide [LocalGlassFloating].
 */
fun Modifier.glassFloating(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this else drawWithContent { if (!backdrop.recording) drawContent() }

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Marks the screen glass floats over. While glass shows it, its content is recorded first without
 * the floating things, then drawn as usual. A moving screen (the room's light, a preview) reaches the
 * glass at most every [REFRESH_MS], so the glass costs a little and never redraws in a loop.
 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInWindow() }
    .drawWithContent {
        if (backdrop.readers == 0) {
            backdrop.recorded = false
            backdrop.shownAt = null
        } else {
            val last = backdrop.shownAt
            if (!backdrop.recorded || last == null || last.elapsedNow().inWholeMilliseconds >= REFRESH_MS) {
                backdrop.recording = true
                try {
                    backdrop.layer.record { this@drawWithContent.drawContent() }
                } finally {
                    backdrop.recording = false
                }
                backdrop.recorded = true
                backdrop.shownAt = TimeSource.Monotonic.markNow()
                backdrop.frames.intValue++
            }
        }
        drawContent()
    }

/** Whether glass here floats over a recorded screen, and this device can blur it smoothly. */
@Composable
fun liquidGlassOn(glass: GlassSettings = Fuse.look.glass): Boolean =
    glass.enabled && LocalGlassFloating.current && LocalGlassBackdrop.current != null &&
        Drawing.backdropBlur && !Drawing.cpu && Fuse.quality.blur

/**
 * Where a glass surface is in the window, and where it was when the screen behind it was last
 * frosted for it: a move only shifts that frosting to line up, it is not made again.
 */
private class GlassPlace {
    var position: Offset = Offset.Zero
    val moves = mutableIntStateOf(0)
    var frostedFrame = -1
    var frostedAt: Offset = Offset.Zero
    var frostedSize: Size = Size.Zero
}

/**
 * Liquid glass, as a surface of [shape] floating over the screen: the screen behind it frosted (blurred
 * by the theme's glass blur and a little more saturated, as light through glass is), a rim along the
 * edge where the screen bends as it would through the edge of a thick pane (seen a little magnified
 * and sharper), and a specular line of light around the edge, brightest at the top left where the
 * light comes from. The panel's own tint goes on top. Off (and drawing nothing) wherever
 * [liquidGlassOn] is false; the caller clips to [shape].
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, glass: GlassSettings = Fuse.look.glass): Modifier {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || !liquidGlassOn(glass)) return this
    DisposableEffect(backdrop) {
        backdrop.readers++
        onDispose { backdrop.readers-- }
    }
    val dark = Fuse.colors.isDark
    val body = rememberGraphicsLayer()
    val rim = rememberGraphicsLayer()
    val place = remember { GlassPlace() }
    return this
        .onGloballyPositioned {
            val now = it.positionInWindow()
            if (now != place.position) {
                place.position = now
                place.moves.intValue++
            }
        }
        .drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val band = RIM.toPx().coerceAtMost(minOf(size.width, size.height) / 4f)
            val inner = Path().apply {
                addOutline(shape.createOutline(Size(size.width - band * 2, size.height - band * 2), layoutDirection, this@drawWithCache))
                translate(Offset(band, band))
            }
            val ring = Path.combine(PathOperation.Difference, path, inner)
            val blur = glass.blur.dp.toPx().coerceAtLeast(1f)
            val saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(SATURATION) })
            // Frosted small and drawn large: under a blur this heavy a quarter of the pixels look the
            // same, at a sixteenth of the work. The sharper rim keeps half.
            body.renderEffect = BlurEffect(blur / BODY_SCALE, blur / BODY_SCALE, TileMode.Clamp)
            body.colorFilter = saturate
            rim.renderEffect = BlurEffect(blur * RIM_BLUR / RIM_SCALE, blur * RIM_BLUR / RIM_SCALE, TileMode.Clamp)
            rim.colorFilter = saturate
            val light = Brush.linearGradient(
                0f to Color.White.copy(alpha = if (dark) 0.55f else 0.85f),
                0.3f to Color.White.copy(alpha = if (dark) 0.12f else 0.3f),
                0.6f to Color.White.copy(alpha = 0f),
                1f to Color.White.copy(alpha = if (dark) 0.22f else 0.4f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            val stroke = Stroke(SPECULAR.toPx())
            // The frosting covers a margin around the glass, so the blur has the screen to draw from
            // at its edges and a slide never shows past the frosted part.
            val margin = MARGIN.toPx()
            fun frostedSize(scale: Float) = IntSize(
                ((size.width + margin * 2) / scale).roundToInt().coerceAtLeast(1),
                ((size.height + margin * 2) / scale).roundToInt().coerceAtLeast(1),
            )
            val bodySize = frostedSize(BODY_SCALE)
            val rimSize = frostedSize(RIM_SCALE)
            val centre = Offset(margin + size.width / 2f, margin + size.height / 2f)
            onDrawBehind {
                // Read so this glass draws again whenever the screen behind it, or it, moves.
                val frame = backdrop.frames.intValue
                place.moves.intValue
                if (!backdrop.recorded) return@onDrawBehind
                val at = place.position - backdrop.origin
                if (frame != place.frostedFrame || place.frostedSize != size) {
                    body.record(bodySize) {
                        scale(1f / BODY_SCALE, pivot = Offset.Zero) { translate(margin - at.x, margin - at.y) { drawLayer(backdrop.layer) } }
                    }
                    rim.record(rimSize) {
                        scale(1f / RIM_SCALE, pivot = Offset.Zero) {
                            scale(LENS, pivot = centre) { translate(margin - at.x, margin - at.y) { drawLayer(backdrop.layer) } }
                        }
                    }
                    place.frostedFrame = frame
                    place.frostedAt = at
                    place.frostedSize = size
                }
                val shift = place.frostedAt - at
                clipPath(path) {
                    translate(shift.x - margin, shift.y - margin) { scale(BODY_SCALE, pivot = Offset.Zero) { drawLayer(body) } }
                }
                clipPath(ring) {
                    translate(shift.x - margin, shift.y - margin) { scale(RIM_SCALE, pivot = Offset.Zero) { drawLayer(rim) } }
                }
                drawPath(path, light, style = stroke)
            }
        }
}

/** The most often the screen behind glass is recorded again (and the glass frosted again). */
private const val REFRESH_MS = 90L

/** How much smaller the frosting is made than it is drawn. */
private const val BODY_SCALE = 4f

/** The same for the rim, which is sharper. */
private const val RIM_SCALE = 2f

/** How far past its edges the screen behind glass is frosted. */
private val MARGIN = 48.dp

/** The band along the edge where the screen bends. */
private val RIM = 14.dp

/** How much the rim magnifies what is behind it. */
private const val LENS = 1.07f

/** The rim's blur against the body's: sharper, as the thick edge of a pane is. */
private const val RIM_BLUR = 0.4f

/** Light through glass: colours a little richer than the screen behind. */
private const val SATURATION = 1.35f

/** The line of light around the edge. */
private val SPECULAR = 1.5.dp
