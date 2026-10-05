package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Which item a widget's carousel shows. The board keeps one per widget, so the triggers (L2, R2)
 * turn the focused widget's carousel and each one stays where it was left; the face reports how
 * many items it has as it draws.
 */
@Stable
internal class CarouselState {
    var index by mutableIntStateOf(0)
    var count by mutableIntStateOf(0)

    /** Goes up each time a step ran into an end, with [nudgeDirection] the way it was pushed. */
    var nudge by mutableIntStateOf(0)
        private set
    var nudgeDirection = 0
        private set

    /** One item on ([by] = 1) or back (-1). False at an end, where the carousel leans and springs back. */
    fun step(by: Int): Boolean {
        if (count <= 1) return false
        val next = index + by
        if (next !in 0 until count) {
            nudgeDirection = by
            nudge++
            return false
        }
        index = next
        return true
    }
}

/** The carousel of the widget being drawn, provided by the board; null elsewhere (it then keeps its own). */
internal val LocalCarousel = compositionLocalOf<CarouselState?> { null }

/** Widgets that show their items one at a time, turned with the triggers (L2, R2) or a swipe. */
internal val WidgetKind.isCarousel: Boolean
    get() = this in CarouselKinds

/**
 * Continue Playing turns page by page, its next game out of sight: it is what you were playing, in
 * the order you played it. The others are catalogues, each showing a sliver of what comes next.
 */
internal val WidgetKind.carouselPeeks: Boolean
    get() = this != WidgetKind.CONTINUE_PLAYING

private val CarouselKinds = setOf(
    WidgetKind.CONTINUE_PLAYING, WidgetKind.SYSTEMS, WidgetKind.RECENTLY_ADDED, WidgetKind.RECENTLY_PLAYED,
    WidgetKind.FAVORITES, WidgetKind.PINNED_GAMES, WidgetKind.COLLECTIONS,
    WidgetKind.JELLYFIN_CONTINUE, WidgetKind.JELLYFIN_NEXT_UP, WidgetKind.JELLYFIN_RECENTLY_ADDED,
    WidgetKind.JELLYFIN_FAVORITES, WidgetKind.JELLYFIN_MOVIES, WidgetKind.JELLYFIN_MUSIC,
)

/**
 * How far an item is from the one in front: 0 in front, 1 for the next (peeking at the right), -1
 * for the one that just left. Read while drawing (parallax, dimming), never while composing.
 */
internal typealias CarouselDepth = () -> Float

/**
 * Items shown one at a time across a widget's face. The item in front fills the face (less a
 * sliver at the right where [peek] shows the next one, dimmed and a little smaller); turning slides
 * the next one into place as the last one drifts off to the left and fades, with its picture moving
 * a little slower than its card, so the face has depth. The triggers step through it (see
 * [CarouselState]), a swipe drags it under the finger and a flick throws it on; at either end it
 * leans the way it was pushed and springs back. [header] (the widget's name and the dots) stays put
 * over the top while the items move under it.
 */
@Composable
internal fun Carousel(
    count: Int,
    peek: Boolean,
    modifier: Modifier = Modifier,
    cardShape: Shape? = null,
    header: @Composable BoxScope.(dots: @Composable () -> Unit) -> Unit = {},
    item: @Composable (index: Int, depth: CarouselDepth) -> Unit,
) {
    val state = LocalCarousel.current ?: remember { CarouselState() }
    SideEffect { if (state.count != count) state.count = count }
    val index = state.index.coerceIn(0, (count - 1).coerceAtLeast(0))
    val motion = Fuse.motion
    val reduced = motion.reduced
    val pos = remember { FuselineValue(index.toFloat()) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val glide = remember(reduced) { if (reduced) tween(120, easing = Curves.Standard) else spring(dampingRatio = 0.86f, stiffness = 260f) }

    // The board moved it (a trigger, or the list changed under it): glide there.
    PageEffect(index, count) {
        if (dragging) return@PageEffect
        if (abs(pos.value - index) > 3f) pos.snapTo(index + (if (pos.value > index) 1f else -1f))
        pos.animateTo(index.toFloat(), glide)
    }
    // At an end: a short lean the way it was pushed, damped back to rest.
    val lean = remember { FuselineValue(0f) }
    PageEffect(state.nudge) {
        if (state.nudge == 0 || reduced) return@PageEffect
        lean.snapTo(0f)
        lean.animateTo(1f, tween(NUDGE_MS, easing = Curves.Linear))
        lean.snapTo(0f)
    }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val gapPx = with(density) { (if (maxWidth < 240.dp) Space.s else Space.m).toPx() }
        val peekShare = when {
            !peek || count <= 1 -> 0f
            maxWidth < 180.dp -> 0.1f
            maxWidth > maxHeight * 2.2f -> 0.12f
            else -> 0.15f
        }
        val peekPx = width * peekShare
        val cardPx = if (peekPx > 0f) width - peekPx - gapPx else width
        val stride = cardPx + (if (peekPx > 0f) gapPx else gapPx * 0.5f)
        val cardWidth = with(density) { cardPx.toDp() }
        val faceHeight = maxHeight
        val dotsFit = maxWidth >= DOTS_FROM
        val nudgePx = with(density) { NUDGE.toPx() }

        // Where it shows now: the glide, plus the finger's pull while dragging.
        fun shown(): Float = pos.value + drag

        // Only the items around the one in front are composed; this changes only as it crosses one.
        val base by remember(count) { derivedStateOf { floor(shown()).toInt() } }
        val first = (base - 1).coerceAtLeast(0)
        val last = (base + 2).coerceAtMost(count - 1)

        Box(
            Modifier.fillMaxSize().pointerInput(count, stride) {
                if (count <= 1) return@pointerInput
                val tracker = VelocityTracker()
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        travelled = 0f
                        dragging = true
                        scope.launch { pos.stop() }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val at = shown()
                        // A flick turns one on whichever way it went; a slow drag settles on the nearest.
                        val from = state.index
                        val target = when {
                            v < -FLICK && travelled < 0f -> from + 1
                            v > FLICK && travelled > 0f -> from - 1
                            else -> at.roundToInt()
                        }.coerceIn(0, count - 1)
                        scope.launch {
                            pos.snapTo(at)
                            drag = 0f
                            dragging = false
                            state.index = target
                            pos.animateTo(target.toFloat(), glide, initialVelocity = (-v / stride).coerceIn(-12f, 12f))
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            pos.snapTo(shown())
                            drag = 0f
                            dragging = false
                            pos.animateTo(state.index.toFloat(), glide)
                        }
                    },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    travelled += dx
                    val raw = pos.value + drag - dx / stride
                    // Past either end the pull gives less and less, like a rubber band.
                    val over = when {
                        raw < 0f -> raw
                        raw > count - 1f -> raw - (count - 1f)
                        else -> 0f
                    }
                    val damped = if (over != 0f) raw - over + over / (1f + abs(over) * 3f) else raw
                    drag = damped - pos.value
                }
            },
        ) {
            for (i in first..last) key(i) {
                val depth: CarouselDepth = { i - shown() }
                Box(
                    Modifier
                        .size(cardWidth, faceHeight)
                        .graphicsLayer {
                            val d = depth()
                            val k = lean.value
                            val leanBy = if (k > 0f) sin(k * PI.toFloat() * 3f) * exp(-k * 4f) * nudgePx * -state.nudgeDirection else 0f
                            translationX = d * stride + leanBy
                            when {
                                d < 0f -> {
                                    // Leaving: drifts off and fades, a little smaller.
                                    alpha = (1f + d * 1.3f).coerceIn(0f, 1f)
                                    val s = 1f + d.coerceAtLeast(-1f) * 0.05f
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(1f, 0.5f)
                                }
                                peekPx > 0f -> {
                                    // Coming: smaller as it waits, standing on its left edge.
                                    val s = 1f - d.coerceAtMost(1.5f) * 0.07f
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                            }
                            if (cardShape != null) {
                                shape = cardShape
                                clip = true
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            // The next one waits in shadow, lighting up as it comes forward.
                            if (peekPx > 0f) {
                                val dim = depth().coerceIn(0f, 1f) * PEEK_DIM
                                if (dim > 0.01f) drawRect(Color.Black.copy(alpha = dim))
                            }
                        },
                ) {
                    item(i, depth)
                }
            }
        }
        Box(Modifier.width(cardWidth)) {
            header { if (count > 1 && dotsFit) CarouselDots(count, ::shown) }
        }
    }
}

/**
 * Art inside a carousel's item moves a little slower than its card, so the picture seems to sit
 * behind it. The art is drawn a touch larger, so its edges never show as it moves.
 */
internal fun Modifier.carouselParallax(depth: CarouselDepth?): Modifier = if (depth == null) this else graphicsLayer {
    val d = depth().coerceIn(-1.2f, 1.2f)
    scaleX = PARALLAX_ZOOM
    scaleY = PARALLAX_ZOOM
    translationX = -d * size.width * PARALLAX
}

/**
 * Where a carousel is, as dots: as many as fit (seven at most, the outer ones smaller while more lie
 * beyond), the one in front a longer pill that stretches between dots as it moves.
 */
@Composable
internal fun CarouselDots(count: Int, position: () -> Float, color: Color = Fuse.colors.onArt) {
    val shown = minOf(count, MAX_DOTS)
    val dot = 6.dp
    val gap = 5.dp
    val pill = 16.dp
    Box(
        Modifier
            .background(Color.Black.copy(alpha = 0.28f), SquircleShape.fraction(0.5f))
            .padding(horizontal = Space.s - Space.xxs, vertical = Space.xs + Space.xxs),
    ) {
        Canvas(Modifier.size(width = dot * (shown - 1) + gap * (shown - 1) + pill, height = dot)) {
            val p = position().coerceIn(0f, count - 1f)
            val d = dot.toPx()
            val g = gap.toPx()
            val long = pill.toPx()
            // The window of dots shown slides along once the one in front nears its edge.
            val start = (p - (shown - 1) / 2f).coerceIn(0f, (count - shown).toFloat())
            val h = size.height
            val from = start.toInt()
            var x = 0f
            for (slot in 0 until shown) {
                val i = from + slot
                // How much this dot is the one in front: it grows into the pill as the item arrives.
                val near = 1f - abs(p - i).coerceAtMost(1f)
                val w = d + (long - d) * near
                // The outer dots shrink while more lie beyond them.
                val beyond = (slot == 0 && from > 0) || (slot == shown - 1 && from + shown < count)
                val r = h / 2 * (if (beyond && near == 0f) 0.6f else 1f)
                drawRoundRect(
                    color.copy(alpha = 0.42f + 0.58f * near),
                    topLeft = Offset(x + if (r < h / 2) (d / 2 - r) else 0f, h / 2 - r),
                    size = androidx.compose.ui.geometry.Size(if (r < h / 2) r * 2 else w, r * 2),
                    cornerRadius = CornerRadius(r),
                )
                x += w + g
            }
        }
    }
}

/**
 * A carousel's name over art, with its dots beside it: the widget's icon and name in the overline
 * style, set on a soft dark edge so it reads over any picture.
 */
@Composable
internal fun BoxScope.CarouselHeader(icon: ImageVector, label: String, dots: @Composable () -> Unit, compact: Boolean = false) {
    val c = Fuse.colors
    Box(
        Modifier.fillMaxWidth().height(if (compact) 44.dp else 64.dp)
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.42f), 1f to Color.Transparent)),
    )
    Row(Modifier.padding(if (compact) Space.m else Space.l), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(icon, size = Size.iconXS, tint = c.onArtMuted)
        Spacer(Modifier.width(Space.s - Space.xxs))
        FText(label.uppercase(), Fuse.type.overline, color = c.onArtMuted, maxLines = 1)
        Spacer(Modifier.width(Space.s))
        dots()
    }
}

/** How dark the next item waits, before it comes forward. */
private const val PEEK_DIM = 0.5f

/** A flick at least this fast (pixels a second) turns one on. */
private const val FLICK = 650f

/** How far and how long a carousel leans at an end. */
private val NUDGE = 14.dp
private const val NUDGE_MS = 420

/** Art moves this share of its width slower than its card, drawn this much larger. */
private const val PARALLAX = 0.08f
private const val PARALLAX_ZOOM = 1.18f

/** At most this many dots; narrower than [DOTS_FROM] a face shows none. */
private const val MAX_DOTS = 7
private val DOTS_FROM = 132.dp
