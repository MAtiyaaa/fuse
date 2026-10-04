package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.settings.QrCode
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkState
import kotlinx.coroutines.delay

/**
 * The keyboard's phone key: a code to scan with a phone's camera, which signs the phone in to Phone
 * Link without the password and opens its keyboard for the field on screen. The phone's own
 * keyboard then types here (autocorrect, dictation and paste included). The code works once, for
 * two minutes, and renews itself while this is open. Once a phone joins, this closes by itself.
 * With Phone Link off it offers to turn it on.
 */
@Composable
internal fun PhoneTypingOverlay(app: AppState) {
    val open = app.phoneTyping
    val link = app.phoneLink
    val state = link?.state?.collectAsState()?.value ?: PhoneLinkState()
    val enabled = app.store.prefs.collectAsState().value.phoneLinkEnabled
    val phones by RemoteInput.phones.collectAsState()
    val address = state.addresses.firstOrNull()
    var pairing by remember { mutableStateOf<String?>(null) }
    // Phones following the device when this opened; one more means the phone just joined.
    var baseline by remember { mutableIntStateOf(0) }
    var joined by remember { mutableStateOf(false) }

    fun close() {
        app.phoneTyping = false
        app.platform.sounds.play(SoundCue.CLOSE)
    }
    fun turnOn() = app.store.updatePrefs { it.copy(phoneLinkEnabled = true) }

    LaunchedEffect(open) {
        if (open) {
            app.platform.sounds.play(SoundCue.OPEN)
            baseline = RemoteInput.phones.value
            joined = false
        } else {
            pairing = null
        }
    }
    // A fresh code while this is open, before the last one runs out.
    LaunchedEffect(open, state.running, address) {
        if (!open || !state.running || address == null || link == null) return@LaunchedEffect
        while (true) {
            pairing = link.pairingLink(address)
            delay(PAIRING_RENEW_MS)
        }
    }
    // A phone joined: "Connected" for a moment, then back to the keyboard by itself.
    if (open && phones > baseline && !joined) joined = true
    LaunchedEffect(open, joined) {
        if (open && joined) {
            delay(JOINED_SHOWN_MS)
            app.phoneTyping = false
        }
    }

    if (open) {
        InputLayer(priority = LayerPriority.DIALOG + 4, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { close(); NavResult.CONSUMED }
                NavAction.SELECT -> { if (link != null && !enabled) turnOn(); NavResult.ACTIVATED }
                else -> NavResult.CONSUMED
            }
        }
    }

    Overlay(visible = open, onDismiss = { close() }, edge = OverlayEdge.CENTER) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val side = maxWidth >= SIDE_BY_SIDE_FROM && maxHeight >= SIDE_BY_SIDE_HEIGHT
            val qr = if (maxHeight < COMPACT_HEIGHT) QR_COMPACT else QR_SIZE
            Panel(Modifier.widthIn(max = minOf(if (side) WIDTH_WIDE else WIDTH_NARROW, room))) {
                val code: @Composable () -> Unit = {
                    PairingCode(app, link, enabled, state, address, pairing, qr, joined, onTurnOn = ::turnOn)
                }
                if (side) {
                    Row(Modifier.padding(Space.xl), verticalAlignment = Alignment.CenterVertically) {
                        code()
                        Spacer(Modifier.width(Space.xl))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Explainer(Modifier.fillMaxWidth(), centred = false)
                            Status(phones, joined)
                        }
                    }
                } else {
                    // Upright: what it is, the code, then whether the phone is with us.
                    Column(Modifier.padding(Space.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
                        Explainer(Modifier.fillMaxWidth(), centred = true)
                        code()
                        Status(phones, joined)
                    }
                }
            }
        }
    }
}

/** The code itself, or what stands in its way (Phone Link off, starting, no network). */
@Composable
private fun PairingCode(
    app: AppState,
    link: io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl?,
    enabled: Boolean,
    state: PhoneLinkState,
    address: String?,
    pairing: String?,
    size: Dp,
    joined: Boolean,
    onTurnOn: () -> Unit,
) {
    val modules = remember(link, pairing) { pairing?.let { link?.qr(it) } }
    Box(Modifier.width(size + Space.xl), contentAlignment = Alignment.Center) {
        when {
            link == null -> EmptyState(FuseIcons.Smartphone, "Not in this build", message = "Phone Link isn't part of this version of Fuse.", compact = true)
            !enabled -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                EmptyState(FuseIcons.Smartphone, "Phone Link is off", message = "It runs only while Fuse is open, for phones on the same Wi-Fi.", compact = true)
                FuseButton("Turn on", selected = true, onClick = onTurnOn, icon = FuseIcons.Power, kind = ButtonKind.PRIMARY, height = 40.dp)
            }
            state.error != null && !state.running -> EmptyState(FuseIcons.Warning, "Couldn't start", message = state.error.trimEnd('.') + ".", tint = Fuse.colors.danger, compact = true)
            state.running && address == null -> EmptyState(FuseIcons.WifiOff, "Not on a network", message = "Join Wi-Fi, then scan from a phone on the same network.", compact = true)
            modules == null -> Box(Modifier.size(size), contentAlignment = Alignment.Center) { Spinner(size = Size.iconL) }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(contentAlignment = Alignment.Center) {
                    QrCode(modules, size)
                    // Once the phone is in, the code steps back behind a tick.
                    if (joined) {
                        Box(
                            Modifier.size(size).clip(RoundedCornerShape(Radius.m)).background(Fuse.colors.surface.copy(alpha = 0.86f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(Size.thumbL).clip(CircleShape).background(Fuse.colors.accent), contentAlignment = Alignment.Center) {
                                FuseIcon(FuseIcons.Check, size = Size.iconXL, tint = Fuse.colors.onAccent)
                            }
                        }
                    }
                }
                FText(address!!.removePrefix("http://").trimEnd('/'), Fuse.type.caption.tabular(), color = Fuse.colors.textMuted, maxLines = 1, align = TextAlign.Center)
            }
        }
    }
}

/** What this is for and, with room, the three steps. */
@Composable
private fun Explainer(modifier: Modifier, centred: Boolean) {
    val c = Fuse.colors
    val align = if (centred) Alignment.CenterHorizontally else Alignment.Start
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(Modifier.size(Size.thumb).clip(RoundedCornerShape(Radius.m)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.Smartphone, size = Size.iconM, tint = c.accent)
        }
        Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            FText("Type on your phone", Fuse.type.title, maxLines = 1, align = if (centred) TextAlign.Center else TextAlign.Start)
            FText(
                "Scan the code with your phone's camera. Your phone's own keyboard types here, and paste works too.",
                Fuse.type.body, color = c.textMuted, maxLines = 3, align = if (centred) TextAlign.Center else TextAlign.Start,
            )
        }
        if (!centred) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Step(1, "Join the same Wi-Fi as this device")
                Step(2, "Scan the code, no password needed")
                Step(3, "Type, then press Done on your phone")
            }
        }
    }
}

/** Whether a phone is with us yet, and the way back. */
@Composable
private fun Status(phones: Int, joined: Boolean) {
    val c = Fuse.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (joined) true else null)
            Spacer(Modifier.width(Space.s))
            FText(
                when {
                    joined -> "Connected. The keyboard is open on your phone"
                    phones > 0 -> "A phone is connected. It has the keyboard too"
                    else -> "Waiting for your phone"
                },
                Fuse.type.label, color = if (joined) c.text else c.textMuted, maxLines = 2,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ButtonGlyph(HintButton.BACK, size = Size.glyphS)
            Spacer(Modifier.width(Space.xs + Space.xxs))
            FText("Back to the keyboard", Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/** A numbered step: its number in a small accent disc, then what to do. */
@Composable
private fun Step(n: Int, text: String) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Size.iconM).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            FText(n.toString(), Fuse.type.numericSmall, color = c.accent, maxLines = 1)
        }
        Spacer(Modifier.width(Space.m))
        FText(text, Fuse.type.label, color = c.textMuted, maxLines = 1)
    }
}

/** A code is good for two minutes; a new one comes a little before that. */
private const val PAIRING_RENEW_MS = 100_000L

/** How long "Connected" shows before this closes by itself. */
private const val JOINED_SHOWN_MS = 1_200L

/** The code's size, and on a short screen; the dialog's widths; where the code and the words sit side by side. */
private val QR_SIZE = 200.dp
private val QR_COMPACT = 150.dp
private val WIDTH_WIDE = 680.dp
private val WIDTH_NARROW = 420.dp
private val SIDE_BY_SIDE_FROM = 600.dp
private val SIDE_BY_SIDE_HEIGHT = 380.dp
private val COMPACT_HEIGHT = 560.dp
