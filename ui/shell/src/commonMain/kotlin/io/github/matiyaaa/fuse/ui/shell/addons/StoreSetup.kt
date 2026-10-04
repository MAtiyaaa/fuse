package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.StoreVariant
import io.github.matiyaaa.fuse.ui.designsystem.components.Badge
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import kotlinx.coroutines.launch

/** What each edition is, in a line. */
internal fun StoreVariant.title(): String = when (this) {
    StoreVariant.STANDARD -> "Standard"
    StoreVariant.DUAL_SCREEN -> "Dual-Screen"
}

internal fun StoreVariant.detail(): String = when (this) {
    StoreVariant.STANDARD -> "For phones, tablets and handhelds with one screen."
    StoreVariant.DUAL_SCREEN -> "For devices with a second screen, with apps and builds that use it."
}

/**
 * The Store's first page: which edition of the Obtainium Emulation Pack to follow. Two large cards,
 * each with its device drawn, the one that suits this device marked. The choice is kept and can be
 * changed in Settings, Store.
 */
@Composable
internal fun StoreSetup(app: AppState, recommended: StoreVariant, active: Boolean, topPadding: Dp) {
    val c = Fuse.colors
    val options = StoreVariant.entries
    var chosen by remember { mutableStateOf(options.indexOf(recommended)) }
    val focused = active && app.focusZone == FocusZone.CONTENT
    fun choose(v: StoreVariant) {
        app.scope.launch { app.store.appStore.chooseVariant(v) }
    }
    LaunchedEffect(focused) {
        if (focused) app.hints = listOf(Hint(HintButton.CONFIRM, "Choose"))
    }
    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT -> if (chosen > 0) { chosen--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> if (chosen < options.lastIndex) { chosen++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.SELECT -> { choose(options[chosen]); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }
    // Everything fits the screen it is on: the cards take the room left under the words, and on a
    // short screen the words get smaller and fewer, never pushed off the bottom.
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = topPadding), contentAlignment = Alignment.TopCenter) {
        val short = maxHeight < SHORT
        val tiny = maxHeight < TINY
        Column(
            Modifier.widthIn(max = 960.dp).fillMaxSize()
                .padding(start = Space.gutter, end = Space.gutter, top = if (short) Space.xs else Space.l, bottom = Size.hintHeight + Space.s),
            horizontalAlignment = Alignment.Start,
        ) {
            if (!short) {
                FText("STORE", Fuse.type.overline, color = c.textMuted, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
            }
            FText("Choose your Store", if (short) Fuse.type.title else Fuse.type.display, maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            FText(
                if (tiny) "Pick the edition for this device. You can change it later in Settings."
                else "The Store installs emulators and gaming apps from the Obtainium Emulation Pack, a list its community keeps current. Pick the edition for this device; you can change it later in Settings.",
                if (short) Fuse.type.caption else Fuse.type.body, color = c.textMuted, maxLines = if (short) 2 else 3,
            )
            Spacer(Modifier.height(if (short) Space.m else Space.xl))
            // The cards share what is left, up to a comfortable size, with room under them for the
            // chosen card's lift and focus mark.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                Row(
                    Modifier.fillMaxWidth().heightIn(max = CARD_MAX).fillMaxHeight().padding(bottom = Space.l),
                    // Clear room between the cards, also when the chosen one lifts.
                    horizontalArrangement = Arrangement.spacedBy(if (short) Space.xl else Space.xxl),
                ) {
                    options.forEachIndexed { i, v ->
                        EditionCard(
                            v,
                            recommended = v == recommended,
                            selected = focused && chosen == i,
                            compact = short,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { chosen = i; app.focusZone = FocusZone.CONTENT; choose(v) }
                    }
                }
            }
            if (!tiny) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.ShieldCheck, size = Size.iconS, tint = c.textFaint)
                    Spacer(Modifier.size(Space.s))
                    FText(
                        "Apps install through Android's own installer, which asks you every time.",
                        Fuse.type.caption, color = c.textMuted, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Below this height the page's words get smaller; below [TINY] they get fewer. */
private val SHORT = 560.dp
private val TINY = 400.dp
private val CARD_MAX = 320.dp

@Composable
private fun EditionCard(variant: StoreVariant, recommended: Boolean, selected: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    // Everything here follows the theme: the accent lights the screens, the ink is the text colour.
    val accent = c.accent
    val shape = remember { SquircleShape.fraction(0.08f) }
    val lit by io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat(if (selected) 1f else 0f, Fuse.motion.focusSpring(), label = "edition")
    Tile(selected = selected, modifier = modifier, shape = shape, cornerFraction = 0.08f, glow = accent, maxGrow = 8.dp, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.surfaceRaised, c.surface))))
        Column(Modifier.fillMaxSize().padding(if (compact) Space.m else Space.l)) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.10f + 0.10f * lit), Color.Transparent)),
                    shape = SquircleShape.fraction(0.12f),
                ),
                contentAlignment = Alignment.Center,
            ) {
                // The drawing keeps its shape inside whatever room the card has.
                Device(variant, ink = c.text, body = c.surfaceRaised, accent = accent, lit = lit, modifier = Modifier.fillMaxSize(0.82f))
            }
            Spacer(Modifier.height(if (compact) Space.s else Space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(variant.title(), if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (recommended) {
                    Spacer(Modifier.width(Space.s))
                    Badge("Recommended here", color = accent, icon = FuseIcons.Sparkle)
                }
            }
            FText(variant.detail(), Fuse.type.caption, color = c.textMuted, maxLines = if (compact) 1 else 2, minLines = if (compact) 1 else 2)
        }
    }
}

/**
 * A drawn handheld: one wide screen between the controls, or a clamshell with two screens. The body
 * is a panel of the theme, the outline its text colour, and the screens glow in its accent with a
 * hint of Fuse on them (a row of tiles, the selected one lit); [lit] brightens them as the card is
 * chosen.
 */
@Composable
private fun Device(variant: StoreVariant, ink: Color, body: Color, accent: Color, lit: Float, modifier: Modifier) {
    val ratio = if (variant == StoreVariant.STANDARD) 2.1f else 1.25f
    Canvas(modifier.wrapContentSize().aspectRatio(ratio, matchHeightConstraintsFirst = true)) {
        val line = 1.6.dp.toPx()
        val outline = ink.copy(alpha = 0.42f + 0.18f * lit)
        val parts = ink.copy(alpha = 0.30f + 0.12f * lit)
        fun shell(at: Offset, size: GeoSize, radius: Float) {
            val r = CornerRadius(radius)
            drawRoundRect(Brush.verticalGradient(listOf(lerp(body, ink, 0.06f), body), startY = at.y, endY = at.y + size.height), at, size, r)
            drawRoundRect(outline, at, size, r, style = Stroke(line))
        }
        when (variant) {
            StoreVariant.STANDARD -> {
                val h = size.height * 0.8f
                val top = (size.height - h) / 2
                shell(Offset(0f, top), GeoSize(size.width, h), h * 0.42f)
                val screen = GeoSize(size.width * 0.54f, h * 0.74f)
                val at = Offset((size.width - screen.width) / 2, top + (h - screen.height) / 2)
                glass(at, screen, accent, ink, lit, line, tiles = 4)
                controls(Offset(size.width * 0.115f, top + h * 0.5f), h * 0.15f, parts, accent, Offset(size.width * 0.885f, top + h * 0.5f))
            }
            StoreVariant.DUAL_SCREEN -> {
                val w = size.width
                val half = size.height * 0.47f
                // Lid with the main screen, a slim hinge, then the base with the second screen.
                shell(Offset(w * 0.06f, 0f), GeoSize(w * 0.88f, half), half * 0.16f)
                glass(Offset(w * 0.13f, half * 0.12f), GeoSize(w * 0.74f, half * 0.76f), accent, ink, lit, line, tiles = 3)
                drawRoundRect(parts, Offset(w * 0.32f, half + size.height * 0.012f), GeoSize(w * 0.36f, size.height * 0.036f), CornerRadius(size.height * 0.018f))
                val baseTop = size.height - half
                shell(Offset(0f, baseTop), GeoSize(w, half), half * 0.16f)
                glass(Offset(w * 0.3f, baseTop + half * 0.16f), GeoSize(w * 0.4f, half * 0.68f), accent, ink, lit * 0.7f, line, tiles = 0)
                controls(Offset(w * 0.15f, baseTop + half * 0.5f), half * 0.15f, parts, accent, Offset(w * 0.85f, baseTop + half * 0.5f))
            }
        }
    }
}

/**
 * A screen lit in [accent]: a glow from its top, a row of [tiles] (Fuse's shelf, the first one
 * selected), or with none a companion screen's clock and two lines.
 */
private fun DrawScope.glass(at: Offset, size: GeoSize, accent: Color, ink: Color, lit: Float, line: Float, tiles: Int) {
    val r = CornerRadius(size.height * 0.08f)
    drawRoundRect(Color.Black.copy(alpha = 0.55f), at, size, r)
    drawRoundRect(
        Brush.verticalGradient(listOf(accent.copy(alpha = 0.38f + 0.22f * lit), accent.copy(alpha = 0.08f)), startY = at.y, endY = at.y + size.height),
        at, size, r,
    )
    drawRoundRect(ink.copy(alpha = 0.22f), at, size, r, style = Stroke(line))
    val pad = size.height * 0.16f
    if (tiles > 0) {
        val gap = size.width * 0.035f
        val tileW = (size.width - pad * 2 - gap * (tiles - 1)) / tiles
        val tileH = minOf(tileW * 1.05f, size.height * 0.5f)
        val y = at.y + size.height - pad - tileH
        for (i in 0 until tiles) {
            val x = at.x + pad + i * (tileW + gap)
            val selected = i == 0
            drawRoundRect(
                if (selected) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.16f),
                Offset(x, y), GeoSize(tileW, tileH), CornerRadius(tileW * 0.18f),
            )
        }
        // The title line above the shelf.
        drawRoundRect(Color.White.copy(alpha = 0.42f), Offset(at.x + pad, at.y + pad), GeoSize(size.width * 0.32f, size.height * 0.07f), CornerRadius(size.height * 0.035f))
    } else {
        val cx = at.x + size.width * 0.28f
        val cy = at.y + size.height * 0.5f
        drawCircle(Color.White.copy(alpha = 0.55f), size.height * 0.2f, Offset(cx, cy), style = Stroke(line))
        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(at.x + size.width * 0.5f, cy - size.height * 0.14f), GeoSize(size.width * 0.34f, size.height * 0.09f), CornerRadius(size.height * 0.045f))
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(at.x + size.width * 0.5f, cy + size.height * 0.05f), GeoSize(size.width * 0.24f, size.height * 0.09f), CornerRadius(size.height * 0.045f))
    }
}

/** A filled D-pad at [dpad] and four face buttons at [buttonsAt], each [arm] long; the confirm button in [accent]. */
private fun DrawScope.controls(dpad: Offset, arm: Float, ink: Color, accent: Color, buttonsAt: Offset) {
    val t = arm * 0.64f
    drawRoundRect(ink, Offset(dpad.x - arm, dpad.y - t / 2), GeoSize(arm * 2, t), CornerRadius(t / 3))
    drawRoundRect(ink, Offset(dpad.x - t / 2, dpad.y - arm), GeoSize(t, arm * 2), CornerRadius(t / 3))
    val d = arm * 0.86f
    val radius = arm * 0.36f
    listOf(Offset(0f, -d), Offset(d, 0f), Offset(0f, d), Offset(-d, 0f)).forEachIndexed { i, o ->
        drawCircle(if (i == 2) accent else ink, radius, buttonsAt + o)
    }
}

