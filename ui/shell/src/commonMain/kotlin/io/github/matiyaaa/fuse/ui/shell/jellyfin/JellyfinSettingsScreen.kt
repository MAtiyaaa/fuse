package io.github.matiyaaa.fuse.ui.shell.jellyfin

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.data.settings.JellyfinSettings
import io.github.matiyaaa.fuse.jellyfin.DiscoveredServer
import io.github.matiyaaa.fuse.jellyfin.JellyfinService
import io.github.matiyaaa.fuse.jellyfin.JellyfinState
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.hasTwoScreens
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.settings.choiceRow
import io.github.matiyaaa.fuse.ui.shell.settings.infoRow
import io.github.matiyaaa.fuse.ui.shell.settings.toggleRow
import kotlinx.coroutines.launch

/** What a test of one address found: whether it answered, and what to say about it. */
internal data class AddressTest(val running: Boolean, val ok: Boolean = false, val text: String? = null)

/** The page's own state while it is open: servers found nearby, tests, a sign-in under way. */
internal class JellyfinSettingsState {
    var scanning by mutableStateOf(false)
    var found by mutableStateOf<List<DiscoveredServer>?>(null)
    val tests = mutableStateMapOf<String, AddressTest>()
    var signingIn by mutableStateOf(false)
    val sel = LinearSelection()
}

/** How things stand, in a word or two and a sentence, with a dot: green, red, or grey for neither. */
internal data class JellyfinStatus(val ok: Boolean?, val title: String, val detail: String)

internal fun jellyfinStatus(s: JellyfinState, prefs: JellyfinSettings): JellyfinStatus = when {
    !prefs.enabled -> JellyfinStatus(null, "Off", "Turn Jellyfin on to bring your server's films, shows and music into Addons")
    prefs.localAddress.isBlank() && prefs.remoteAddress.isBlank() -> JellyfinStatus(null, "Not set up", "Add your server's address, then sign in")
    s.account == null -> JellyfinStatus(null, "Not signed in", "Sign in with your Jellyfin user to browse and play")
    s.authRequired -> JellyfinStatus(false, "Signed out", "The server signed this device out. Sign in again")
    s.checking && s.base == null -> JellyfinStatus(null, "Connecting", "Asking your home and outside addresses at once")
    s.offline -> JellyfinStatus(false, "Can't reach the server", "Neither address answered. Pages you've opened are kept, and Fuse keeps trying")
    s.route == io.github.matiyaaa.fuse.jellyfin.Route.LOCAL -> JellyfinStatus(true, "Connected at home", s.serverName ?: "Your server")
    s.route == io.github.matiyaaa.fuse.jellyfin.Route.REMOTE -> JellyfinStatus(true, "Connected from outside", s.serverName ?: "Your server")
    else -> JellyfinStatus(null, "Connecting", s.serverName ?: "Looking for your server")
}

private val QUALITY = listOf(
    0L to "No limit", 60_000_000L to "60 Mbps", 40_000_000L to "40 Mbps", 20_000_000L to "20 Mbps",
    12_000_000L to "12 Mbps", 8_000_000L to "8 Mbps", 4_000_000L to "4 Mbps", 2_000_000L to "2 Mbps",
)

/** The languages offered for sound and subtitles, as the ISO 639-2 codes Jellyfin uses. */
private val LANGUAGES = listOf(
    "" to "Server's choice", "eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German",
    "ita" to "Italian", "por" to "Portuguese", "dut" to "Dutch", "swe" to "Swedish", "nor" to "Norwegian",
    "dan" to "Danish", "fin" to "Finnish", "pol" to "Polish", "rus" to "Russian", "ukr" to "Ukrainian",
    "tur" to "Turkish", "ara" to "Arabic", "heb" to "Hebrew", "hin" to "Hindi", "jpn" to "Japanese",
    "kor" to "Korean", "chi" to "Chinese", "tha" to "Thai", "vie" to "Vietnamese", "ind" to "Indonesian",
)

/**
 * Settings, Addons, Jellyfin: turning it on, how Fuse reaches the server (at home, from outside, or
 * whichever answers), signing in (the password is used once and never kept), and how Fuse Player
 * plays: quality on each route, sound and subtitle languages, subtitle look, and the second screen
 * where there is one. The page leads with where things stand, so it is clear before changing
 * anything.
 */
@Composable
internal fun JellyfinSettingsScreen(app: AppState) {
    val service = app.jellyfin
    val prefs by app.store.prefs.collectAsState()
    val j = prefs.jellyfin
    val page = rememberRouteState(app.navigator, "jellyfin.settings") { JellyfinSettingsState() }
    val state = service?.state?.collectAsState()?.value ?: JellyfinState()
    val status = jellyfinStatus(state, j)
    val reveal = rememberReveal()
    fun set(change: (JellyfinSettings) -> JellyfinSettings) = app.store.updatePrefs { it.copy(jellyfin = change(it.jellyfin)) }

    val rows = if (service == null) {
        listOf(infoRow("none", "Jellyfin isn't part of this build", icon = FuseIcons.Clapperboard))
    } else {
        jellyfinRows(app, service, page, state, j, ::set)
    }
    page.sel.keepOn(rows.map { it.id })
    page.sel.clamp(rows.size)

    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Back"))
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, page.sel)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        val wide = maxWidth >= 1040.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Header(status, compact, Modifier.reveal(reveal, 0))
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.widthIn(max = Size.touch * 18).weight(1f, fill = !wide).fillMaxHeight().reveal(reveal, 1)) {
                    MenuList(rows, page.sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                }
                if (wide) ServerCard(state, j, status, Modifier.width(320.dp).reveal(reveal, 2))
            }
        }
    }
}

@Composable
private fun Header(status: JellyfinStatus, compact: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val well: Dp = if (compact) Size.thumb else Size.thumbL
        Box(Modifier.size(well).clip(RoundedCornerShape(well * 0.28f)).background(Fuse.colors.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.Clapperboard, size = well * 0.46f, tint = Fuse.colors.accent)
        }
        Spacer(Modifier.width(Space.l))
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText("Jellyfin", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.ok)
                Spacer(Modifier.width(Space.s))
                FText(status.title, Fuse.type.bodyStrong, color = Fuse.colors.text, maxLines = 1)
                FText("  ·  ${status.detail}", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
            }
        }
    }
}

/** On wide screens, the server at a glance beside the settings. */
@Composable
private fun ServerCard(s: JellyfinState, j: JellyfinSettings, status: JellyfinStatus, modifier: Modifier) {
    Panel(modifier) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(FuseIcons.Server, size = 20.dp, tint = Fuse.colors.textMuted)
                Spacer(Modifier.width(Space.s))
                FText(s.serverName ?: s.account?.serverName ?: "Your server", Fuse.type.bodyStrong, maxLines = 1)
            }
            Fact("Status", status.title)
            Fact("Signed in as", s.account?.userName ?: "Nobody yet")
            Fact("Reaching it", when (s.route) {
                io.github.matiyaaa.fuse.jellyfin.Route.LOCAL -> "At home"
                io.github.matiyaaa.fuse.jellyfin.Route.REMOTE -> "From outside"
                null -> if (s.offline) "Not right now" else "Not yet"
            })
            Fact("Mode", modeName(j.mode))
            s.serverVersion?.let { Fact("Version", it) }
            FText(
                "Fuse only talks to your server: signing in, what you browse and play, and where you stopped. The password is used once to sign in and never kept.",
                Fuse.type.caption, color = Fuse.colors.textMuted,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FText(label, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
        FText(value, Fuse.type.label, maxLines = 1)
    }
}

private fun modeName(mode: String) = when (mode) {
    "LOCAL" -> "Home only"
    "REMOTE" -> "Outside only"
    else -> "Automatic"
}

private fun jellyfinRows(
    app: AppState,
    service: JellyfinService,
    page: JellyfinSettingsState,
    state: JellyfinState,
    j: JellyfinSettings,
    set: ((JellyfinSettings) -> JellyfinSettings) -> Unit,
): List<MenuAction> = buildList {
    add(toggleRow("enabled", "Jellyfin", FuseIcons.Clapperboard, j.enabled, "Films, shows and music from your Jellyfin server in Addons, played in Fuse Player") { v -> set { it.copy(enabled = v) } })
    if (!j.enabled) {
        add(infoRow("off", "Nothing runs while it's off", detail = "No requests, no Addons tab and no Home widgets until you turn it on", icon = FuseIcons.ShieldCheck))
        return@buildList
    }

    // Server ---------------------------------------------------------------------------------------
    val server = "Server"
    add(app.choiceRow(
        "mode", "Connection", FuseIcons.Network, j.mode,
        listOf("AUTO" to "Automatic", "LOCAL" to "Home only", "REMOTE" to "Outside only"),
        detail = "Automatic uses the home address when it answers and the outside one otherwise",
        optionDetail = {
            when (it) {
                "LOCAL" -> "Only the home address, on the same network"
                "REMOTE" -> "Only the outside address, from anywhere"
                else -> "Home when it answers, outside otherwise, back home when it can. Never mid-film"
            }
        },
    ) { v -> set { it.copy(mode = v) } }.copy(section = server))
    add(MenuAction(
        "local", "Home address", FuseIcons.Home,
        detail = if (page.scanning) "Looking for servers on this network" else "Find a server on this network or type its address",
        trailing = Trailing.Value(if (page.scanning) "Looking" else j.localAddress.ifBlank { "Not set" }),
        section = server,
        onSelect = { if (!page.scanning) chooseHomeAddress(app, service, page, j, set) },
    ))
    add(MenuAction(
        "remote", "Outside address", FuseIcons.Globe,
        detail = "For away from home: your server's web address, with its path if it has one",
        trailing = Trailing.Value(j.remoteAddress.ifBlank { "Not set" }),
        section = server,
        onSelect = {
            app.textInput = TextInputSpec("Outside address", j.remoteAddress, "https://jellyfin.example.com", capitalize = false) { v ->
                saveAndTest(app, service, page, "test.remote", v) { a -> set { it.copy(remoteAddress = a) } }
            }
        },
    ))
    for ((key, label, address) in listOf(Triple("test.local", "Test home address", j.localAddress), Triple("test.remote", "Test outside address", j.remoteAddress))) {
        if (address.isBlank()) continue
        val t = page.tests[key]
        add(MenuAction(
            key, label, if (t?.running == false && !t.ok) FuseIcons.WifiOff else FuseIcons.Signal,
            detail = t?.text ?: address,
            trailing = when {
                t == null -> Trailing.None
                t.running -> Trailing.Value("Testing")
                t.ok -> Trailing.Value("Works")
                else -> Trailing.Value("No answer")
            },
            section = server,
            onSelect = {
                if (t?.running == true) return@MenuAction
                page.tests[key] = AddressTest(running = true, text = "Asking $address")
                app.scope.launch {
                    val r = service.test(address)
                    page.tests[key] = r.fold(
                        onSuccess = { (base, info) -> AddressTest(false, true, listOfNotNull(info.name, info.version?.let { "Jellyfin $it" }, base).joinToString("  ·  ")) },
                        onFailure = { AddressTest(false, false, it.message ?: "No answer") },
                    )
                    // An address that answers here is one Fuse can use now: the status above follows it.
                    if (r.isSuccess) service.reconnect(force = true)
                }
            },
        ))
    }

    // Account --------------------------------------------------------------------------------------
    val account = "Account"
    val a = state.account
    if (a == null || state.authRequired) {
        add(MenuAction(
            "signin", if (state.authRequired) "Sign in again" else "Sign in", if (state.authRequired) FuseIcons.Warning else FuseIcons.User,
            detail = when {
                j.localAddress.isBlank() && j.remoteAddress.isBlank() -> "Add an address first"
                page.signingIn -> "Signing in"
                else -> "Your Jellyfin user name and password. The password is used once and never kept"
            },
            trailing = if (page.signingIn) Trailing.Value("Signing in") else Trailing.Chevron,
            unavailableReason = if (j.localAddress.isBlank() && j.remoteAddress.isBlank()) "Add an address first" else null,
            section = account,
            onSelect = { if (!page.signingIn) signIn(app, service, page, a?.userName.orEmpty()) },
        ))
    }
    if (a != null) {
        add(infoRow("user", "Signed in as ${a.userName ?: "you"}", value = state.serverName ?: a.serverName, icon = FuseIcons.CircleUser).copy(section = account))
        add(MenuAction(
            "signout", "Sign out", FuseIcons.LogOut,
            detail = "Forgets this device's sign-in. Kept pages are cleared",
            section = account,
            onSelect = {
                app.confirm = ConfirmSpec("Sign out of Jellyfin?", "Fuse forgets its sign-in on this device. Your server and what you've watched stay as they are.", "Sign out", false) {
                    app.scope.launch {
                        runCatching { service.signOut() }
                        app.toasts.show("Signed out of Jellyfin")
                    }
                }
            },
        ))
    }

    // Playback -------------------------------------------------------------------------------------
    val playback = "Playback"
    add(app.choiceRow("q.local", "Quality at home", FuseIcons.Gauge, QUALITY.minBy { kotlin.math.abs(it.first - j.localMaxBitrate) }.first, QUALITY,
        detail = "The most a stream carries on the home network. Lower asks the server to convert") { v -> set { it.copy(localMaxBitrate = v) } }.copy(section = playback))
    add(app.choiceRow("q.remote", "Quality from outside", FuseIcons.Gauge, QUALITY.minBy { kotlin.math.abs(it.first - j.remoteMaxBitrate) }.first, QUALITY,
        detail = "The most a stream carries away from home. Match it to your upload speed") { v -> set { it.copy(remoteMaxBitrate = v) } }.copy(section = playback))
    add(toggleRow("hw", "Hardware decoding", FuseIcons.Chip, j.hardwareDecoding, "Smoother and cooler. Turn off if the picture shows blocks or stays black") { v -> set { it.copy(hardwareDecoding = v) } }.copy(section = playback))
    add(toggleRow("next", "Play the next episode", FuseIcons.SkipForward, j.autoplayNext, "Counts down at the end, so you can stop it") { v -> set { it.copy(autoplayNext = v) } }.copy(section = playback))
    add(app.choiceRow("seek", "Skip by", FuseIcons.ChevronsRight, j.seekSeconds, listOf(5, 10, 15, 30, 60).map { it to "$it seconds" },
        detail = "Left and Right while the controls are hidden. Holding goes further") { v -> set { it.copy(seekSeconds = v) } }.copy(section = playback))
    add(app.choiceRow("hide", "Hide controls after", FuseIcons.Timer, j.controlsTimeoutSeconds, listOf(3, 4, 6, 10).map { it to "$it seconds" }) { v -> set { it.copy(controlsTimeoutSeconds = v) } }.copy(section = playback))
    add(toggleRow("speed", "Remember speed", FuseIcons.Gauge, j.rememberSpeed, "Keeps the last speed you chose for the next thing you play") { v -> set { it.copy(rememberSpeed = v) } }.copy(section = playback))

    // Sound and subtitles --------------------------------------------------------------------------
    val tracks = "Sound and subtitles"
    add(app.choiceRow("audio", "Sound language", FuseIcons.AudioLines, j.audioLanguage, LANGUAGES,
        detail = "Chosen when a title has it; otherwise the server's choice") { v -> set { it.copy(audioLanguage = v) } }.copy(section = tracks))
    add(app.choiceRow("subs", "Subtitles", FuseIcons.Captions, j.subtitleMode,
        listOf("DEFAULT" to "Server's choice", "ALWAYS" to "Always", "FOREIGN" to "Other languages only", "FORCED" to "Signs and songs only", "OFF" to "Off"),
        optionDetail = {
            when (it) {
                "ALWAYS" -> "Subtitles in your language whenever there are some"
                "FOREIGN" -> "Only when the sound isn't in your subtitle language"
                "FORCED" -> "Only the parts meant to be read, like signs"
                "OFF" -> "No subtitles until you turn them on"
                else -> "As your Jellyfin user is set up"
            }
        }) { v -> set { it.copy(subtitleMode = v) } }.copy(section = tracks))
    if (j.subtitleMode != "OFF") {
        add(app.choiceRow("subs.lang", "Subtitle language", FuseIcons.Type, j.subtitleLanguage, LANGUAGES) { v -> set { it.copy(subtitleLanguage = v) } }.copy(section = tracks))
    }
    add(app.choiceRow("subs.size", "Subtitle size", FuseIcons.TextSize, listOf(0.8f, 1f, 1.2f, 1.4f, 1.6f).minBy { kotlin.math.abs(it - j.subtitleScale) },
        listOf(0.8f to "Small", 1f to "Default", 1.2f to "Large", 1.4f to "Larger", 1.6f to "Largest")) { v -> set { it.copy(subtitleScale = v) } }.copy(section = tracks))
    add(app.choiceRow("subs.lift", "Subtitle position", FuseIcons.MoveVertical, listOf(0f, 0.05f, 0.1f, 0.15f).minBy { kotlin.math.abs(it - j.subtitleLift) },
        listOf(0f to "Bottom", 0.05f to "A little higher", 0.1f to "Higher", 0.15f to "Highest"),
        detail = "Higher keeps them clear of a TV's edge") { v -> set { it.copy(subtitleLift = v) } }.copy(section = tracks))
    add(toggleRow("subs.bg", "Subtitle background", FuseIcons.RectHorizontal, j.subtitleBackground, "A dark box behind the words, for bright scenes") { v -> set { it.copy(subtitleBackground = v) } }.copy(section = tracks))

    // Second screen --------------------------------------------------------------------------------
    if (app.platform.features.secondScreen || app.hasTwoScreens) {
        val second = "Second screen"
        add(app.choiceRow("c.where", "Films play on", FuseIcons.PanelTop, j.playOn,
            listOf("ASK" to "Ask each time", "MAIN" to "The main screen", "SECOND" to "The second screen"),
            detail = "The other screen is its remote, and the menus stay free to browse. While it plays, Y moves it to the other screen",
            optionDetail = {
                when (it) {
                    "SECOND" -> "The touch screen below: with the menus below, as on a phone; otherwise the main screen keeps browsing"
                    "MAIN" -> "The big screen above, with the touch screen as its remote"
                    else -> "Each film or episode asks which screen, with the two drawn side by side"
                }
            },
        ) { v -> set { it.copy(playOn = v) } }.copy(section = second))
        add(app.choiceRow("c.player", "While playing", FuseIcons.DualScreen, j.playerCompanion,
            listOf("REMOTE" to "Controls and art", "OFF" to "Nothing"),
            detail = "The screen without the picture becomes a remote: art, time, and buttons to pause, skip and change tracks") { v -> set { it.copy(playerCompanion = v) } }.copy(section = second))
        add(app.choiceRow("c.browse", "While browsing", FuseIcons.MonitorPlay, j.browsingCompanion,
            listOf("DETAILS" to "Art and details", "MINIMAL" to "Art only", "OFF" to "Nothing"),
            detail = "What the other screen shows about the film or show you're on") { v -> set { it.copy(browsingCompanion = v) } }.copy(section = second))
    }
}

/** Finds servers on this network, then offers them with typing an address and clearing it. */
private fun chooseHomeAddress(app: AppState, service: JellyfinService, page: JellyfinSettingsState, j: JellyfinSettings, set: ((JellyfinSettings) -> JellyfinSettings) -> Unit) {
    page.scanning = true
    app.scope.launch {
        val found = runCatching { service.discover() }.getOrDefault(emptyList())
        page.found = found
        page.scanning = false
        app.choice = ChoiceSpec(
            title = "Home address",
            icon = FuseIcons.Home,
            message = if (found.isEmpty()) "No server answered on this network. Type its address instead, like 192.168.1.20:8096." else "Servers on this network",
            options = found.map { s ->
                MenuAction("found.${s.address}", s.name, FuseIcons.Server, detail = s.address, trailing = Trailing.Check(s.address == j.localAddress), onSelect = {
                    app.choice = null
                    set { it.copy(localAddress = s.address) }
                })
            } + MenuAction("type", "Type an address", FuseIcons.Keyboard, onSelect = {
                app.choice = null
                app.textInput = TextInputSpec("Home address", j.localAddress, "192.168.1.20:8096", capitalize = false) { v ->
                    saveAndTest(app, service, page, "test.local", v) { a -> set { it.copy(localAddress = a) } }
                }
            }) + listOfNotNull(
                MenuAction("clear", "Clear the home address", FuseIcons.Eraser, onSelect = {
                    app.choice = null
                    set { it.copy(localAddress = "") }
                }).takeIf { j.localAddress.isNotBlank() },
            ),
        )
    }
}

/**
 * Keeps a typed address and tests it straight away. When one way of reading it answers, that exact
 * address (scheme, port and path) is what's kept, so connecting later doesn't guess again.
 */
private fun saveAndTest(app: AppState, service: JellyfinService, page: JellyfinSettingsState, key: String, typed: String, keep: (String) -> Unit) {
    val address = typed.trim()
    keep(address)
    if (address.isEmpty()) {
        page.tests.remove(key)
        return
    }
    page.tests[key] = AddressTest(running = true, text = "Asking $address")
    app.scope.launch {
        val r = service.test(address)
        r.onSuccess { (base, _) -> if (base != address) keep(base) }
        page.tests[key] = r.fold(
            onSuccess = { (base, info) -> AddressTest(false, true, listOfNotNull(info.name, info.version?.let { "Jellyfin $it" }, base).joinToString("  ·  ")) },
            onFailure = { AddressTest(false, false, it.message ?: "No answer") },
        )
        r.onFailure { app.toasts.show("Nothing answered at $address. ${it.message ?: ""}".trim()) }
        if (r.isSuccess) service.reconnect(force = true)
    }
}

/** Asks for the user name, then the password, then signs in; the password goes nowhere else. */
private fun signIn(app: AppState, service: JellyfinService, page: JellyfinSettingsState, previous: String) {
    app.textInput = TextInputSpec("Jellyfin user name", previous, "User name", capitalize = false, doneLabel = "Next") { user ->
        val name = user.trim()
        if (name.isEmpty()) return@TextInputSpec
        app.textInput = TextInputSpec("Password for $name", "", "Password", secret = true, capitalize = false, doneLabel = "Sign in") { password ->
            page.signingIn = true
            app.scope.launch {
                val r = service.signIn(name, password)
                page.signingIn = false
                r.onSuccess { app.toasts.show("Signed in to ${it.serverName ?: "Jellyfin"} as ${it.userName ?: name}") }
                    .onFailure { app.toasts.show(it.message ?: "Couldn't sign in") }
            }
        }
    }
}
