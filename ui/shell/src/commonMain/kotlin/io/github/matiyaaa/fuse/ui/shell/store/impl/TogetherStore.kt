package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.online.OnlineValue
import io.github.matiyaaa.fuse.launch.online.PlayTogether
import io.github.matiyaaa.fuse.launch.online.Rpcn
import io.github.matiyaaa.fuse.launch.online.TogetherWay
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.sync.Hosting
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.ui.shell.store.Together
import io.github.matiyaaa.fuse.ui.shell.store.TogetherHost
import io.github.matiyaaa.fuse.ui.shell.store.TogetherView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playing a game with others ([PlayTogether]): what its emulator needs, set in the emulator's own
 * files when the person asks (recorded apart, so it can be put back on its own), who in the
 * household hosts it (through Fuse One's presence), and what hosting or joining changes before the
 * game starts. RPCS3's RPCN account is written as RPCS3 writes it, the password stretched and
 * never kept ([Rpcn]).
 */
internal class TogetherStore(
    private val ctx: StoreContext,
    private val settings: NativeSettingsStore,
    /** The emulator [Game] starts in here, as Play would choose it. */
    private val startsIn: suspend (Game) -> InstalledEmulator?,
    private val sync: () -> SyncLaunch?,
    private val saveQuery: suspend (GameId) -> SaveQuery?,
    /** Makes an RPCN account over the network ([RpcnNetwork.create]); a test's own in tests. */
    private val rpcnNetwork: suspend (host: String, npid: String, derived: String, email: String) -> String? = { h, n, d, e -> io.github.matiyaaa.fuse.launch.online.RpcnNetwork.create(h, n, d, e) },
) {
    private val data = ctx.data

    /** The Fuse One profile playing here, whose name the others see. */
    fun nickname(): String? = sync()?.service?.activeProfile?.value?.name

    private fun family(e: InstalledEmulator) = e.id.value.substringAfter('.')

    /** Fuse's word for a way, as hosts say it to the household ([Hosting.way]). */
    fun wayKey(way: TogetherWay): String = when (way) {
        TogetherWay.NETPLAY -> "netplay"
        TogetherWay.AD_HOC -> "adhoc"
        else -> way.name.lowercase()
    }

    private fun no(title: String, reason: String) = TogetherView.Unavailable(title, reason)

    suspend fun view(id: GameId): TogetherView {
        val game = data.games.get(id) ?: return no("Game not found", "This game is no longer in the library.")
        val installed = startsIn(game) ?: return no("No emulator for it here", "Install an emulator for ${game.displayTitle}'s system first.")
        val files = ctx.services.emulatorFiles
        val way = PlayTogether.way(family(installed))
            // Rooms whose settings Fuse can't reach here are its own menus' alone.
            ?.let { if (it == TogetherWay.ROOMS && files == null) TogetherWay.IN_EMULATOR else it }
            ?: return no("Not with ${installed.name}", "Fuse doesn't know how ${installed.name} plays with others. Look in its own menus for multiplayer or netplay.")
        val missing = if (files == null) emptyList() else missing(installed, way)
        val rpcn = if (way == TogetherWay.ONLINE_SERVICE && family(installed) == "rpcs3") account(installed)?.npid else null
        val wiiLink = if (files != null && way == TogetherWay.NETPLAY_WINDOW && game.platformId.value == "wii") {
            val on = PlayTogether.wiiLink(true)
            settings.current(installed.id, listOf(on))?.let { now -> PlayTogether.ready(listOf(on)) { now[it] } }
        } else null
        val link = sync()
        val hosts = hostsFor(id, way)
        return TogetherView.Ready(
            title = game.displayTitle,
            emulator = installed.name,
            family = family(installed),
            wiiLink = wiiLink,
            way = way,
            missing = missing,
            canHost = files != null && ctx.host.isDesktop && way in HOSTED,
            hosts = hosts,
            household = link != null && link.service.activeProfile.value != null,
            rpcn = rpcn,
            canPutBack = files != null && settings.changedTogether(installed.id),
        )
    }

    /** The household's devices hosting [id] now in the same way as here (a RetroArch game is joined from RetroArch). */
    private suspend fun hostsFor(id: GameId, way: TogetherWay): List<TogetherHost> {
        val svc = sync()?.service ?: return emptyList()
        val q = saveQuery(id) ?: return emptyList()
        val names = svc.profiles.value.associate { it.id to it.name }
        return svc.hostedFor(q).mapNotNull { p ->
            val h = p.hosting?.takeIf { it.way == wayKey(way) } ?: return@mapNotNull null
            TogetherHost(p.deviceId, p.deviceName, names[p.profile] ?: p.deviceName, p.since, h.addresses.firstOrNull(), h.port)
        }
    }

    /** What [installed] still needs for its way of playing together, in words. */
    private suspend fun missing(installed: InstalledEmulator, way: TogetherWay): List<String> {
        fun ready(values: List<OnlineValue>, now: Map<OnlineValue, String?>?) = now != null && PlayTogether.ready(values) { now[it] }
        return when (way) {
            TogetherWay.ONLINE_SERVICE -> if (family(installed) == "shadps4") {
                // Signed in from shadPS4's own users: Fuse never writes the password it keeps as typed.
                val shadNet = PlayTogether.shadPs4(installed.version)
                listOfNotNull("Its network on, through ShadNet".takeIf { !ready(shadNet, settings.current(installed.id, shadNet)) })
            } else listOfNotNull(
                "Its network on, through RPCN".takeIf { !ready(PlayTogether.rpcs3, settings.current(installed.id, PlayTogether.rpcs3)) },
                "An RPCN account".takeIf { account(installed) == null },
            )
            TogetherWay.NETWORK_ADAPTER -> listOfNotNull("Its network adapter on".takeIf { !ready(PlayTogether.pcsx2, settings.current(installed.id, PlayTogether.pcsx2)) })
            TogetherWay.AD_HOC -> {
                val wlan = listOf(OnlineValue(null, "Network", "EnableWlan", "True"))
                listOfNotNull(
                    "Its wireless on".takeIf { !ready(wlan, settings.current(installed.id, wlan)) },
                    "Your name for games' online servers".takeIf { onlineName(installed) != null },
                )
            }
            TogetherWay.NETPLAY_WINDOW -> {
                val name = PlayTogether.dolphin(null, nickname())
                listOfNotNull("Your name in its NetPlay".takeIf { name.isNotEmpty() && !ready(name, settings.current(installed.id, name)) })
            }
            TogetherWay.ROOMS -> {
                val rooms = PlayTogether.rooms(nickname(), null)
                listOfNotNull("Its rooms set up for your household".takeIf { !ready(rooms, settings.current(installed.id, rooms)) })
            }
            TogetherWay.NETPLAY, TogetherWay.IN_EMULATOR -> emptyList()
        }
    }

    /**
     * PPSSPP's name for games' revival servers, to give it when it has none of its own (a name it
     * keeps is the person's, never replaced); null when it has one or Fuse has no name to give.
     */
    private suspend fun onlineName(installed: InstalledEmulator): OnlineValue? {
        val name = PlayTogether.ppssppUsername(nickname())?.let { OnlineValue(null, "Network", "InfrastructureUsername", it) } ?: return null
        val now = settings.current(installed.id, listOf(name)) ?: return null
        return name.takeIf { now[it].isNullOrBlank() }
    }

    /** Sets [id]'s emulator up for its way of playing together (see [missing]). */
    suspend fun setUp(id: GameId): Boolean {
        val installed = installedFor(id) ?: return false
        val values = when (PlayTogether.way(family(installed))) {
            TogetherWay.ONLINE_SERVICE -> if (family(installed) == "shadps4") PlayTogether.shadPs4(installed.version) else PlayTogether.rpcs3
            TogetherWay.NETWORK_ADAPTER -> PlayTogether.pcsx2
            TogetherWay.AD_HOC -> listOf(OnlineValue(null, "Network", "EnableWlan", "True")) +
                listOfNotNull(PlayTogether.nickname(nickname(), 16)?.let { OnlineValue(null, "SystemParam", "NickName", it) }, onlineName(installed))
            TogetherWay.NETPLAY_WINDOW -> PlayTogether.dolphin(null, nickname())
            TogetherWay.ROOMS -> PlayTogether.rooms(nickname(), null)
            else -> return false
        }
        if (values.isEmpty() || !settings.setTogether(installed.id, values)) return false
        // Kept, to look at again after an update (see [recheck]).
        data.owned.set(SET_UP, installed.id.value, values.map { listOf(it.file.orEmpty(), it.section, it.key, it.value).joinToString(SEP) }.toSet())
        return true
    }

    /**
     * After [family]'s emulator was updated: each of its installs that [setUp] set up, looked at
     * again. Null when every value is still there, else what to say.
     */
    suspend fun recheck(family: String): String? {
        if (ctx.installed.value.isEmpty()) return null
        for (e in ctx.installed.value.filter { it.id.value.substringAfter('.') == family }) {
            val values = data.owned.get(SET_UP, e.id.value).mapNotNull { line ->
                line.split(SEP).takeIf { it.size == 4 }?.let { OnlineValue(it[0].ifEmpty { null }, it[1], it[2], it[3]) }
            }
            if (values.isEmpty()) continue
            val now = settings.current(e.id, values) ?: continue
            if (!PlayTogether.ready(values) { now[it] }) {
                return "${e.name}'s setup for playing together isn't there after its update. Set it up again from a game's Play Together."
            }
        }
        return null
    }

    suspend fun putBack(id: GameId): Boolean = installedFor(id)?.let { settings.restoreTogether(it.id) } ?: false

    /** Dolphin's WiiConnect24 through WiiLink for [id], a Wii game, [on] or off (recorded with the rest, so Put Back undoes it). */
    suspend fun setWiiLink(id: GameId, on: Boolean): Boolean {
        val game = data.games.get(id)?.takeIf { it.platformId.value == "wii" } ?: return false
        val installed = startsIn(game)?.takeIf { PlayTogether.way(family(it)) == TogetherWay.NETPLAY_WINDOW } ?: return false
        return settings.setTogether(installed.id, listOf(PlayTogether.wiiLink(on)))
    }

    private suspend fun installedFor(id: GameId): InstalledEmulator? = data.games.get(id)?.let { startsIn(it) }

    /**
     * Before [game] starts in [installed] to be played together: PPSSPP pointed at the ad hoc server
     * (its own when hosting, the host's when joining) and Dolphin given the host's address. Null when
     * ready, else why not.
     */
    suspend fun prepare(installed: InstalledEmulator, together: Together): String? {
        val way = PlayTogether.way(family(installed)) ?: return "Fuse doesn't know how ${installed.name} plays with others."
        if (ctx.services.emulatorFiles == null) return "Playing together is set up from Fuse on a computer."
        if (together is Together.JoinCode) {
            if (way != TogetherWay.NETPLAY_WINDOW) return "${installed.name} doesn't join games by host code."
            return if (settings.setTogether(installed.id, PlayTogether.dolphinCode(together.code, nickname()))) null else "Fuse couldn't change Dolphin's settings."
        }
        val address = addressFor(together)
        return when (way) {
            TogetherWay.NETPLAY -> null
            TogetherWay.AD_HOC -> {
                val server = address ?: ctx.services.homeAddresses().firstOrNull()
                    ?: return "Fuse can't tell this computer's address on your home network, which the others reach PPSSPP's server at."
                if (settings.setTogether(installed.id, PlayTogether.ppsspp(server, hosting = address == null, nick = nickname()))) null
                else "Fuse couldn't change ${installed.name}'s settings. Start it once so it sets up its folders, then try again."
            }
            TogetherWay.NETPLAY_WINDOW ->
                if (settings.setTogether(installed.id, PlayTogether.dolphin(address, nickname()))) null else "Fuse couldn't change Dolphin's settings."
            TogetherWay.ROOMS ->
                if (settings.setTogether(installed.id, PlayTogether.rooms(nickname(), address))) null
                else "Fuse couldn't change ${installed.name}'s settings. Start it once so it sets up its folders, then try again."
            else -> "${installed.name} plays together from its own menus."
        }
    }

    /** [prepare] for [id]'s emulator, without starting anything. */
    suspend fun ready(id: GameId, together: Together): String? {
        val installed = installedFor(id) ?: return "This game has no emulator here."
        return prepare(installed, together)
    }

    /** RetroArch's netplay arguments for [together], as the profile playing here. */
    fun netplayArgs(together: Together): List<String> = PlayTogether.retroArchArgs(
        addressFor(together),
        (together as? Together.Join)?.host?.port?.takeIf { it in 1..65535 } ?: PlayTogether.RETROARCH_PORT,
        nickname(),
    )

    private fun addressFor(together: Together): String? = when (together) {
        Together.Host -> null
        is Together.Join -> together.host.address
        is Together.JoinAddress -> together.address
        // Reached through Dolphin's traversal server, not an address (see [prepare]).
        is Together.JoinCode -> null
    }

    /** What the household is told while [installed] hosts here. */
    fun hosting(installed: InstalledEmulator): Hosting? {
        val way = PlayTogether.way(family(installed))?.takeIf { it in HOSTED } ?: return null
        val port = if (way == TogetherWay.NETPLAY) PlayTogether.RETROARCH_PORT else PlayTogether.PPSSPP_SERVER_PORT
        return Hosting(wayKey(way), family(installed), ctx.services.homeAddresses(), port)
    }

    // ------------------------------------------------------------------------------- RPCN

    private suspend fun account(installed: InstalledEmulator): Rpcn.Account? {
        val (_, path) = settings.rpcnPath(installed.id) ?: return null
        return Rpcn.account(ctx.services.fs.readText(path, MAX_TEXT))
    }

    suspend fun signIn(id: GameId, user: String, password: String, token: String): String? {
        val installed = installedFor(id)?.takeIf { family(it) == "rpcs3" } ?: return "This game doesn't start in RPCS3 here."
        val name = user.trim()
        if (!Rpcn.validUser(name)) return "An RPCN user name is up to 16 letters, digits, - or _."
        if (password.isEmpty()) return "Type your RPCN password."
        if (!Rpcn.validToken(token.trim())) return "The token is the code RPCN sent to your email, up to 16 letters and digits."
        val (files, path) = settings.rpcnPath(installed.id) ?: return "Start RPCS3 once so it sets up its folders, then try again."
        // Stretched as RPCS3 does (200,000 rounds): off the interface's thread, and the password goes no further.
        val derived = withContext(Dispatchers.Default) { Rpcn.derivePassword(password) }
        val text = Rpcn.signIn(ctx.services.fs.readText(path, MAX_TEXT), name, derived, token.trim())
        if (!files.writePrivate(path, text)) return "Fuse couldn't write RPCS3's RPCN settings."
        // RPCS3's network through RPCN too, if it isn't yet: an account alone doesn't take its games online.
        settings.setTogether(installed.id, PlayTogether.rpcs3)
        return null
    }

    /**
     * Makes an RPCN account from Fuse, as RPCS3's own dialog makes it (Configuration, RPCN, Create
     * Account): the password stretched as RPCS3 does, then RPCS3 signed in with it, waiting only for
     * the token RPCN emails ([rpcnToken]). Null when made, else why not.
     */
    suspend fun create(id: GameId, user: String, password: String, email: String): String? {
        val installed = installedFor(id)?.takeIf { family(it) == "rpcs3" } ?: return "This game doesn't start in RPCS3 here."
        val name = user.trim()
        val mail = email.trim()
        if (!Rpcn.validUser(name)) return "An RPCN user name is up to 16 letters, digits, - or _."
        if (password.length < 3) return "Choose a longer RPCN password."
        if (!Rpcn.validEmail(mail)) return "That isn't an email address RPCN takes."
        val (files, path) = settings.rpcnPath(installed.id) ?: return "Start RPCS3 once so it sets up its folders, then try again."
        val current = ctx.services.fs.readText(path, MAX_TEXT)
        val host = Rpcn.account(current)?.host ?: Rpcn.HOST
        val derived = withContext(Dispatchers.Default) { Rpcn.derivePassword(password) }
        rpcnNetwork(host, name, derived, mail)?.let { return it }
        // Made: RPCS3 signs in with it once the token is in, as after its own dialog.
        if (!files.writePrivate(path, Rpcn.signIn(current, name, derived, ""))) return "The account was made, but Fuse couldn't write RPCS3's RPCN settings. Sign in with it."
        settings.setTogether(installed.id, PlayTogether.rpcs3)
        return null
    }

    /** RPCS3's RPCN account given the [token] RPCN emailed. Null when done, else why not. */
    suspend fun token(id: GameId, token: String): String? {
        val installed = installedFor(id)?.takeIf { family(it) == "rpcs3" } ?: return "This game doesn't start in RPCS3 here."
        if (!Rpcn.validToken(token.trim())) return "The token is the code RPCN sent to your email, up to 16 letters and digits."
        val (files, path) = settings.rpcnPath(installed.id) ?: return "Start RPCS3 once so it sets up its folders, then try again."
        val text = ctx.services.fs.readText(path, MAX_TEXT) ?: return "RPCS3 has no RPCN account yet."
        return if (files.writePrivate(path, Rpcn.withToken(text, token.trim()))) null else "Fuse couldn't write RPCS3's RPCN settings."
    }

    suspend fun signOut(id: GameId): Boolean {
        val installed = installedFor(id)?.takeIf { family(it) == "rpcs3" } ?: return false
        val (files, path) = settings.rpcnPath(installed.id) ?: return false
        val text = ctx.services.fs.readText(path, MAX_TEXT) ?: return true
        return files.writePrivate(path, Rpcn.signOut(text))
    }

    private companion object {
        const val MAX_TEXT = 64 * 1024

        /** What [setUp] set, by emulator, for [recheck]. */
        const val SET_UP = "together.setup"
        const val SEP = "\u001f"

        /** The ways Fuse starts a game hosting, for the household to join. */
        val HOSTED = setOf(TogetherWay.NETPLAY, TogetherWay.AD_HOC)
    }
}
