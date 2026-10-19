package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.launch.online.PlayTogether
import io.github.matiyaaa.fuse.launch.online.TogetherWay
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import io.github.matiyaaa.fuse.ui.shell.store.Together
import io.github.matiyaaa.fuse.ui.shell.store.TogetherView
import kotlinx.coroutines.launch

/**
 * Playing [card] with others, as its emulator does it: the household's games open to join first,
 * then hosting it here, joining by an address, what the emulator still needs (set up in its own
 * settings, and put back again), and RPCS3's RPCN account. Each way says how it works in its own words.
 */
internal fun AppState.playTogetherSheet(card: GameCard) {
    contextMenu = null
    scope.launch {
        when (val v = store.library.together(card.id)) {
            is TogetherView.Unavailable -> showProblem(Problem(v.title, v.reason, ProblemKind.EMULATOR, Severity.INFO, reassurance = null))
            is TogetherView.Ready -> choice = ChoiceSpec(
                icon = FuseIcons.Users,
                title = "Play ${card.title} Together",
                message = togetherText(v),
                options = togetherRows(card, v),
            )
        }
    }
}

private fun togetherText(v: TogetherView.Ready): String {
    val household = if (v.household) " Your household sees a game you host, and can join it from here." else " With Fuse One, your household sees a game you host here and joins it from here."
    return when (v.way) {
        TogetherWay.NETPLAY -> "${v.emulator}'s netplay: one player hosts and the others join, each with this game and the same core." + household
        TogetherWay.AD_HOC -> "PPSSPP's ad hoc play: every player reaches one server. Hosting runs PPSSPP's own here; joining points PPSSPP at the host's. Then each opens the game's own multiplayer." + household
        TogetherWay.NETPLAY_WINDOW -> "Dolphin plays together from its NetPlay window: Tools, Start NetPlay. Fuse gives it your name and, to join, the host's address or host code on its Connect tab."
        TogetherWay.ONLINE_SERVICE -> if (v.family == SHADPS4) {
            "shadPS4 takes games online through ShadNet, its own service. Fuse turns on its network and ShadNet; sign in with your ShadNet account in shadPS4's own user settings, which keep your password as you type it. Each game's online play is as its servers allow."
        } else "RPCS3 takes games online through RPCN, its own service, with your account. Each game's online play is as its servers allow."
        TogetherWay.NETWORK_ADAPTER -> "PCSX2's network adapter lets a game reach its online service through this computer's connection. A game's revival service may also ask for its own DNS server, set in PCSX2."
        TogetherWay.ROOMS -> "${v.emulator} plays together in rooms: one player opens Multiplayer, Create Room, and the others join from Multiplayer, Direct Connect to Room, at the host's address. Fuse gives it your name and leaves the rooms you create unlisted, joined by address alone."
        TogetherWay.IN_EMULATOR -> when (v.family) {
            // Cemu v2.6, Account::ValidateOnlineFiles and GeneralSettings2's Account page.
            "cemu" -> "Cemu goes online with your own Wii U's files: otp.bin and seeprom.bin in its folder and your console's account, with Pretendo or Nintendo chosen for it in Options, General Settings, Account. Fuse doesn't change them."
            else -> "${v.emulator} plays together from its own menus (its multiplayer rooms or local wireless). Fuse doesn't change them."
        }
    }
}

private fun AppState.togetherRows(card: GameCard, v: TogetherView.Ready): List<MenuAction> = buildList {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    // The household's games open to join, newest first.
    for (h in v.hosts.sortedByDescending { it.since }) add(MenuAction(
        "join.${h.deviceId}", "Join ${h.who}'s Game", FuseIcons.LogIn,
        detail = "On ${h.device}, hosting since ${agoText(h.since, now)}",
        section = "In Your Household",
        enabled = h.address != null,
        unavailableReason = if (h.address == null) "${h.device} didn't say where it can be reached" else null,
        onSelect = { playTogether(card, Together.Join(h)) },
    ))
    if (v.canHost) add(MenuAction(
        "host", "Host a Game", FuseIcons.Router,
        detail = if (v.household) "Starts it here, open for your household to join" else "Starts it here, for others to join at this computer's address",
        section = "Play",
        onSelect = { playTogether(card, Together.Host) },
    ))
    if (v.way == TogetherWay.NETPLAY && v.canHost || v.way == TogetherWay.AD_HOC && v.canHost || v.way in WAITS_IN_EMULATOR) add(MenuAction(
        "address", "Join by Address", FuseIcons.Link,
        detail = "Someone's game at an address on your network",
        section = "Play",
        onSelect = { joinByAddress(card, v) },
    ))
    if (v.way == TogetherWay.NETPLAY_WINDOW) add(MenuAction(
        "code", "Join by Host Code", FuseIcons.Hash,
        detail = "A game hosted over the internet, by the code Dolphin gave its host",
        section = "Play",
        onSelect = { joinByCode(card) },
    ))
    // RPCN's account has a row of its own below.
    val needs = v.missing.filter { it != RPCN_ACCOUNT }
    if (needs.isNotEmpty()) add(MenuAction(
        "setup", "Set Up ${v.emulator}", FuseIcons.Wrench,
        detail = "Needs " + needs.joinToString(" and ") { it.replaceFirstChar { c -> c.lowercase() } },
        section = "Setup",
        onSelect = {
            scope.launch {
                val done = store.library.setUpTogether(card.id)
                if (done) toasts.show("${v.emulator} is set up for playing together", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
                else toasts.show("Fuse couldn't change ${v.emulator}'s settings. Start it once, then try again.", ToastKind.WARNING)
                playTogetherSheet(card)
            }
        },
    ))
    if (v.way == TogetherWay.ONLINE_SERVICE && v.family != SHADPS4) {
        val signedIn = v.rpcn
        if (signedIn == null) add(MenuAction("rpcn", "Sign In to RPCN", FuseIcons.Globe, detail = "Your RPCN user name, password and the token RPCN emailed you", section = "Setup", onSelect = { rpcnSignIn(card) }))
        else {
            add(MenuAction("rpcn", "RPCN", FuseIcons.Globe, detail = "RPCS3 signs in with this account. Choose to sign in with another", trailing = Trailing.Value(signedIn), section = "Setup", onSelect = { rpcnSignIn(card) }))
            add(MenuAction("rpcn.out", "Sign Out of RPCN", FuseIcons.LogOut, detail = "Your user name stays; the password and token leave RPCS3", section = "Setup", onSelect = {
                scope.launch {
                    if (store.library.signOutRpcn(card.id)) toasts.show("RPCS3 is signed out of RPCN", ToastKind.INFO, icon = FuseIcons.LogOut)
                    playTogetherSheet(card)
                }
            }))
        }
    }
    v.wiiLink?.let { on ->
        add(MenuAction(
            "wiilink", "WiiLink", FuseIcons.Earth,
            detail = "The Wii's WiiConnect24 channels, like Forecast and the Nintendo Channel, through WiiLink, under its terms at wiilink24.com/tos",
            trailing = Trailing.Switch(on),
            section = "Setup",
            onSelect = {
                scope.launch {
                    if (store.library.setWiiLink(card.id, !on)) {
                        toasts.show(if (on) "Dolphin's WiiLink is off" else "Dolphin's WiiConnect24 channels go through WiiLink", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
                    } else toasts.show("Fuse couldn't change Dolphin's settings. Start it once, then try again.", ToastKind.WARNING)
                    playTogetherSheet(card)
                }
            },
        ))
    }
    if (v.canPutBack) add(MenuAction(
        "putback", "Put Back ${v.emulator}'s Settings", FuseIcons.RotateCcw,
        detail = "What Fuse changed for playing together; anything changed in ${v.emulator} since stays",
        section = "Setup",
        onSelect = {
            scope.launch {
                if (store.library.putBackTogether(card.id)) toasts.show("${v.emulator}'s settings are back as they were", ToastKind.SUCCESS, icon = FuseIcons.RotateCcw)
                playTogetherSheet(card)
            }
        },
    ))
}

/**
 * Joins a game at an address the person types: RetroArch and PPSSPP start it; Dolphin gets it on
 * its Connect tab, Azahar and Eden in Direct Connect to Room.
 */
private fun AppState.joinByAddress(card: GameCard, v: TogetherView.Ready) {
    choice = null
    textInput = TextInputSpec(
        "Join by address", "", placeholder = "192.168.1.20", capitalize = false, doneLabel = "Join",
        message = "The address of the computer hosting it, on your home network",
    ) { typed ->
        val address = PlayTogether.address(typed) ?: return@TextInputSpec toasts.show("That isn't an address. Type it like 192.168.1.20.", ToastKind.WARNING)
        if (v.way in WAITS_IN_EMULATOR) scope.launch {
            // The emulator's own window joins: the address waits there.
            val problem = store.library.readyTogether(card.id, Together.JoinAddress(address))
            val done = if (v.way == TogetherWay.ROOMS) "${v.emulator}'s Direct Connect to Room has $address. Open ${v.emulator}: Multiplayer, Direct Connect to Room."
            else "Dolphin's NetPlay has $address on its Connect tab. Open Dolphin: Tools, Start NetPlay, Connect."
            if (problem == null) toasts.show(done, ToastKind.INFO, durationMs = 9_000L)
            else toasts.show(problem, ToastKind.WARNING, durationMs = 8_000L)
        } else playTogether(card, Together.JoinAddress(address))
    }
}

/** Joins a Dolphin game hosted through its traversal server: the host code waits on its Connect tab. */
private fun AppState.joinByCode(card: GameCard) {
    choice = null
    textInput = TextInputSpec(
        "Join by host code", "", placeholder = "1a2b3c4d", capitalize = false, doneLabel = "Join",
        message = "The code Dolphin shows the host while it hosts",
    ) { typed ->
        val code = PlayTogether.hostCode(typed)
            ?: return@TextInputSpec toasts.show("A host code is 8 characters, digits and the letters a to f, like 1a2b3c4d.", ToastKind.WARNING)
        scope.launch {
            val problem = store.library.readyTogether(card.id, Together.JoinCode(code))
            if (problem == null) toasts.show("Dolphin's NetPlay has host code $code on its Connect tab. Open Dolphin: Tools, Start NetPlay, Connect.", ToastKind.INFO, durationMs = 9_000L)
            else toasts.show(problem, ToastKind.WARNING, durationMs = 8_000L)
        }
    }
}

/** The ways whose own window joins a game, the address waiting there for it. */
private val WAITS_IN_EMULATOR = setOf(TogetherWay.NETPLAY_WINDOW, TogetherWay.ROOMS)

/** RPCN's sign-in, as RPCS3's own dialog asks it: user name, password, then the token RPCN emailed. */
private fun AppState.rpcnSignIn(card: GameCard) {
    choice = null
    textInput = TextInputSpec("RPCN user name", "", placeholder = "Up to 16 letters, digits, - or _", capitalize = false, doneLabel = "Next",
        message = "Made in RPCS3: Configuration, RPCN. Fuse signs RPCS3 in with it.") { user ->
        textInput = TextInputSpec("RPCN password", "", secret = true, capitalize = false, doneLabel = "Next",
            message = "Used once, as RPCS3 uses it, and kept nowhere") { password ->
            textInput = TextInputSpec("RPCN token", "", placeholder = "From RPCN's email", capitalize = false, doneLabel = "Sign In",
                message = "The code RPCN sent to your email when the account was made") { token ->
                scope.launch {
                    toasts.show("Signing RPCS3 in to RPCN", ToastKind.INFO, icon = FuseIcons.Globe)
                    val problem = store.library.signInRpcn(card.id, user, password, token)
                    if (problem == null) toasts.show("RPCS3 signs in to RPCN as ${user.trim()} from now on", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
                    else toasts.show(problem, ToastKind.WARNING, durationMs = 8_000L)
                    playTogetherSheet(card)
                }
            }
        }
    }
}

/** What [TogetherView.Ready.missing] says for RPCS3 without an account (see TogetherStore). */
private const val RPCN_ACCOUNT = "An RPCN account"

/** shadPS4's family: online through ShadNet, signed in from its own users rather than from Fuse. */
private const val SHADPS4 = "shadps4"
