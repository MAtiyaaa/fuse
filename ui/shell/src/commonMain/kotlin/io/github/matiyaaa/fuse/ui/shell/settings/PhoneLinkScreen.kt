package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.elevated
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkState
import kotlinx.coroutines.launch

/** Settings, Accounts, Phone Link: the switch, pairing, sign-in and what a phone may do. */
@Composable
fun phoneLinkRows(app: AppState): List<MenuAction> {
    val link = app.phoneLink ?: return listOf(
        infoRow("none", "Phone Link isn't part of this build", icon = FuseIcons.Smartphone),
    )
    val state by link.state.collectAsState()
    val on = app.store.prefs.collectAsState().value.phoneLinkEnabled
    return buildList {
        add(phoneLinkSwitch(app, on))
        add(MenuAction(
            "pair", "Pair a phone", FuseIcons.QrCode,
            detail = when {
                !on -> "Shows a QR code for your phone's camera, and the sign-in phones use"
                state.addresses.isNotEmpty() -> state.addresses.first().removePrefix("http://").trimEnd('/')
                else -> "Join a Wi-Fi network to pair a phone"
            },
            trailing = Trailing.Chevron,
            onSelect = { app.go(Route.PhoneLink) },
        ))
        addAll(phoneLinkAccountRows(app, link, state))
        labelled("") { addAll(phoneLinkInfoRows()) }
    }
}

private fun phoneLinkSwitch(app: AppState, on: Boolean) = toggleRow(
    "enabled", "Phone Link", FuseIcons.Smartphone, on,
    detail = "Manage your library from a phone on the same Wi-Fi while Fuse is open",
) { v -> app.store.updatePrefs { it.copy(phoneLinkEnabled = v) } }

private fun phoneLinkAccountRows(app: AppState, link: PhoneLinkControl, state: PhoneLinkState): List<MenuAction> = buildList {
    add(MenuAction(
        "account", "Sign-in", FuseIcons.User,
        detail = if (state.username == null) "Set the username and password phones sign in with"
        else "Changing the username or password signs every phone out",
        trailing = Trailing.Value(state.username ?: "Not set"),
        onSelect = { askUsername(app, link, state.username) },
    ))
    add(MenuAction(
        "sessions", "Signed-in phones", FuseIcons.Users,
        detail = if (state.sessions > 0) "Select to sign every phone out" else null,
        trailing = if (state.sessions > 0) Trailing.Badge(state.sessions.toString()) else Trailing.None,
        unavailableReason = if (state.sessions == 0) "No phone is signed in" else null,
        onSelect = {
            app.confirm = ConfirmSpec(
                title = "Sign out all phones?",
                message = "Every phone signed in to Phone Link needs the username and password again.",
                confirmLabel = "Sign out",
                destructive = false,
            ) {
                app.scope.launch {
                    link.signOutAll()
                    app.toasts.show("Every phone is signed out")
                }
            }
        },
    ))
    state.error?.let { add(infoRow("error", "Phone Link couldn't start", detail = it.trimEnd('.') + ". Turn Phone Link off and on again, or restart Fuse", icon = FuseIcons.Warning)) }
}

private fun phoneLinkInfoRows(): List<MenuAction> = listOf(
    infoRow(
        "can", "What a phone can do",
        detail = "See what's playing and downloading, browse and search your library, fix names, details and art, fill art, and download your screenshots and recordings",
        icon = FuseIcons.MonitorSmartphone,
    ),
    infoRow(
        "cannot", "What stays on this device",
        detail = "Phones can't delete games or captures, see keys or passwords, or change settings. Only phones on your network can connect",
        icon = FuseIcons.ShieldCheck,
    ),
)

/** Username first, then the password; both are needed because only a hash of the password is kept. */
private fun askUsername(app: AppState, link: PhoneLinkControl, current: String?) {
    app.textInput = TextInputSpec(
        title = "Phone Link username",
        initial = current.orEmpty(),
        placeholder = "Username",
        capitalize = false,
        doneLabel = "Next",
    ) { name ->
        val username = name.trim()
        if (username.isEmpty()) {
            app.toasts.show("A username is needed")
        } else {
            askPassword(app, link, username)
        }
    }
}

private fun askPassword(app: AppState, link: PhoneLinkControl, username: String) {
    app.textInput = TextInputSpec(
        title = "Password for $username",
        initial = "",
        placeholder = "At least 6 characters",
        secret = true,
        capitalize = false,
        doneLabel = "Save",
    ) { password ->
        app.scope.launch {
            link.setAccount(username, password)
                .onSuccess { app.toasts.show("Phones can now sign in as $username") }
                .onFailure {
                    app.toasts.show(it.message ?: "Couldn't save the sign-in")
                    askPassword(app, link, username)
                }
        }
    }
}

/**
 * Pair a phone: a QR code for the phone's camera with the address beside it, the sign-in and what
 * a phone may do. The code only holds the address; signing in still needs the username and password
 * set here.
 */
@Composable
fun PhoneLinkScreen(app: AppState) {
    val link = app.phoneLink
    val state = link?.state?.collectAsState()?.value ?: PhoneLinkState()
    val on = app.store.prefs.collectAsState().value.phoneLinkEnabled
    val sel = rememberRouteState(app.navigator, "phonelink") { LinearSelection() }
    var addressIndex by remember { mutableStateOf(0) }
    val address = state.addresses.getOrNull(addressIndex) ?: state.addresses.firstOrNull()

    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Select"), Hint(HintButton.BACK, "Back"))
    }

    val rows = buildList {
        add(phoneLinkSwitch(app, on))
        if (link != null) {
            addAll(phoneLinkAccountRows(app, link, state))
            if (state.addresses.size > 1) {
                add(MenuAction(
                    "address", "Address in the code", FuseIcons.Network,
                    detail = "This device is on more than one network. Pick the one your phone is on",
                    trailing = Trailing.Value(address?.removePrefix("http://")?.trimEnd('/') ?: ""),
                    onSelect = {
                        app.choice = ChoiceSpec(
                            title = "Address in the code",
                            options = state.addresses.mapIndexed { i, a ->
                                MenuAction("a$i", a.removePrefix("http://").trimEnd('/'), null, trailing = Trailing.Check(a == address), onSelect = {
                                    addressIndex = i
                                    app.choice = null
                                })
                            },
                        )
                    },
                ))
            }
        }
        labelled("") { addAll(phoneLinkInfoRows()) }
    }
    sel.clamp(rows.size)

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, rows, sel) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > WIDE_FROM
        val short = maxHeight < SHORT_BELOW
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) Space.gutter else Space.gutterCompact)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            SettingsPageHeading("Phone Link", "Your library from a phone on the same Wi-Fi", short, Modifier.reveal(0), stacked = !wide) {
                LinkStatus(on, state, address)
            }
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            if (wide) {
                Row(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    Panel(Modifier.weight(0.42f).fillMaxHeight().reveal(1)) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(Space.l), contentAlignment = Alignment.Center) {
                            // Short screens (a handheld) keep the code big and leave out the numbered steps.
                            val steps = maxHeight >= STEPS_FROM
                            val qr = minOf(maxWidth, maxHeight - if (steps) STEPS_ROOM else ADDRESS_ROOM).coerceIn(QR_MIN, QR_MAX)
                            PairingCard(link, on, state, address, qr, steps, Modifier.fillMaxWidth())
                        }
                    }
                    Panel(Modifier.weight(0.58f).fillMaxHeight().reveal(2)) {
                        MenuList(
                            rows, sel,
                            showSelection = app.focusZone == FocusZone.CONTENT,
                            modifier = Modifier.padding(Space.s),
                            fadeEdges = true,
                        )
                    }
                }
            } else {
                // The code stays in view above the rows; the steps give way to one line.
                Column(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    Panel(Modifier.fillMaxWidth().reveal(1)) {
                        PairingCard(link, on, state, address, QR_COMPACT, false, Modifier.fillMaxWidth().padding(Space.l))
                    }
                    Panel(Modifier.fillMaxWidth().weight(1f).reveal(2)) {
                        MenuList(
                            rows, sel,
                            showSelection = app.focusZone == FocusZone.CONTENT,
                            modifier = Modifier.padding(Space.s),
                            fadeEdges = true,
                        )
                    }
                }
            }
        }
    }
}

/** Whether Phone Link is up: a dot (a ring when it failed to start) and a few words. */
@Composable
private fun LinkStatus(on: Boolean, state: PhoneLinkState, address: String?) {
    val ok = when {
        !on -> null
        state.error != null && !state.running -> false
        state.running && address != null -> true
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(ok)
        Spacer(Modifier.width(Space.s))
        FText(statusLine(on, state, address), Fuse.type.label, color = if (ok == true) Fuse.colors.text else Fuse.colors.textMuted, maxLines = 1)
    }
}

private fun statusLine(on: Boolean, state: PhoneLinkState, address: String?): String = when {
    !on -> "Off"
    !state.running && state.error != null -> "Couldn't start"
    !state.running -> "Starting"
    address == null -> "On, waiting for a network"
    state.username == null -> "On. Set a sign-in to pair a phone"
    else -> "On"
}

/**
 * The code to scan with the address under it and the steps, or (when there can't be a code yet)
 * what is missing and what to do about it, in the shape every empty state has.
 */
@Composable
private fun PairingCard(link: PhoneLinkControl?, on: Boolean, state: PhoneLinkState, address: String?, qrSize: Dp, steps: Boolean, modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        val modules = remember(link, address) { if (link != null && address != null) link.qr(address) else null }
        when {
            link == null -> EmptyState(FuseIcons.Smartphone, "Not in this build", message = "Phone Link isn't part of this version of Fuse.", compact = true)
            !on -> EmptyState(FuseIcons.Smartphone, "Phone Link is off", message = "Turn it on to pair a phone. It only runs while Fuse is open.", compact = true)
            state.error != null && !state.running -> EmptyState(
                FuseIcons.Warning, "Couldn't start",
                message = state.error.trimEnd('.') + ". Turn Phone Link off and on again, or restart Fuse.",
                tint = Fuse.colors.danger, compact = true,
            )
            !state.running -> EmptyState(FuseIcons.Hourglass, "Starting", message = "One moment.", compact = true)
            address == null || modules == null -> EmptyState(FuseIcons.WifiOff, "Not on a network", message = "Join Wi-Fi, then pair from a phone on the same network.", compact = true)
            else -> {
                QrCode(modules, qrSize)
                Spacer(Modifier.height(Space.xxs))
                FText(address.removePrefix("http://").trimEnd('/'), Fuse.type.titleSmall.tabular(), maxLines = 1, align = TextAlign.Center)
                if (steps) {
                    Steps(state.username)
                } else {
                    FText(
                        if (state.username == null) "Set a sign-in, then scan on the same Wi-Fi" else "Scan on the same Wi-Fi, then sign in as ${state.username}",
                        Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 2, align = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Steps(username: String?) {
    Column(Modifier.fillMaxWidth().padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Step(1, "Join the same Wi-Fi as this device")
        Step(2, "Scan the code with your phone's camera")
        Step(3, if (username == null) "Set a sign-in here first" else "Sign in as $username", attention = username == null)
    }
}

/** A numbered step: its number in a small accent disc, then what to do. */
@Composable
private fun Step(n: Int, text: String, attention: Boolean = false) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Size.iconM).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            FText(n.toString(), Fuse.type.numericSmall, color = c.accent, maxLines = 1)
        }
        Spacer(Modifier.width(Space.m))
        FText(text, Fuse.type.label, color = if (attention) c.text else c.textMuted, maxLines = 1)
    }
}

/**
 * The code as dark modules on white with a quiet zone, whatever the theme: phone cameras read codes
 * best that way. It sits on the panel as a small lit card. Modules are whole pixels, so their edges
 * stay crisp for scanning.
 */
@Composable
private fun QrCode(modules: List<BooleanArray>, size: Dp) {
    val shape = RoundedCornerShape(Radius.m)
    Box(
        Modifier
            .size(size)
            .elevated(Elevation.raised, shape, fill = QR_PAPER)
            .padding(size * 0.06f),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = modules.size
            if (n == 0) return@Canvas
            val cell = this.size.minDimension / n
            val px = kotlin.math.floor(cell).coerceAtLeast(1f)
            val offset = (this.size.minDimension - px * n) / 2f
            for (y in 0 until n) {
                val row = modules[y]
                for (x in 0 until n) {
                    if (row[x]) {
                        drawRect(QR_INK, topLeft = Offset(offset + x * px, offset + y * px), size = androidx.compose.ui.geometry.Size(px, px))
                    }
                }
            }
        }
    }
}

/** A QR code is always dark on white so any camera reads it, so these two colours are fixed. */
private val QR_PAPER = Color.White
private val QR_INK = Color(0xFF101114)

private val WIDE_FROM = 760.dp
private val SHORT_BELOW = 560.dp

/** The numbered steps show when the card is at least this tall; room kept under the code for them, or for the address alone. */
private val STEPS_FROM = 400.dp
private val STEPS_ROOM = 156.dp
private val ADDRESS_ROOM = 48.dp

/** The code's size: as large as the card allows within these, and on narrow screens. */
private val QR_MIN = 112.dp
private val QR_MAX = 300.dp
private val QR_COMPACT = 168.dp
