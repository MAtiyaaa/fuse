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
        val way = PlayTogether.way(family(installed))
            ?: return no("Not with ${installed.name}", "Fuse doesn't know how ${installed.name} plays with others. Look in its own menus for multiplayer or netplay.")
        val files = ctx.services.emulatorFiles
        val missing = if (files == null) emptyList() else missing(installed, way)
        val rpcn = if (way == TogetherWay.ONLINE_SERVICE) account(installed)?.npid else null
        val link = sync()
        val hosts = hostsFor(id, way)
        return TogetherView.Ready(
            title = game.displayTitle,
            emulator = installed.name,
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
            TogetherWay.ONLINE_SERVICE -> listOfNotNull(
                "Its network on, through RPCN".takeIf { !ready(PlayTogether.rpcs3, settings.current(installed.id, PlayTogether.rpcs3)) },
                "An RPCN account".takeIf { account(installed) == null },
            )
            TogetherWay.NETWORK_ADAPTER -> listOfNotNull("Its network adapter on".takeIf { !ready(PlayTogether.pcsx2, settings.current(installed.id, PlayTogether.pcsx2)) })
            TogetherWay.AD_HOC -> {
                val wlan = listOf(OnlineValue(null, "Network", "EnableWlan", "True"))
                listOfNotNull("Its wireless on".takeIf { !ready(wlan, settings.current(installed.id, wlan)) })
            }
            TogetherWay.NETPLAY_WINDOW -> {
                val name = PlayTogether.dolphin(null, nickname())
                listOfNotNull("Your name in its NetPlay".takeIf { name.isNotEmpty() && !ready(name, settings.current(installed.id, name)) })
            }
            TogetherWay.NETPLAY, TogetherWay.IN_EMULATOR -> emptyList()
        }
    }

    /** Sets [id]'s emulator up for its way of playing together (see [missing]). */
    suspend fun setUp(id: GameId): Boolean {
        val installed = installedFor(id) ?: return false
        val values = when (PlayTogether.way(family(installed))) {
            TogetherWay.ONLINE_SERVICE -> PlayTogether.rpcs3
            TogetherWay.NETWORK_ADAPTER -> PlayTogether.pcsx2
            TogetherWay.AD_HOC -> listOf(OnlineValue(null, "Network", "EnableWlan", "True")) +
                listOfNotNull(PlayTogether.nickname(nickname(), 16)?.let { OnlineValue(null, "SystemParam", "NickName", it) })
            TogetherWay.NETPLAY_WINDOW -> PlayTogether.dolphin(null, nickname())
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

    private suspend fun installedFor(id: GameId): InstalledEmulator? = data.games.get(id)?.let { startsIn(it) }

    /**
     * Before [game] starts in [installed] to be played together: PPSSPP pointed at the ad hoc server
     * (its own when hosting, the host's when joining) and Dolphin given the host's address. Null when
     * ready, else why not.
     */
    suspend fun prepare(installed: InstalledEmulator, together: Together): String? {
        val way = PlayTogether.way(family(installed)) ?: return "Fuse doesn't know how ${installed.name} plays with others."
        if (ctx.services.emulatorFiles == null) return "Playing together is set up from Fuse on a computer."
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
