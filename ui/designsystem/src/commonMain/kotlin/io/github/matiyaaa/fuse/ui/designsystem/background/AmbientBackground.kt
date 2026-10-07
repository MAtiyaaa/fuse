package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawGrain
import io.github.matiyaaa.fuse.model.AmbientSpec
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Theme backgrounds drawn in code (no image assets), all original. When animation is allowed they
 * move slowly and are throttled to [fps] so an idle home screen costs almost nothing; otherwise they
 * are drawn once, at their first frame, and never invalidate. [ambient] sets how bright and how fast
 * each one is, and the second colour some of them blend with the accent.
 *
 * Each style is a [Scene] that builds its paths, brushes and seeded particles once per size and
 * then only moves them, so a frame allocates nothing.
 */
@Composable
fun AmbientBackground(
    style: BackgroundStyle,
    accent: Color,
    modifier: Modifier = Modifier,
    ambient: AmbientSpec = AmbientSpec(),
    animate: Boolean = Fuse.motion.ambient && Fuse.quality.animatedBackground,
    fps: Int = 30,
) {
    val colors = Fuse.colors
    var time by remember { mutableFloatStateOf(0f) }
    val k = ambient.intensity.coerceIn(0f, 1.5f)
    val fusi = LocalFusiScreen.current
    val scene = remember(style, accent, ambient.secondary, colors, k, fusi) {
        sceneFor(style, SceneLook(accent, ambient.secondary?.let { Color(it) }, colors, k), fusi)
    }
    val moving = animate && style.moves && ambient.speed > 0f && k > 0f
    // A room at rest is drawn once into one picture and shown as that picture every frame.
    val flat = remember { io.github.matiyaaa.fuse.ui.designsystem.effects.FlatLayer() }
    if (moving) {
        // Slow scenes ask for fewer frames than the caller allows; nothing moves faster than it needs.
        val rate = minOf(fps, scene.fps).coerceAtLeast(1)
        LaunchedEffect(rate, ambient.speed) {
            val frame = 1000L / rate
            var last = 0L
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { now ->
                    if (now - last >= frame) {
                        time = (now - start) / 1000f * ambient.speed
                        last = now
                    }
                }
            }
        }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        if (moving) {
            drawRect(colors.ink)
            scene.draw(this, time)
            // Scenes are mostly soft light on a dark room: grain keeps them from banding.
            drawGrain()
        } else {
            flat.draw(this, scene) {
                drawRect(colors.ink)
                scene.draw(this, time)
                drawGrain()
            }
        }
    }
}

private fun sceneFor(style: BackgroundStyle, look: SceneLook, fusi: FusiScreen): Scene = when (style) {
    BackgroundStyle.HERO, BackgroundStyle.SOLID -> RoomScene(look)
    BackgroundStyle.WAVE -> WaveScene(look)
    BackgroundStyle.AURORA -> AuroraScene(look)
    BackgroundStyle.ORBITAL -> OrbitalScene(look)
    BackgroundStyle.GRID -> GridScene(look)
    BackgroundStyle.STRIPES -> StripesScene(look)
    BackgroundStyle.STARS -> StarsScene(look)
    BackgroundStyle.PETALS -> PetalsScene(look)
    BackgroundStyle.HORIZON -> HorizonScene(look)
    BackgroundStyle.FIREFLIES -> FirefliesScene(look)
    BackgroundStyle.CAUSTICS -> CausticsScene(look)
    BackgroundStyle.LCD -> LcdScene(look)
    BackgroundStyle.MESH -> MeshScene(look)
    BackgroundStyle.CONTOURS -> ContoursScene(look)
    BackgroundStyle.DUNES -> DunesScene(look)
    BackgroundStyle.FUSI -> FusiScene(look, fusi)
}

/**
 * What a scene is lit with: [accent] (the theme's, or the focused game's), the theme's [second]
 * colour if it has one, its roles and the intensity [k] (0 to 1.5).
 */
internal class SceneLook(val accent: Color, val second: Color?, val colors: FuseColors, val k: Float) {
    val ink: Color get() = colors.ink
    val text: Color get() = colors.text
    val dark: Boolean get() = colors.isDark
}

/**
 * One background. [build] runs when the size changes and makes everything that depends on it;
 * [paint] runs every frame at time `t` (seconds scaled by the theme's speed) and must not allocate.
 * At `t = 0` a scene shows its resting picture, which is all a still background ever shows.
 */
internal abstract class Scene(protected val look: SceneLook) {
    private var built = Size.Zero

    fun draw(scope: DrawScope, t: Float) {
        with(scope) {
            if (size.width <= 0f || size.height <= 0f) return
            if (size != built) {
                built = size
                build()
            }
            paint(t)
        }
    }

    /** The most frames a second this scene needs to look smooth; slow scenes ask for fewer. */
    open val fps: Int = 30

    protected open fun DrawScope.build() {}

    protected abstract fun DrawScope.paint(t: Float)

    protected val k: Float get() = look.k

    /**
     * How large marks drawn in dp (petals, fireflies, cells) should be on this canvas: 1 on a
     * screen, down to 0.45 on a small preview card, so a gallery card looks like the screen, only
     * smaller.
     */
    protected val DrawScope.fit: Float get() = (size.minDimension / (480f * density)).coerceIn(0.45f, 1f)
}

/**
 * A soft round light that falls off like a gaussian (no visible rim, less banding than a straight
 * ramp). The brush is made once around the origin and placed, sized and stretched with a transform,
 * so drawing one costs no allocation.
 */
internal class Glow(color: Color) {
    val brush: Brush = Brush.radialGradient(
        colorStops = *Array(FALLOFF.size) { i -> FALLOFF[i].first to color.copy(alpha = color.alpha * FALLOFF[i].second) },
        center = Offset.Zero,
        radius = UNIT,
    )

    /** Draws the light centred on ([x], [y]) with radii [rx] and [ry], turned by [degrees]. */
    fun draw(scope: DrawScope, x: Float, y: Float, rx: Float, ry: Float = rx, alpha: Float = 1f, degrees: Float = 0f) {
        if (alpha <= 0.002f || rx <= 0f || ry <= 0f) return
        scope.withTransform({
            translate(x, y)
            if (degrees != 0f) rotate(degrees, Offset.Zero)
            scale(rx / UNIT, ry / UNIT, Offset.Zero)
        }) {
            drawCircle(brush, radius = UNIT, center = Offset.Zero, alpha = alpha.coerceAtMost(1f))
        }
    }

    companion object {
        const val UNIT = 64f

        /** exp(-4.5 t²), shifted to reach zero at the rim. */
        private val FALLOFF: List<Pair<Float, Float>> = List(9) { i ->
            val t = i / 8f
            val e = exp(-4.5f)
            t to ((exp(-4.5f * t * t) - e) / (1f - e)).coerceAtLeast(0f)
        }
    }
}

/** Deterministic pseudo-random numbers (xorshift), so a scene looks the same on every device and every run. */
internal class Seeded(seed: Int) {
    private var s = if (seed == 0) 0x2F6E2B1 else seed

    /** 0 until 1. */
    fun next(): Float {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return (s ushr 8) / 16_777_216f
    }

    fun between(a: Float, b: Float): Float = a + (b - a) * next()
}

internal fun smooth(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** [v] wrapped into 0 until [span]. */
internal fun wrap(v: Float, span: Float): Float = ((v % span) + span) % span

/**
 * How much light a point may carry, 0.2 to 1: low over the title area at the top left and along
 * the HUD line at the top, full elsewhere. Scenes scale their brightest marks by it so text over
 * any background reads.
 */
internal fun DrawScope.calm(x: Float, y: Float): Float {
    val u = x / size.width
    val v = y / size.height
    val d = sqrt((u / 0.5f) * (u / 0.5f) + (v / 0.45f) * (v / 0.45f))
    val corner = 0.2f + 0.8f * smooth(0.55f, 1.3f, d)
    val hud = 0.4f + 0.6f * smooth(0.03f, 0.16f, v)
    return min(corner, hud)
}
