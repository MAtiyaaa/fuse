package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as PxSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.model.FocusStyle
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberPressProgress
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.squirclePath
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlinx.coroutines.delay

/**
 * The building block of every browsable surface: a piece of art that lifts toward you when selected.
 *
 * Selection is shown several ways at once so it never depends on colour alone (the spark):
 *
 * - **Lift**: the tile scales up on a firm spring and its shadow grows long and soft. In the `GLOW`
 *   focus style the shadow is tinted with [glow] (the art's or platform's colour) and a faint pool of
 *   that colour gathers around the tile's lower edge, as if it lit the surface beneath it.
 * - **Light**: the light edge along its top brightens and a faint sheen rises inside the top of the
 *   glass, so the tile reads as an object catching the light.
 * - **Sweep**: when focus arrives, one band of light (with a fainter glint behind it) passes over
 *   the tile like a reflection crossing glass. Standard and Enhanced motion only, never in Low
 *   Power Mode.
 * - **Accent bar**: in the `GLOW` and `BAR` styles a short bar in the accent colour springs out
 *   under the tile a beat after the lift, with a small overshoot.
 * - **Ring**: an outline in the focus colour in the `RING` style, and in every style with High
 *   contrast focus. It settles outward as the tile lifts.
 *
 * With a mouse, hovering a tile lifts it a little (no sweep, no bar); pressing it (touch or mouse)
 * pushes it down and springs back. Under Reduced motion nothing scales: the bar, ring and light
 * carry the state.
 *
 * Everything is drawn from paths and brushes built once per size, and animated values are read
 * only while drawing, so focus moves never recompose a tile.
 *
 * [glow] tints the lift shadow (usually the art's or platform's colour). [interactionSource] lets
 * the caller observe hover and press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    selected: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction),
    glow: Color = Fuse.colors.accent,
    cornerFraction: Float = Fuse.geometry.tileCornerFraction,
    showSpark: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    /**
     * The most a side may grow when the tile lifts or is pressed, for tiles much larger than a game's
     * (Home's widgets), where the usual share of their size would push them over their neighbours.
     */
    maxGrow: Dp? = null,
    /**
     * Whether the tile is a surface of its own: its shadow, glass sheen, light edge, hover and press
     * tints and sweep. Off for content that draws its own cards and leaves parts of the tile empty
     * (a carousel with the next card peeking), where those would show as a box around nothing; the
     * lift, the bar and the ring stay.
     */
    surface: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = Fuse.motion
    val look = Fuse.look
    val colors = Fuse.colors
    val quality = Fuse.quality
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = onClick != null || onLongClick != null
    val hovered by interaction.collectIsHoveredAsState()

    val lift by fuselineFloat(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.focusSpring(),
        label = "lift",
    )
    val hoverOn = interactive && hovered && !selected
    val hover by fuselineFloat(if (hoverOn) 1f else 0f, motion.hover(hoverOn), label = "hover")
    val press by rememberPressProgress(interaction)

    val sweep = remember { FuselineValue(1f) }
    val sweepOn = motion.sweep && quality.animatedBackground
    LaunchedEffect(selected, sweepOn) {
        if (selected && sweepOn) {
            sweep.snapTo(0f)
            // Let the lift get under way first, so the light crosses a tile that is already rising.
            delay(SWEEP_DELAY_MS)
            sweep.animateTo(1f, tween(Durations.SWEEP, easing = Curves.Sweep))
        } else {
            sweep.snapTo(1f)
        }
    }
    val bar = remember { FuselineValue(if (selected) 1f else 0f) }
    LaunchedEffect(selected) {
        if (selected) bar.animateTo(1f, motion.barSpring()) else bar.animateTo(0f, motion.exit(Durations.FAST))
    }

    val style = look.focusStyle
    val ring = style == FocusStyle.RING || look.highContrastFocus
    val spark = showSpark && style != FocusStyle.RING
    val focusScale = motion.focusScale
    val hoverScale = (focusScale - 1f) * HOVER_SHARE
    val pressDepth = 1f - motion.pressScale
    // GLOW: at rest a plain dark contact shadow; as the tile lifts the shadow warms into a halo in
    // the tile's own colour, only lightly darkened so it reads as coloured light. The ambient part
    // stays dark so the row below is never washed in colour.
    val glowing = style == FocusStyle.GLOW
    val restSpot = if (glowing) lerp(glow, Color.Black, 0.78f) else colors.shadow
    val liftSpot = if (glowing) lerp(glow, Color.Black, 0.22f) else colors.shadow
    val ambient = if (glowing) lerp(glow, Color.Black, 0.75f).copy(alpha = 0.3f) else colors.shadow.copy(alpha = colors.shadow.alpha * 0.45f)
    val accent = colors.accent
    val focusColor = colors.focus
    val pool = glowing
    val poolColor = lerp(glow, Color.White, 0.08f)

    val clickable = if (interactive) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else Modifier

    Box(
        modifier
            // Shadows and focus overflow must paint above later siblings, not underneath them.
            .zIndex(if (selected) 2f else if (hoverOn) 1f else 0f)
            .graphicsLayer {
                val p = press
                val raised = maxOf(lift, hover * HOVER_SHARE)
                // A large tile grows by at most maxGrow a side, however wide it is.
                val cap = maxGrow?.let { 2f * it.toPx() / size.width.coerceAtLeast(1f) } ?: Float.MAX_VALUE
                val scale = 1f + minOf(focusScale - 1f, cap) * lift + minOf(hoverScale, cap) * hover * (1f - lift) - minOf(pressDepth, cap) * p
                scaleX = scale
                scaleY = scale
                val rest = Elevation.tile.shadow.toPx()
                val up = Elevation.tileFocused.shadow.toPx()
                // A pressed tile is pushed toward the surface, so its shadow tightens.
                shadowElevation = if (!surface) 0f else (rest + (up - rest) * raised) * (1f - 0.45f * p.coerceIn(0f, 1f))
                spotShadowColor = if (glowing) lerp(restSpot, liftSpot, raised.coerceIn(0f, 1f)) else liftSpot
                ambientShadowColor = ambient
                this.shape = shape
                clip = false
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val corner = minOf(w, h) * cornerFraction
                val outline = squirclePath(w, h, corner, SMOOTHING)
                val edgeStroke = Stroke(Size.stroke.toPx())
                // The light edge: brightest along the top, gone by the middle.
                val edgeBrush = Brush.verticalGradient(
                    0f to Color.White,
                    0.16f to Color.White.copy(alpha = 0.42f),
                    0.4f to Color.White.copy(alpha = 0.1f),
                    0.75f to Color.Transparent,
                )
                // The sheen: light caught inside the top of the glass.
                val sheen = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.12f),
                    0.22f to Color.White.copy(alpha = 0.04f),
                    0.5f to Color.Transparent,
                )
                // The sweep: a soft band with a bright core, slanted like a reflection, defined
                // around x = 0 and moved by translation so nothing is built while it runs.
                // Proportioned to the short side, so a wide card gets a band, not a wash.
                val band = minOf(w * 0.62f, h * 0.9f)
                val slant = h * SLANT
                val dx = 0.94f
                val dy = 0.34f
                val sweepBrush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.3f to Color.White.copy(alpha = 0.05f),
                    0.5f to Color.White.copy(alpha = 0.24f),
                    0.7f to Color.White.copy(alpha = 0.05f),
                    1f to Color.Transparent,
                    start = Offset(-dx * band / 2, -dy * band / 2),
                    end = Offset(dx * band / 2, dy * band / 2),
                )
                val glint = band * 0.22f
                val glintBrush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.12f),
                    1f to Color.Transparent,
                    start = Offset(-dx * glint / 2, -dy * glint / 2),
                    end = Offset(dx * glint / 2, dy * glint / 2),
                )
                // The ring, at its full distance; it is scaled in from the tile's edge as it appears.
                val gap = Size.focusGap.toPx() + Size.focusStroke.toPx() / 2
                val ringPath = squirclePath(w + gap * 2, h + gap * 2, corner + gap, SMOOTHING)
                val ringStroke = Stroke(Size.focusStroke.toPx())
                val ringRestX = w / (w + gap * 2)
                val ringRestY = h / (h + gap * 2)
                // The bar.
                val barWide = if (style == FocusStyle.BAR) 1.4f else 1f
                val barW = Size.sparkWidth.toPx() * barWide
                val barH = Size.sparkHeight.toPx() * (if (style == FocusStyle.BAR) 1.3f else 1f)
                val barY = h + Size.sparkGap.toPx()
                val barCorner = CornerRadius(barH / 2)
                val restEdge = Elevation.tile.edge
                val focusEdge = Elevation.tileFocused.edge
                // GLOW: light in the tile's colour pooling around its lower half, as if the lifted
                // tile lit the surface under it. Drawn behind the tile, so only its rim shows.
                val poolRadius = w * 0.62f
                val poolBrush = Brush.radialGradient(
                    0f to poolColor,
                    0.55f to poolColor.copy(alpha = 0.32f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = poolRadius,
                )
                val poolSquash = (h / w * 0.6f).coerceIn(0.4f, 1.1f)

                onDrawWithContent {
                    val l = lift
                    if (pool && surface && l > 0.01f) {
                        translate(w / 2, h * 0.7f) {
                            scale(1f, poolSquash, pivot = Offset.Zero) {
                                drawCircle(poolBrush, radius = poolRadius, center = Offset.Zero, alpha = POOL_ALPHA * l.coerceIn(0f, 1f))
                            }
                        }
                    }
                    drawContent()
                    val hv = hover
                    val p = press.coerceIn(0f, 1f)
                    val lit = maxOf(l, hv * 0.5f)
                    if (surface) {
                        drawPath(outline, sheen, alpha = 0.35f + 0.65f * lit)
                        if (hv > 0.005f) drawPath(outline, Color.White, alpha = HOVER_TINT * hv)
                        if (p > 0.005f) drawPath(outline, Color.Black, alpha = PRESS_SHADE * p)
                        drawPath(outline, edgeBrush, alpha = restEdge + (focusEdge - restEdge) * lit, style = edgeStroke)
                    }

                    val s = sweep.value
                    if (surface && s < 1f && s > 0f && l > 0.3f) {
                        val x = -band + (w + band * 2 + slant) * s
                        clipPath(outline) {
                            translate(left = x) {
                                drawRect(sweepBrush, topLeft = Offset(-band - slant, 0f), size = PxSize(band * 2 + slant, h))
                            }
                            translate(left = x - band * 0.5f) {
                                drawRect(glintBrush, topLeft = Offset(-glint - slant, 0f), size = PxSize(glint * 2 + slant, h))
                            }
                        }
                    }
                    if (ring && l > 0.01f) {
                        translate(-gap, -gap) {
                            scale(
                                scaleX = ringRestX + (1f - ringRestX) * l,
                                scaleY = ringRestY + (1f - ringRestY) * l,
                                pivot = Offset(w / 2 + gap, h / 2 + gap),
                            ) {
                                drawPath(ringPath, focusColor, alpha = l.coerceIn(0f, 1f), style = ringStroke)
                            }
                        }
                    }
                    val b = bar.value
                    if (spark && b > 0.01f) {
                        val width = barW * b
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset((w - width) / 2, barY),
                            size = PxSize(width, barH),
                            cornerRadius = barCorner,
                            alpha = (b * 1.6f).coerceIn(0f, 1f),
                        )
                    }
                }
            }
            .clip(shape)
            .then(clickable)
            // Screen readers (and UI tests) can tell which tile the controller is on.
            .semantics { this.selected = selected },
        content = content,
    )
}

/** Corner smoothing of every tile (the Fuse squircle). */
private const val SMOOTHING = 0.6f

/** How long the sweep waits for the lift to get going. */
private const val SWEEP_DELAY_MS = 30L

/** Horizontal lean of the sweep across the tile's height. */
private const val SLANT = 0.36f

/** Share of the focus lift a mouse hover gives. */
private const val HOVER_SHARE = 0.4f

/** White laid over a hovered tile. */
private const val HOVER_TINT = 0.06f

/** Black laid over a fully pressed tile. */
private const val PRESS_SHADE = 0.1f

/** Strength of the GLOW style's pool of coloured light under a focused tile. */
private const val POOL_ALPHA = 0.3f
