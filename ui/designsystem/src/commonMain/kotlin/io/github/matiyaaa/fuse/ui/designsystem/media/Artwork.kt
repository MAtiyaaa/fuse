package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.SizeResolver
import io.github.matiyaaa.fuse.model.ArtworkSizing
import androidx.compose.ui.graphics.painter.Painter
import io.github.matiyaaa.fuse.ui.designsystem.effects.shimmer
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.picturesMove
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import kotlinx.coroutines.delay

/**
 * Loads artwork with Coil (memory + disk cache, decoded at the size it is drawn, cancelled when the
 * composable leaves). If the image is missing or fails, [fallback] is shown instead of an empty
 * box, so a library with no scraped art still looks designed.
 *
 * While the image loads, the fallback waits [fallbackDelayMs] and only appears if loading takes
 * longer, so a title never flashes up before a logo that was just a moment away. Cropped art (covers,
 * tiles, heroes) holds its place meanwhile with a quiet skeleton fill, and when the fallback does
 * appear a calm shimmer passes over it until the art arrives ([loading]; still under Reduced motion
 * and in Low Power Mode). The art then crossfades in over whatever was there. Art that comes
 * straight from the memory cache appears at once, without the fade.
 *
 * [focusX]/[focusY] (0..1) choose which part of the image stays visible when it is cropped.
 * [tint] recolours the image (single-colour logos, such as system logos, drawn in white).
 *
 * [backdrop] draws the whole image ([ContentScale.Fit]) over a dimmed, cropped copy of itself,
 * blurred by [backdropBlur] (0 for none), so art of another shape fills the slot without losing its
 * edges: a portrait cover in a square tile keeps its title and logo.
 *
 * Fitted art (logos, icons, badges, backdrops) is decoded for the space it is laid out in. Without
 * that, an SVG logo would be drawn at its own small canvas size and stretched, and come out soft.
 *
 * A moving picture (a GIF or an animated WebP) plays while it is shown, or holds its first frame
 * where pictures may not move ([MovingPictures]); a GIF keeps its own colours, whatever [tint] says.
 */
@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    focusX: Float = 0.5f,
    focusY: Float = 0.5f,
    zoom: Float = 1f,
    fadeIn: Boolean = true,
    tint: Color? = null,
    fallbackDelayMs: Long = 400,
    backdrop: Boolean = false,
    backdropBlur: Dp = 0.dp,
    /** Show loading (skeleton, then shimmer over the fallback) for cropped art. */
    loading: Boolean = true,
    /**
     * Keeps this art ready in memory once shown ([ShownArt]): for backgrounds and wallpapers, which
     * must never fade in from nothing when Fuse comes back from a game (the system empties image
     * caches while Fuse is in the background).
     */
    pin: Boolean = false,
    /** Told the picture's shape (width over height) once it is loaded, for a slot that sizes to it, such as a logo's. */
    onAspect: ((Float) -> Unit)? = null,
    fallback: @Composable () -> Unit = {},
) {
    if (model == null) {
        Box(modifier) { fallback() }
        return
    }
    val tint = tint.takeUnless { MovingPictures.looksMoving(model) }
    val still = !Fuse.motion.picturesMove(Fuse.quality)
    val context = LocalPlatformContext.current
    val fit = backdrop || contentScale == ContentScale.Fit
    val sizer = rememberConstraintsSizeResolver()
    // The size it is decoded at only ever grows: a picture decoded larger serves a smaller slot just
    // as well, so a grid of tiles shrinking with a folding header (a change of size every frame)
    // never asks for its pictures again, and never recomposes for it. The largest size asked for is
    // kept outside the snapshot: a tile's first layout used to write it as state, which built every
    // tile twice and sent each its picture's request twice (the first called off) as a grid opened.
    // Only a slot that grows past it (a tile unfolding larger than it ever was) asks again.
    val decoded = remember(zoom) { IntArray(2) }
    var grown by remember(zoom) { mutableIntStateOf(0) }
    val decodeSizer = remember(sizer, zoom, grown) {
        SizeResolver {
            val measured = sizer.size()
            val target = ArtworkSizing.decode(
                (measured.width as? Dimension.Pixels)?.px ?: 512,
                (measured.height as? Dimension.Pixels)?.px ?: 512,
                enlargement = maxOf(1.15f, zoom),
            )
            decoded[0] = maxOf(decoded[0], target.width)
            decoded[1] = maxOf(decoded[1], target.height)
            Size(decoded[0], decoded[1])
        }
    }
    // A picture that failed is asked for again a few times ([ArtRetry]), not kept failed while shown.
    var attempt by remember(model) { mutableStateOf(0) }
    val request = remember(model, context, fit, decodeSizer, attempt, still) {
        ImageRequest.Builder(context).data(model).crossfade(false)
            .size(decodeSizer).scale(if (fit) Scale.FIT else Scale.FILL)
            .memoryCacheKeyExtra(ArtRetry.EXTRA, attempt.toString())
            .apply { if (still) MovingPictures.still(this, model) }
            .build()
    }
    // On a computer, art is drawn with mipmaps: sharper where it is scaled (a tile lifting, a
    // fractional desktop scale) instead of the plainest filter. Android keeps the plainest, for speed.
    val filter = if (io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.cachedLayers) androidx.compose.ui.graphics.FilterQuality.Low else androidx.compose.ui.graphics.FilterQuality.Medium
    val loader = rememberAsyncImagePainter(request, contentScale = if (backdrop) ContentScale.Fit else contentScale, filterQuality = filter)
    // The soft copy behind fitted art is the picture decoded a few pixels across and drawn large:
    // stretched smoothly, it reads as blurred, at the cost of one tiny picture. A blur filter on each
    // tile was worked out again every frame for every tile on screen, which made a grid of covers
    // stutter as it scrolled on Android.
    val soft = backdrop && backdropBlur > 0.dp
    val softRequest = remember(model, context, attempt, still, soft) {
        if (!soft) null else ImageRequest.Builder(context).data(model).crossfade(false)
            .size(BACKDROP_PX, BACKDROP_PX).scale(Scale.FILL)
            // Its own entry in the memory cache, so the full-size picture is never handed back for it.
            .memoryCacheKeyExtra(BACKDROP_EXTRA, attempt.toString())
            .apply { if (still) MovingPictures.still(this, model) }
            .build()
    }
    val softPainter = softRequest?.let { rememberAsyncImagePainter(it, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low) }
    val state by loader.state.collectAsStateCompat()
    val success = state as? AsyncImagePainter.State.Success
    val failed = state is AsyncImagePainter.State.Error
    ArtRetry.Watch(failed, attempt) { attempt++ }
    PlayWhileShown(success?.result?.image)
    if (onAspect != null && success != null) {
        val image = success.result.image
        SideEffect { if (image.width > 0 && image.height > 0) onAspect(image.width.toFloat() / image.height) }
    }
    // Art already shown this session is never faded in again; pinned art is drawn at once while the
    // loader fetches it again.
    var lastPainter by remember(model) { mutableStateOf<Painter?>(null) }
    SideEffect { if (success != null) lastPainter = success.painter }
    val held = if (success == null) lastPainter ?: ShownArt.pinned(model) else null
    val painter = held ?: loader
    // Capture session history before recording this load. Looking it up after shown() makes
    // every first network result bypass its fade. Keep the decision stable across fade frames.
    val alreadyShown = remember(model) { ShownArt.wasShown(model) }
    val fromMemory = success?.result?.dataSource == DataSource.MEMORY_CACHE || held != null || alreadyShown
    SideEffect {
        if (success != null) {
            ShownArt.shown(model, if (pin) success.painter else null)
            io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.mark(io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.ART)
        }
    }
    val alpha = remember(model) { FuselineValue(0f) }
    val fade = Fuse.motion.fade(Durations.BASE)
    // Read through a derived state so the fade itself never recomposes this.
    val shown by remember(alpha) { derivedStateOf { alpha.value >= 1f } }
    // Only art that fills its slot gets a placeholder: never logos, tinted marks or overlays.
    val placeholder = loading && fadeIn && contentScale == ContentScale.Crop && !backdrop && tint == null
    // Keyed on [alpha] too: a new picture ready at once (from memory) must still be shown, though
    // nothing else about the load changed.
    LaunchedEffect(alpha, success != null || held != null, fromMemory) {
        when {
            success == null && held == null -> alpha.snapTo(0f)
            !fadeIn || fromMemory -> alpha.snapTo(1f)
            else -> alpha.animateTo(1f, fade)
        }
    }
    // Quick loads (memory or disk) never show the fallback; slow ones (network) do after a moment.
    // Only while it is still loading: art that came at once never wakes again to say it was slow
    // (every tile on screen used to be built once more, 400 ms after it appeared, for nothing).
    var slow by remember(model) { mutableStateOf(false) }
    val arrived = success != null || held != null
    LaunchedEffect(model, arrived) {
        if (arrived) return@LaunchedEffect
        delay(fallbackDelayMs)
        slow = true
    }
    Box(modifier.then(sizer).onSizeChanged { measured ->
        // Asked again only when the slot grows past every size decoded so far.
        if (decoded[0] == 0) return@onSizeChanged
        val need = ArtworkSizing.decode(measured.width, measured.height, enlargement = maxOf(1.15f, zoom))
        if (need.width > decoded[0] || need.height > decoded[1]) {
            decoded[0] = maxOf(decoded[0], need.width)
            decoded[1] = maxOf(decoded[1], need.height)
            grown++
        }
    }) {
        val waiting = success == null && !failed
        if (placeholder && !failed && !slow && !shown) {
            // Holds the art's place while it loads, so a tile never shows a hole.
            Box(Modifier.matchParentSize().background(Fuse.colors.skeleton))
        }
        if (failed || (slow && !shown)) {
            if (placeholder) Box(Modifier.matchParentSize().shimmer(active = waiting)) { fallback() } else fallback()
        }
        if (backdrop) {
            val dim = if (soft) BACKDROP_DIM else BACKDROP_DIM_SHARP
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha.value }
                    .drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = dim))
                    }
                    .paint(softPainter ?: painter, contentScale = ContentScale.Crop),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (zoom != 1f) {
                        scaleX = zoom
                        scaleY = zoom
                    }
                    this.alpha = alpha.value
                }
                .paint(
                    painter,
                    contentScale = if (backdrop) ContentScale.Fit else contentScale,
                    alignment = BiasAlignment(focusX * 2 - 1, focusY * 2 - 1),
                    colorFilter = tint?.let { ColorFilter.tint(it) },
                ),
        )
    }
}

/** How many pixels across the soft copy behind fitted art is decoded at: few enough to read as blurred. */
private const val BACKDROP_PX = 18

/** The memory cache's mark for the soft copy, apart from the picture itself. */
private const val BACKDROP_EXTRA = "fuse.backdrop"

/** How much the copy behind fitted art is dimmed: blurred, or left sharp where blur is off. */
private const val BACKDROP_DIM = 0.3f
private const val BACKDROP_DIM_SHARP = 0.55f

/** Alignment helper for callers positioning cropped art. */
fun focusAlignment(x: Float, y: Float): Alignment = BiasAlignment(x * 2 - 1, y * 2 - 1)

/**
 * Art Fuse has shown this session: which (so it never fades in twice) and, for a few backgrounds
 * and wallpapers, the picture itself, held so it is there at once when Fuse comes back.
 */
object ShownArt {
    private const val PINNED = 8
    private const val SEEN = 1024
    private val pinned = object : LinkedHashMap<Any, androidx.compose.ui.graphics.painter.Painter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, androidx.compose.ui.graphics.painter.Painter>?) = size > PINNED
    }
    private val seen = object : LinkedHashMap<Any, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Unit>?) = size > SEEN
    }

    internal fun shown(model: Any, painter: androidx.compose.ui.graphics.painter.Painter?) {
        seen[model] = Unit
        if (painter != null) pinned[model] = painter
    }

    internal fun wasShown(model: Any): Boolean = seen.containsKey(model)

    internal fun pinned(model: Any): androidx.compose.ui.graphics.painter.Painter? = pinned[model]
}
