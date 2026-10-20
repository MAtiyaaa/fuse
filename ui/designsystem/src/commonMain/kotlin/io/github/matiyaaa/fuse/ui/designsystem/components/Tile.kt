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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
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
    /**
     * What a screen reader says for the tile (a game's or a system's name), for tiles whose words are
     * part of their art; a tile with words of its own is read from them.
     */
    label: String? = null,
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
    val bar = remember { FuselineValue(if (selected) 1f else 0f) }
    // A tile that has never been selected has nothing to animate: no work starts for it. A grid
    // flung fast composes dozens of tiles a frame, and each started two of these before.
    var everSelected by remember { mutableStateOf(selected) }
    if (selected) everSelected = true
    if (everSelected) {
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
        LaunchedEffect(selected) {
            if (selected) bar.animateTo(1f, motion.barSpring()) else bar.animateTo(0f, motion.exit(Durations.FAST))
        }
    }

    // The theme's own frame for its tiles (see TileLook): the art edge to edge, or set in a frame.
    val tileLook = look.spec.tiles
    val gloss = tileLook == io.github.matiyaaa.fuse.model.TileLook.GLOSS
    val frameColor = when (tileLook) {
        io.github.matiyaaa.fuse.model.TileLook.GLOSS -> if (colors.isDark) GLOSS_FRAME_NIGHT else Color.White
        io.github.matiyaaa.fuse.model.TileLook.PANEL -> if (colors.isDark) PANEL_FRAME_NIGHT else PANEL_FRAME
        io.github.matiyaaa.fuse.model.TileLook.INK -> colors.text
        io.github.matiyaaa.fuse.model.TileLook.NEON -> colors.accent
        io.github.matiyaaa.fuse.model.TileLook.MAT -> colors.surfaceRaised
        io.github.matiyaaa.fuse.model.TileLook.PRISM -> colors.accent
        io.github.matiyaaa.fuse.model.TileLook.PLAIN -> Color.Transparent
    }
    val bevelColor = if (colors.isDark) Color.Black.copy(alpha = 0.35f) else GLOSS_BEVEL
    // GLOSS: the focus ring breathes slowly while it rests on a tile.
    val breathe = remember { FuselineValue(0f) }
    val breathing = gloss && selected && motion.ambient
    LaunchedEffect(breathing) {
        if (!breathing) {
            breathe.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            breathe.animateTo(1f, tween(BREATHE_MS, easing = BREATHE_CURVE))
            breathe.animateTo(0f, tween(BREATHE_MS, easing = BREATHE_CURVE))
        }
    }

    val style = look.focusStyle
    val ring = style == FocusStyle.RING || look.highContrastFocus
    val spark = showSpark && style != FocusStyle.RING && style != FocusStyle.BRACKETS
    // BRACKETS: four corners close in around the tile as it lifts, in the accent.
    val brackets = style == FocusStyle.BRACKETS
    val focusScale = motion.focusScale
    val hoverScale = (focusScale - 1f) * HOVER_SHARE
    val pressDepth = 1f - motion.pressScale
    // GLOW: at rest a plain dark contact shadow; as the tile lifts the shadow warms into a halo in
    // the tile's own colour, only lightly darkened so it reads as coloured light. The ambient part
    // stays dark so the row below is never washed in colour.
    val glowing = style == FocusStyle.GLOW
    // Where shadows can't be tinted faintly (see Drawing.tintedShadows), the lift stays a plain shadow.
    val tinted = glowing && io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.tintedShadows
    val restSpot = if (tinted) lerp(glow, Color.Black, 0.78f) else colors.shadow
    val liftSpot = if (tinted) lerp(glow, Color.Black, 0.22f) else colors.shadow
    val ambient = if (tinted) lerp(glow, Color.Black, 0.75f).copy(alpha = 0.3f) else colors.shadow.copy(alpha = colors.shadow.alpha * 0.45f)
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
                // Drawn by the processor, a shadow under every tile at rest cost a quarter of each frame: only the lifted one casts one there.
                val rest = if (io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.restingShadows && !io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.cpu) Elevation.tile.shadow.toPx() else 0f
                val up = Elevation.tileFocused.shadow.toPx()
                // A pressed tile is pushed toward the surface, so its shadow tightens.
                shadowElevation = if (!surface) 0f else (rest + (up - rest) * raised) * (1f - 0.45f * p.coerceIn(0f, 1f))
                spotShadowColor = if (tinted) lerp(restSpot, liftSpot, raised.coerceIn(0f, 1f)) else liftSpot
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
                // The theme's frame: its width, and for GLOSS the bevel just inside it and the gloss.
                val short = minOf(w, h)
                val frameStroke = Stroke(when (tileLook) {
                    io.github.matiyaaa.fuse.model.TileLook.GLOSS -> short * GLOSS_FRAME_SHARE
                    io.github.matiyaaa.fuse.model.TileLook.PANEL -> maxOf(short * PANEL_FRAME_SHARE, 2f)
                    io.github.matiyaaa.fuse.model.TileLook.INK -> maxOf(short * INK_FRAME_SHARE, 2.5f)
                    io.github.matiyaaa.fuse.model.TileLook.NEON -> maxOf(Size.stroke.toPx() * 1.5f, 1.5f)
                    io.github.matiyaaa.fuse.model.TileLook.PRISM -> maxOf(short * PRISM_FRAME_SHARE, 2.5f)
                    // Drawn on the outline, half of it falls outside the clipped art: twice the mount.
                    io.github.matiyaaa.fuse.model.TileLook.MAT -> short * MAT_SHARE * 2f
                    io.github.matiyaaa.fuse.model.TileLook.PLAIN -> 0f
                })
                val neonHalo = Stroke(frameStroke.width * NEON_HALO)
                // PRISM: light running from the tile's own colour, through white, to the accent.
                val prism = Brush.linearGradient(
                    0f to lerp(glow, Color.White, 0.25f),
                    0.5f to lerp(glow, accent, 0.5f),
                    1f to lerp(accent, Color.White, 0.15f),
                    start = Offset.Zero,
                    end = Offset(w, h),
                )
                // BRACKETS: each corner's arm, and how far out they sit at full lift.
                val arm = short * BRACKET_ARM
                val bracketStroke = Stroke(Size.focusStroke.toPx() * 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val bracketOut = Size.focusGap.toPx() * 1.8f
                val bevelStroke = Stroke(Size.stroke.toPx())
                val bevelInset = frameStroke.width / 2 + bevelStroke.width / 2
                val bevelPath = if (gloss) squirclePath(w - bevelInset * 2, h - bevelInset * 2, (corner - bevelInset).coerceAtLeast(0f), SMOOTHING) else null
                val bevelBrush = Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to bevelColor)
                val glossBrush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (colors.isDark) 0.16f else 0.42f),
                    1f to Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.06f),
                    startY = -h * 0.6f,
                    endY = h * 0.42f,
                )

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
                    if (surface) when (tileLook) {
                        io.github.matiyaaa.fuse.model.TileLook.PLAIN -> Unit
                        io.github.matiyaaa.fuse.model.TileLook.GLOSS -> {
                            clipPath(outline) {
                                drawOval(glossBrush, topLeft = Offset(-w * 0.35f, -h * 0.6f), size = PxSize(w * 1.7f, h * 1.02f))
                            }
                            bevelPath?.let { translate(bevelInset, bevelInset) { drawPath(it, bevelBrush, style = bevelStroke) } }
                            drawPath(outline, frameColor, alpha = 0.92f, style = frameStroke)
                        }
                        io.github.matiyaaa.fuse.model.TileLook.NEON -> {
                            // A faint line at rest that brightens, with a soft halo, as the tile lifts.
                            drawPath(outline, frameColor, alpha = NEON_REST * (1f - lit) * 0.5f + 0.5f * lit, style = neonHalo)
                            drawPath(outline, frameColor, alpha = NEON_REST + (1f - NEON_REST) * lit, style = frameStroke)
                        }
                        io.github.matiyaaa.fuse.model.TileLook.PRISM -> {
                            // Lit at rest, brighter and haloed once lifted, as a handheld's system tiles glow.
                            drawPath(outline, prism, alpha = PRISM_HALO * (0.35f + 0.65f * lit), style = neonHalo)
                            drawPath(outline, prism, alpha = PRISM_REST + (1f - PRISM_REST) * lit, style = frameStroke)
                        }
                        io.github.matiyaaa.fuse.model.TileLook.INK -> drawPath(outline, frameColor, alpha = INK_ALPHA, style = frameStroke)
                        // Inside the tile only: the mount covers the art's edge and never spills into the gap.
                        io.github.matiyaaa.fuse.model.TileLook.MAT -> clipPath(outline) { drawPath(outline, frameColor, style = frameStroke) }
                        io.github.matiyaaa.fuse.model.TileLook.PANEL -> drawPath(outline, frameColor, style = frameStroke)
                    }
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
                                // GLOSS: the ring breathes between full and a soft two thirds while focus rests.
                                val breath = if (gloss) 1f - BREATHE_DEPTH * breathe.value else 1f
                                drawPath(ringPath, focusColor, alpha = (l * breath).coerceIn(0f, 1f), style = ringStroke)
                            }
                        }
                    }
                    if (brackets && l > 0.01f) {
                        // From the tile's own corners out to just beyond them as it lifts.
                        val o = bracketOut * l
                        val a = (l * 1.4f).coerceIn(0f, 1f)
                        val left = -o
                        val top = -o
                        val right = w + o
                        val bottom = h + o
                        val len = arm * (0.6f + 0.4f * l)
                        fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
                            drawLine(accent, Offset(cx, cy), Offset(cx + dx * len, cy), strokeWidth = bracketStroke.width, cap = bracketStroke.cap, alpha = a)
                            drawLine(accent, Offset(cx, cy), Offset(cx, cy + dy * len), strokeWidth = bracketStroke.width, cap = bracketStroke.cap, alpha = a)
                        }
                        corner(left, top, 1f, 1f)
                        corner(right, top, -1f, 1f)
                        corner(left, bottom, 1f, -1f)
                        corner(right, bottom, -1f, -1f)
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
            // Screen readers (and UI tests) can tell which tile the controller is on, and what it is.
            .semantics {
                this.selected = selected
                if (label != null) contentDescription = label
                if (interactive) role = Role.Button
            },
        content = content,
    )
}

/** Corner smoothing of every tile (the Fuse squircle). */
private const val SMOOTHING = 0.6f

/** The frames' widths as a share of the tile's short side (see TileLook). */
private const val GLOSS_FRAME_SHARE = 0.045f
private const val PANEL_FRAME_SHARE = 0.022f
private const val INK_FRAME_SHARE = 0.028f
private const val MAT_SHARE = 0.055f
private val PANEL_FRAME = Color(0xFFB4BCC6)
private val PANEL_FRAME_NIGHT = Color(0xFF5E6772)

/** GLOSS: the frame in a dark theme (brushed silver), and the bevel's shade in a bright one. */
private val GLOSS_FRAME_NIGHT = Color(0xFFAEB8C4)
private val GLOSS_BEVEL = Color(0x2E3A4A5C)

/** GLOSS: one breath of the focus ring, in, and how far it fades at the bottom of it. */
private const val BREATHE_MS = 1_300
private const val BREATHE_DEPTH = 0.35f
private val BREATHE_CURVE = io.github.matiyaaa.fuse.ui.fuseline.CubicCurve(0.45f, 0f, 0.55f, 1f)

/** INK: how solid the outline is. NEON: the line's strength at rest, and its halo's width against it. */
private const val INK_ALPHA = 0.88f
private const val NEON_REST = 0.45f
private const val NEON_HALO = 4f

/** PRISM's frame as a share of the tile's short side, its halo's strength and its brightness at rest. */
private const val PRISM_FRAME_SHARE = 0.022f
private const val PRISM_HALO = 0.5f
private const val PRISM_REST = 0.7f

/** BRACKETS: each corner's arm as a share of the tile's short side. */
private const val BRACKET_ARM = 0.2f

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
