package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawDitheredRect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawGrain
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.ambientOn
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Enter
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.fuseline.withInfiniteFrameMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the backdrop should show for the focused item. */
data class HeroSource(
    /** Identity of the focused item; changing it is what triggers a transition. */
    val id: Any,
    /** Hero image (path or URL), or null for an art-less item (a lit gradient is used). */
    val model: Any?,
    /** Colour that lights the room: platform or art colour. */
    val accent: Color,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.35f,
    /** Muted gameplay clip that may replace the art after the selection rests (path or URL). */
    val video: String? = null,
    /**
     * Art that isn't a background (box art or a cover): drawn blurred and darker so it reads as a
     * room, not as a picture.
     */
    val blurred: Boolean = false,
    /** Shown while [model] is still loading (usually the system's background, already in memory). */
    val placeholder: Any? = null,
)

/**
 * The size backgrounds are decoded at: they sit dimmed behind the interface, so a little under the
 * screen's, but not so far under that a big TV shows them soft.
 */
val RenderQuality.heroDecodePx: Int get() = (heroMaxPx * HERO_DECODE_SHARE).roundToInt()

private const val HERO_DECODE_SHARE = 0.85f

/** Art narrower than this share of the screen is softened rather than stretched sharp (see HeroLayerView). */
private const val SOFT_BELOW_SHARE = 0.4f

/** How long a new background may take before the room shows its placeholder instead of the old one. */
private const val PLACEHOLDER_MS = 120L

/** Frames a new picture is drawn at a pixel's size before its fade, so its upload is done by then. */
private const val PRIME_FRAMES = 2

/** Enough for the picture to be drawn (and so sent to the graphics card), too little to be seen. */
private const val PRIME_ALPHA = 0.01f

/** The Enhanced drift: how far the art zooms and pans (of its size) at the end of a leg. */
private const val DRIFT_ZOOM = 0.045f
private const val DRIFT_PAN = 0.012f

/** The drift moves about a pixel a second, so 30 updates a second are more than enough. */
private const val DRIFT_FRAME_MS = 33L

private class HeroLayer(val source: HeroSource) {
    val alpha = FuselineValue(0f)
    var ready = false
    /** Fading out because the screen has no background. */
    var leaving = false
}

/**
 * The room the interface lives in. The focused item's hero art fills the screen behind everything,
 * softened by scrims so text stays readable.
 *
 * Transitions never flash and never show the wrong art: new art crossfades in over the old once it
 * has decoded, settling from a slight zoom, and art that takes longer than a moment first shows a
 * placeholder (the system's background, or a lit room in the item's colour) so the previous item's
 * art never lingers. A screen without a background ([source] null) fades the room back to the
 * theme's own. While the user is moving quickly the backdrop waits for the selection to rest
 * ([settleMs]) so fast scrolling never queues dozens of decodes.
 *
 * In Enhanced motion the art that is resting drifts and zooms very slowly, like a camera breathing
 * (never in Low Power Mode, and only about 30 times a second, since it moves a pixel or so a
 * second).
 *
 * The scrims are eased curves rather than straight ramps, so they never show a band: the left one
 * keeps the stage title readable over any art, the bottom one carries the tiles and hint line, and
 * a soft top one keeps the top line clear.
 */
@OptIn(FlowPreview::class)
@Composable
fun HeroBackdrop(
    source: HeroSource?,
    modifier: Modifier = Modifier,
    /** 0..1 darkness laid over the art. */
    dim: Float = 0.35f,
    /** 0..1 strength of the legibility gradients (left and bottom). */
    gradient: Float = 0.9f,
    brightness: Float = 1f,
    settleMs: Long = 110,
    /** Vertical parallax offset in px (content scroll); the art moves a fraction of it. */
    parallax: () -> Float = { 0f },
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val colors = Fuse.colors
    val motion = Fuse.motion
    val layers = remember { mutableStateListOf<HeroLayer>() }
    val latest by rememberUpdatedState(source)

    LaunchedEffect(Unit) {
        snapshotFlow { latest }
            .distinctUntilChanged { a, b -> a?.id == b?.id && a?.model == b?.model && a?.blurred == b?.blurred }
            .debounce { if (layers.none { !it.leaving }) 0L else settleMs }
            .collect { next ->
                if (next == null) {
                    // No background here: the room fades back to the theme's own.
                    val fading = layers.filterNot { it.leaving }
                    fading.forEach { it.leaving = true }
                    launch {
                        coroutineScope { fading.forEach { l -> launch { l.alpha.animateTo(0f, motion.tween(Durations.BASE, Curves.Fade)) } } }
                        layers.removeAll(fading)
                    }
                    return@collect
                }
                // Another item with the same art (games on their system's background) keeps the layer.
                val top = layers.lastOrNull { !it.leaving }
                if (top?.source?.let { it.model == next.model && it.blurred == next.blurred && (it.id == next.id || next.model != null) } == true) return@collect
                // Drop layers that never finished loading: they belong to items already left behind.
                layers.removeAll { !it.ready && !it.leaving }
                layers.add(HeroLayer(next))
                if (layers.size > 3) layers.removeAt(0)
            }
    }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (motion.parallax) translationY = -parallax() * 0.12f
                },
        ) {
            for (layer in layers) {
                key(layer) {
                    HeroLayerView(
                        layer = layer,
                        brightness = brightness,
                        onReady = {
                            layer.ready = true
                        },
                        onShown = {
                            // Everything under a fully shown layer is invisible: release it.
                            val index = layers.indexOf(layer)
                            if (index > 0) repeat(index) { layers.removeAt(0) }
                        },
                    )
                }
            }
            overlay()
        }
        val accent = layers.lastOrNull { !it.leaving }?.source?.accent ?: colors.accent
        val ink = colors.ink
        // The dim and the three scrims never change with the art: drawn once into one picture.
        val flat = io.github.matiyaaa.fuse.ui.designsystem.effects.rememberFlatLayer()
        val dark = colors.isDark
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val left = Brush.horizontalGradient(*scrim(ink, 0.94f * gradient, LEFT_SCRIM))
                val top = Brush.verticalGradient(*scrim(ink, 0.6f * gradient, TOP_SCRIM))
                val bottom = Brush.verticalGradient(*scrim(ink, 0.97f * gradient, BOTTOM_SCRIM))
                val key = listOf(ink, dark, dim, gradient)
                // The room is lit by the game: a low, wide glow in its colour.
                val glow = Brush.radialGradient(
                    0f to lerp(accent, Color.Transparent, 0.35f).copy(alpha = 0.28f),
                    0.55f to lerp(accent, Color.Transparent, 0.35f).copy(alpha = 0.09f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.12f, size.height * 1.05f),
                    radius = size.maxDimension * 0.7f,
                )
                onDrawBehind {
                    flat.draw(this, key) {
                        // A dark room dims the art; a bright one washes it toward its paper, so dark text
                        // reads on it instead of on a grey haze.
                        drawDitheredRect(if (dark) Color.Black.copy(alpha = dim) else ink.copy(alpha = (dim + LIGHT_WASH).coerceAtMost(0.9f)))
                        drawDitheredRect(left)
                        drawDitheredRect(top)
                        drawDitheredRect(bottom)
                    }
                    drawDitheredRect(glow)
                }
            },
        )
    }
}

/**
 * Scrim curves as (position, strength) pairs: strong at the edge, easing out over a long tail.
 * Left: the stage title sits in the first 45%. Top: under the top line. Bottom: tiles and hints.
 */
/** How much more a bright room washes the art than a dark one dims it. */
private const val LIGHT_WASH = 0.3f

private val LEFT_SCRIM = floatArrayOf(0f, 1f, 0.12f, 0.92f, 0.24f, 0.77f, 0.36f, 0.59f, 0.48f, 0.37f, 0.6f, 0.18f, 0.7f, 0.07f, 0.8f, 0f)
private val TOP_SCRIM = floatArrayOf(0f, 1f, 0.06f, 0.72f, 0.12f, 0.4f, 0.18f, 0.16f, 0.24f, 0f)
private val BOTTOM_SCRIM = floatArrayOf(0.48f, 0f, 0.58f, 0.1f, 0.68f, 0.3f, 0.78f, 0.56f, 0.88f, 0.8f, 1f, 1f)

private fun scrim(color: Color, strength: Float, curve: FloatArray): Array<Pair<Float, Color>> =
    Array(curve.size / 2) { i -> curve[i * 2] to color.copy(alpha = strength * curve[i * 2 + 1]) }

/**
 * The room of an item without art, and the placeholder while art loads: a key light in [accent]
 * high on the right, a softer bounce from the lower left in a cooler shade, and the room between.
 * In a dark theme the room is night; in a light one it is the theme's own paper, lit in the
 * accent, so an art-less game never drops a dark slab into a bright interface.
 */
@Composable
private fun LitRoom(accent: Color, modifier: Modifier = Modifier) {
    val colors = Fuse.colors
    val dark = colors.isDark
    val room = if (dark) Color.Black else colors.ink
    Box(
        modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val night = Brush.verticalGradient(listOf(lerp(room, accent, if (dark) 0.16f else 0.1f), room))
            val key = Brush.radialGradient(
                0f to lerp(room, accent, if (dark) 0.62f else 0.38f),
                0.45f to lerp(room, accent, if (dark) 0.26f else 0.16f).copy(alpha = 0.7f),
                1f to Color.Transparent,
                center = Offset(w * 0.72f, h * 0.28f),
                radius = size.maxDimension * 0.75f,
            )
            val bounce = Brush.radialGradient(
                0f to lerp(accent, Color(0xFF3A4C8C), 0.45f).copy(alpha = if (dark) 0.3f else 0.16f),
                1f to Color.Transparent,
                center = Offset(w * 0.1f, h * 1.1f),
                radius = size.maxDimension * 0.6f,
            )
            onDrawBehind {
                drawDitheredRect(night)
                drawDitheredRect(key)
                drawDitheredRect(bounce)
                drawGrain()
            }
        },
    )
}

@Composable
private fun HeroLayerView(layer: HeroLayer, brightness: Float, onReady: () -> Unit, onShown: () -> Unit) {
    // What the art is shaded with: black in a dark room, the room's own paper in a bright one.
    val veil = Fuse.colors.let { if (it.isDark) Color.Black else it.ink }
    val motion = Fuse.motion
    val source = layer.source
    val settle = remember { FuselineValue(1.04f) }
    if (source.model == null) {
        LaunchedEffect(layer) {
            onReady()
            layer.alpha.animateTo(1f, motion.tween(Durations.HERO, Curves.Fade))
            onShown()
        }
        LitRoom(source.accent, Modifier.graphicsLayer { alpha = layer.alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha })
        return
    }
    val context = LocalPlatformContext.current
    // Decoded a little under the screen's size and no larger than the performance profile allows.
    val px = Fuse.quality.heroDecodePx
    val canBlur = Fuse.quality.blur
    val request = remember(source.model, context, px) { heroRequest(context, source.model, px) }
    val loader = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
    val state by loader.state.collectAsStateCompat()
    // A room shown before is drawn at once from the picture held for it ([ShownArt]), even when the
    // system emptied the image cache while Fuse was in the background, so it never pops in.
    val loaded = state as? AsyncImagePainter.State.Success
    val held = if (loaded == null) ShownArt.pinned(source.model) else null
    if (loaded != null) ShownArt.shown(source.model, loaded.painter)
    val painter = held ?: loader
    val imageAlpha = remember { FuselineValue(0f) }
    // What the picture's layers read: copied from [imageAlpha] on every frame it moves. A layer that
    // first read the picture while it was hidden is told of each change this way; reading the moving
    // value itself, it could miss a change made while nothing drew it, and keep the picture hidden
    // over the placeholder for good (art that took longer than [PLACEHOLDER_MS] never appeared).
    var shownImage by remember { mutableFloatStateOf(0f) }
    var placeholder by remember { mutableStateOf(false) }
    // A blurred room exists only once baked: until then the layer has nothing to show.
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    // Drawn for a frame or two at a single pixel before the fade, so the picture is already on the
    // graphics card when the fade starts: its upload never lands in a frame of the fade.
    var priming by remember { mutableStateOf(false) }
    var boxWidth by remember { mutableStateOf(0) }
    val canBlurNow by rememberUpdatedState(canBlur)
    fun isSoft(): Boolean {
        val shown = held ?: (state as? AsyncImagePainter.State.Success)?.painter ?: return false
        val s = shown.intrinsicSize
        return !source.blurred && boxWidth > 0 && !s.isUnspecified && s.width > 0f && s.width < boxWidth * SOFT_BELOW_SHARE
    }
    LaunchedEffect(layer) {
        val decoded = { held != null || state is AsyncImagePainter.State.Success }
        val finished = {
            state is AsyncImagePainter.State.Error ||
                decoded() && (!((source.blurred || isSoft()) && canBlurNow) || frozen != null)
        }
        val quick = withTimeoutOrNull(PLACEHOLDER_MS) { snapshotFlow { finished() }.first { it } } != null
        if (quick) {
            // Shown at once under the room's own fade.
            imageAlpha.snapTo(1f)
            shownImage = 1f
            priming = true
            repeat(PRIME_FRAMES) { androidx.compose.runtime.withFrameNanos { } }
            priming = false
            onReady()
            if (!motion.reduced) {
                coroutineScope {
                    launch { settle.animateTo(1f, motion.tween(Durations.DELIBERATE + 300, Curves.Enter)) }
                    layer.alpha.animateTo(1f, motion.tween(Durations.HERO + 80, Curves.Fade))
                }
            } else {
                layer.alpha.animateTo(1f, motion.tween(Durations.FAST, Curves.Fade))
            }
            onShown()
        } else {
            // Slow art: the placeholder replaces the old room now, and the art fades in over it.
            placeholder = true
            settle.snapTo(1f)
            onReady()
            layer.alpha.animateTo(1f, motion.tween(Durations.BASE, Curves.Fade))
            onShown()
            snapshotFlow { finished() }.first { it }
            priming = true
            repeat(PRIME_FRAMES) { androidx.compose.runtime.withFrameNanos { } }
            priming = false
            imageAlpha.animateTo(1f, motion.tween(Durations.HERO, Curves.Fade)) { shownImage = value }
            shownImage = 1f
        }
    }
    val failed = state is AsyncImagePainter.State.Error
    // Art far smaller than the screen (an emulator's snapshot, wide art made for a list) would be
    // stretched into blocks across a TV: it is shown as a softened room instead, like box art.
    val soft = isSoft()
    // Enhanced motion: once shown, the art breathes, drifting and zooming very slowly.
    var drift by remember { mutableFloatStateOf(0f) }
    val drifting = motion.drift && motion.ambientOn(Fuse.quality)
    if (drifting) {
        LaunchedEffect(layer) {
            snapshotFlow { layer.alpha.value >= 1f }.first { it }
            // Woken only when an update is due, and held still while the person is doing something.
            io.github.matiyaaa.fuse.ui.fuseline.decorationFrames((1000L / DRIFT_FRAME_MS).toInt()) { played ->
                // 0 to 1 and back, eased at both ends, one leg per Durations.DRIFT.
                val t = ((played / 1_000_000L) % (Durations.DRIFT * 2L)).toFloat() / Durations.DRIFT
                drift = (1f - cos(PI.toFloat() * t)) / 2f
            }
        }
    }
    val angle = remember(source.id) { (source.id.hashCode() and 0xFFFF) / 65_535f * 2f * PI.toFloat() }
    if (priming) {
        // Outside the room's layer (at no opacity a layer isn't drawn at all), a pixel's worth.
        // The picture itself (Coil's decoded one), not the loader around it, which keeps track of
        // the size it is drawn at.
        val primeWith = frozen
        val primeArt = held ?: (state as? AsyncImagePainter.State.Success)?.painter
        Box(
            Modifier.size(1.dp).drawBehind {
                when {
                    primeWith != null -> drawImage(primeWith, dstSize = IntSize(1, 1), alpha = PRIME_ALPHA)
                    primeArt != null -> with(primeArt) { draw(Size(1f, 1f), alpha = PRIME_ALPHA) }
                }
            },
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxWidth = it.width }
            .graphicsLayer {
                alpha = layer.alpha.value
                // Fading rooms are drawn straight onto the one below, never through a screen-sized
                // buffer of their own: a crossfade then costs no more than the rooms themselves.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                val d = if (drifting) drift else 0f
                val zoom = settle.value * (1f + DRIFT_ZOOM * d)
                scaleX = zoom
                scaleY = zoom
                if (d > 0f) {
                    translationX = cos(angle) * size.width * DRIFT_PAN * d
                    translationY = sin(angle) * size.height * DRIFT_PAN * d
                }
            }
            .drawWithContent {
                drawContent()
                if (brightness < 1f) drawDitheredRect(veil.copy(alpha = 1f - brightness))
            },
    ) {
        if (placeholder || failed) {
            LitRoom(source.accent)
            val ph = source.placeholder
            if (ph != null && ph != source.model && !failed) {
                val phRequest = remember(ph, context, px) { heroRequest(context, ph, px) }
                Box(Modifier.fillMaxSize().paint(rememberAsyncImagePainter(phRequest, contentScale = ContentScale.Crop), contentScale = ContentScale.Crop))
            }
        }
        if (!failed) {
            val alignment = focusAlignment(source.focusX, source.focusY)
            // The picture's own fade is applied as it is drawn, not by a layer of its own: a layer
            // first drawn at no opacity could keep showing nothing as its opacity came up, and the
            // art never appeared over the placeholder.
            val shade = Modifier.drawWithContent {
                drawContent()
                // Box art behind the interface is a colour field, not a picture: darker, and more so unblurred.
                if (source.blurred || soft) drawDitheredRect(veil.copy(alpha = (if (canBlur) 0.35f else 0.6f) * shownImage))
            }
            if ((source.blurred || soft) && canBlur) {
                // Blurred once, small, then shown scaled up: the same picture for a single draw a frame.
                FrozenBlur(painter, source.model, loaded = held != null || state is AsyncImagePainter.State.Success, alignment, alpha = { shownImage }, onFrozen = { frozen = it }, modifier = Modifier.fillMaxSize().then(shade))
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .paint(painter, contentScale = ContentScale.Crop, alignment = alignment, alpha = shownImage)
                        .then(shade),
                )
            }
        }
    }
}

/**
 * [painter] (once [loaded]) filling the box like [ContentScale.Crop], blurred by [BOX_ART_BLUR]. The
 * blur is worked out once per picture, on a copy a quarter of the box's size, and the result is
 * drawn scaled up with a cubic filter: a blurred picture has no detail for the scaling to lose, and the room then costs
 * one picture a frame instead of a blur over the whole screen every frame (which held every
 * animation in front of it to the blur's pace).
 *
 * Where the platform blurs in full precision ([Dithering.blurs], the desktop), the small copy is
 * drawn by the processor and everything after it runs off the interface's thread: nothing is read
 * back from the graphics card, which waited for all its work in hand. Each baked room is kept
 * ([FrozenRooms]), so going back to a game costs nothing. [onFrozen] gets the picture once it
 * exists: the room fades in only then, so it never fades in empty and pops.
 */
@Composable
private fun FrozenBlur(painter: Painter, model: Any?, loaded: Boolean, alignment: Alignment, alpha: () -> Float, onFrozen: (ImageBitmap) -> Unit, modifier: Modifier) {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    var frozen by remember(painter) { mutableStateOf<ImageBitmap?>(null) }
    var baked by remember(painter) { mutableStateOf(false) }
    val frozenNow by rememberUpdatedState(onFrozen)
    LaunchedEffect(painter, loaded, box) {
        if (!loaded || box.width <= 0 || box.height <= 0) return@LaunchedEffect
        val smooth = io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering.blurs
        val key = FrozenRooms.Key(model, box, alignment, smooth != null)
        FrozenRooms.get(key)?.let { kept ->
            frozen = kept
            baked = smooth != null
            frozenNow(kept)
            return@LaunchedEffect
        }
        val intrinsic = painter.intrinsicSize
        if (intrinsic.isUnspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) return@LaunchedEffect
        val small = IntSize((box.width / FROZEN_SCALE).coerceAtLeast(1), (box.height / FROZEN_SCALE).coerceAtLeast(1))
        val radius = with(density) { BOX_ART_BLUR.toPx() } / FROZEN_SCALE
        fun DrawScope.cover() {
            val scale = max(small.width / intrinsic.width, small.height / intrinsic.height)
            val drawn = Size(intrinsic.width * scale, intrinsic.height * scale)
            val at = alignment.align(IntSize(drawn.width.roundToInt(), drawn.height.roundToInt()), small, LayoutDirection.Ltr)
            translate(at.x.toFloat(), at.y.toFloat()) { with(painter) { draw(drawn) } }
        }
        suspend fun onCard(): ImageBitmap {
            layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            layer.record(density, LayoutDirection.Ltr, small) { cover() }
            return layer.toImageBitmap()
        }
        val made = if (smooth == null) {
            onCard()
        } else {
            // Blurred, stretched to the screen and rounded to 8 bits once, with signed noise as it
            // is rounded: every frame after is the same picture copied as it is. A blur kept in 8
            // bits at a quarter of the size and stretched every frame shows its steps as bands and
            // its grid as squares on a large screen, worst with the processor's cheaper filter.
            val copy = ImageBitmap(small.width, small.height)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(copy), Size(small.width.toFloat(), small.height.toFloat())) { cover() }
            withContext(Dispatchers.Default) { runCatching { smooth(copy, radius, bakeSize(box)) }.getOrNull() } ?: onCard()
        }
        baked = smooth != null && made.width != small.width
        frozen = made
        FrozenRooms.put(key, made)
        frozenNow(made)
    }
    Box(
        modifier
            .onSizeChanged { box = it }
            .drawBehind {
                val image = frozen ?: return@drawBehind
                // Baked at the box's size (or a little under on a very large screen) it is a plain copy.
                // Otherwise cubic, not bilinear: stretching a small picture bilinearly shows its grid
                // as faint squares on a large screen.
                val shown = alpha()
                if (shown <= 0f) return@drawBehind
                drawImage(
                    image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    alpha = shown,
                    filterQuality = when {
                        baked -> FilterQuality.Low
                        io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.cpu -> FilterQuality.Medium
                        else -> FilterQuality.High
                    },
                )
            },
    )
}

/**
 * The blurred rooms baked last, so moving back to a game shows its room at once. A few are enough:
 * at 4K each is about 33 MB.
 */
internal object FrozenRooms {
    data class Key(val model: Any?, val box: IntSize, val alignment: Alignment, val smooth: Boolean)

    private const val KEPT = 4
    private val rooms = object : LinkedHashMap<Key, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>?) = size > KEPT
    }

    fun get(key: Key): ImageBitmap? = if (key.model == null) null else rooms[key]

    fun put(key: Key, room: ImageBitmap) {
        if (key.model != null) rooms[key] = room
    }
}

/**
 * The size a room is baked at: [box], or no larger than [BAKE_MAX_PX] on its long side, so a 4K room
 * keeps its memory in check (a blurred picture has no detail for that last stretch to lose).
 */
internal fun bakeSize(box: IntSize): IntSize {
    val long = max(box.width, box.height).coerceAtLeast(1)
    val fit = if (long > BAKE_MAX_PX) BAKE_MAX_PX.toFloat() / long else 1f
    return IntSize((box.width * fit).roundToInt().coerceAtLeast(1), (box.height * fit).roundToInt().coerceAtLeast(1))
}

/** The largest a baked room is kept, on its long side. */
private const val BAKE_MAX_PX = 3840

/** How strongly box art behind the interface is blurred, and how much smaller the blur is worked out. */
private val BOX_ART_BLUR = 48.dp
private const val FROZEN_SCALE = 4

/** One background decode, the same everywhere, so art warmed ahead of time is found in memory. */
fun heroRequest(context: PlatformContext, model: Any?, px: Int): ImageRequest =
    ImageRequest.Builder(context).data(model).size(px, px).precision(Precision.INEXACT).scale(Scale.FIT).build()
