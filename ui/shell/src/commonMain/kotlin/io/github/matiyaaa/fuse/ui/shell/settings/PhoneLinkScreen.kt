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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
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

/** Settings, Phone Link: the switch, pairing, sign-in and what a phone may do. */
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
        addAll(phoneLinkInfoRows())
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
        trailing = Trailing.Value(state.sessions.toString()),
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
    state.error?.let { add(infoRow("error", "Phone Link couldn't start", detail = it, icon = FuseIcons.Warning)) }
}

private fun phoneLinkInfoRows(): List<MenuAction> = listOf(
    infoRow(
        "can", "What a phone can do",
        detail = "See what's playing and downloading, browse and search your library, fix names, details and art, and fill art",
        icon = FuseIcons.Smartphone,
    ),
    infoRow(
        "cannot", "What stays on this device",
        detail = "Phones can't delete games, see keys or passwords, or change settings. Only phones on your network can connect",
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
        addAll(phoneLinkInfoRows())
    }
    sel.clamp(rows.size)

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, rows, sel) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 760.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            Row(verticalAlignment = Alignment.Bottom) {
                FText("Phone Link", Fuse.type.title, maxLines = 1)
                Spacer(Modifier.width(Space.l))
                FText(statusLine(on, state, address), Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
            }
            Spacer(Modifier.height(Space.l))
            if (wide) {
                Row(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight()) {
                        // Short screens (a handheld) keep the code big and leave out the numbered steps.
                        val steps = maxHeight >= 400.dp
                        val qr = minOf(maxWidth - Space.l * 2, maxHeight - if (steps) 184.dp else 132.dp).coerceIn(112.dp, 300.dp)
                        PairingCard(link, on, state, address, qr, steps, Modifier.fillMaxWidth())
                    }
                    MenuList(rows, sel, modifier = Modifier.weight(0.58f), showSelection = app.focusZone == FocusZone.CONTENT)
                }
            } else {
                MenuList(
                    rows, sel, modifier = Modifier.fillMaxWidth(), showSelection = app.focusZone == FocusZone.CONTENT,
                    header = { Column { PairingCard(link, on, state, address, 200.dp, true, Modifier.fillMaxWidth()); Spacer(Modifier.height(Space.l)) } },
                )
            }
        }
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

/** The code to scan, or what's missing before there can be one. */
@Composable
private fun PairingCard(link: PhoneLinkControl?, on: Boolean, state: PhoneLinkState, address: String?, qrSize: Dp, steps: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    Column(
        modifier.clip(RoundedCornerShape(Fuse.geometry.control)).background(c.text.copy(alpha = 0.06f)).padding(Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        val modules = remember(link, address) { if (link != null && address != null) link.qr(address) else null }
        when {
            link == null -> Waiting(FuseIcons.Smartphone, "Not in this build", "Phone Link isn't part of this version of Fuse.", qrSize)
            !on -> Waiting(FuseIcons.Smartphone, "Phone Link is off", "Turn it on to pair a phone. It only runs while Fuse is open.", qrSize)
            state.error != null && !state.running -> Waiting(FuseIcons.Warning, "Couldn't start", state.error, qrSize)
            !state.running -> Waiting(FuseIcons.Hourglass, "Starting", "One moment.", qrSize)
            address == null || modules == null -> Waiting(FuseIcons.WifiOff, "Not on a network", "Join Wi-Fi, then pair from a phone on the same network.", qrSize)
            else -> {
                QrCode(modules, qrSize)
                FText(address.removePrefix("http://").trimEnd('/'), Fuse.type.titleSmall, maxLines = 1, align = TextAlign.Center)
                if (steps) {
                    Steps(state.username)
                } else {
                    FText(
                        if (state.username == null) "Set a sign-in, then scan on the same Wi-Fi" else "Scan on the same Wi-Fi, then sign in as ${state.username}",
                        Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 2, align = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Waiting(icon: ImageVector, title: String, message: String, size: Dp) {
    val c = Fuse.colors
    Box(Modifier.size(size * 0.8f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.5f).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = size * 0.2f, tint = c.textMuted)
        }
    }
    FText(title, Fuse.type.titleSmall, maxLines = 1, align = TextAlign.Center)
    FText(message, Fuse.type.label, color = c.textMuted, maxLines = 3, align = TextAlign.Center)
}

@Composable
private fun Steps(username: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Step(1, "Join the same Wi-Fi as this device")
        Step(2, "Scan the code with your phone's camera")
        Step(3, if (username == null) "Set a sign-in here first" else "Sign in as $username")
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            FText(n.toString(), Fuse.type.caption, color = c.accent, maxLines = 1)
        }
        Spacer(Modifier.width(Space.s))
        FText(text, Fuse.type.label, color = c.textMuted, maxLines = 1)
    }
}

/** Dark modules on white with a quiet zone, the way phone cameras read codes best. */
@Composable
private fun QrCode(modules: List<BooleanArray>, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(size * 0.06f)) {
        Canvas(Modifier.fillMaxSize()) {
            val n = modules.size
            if (n == 0) return@Canvas
            val cell = this.size.minDimension / n
            // Whole pixels keep the edges crisp, which matters for scanning.
            val px = kotlin.math.floor(cell).coerceAtLeast(1f)
            val offset = (this.size.minDimension - px * n) / 2f
            for (y in 0 until n) {
                val row = modules[y]
                for (x in 0 until n) {
                    if (row[x]) {
                        drawRect(
                            Color(0xFF101114),
                            topLeft = Offset(offset + x * px, offset + y * px),
                            size = androidx.compose.ui.geometry.Size(px, px),
                        )
                    }
                }
            }
        }
    }
}
