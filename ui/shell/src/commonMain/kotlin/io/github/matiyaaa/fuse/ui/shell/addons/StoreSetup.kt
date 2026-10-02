package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topPadding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l),
            horizontalAlignment = Alignment.Start,
        ) {
            FText("STORE", Fuse.type.overline, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            FText("Choose your Store", Fuse.type.display, maxLines = 1)
            Spacer(Modifier.height(Space.s))
            FText(
                "The Store installs emulators and gaming apps from the Obtainium Emulation Pack, a list its community keeps current. Pick the edition for this device; you can change it later in Settings.",
                Fuse.type.body, color = c.textMuted, maxLines = 3,
            )
            Spacer(Modifier.height(Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                options.forEachIndexed { i, v ->
                    EditionCard(
                        v,
                        recommended = v == recommended,
                        selected = focused && chosen == i,
                        modifier = Modifier.weight(1f),
                    ) { chosen = i; app.focusZone = FocusZone.CONTENT; choose(v) }
                }
            }
            // Room for the chosen card's lift and its focus mark.
            Spacer(Modifier.height(Space.xxl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(FuseIcons.ShieldCheck, size = Size.iconS, tint = c.textFaint)
                Spacer(Modifier.size(Space.s))
                FText(
                    "Apps install through Android's own installer, which asks you every time.",
                    Fuse.type.caption, color = c.textMuted, maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun EditionCard(variant: StoreVariant, recommended: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val tint = STORE_TINT.toColor()
    val shape = remember { SquircleShape.fraction(0.08f) }
    Tile(selected = selected, modifier = modifier.height(300.dp), shape = shape, cornerFraction = 0.08f, glow = tint, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.surfaceRaised, c.surface))))
        Column(Modifier.fillMaxSize().padding(Space.l)) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent)),
                    shape = SquircleShape.fraction(0.12f),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Device(variant, lerp(c.text, tint, 0.25f), tint, Modifier.fillMaxHeight(0.8f))
                if (recommended) {
                    Badge("Recommended here", Modifier.align(Alignment.TopEnd), color = tint, icon = FuseIcons.Sparkle)
                }
            }
            Spacer(Modifier.height(Space.m))
            FText(variant.title(), Fuse.type.title, maxLines = 1)
            FText(variant.detail(), Fuse.type.caption, color = c.textMuted, maxLines = 2, minLines = 2)
        }
    }
}

/** A drawn device: one wide screen with controls, or two screens on a hinge. */
@Composable
private fun Device(variant: StoreVariant, ink: Color, tint: Color, modifier: Modifier) {
    val ratio = if (variant == StoreVariant.STANDARD) 2.1f else 1.25f
    Canvas(modifier.aspectRatio(ratio)) {
        val stroke = Stroke(width = 2.dp.toPx())
        val glass = tint.copy(alpha = 0.22f)
        when (variant) {
            StoreVariant.STANDARD -> {
                val body = GeoSize(size.width, size.height * 0.78f)
                val top = (size.height - body.height) / 2
                val r = CornerRadius(body.height * 0.42f)
                drawRoundRect(ink.copy(alpha = 0.08f), Offset(0f, top), body, r)
                drawRoundRect(ink, Offset(0f, top), body, r, style = stroke)
                val screen = GeoSize(body.width * 0.52f, body.height * 0.72f)
                val sx = (size.width - screen.width) / 2
                val sy = top + (body.height - screen.height) / 2
                screen(Offset(sx, sy), screen, glass, ink, stroke)
                controls(Offset(body.width * 0.12f, top + body.height * 0.5f), body.height * 0.15f, ink, stroke, buttonsAt = Offset(body.width * 0.88f, top + body.height * 0.5f))
            }
            StoreVariant.DUAL_SCREEN -> {
                val w = size.width
                val half = size.height * 0.47f
                val r = CornerRadius(half * 0.16f)
                // Lid with the main screen.
                drawRoundRect(ink.copy(alpha = 0.08f), Offset(w * 0.06f, 0f), GeoSize(w * 0.88f, half), r)
                drawRoundRect(ink, Offset(w * 0.06f, 0f), GeoSize(w * 0.88f, half), r, style = stroke)
                screen(Offset(w * 0.14f, half * 0.12f), GeoSize(w * 0.72f, half * 0.76f), glass, ink, stroke)
                // Hinge.
                drawRoundRect(ink.copy(alpha = 0.5f), Offset(w * 0.3f, half), GeoSize(w * 0.4f, size.height * 0.06f - 1f), CornerRadius(4f))
                // Base with the second screen between the controls.
                val baseTop = size.height - half
                drawRoundRect(ink.copy(alpha = 0.08f), Offset(0f, baseTop), GeoSize(w, half), r)
                drawRoundRect(ink, Offset(0f, baseTop), GeoSize(w, half), r, style = stroke)
                screen(Offset(w * 0.3f, baseTop + half * 0.16f), GeoSize(w * 0.4f, half * 0.68f), glass.copy(alpha = 0.16f), ink, stroke)
                controls(Offset(w * 0.15f, baseTop + half * 0.5f), half * 0.15f, ink, stroke, buttonsAt = Offset(w * 0.85f, baseTop + half * 0.5f))
            }
        }
    }
}

private fun DrawScope.screen(at: Offset, size: GeoSize, glass: Color, ink: Color, stroke: Stroke) {
    val r = CornerRadius(size.height * 0.06f)
    drawRoundRect(glass, at, size, r)
    drawRoundRect(ink.copy(alpha = 0.7f), at, size, r, style = stroke)
    // A soft reflection across the glass.
    drawLine(Color.White.copy(alpha = 0.18f), Offset(at.x + size.width * 0.12f, at.y + size.height * 0.2f), Offset(at.x + size.width * 0.32f, at.y + size.height * 0.2f), strokeWidth = stroke.width)
}

/** A D-pad at [dpad] and four face buttons at [buttonsAt], each [arm] long. */
private fun DrawScope.controls(dpad: Offset, arm: Float, ink: Color, stroke: Stroke, buttonsAt: Offset) {
    val t = arm * 0.62f
    drawRoundRect(ink, Offset(dpad.x - arm, dpad.y - t / 2), GeoSize(arm * 2, t), CornerRadius(t / 4), style = stroke)
    drawRoundRect(ink, Offset(dpad.x - t / 2, dpad.y - arm), GeoSize(t, arm * 2), CornerRadius(t / 4), style = stroke)
    val d = arm * 0.85f
    val radius = arm * 0.38f
    listOf(Offset(0f, -d), Offset(d, 0f), Offset(0f, d), Offset(-d, 0f)).forEach { o ->
        drawCircle(ink, radius, buttonsAt + o, style = stroke)
    }
}

