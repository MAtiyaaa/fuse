package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import kotlinx.coroutines.delay

/** The four face buttons as the pad reports them. */
private val faceButtons = setOf(PadButton.A, PadButton.B, PadButton.X, PadButton.Y)

private enum class DetectStage { RIGHT_BUTTON, CONFIRM, DONE }

/**
 * Works out how the pad's buttons are labelled and which one confirms, from two presses: the right
 * face button (tells whether the pad sends Nintendo or Xbox keycodes) and the button the user wants
 * to confirm with. Every press goes only here while it runs, so no button can leave the screen.
 */
internal fun detectedProfile(current: InputProfile, rightButton: PadButton, confirmButton: PadButton): InputProfile {
    // Pads that send Nintendo keycodes report their right face button as A.
    val glyphs = when {
        rightButton == PadButton.A -> GlyphStyle.NINTENDO
        current.glyphs == GlyphStyle.PLAYSTATION -> GlyphStyle.PLAYSTATION
        else -> GlyphStyle.XBOX
    }
    return current.copy(
        nintendoLayout = false,
        glyphs = glyphs,
        swapConfirmBack = confirmButton == PadButton.B,
    )
}

@Composable
fun ButtonDetectOverlay(app: AppState) {
    val open = app.buttonDetect
    val router = LocalInputRouter.current
    var stage by remember(open) { mutableStateOf(DetectStage.RIGHT_BUTTON) }
    var right by remember(open) { mutableStateOf<PadButton?>(null) }
    var result by remember(open) { mutableStateOf<InputProfile?>(null) }
    var notice by remember(open) { mutableStateOf<String?>(null) }
    var heldSince by remember(open) { mutableStateOf<PadButton?>(null) }

    fun close() {
        app.buttonDetect = false
    }

    DisposableEffect(open) {
        if (open) {
            router.exclusive = { button, down ->
                if (down) {
                    heldSince = button
                    when (stage) {
                        DetectStage.RIGHT_BUTTON -> if (button in faceButtons) {
                            right = button
                            notice = null
                            stage = DetectStage.CONFIRM
                        } else {
                            notice = "That was not a face button. Press the right one of the four."
                        }
                        DetectStage.CONFIRM -> if (button == PadButton.A || button == PadButton.B) {
                            val profile = detectedProfile(app.store.prefs.value.input, right ?: PadButton.B, button)
                            app.store.updatePrefs { it.copy(input = profile) }
                            result = profile
                            notice = null
                            stage = DetectStage.DONE
                        } else {
                            notice = "Use the button you press to say yes, usually labelled A."
                        }
                        DetectStage.DONE -> close()
                    }
                } else if (heldSince == button) {
                    heldSince = null
                }
            }
        }
        onDispose { if (open) router.exclusive = null }
    }
    // Holding any button for two seconds leaves, so nobody is ever stuck here.
    LaunchedEffect(heldSince) {
        if (heldSince != null && stage != DetectStage.DONE) {
            delay(2_000)
            close()
        }
    }

    val motion = Fuse.motion
    Overlay(visible = open, onDismiss = ::close, edge = OverlayEdge.CENTER) {
        Panel(Modifier.widthIn(min = 460.dp, max = 560.dp)) {
            Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(stage, transitionSpec = { fadeIn(motion.fade(180)) togetherWith fadeOut(motion.fade(120)) }, label = "detect") { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FacePad(highlight = when (s) {
                            DetectStage.RIGHT_BUTTON -> Face.RIGHT
                            DetectStage.CONFIRM -> null
                            DetectStage.DONE -> if (result?.confirmOnRight == true) Face.RIGHT else Face.BOTTOM
                        })
                        Spacer(Modifier.height(Space.l))
                        FText(
                            when (s) {
                                DetectStage.RIGHT_BUTTON -> "Press the right face button"
                                DetectStage.CONFIRM -> "Now press your confirm button"
                                DetectStage.DONE -> "All set"
                            },
                            Fuse.type.title,
                        )
                        Spacer(Modifier.height(Space.s))
                        FText(
                            when (s) {
                                DetectStage.RIGHT_BUTTON -> "The one on the right of the four face buttons, whatever its letter. This tells Fuse how your pad reports its buttons."
                                DetectStage.CONFIRM -> "The button you use to say yes and open things. The button next to it becomes Back."
                                DetectStage.DONE -> result?.let(::describe).orEmpty()
                            },
                            Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 4,
                        )
                    }
                }
                notice?.let {
                    Spacer(Modifier.height(Space.m))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FuseIcon(FuseIcons.Info, size = 16.dp, tint = Fuse.colors.warning)
                        Spacer(Modifier.size(Space.s))
                        FText(it, Fuse.type.caption, color = Fuse.colors.warning, maxLines = 2)
                    }
                }
                Spacer(Modifier.height(Space.xl))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    FText(
                        if (stage == DetectStage.DONE) "Press any button to close" else "Hold any button to cancel",
                        Fuse.type.caption, color = Fuse.colors.textFaint,
                    )
                    FuseButton(if (stage == DetectStage.DONE) "Done" else "Cancel", selected = true, onClick = ::close, kind = if (stage == DetectStage.DONE) ButtonKind.PRIMARY else ButtonKind.GHOST)
                }
            }
        }
    }
}

private fun describe(p: InputProfile): String {
    val layout = when (p.glyphs) {
        GlyphStyle.NINTENDO -> "Nintendo layout"
        GlyphStyle.PLAYSTATION -> "PlayStation layout"
        else -> "Xbox layout"
    }
    val side = if (p.confirmOnRight) "right" else "bottom"
    return "$layout. Confirm is the $side button and Back is next to it. You can change this in Settings, Inputs."
}

private enum class Face { TOP, LEFT, RIGHT, BOTTOM }

/** A small diamond of four face buttons with one lit, drawn in Fuse's own style. */
@Composable
private fun FacePad(highlight: Face?) {
    val c = Fuse.colors
    @Composable
    fun Dot(face: Face) {
        val on = face == highlight
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(if (on) c.accent else c.text.copy(alpha = 0.08f))
                .border(1.dp, if (on) c.accent else c.text.copy(alpha = 0.22f), CircleShape),
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Dot(Face.TOP)
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) { Dot(Face.LEFT); Dot(Face.RIGHT) }
        Dot(Face.BOTTOM)
    }
}
