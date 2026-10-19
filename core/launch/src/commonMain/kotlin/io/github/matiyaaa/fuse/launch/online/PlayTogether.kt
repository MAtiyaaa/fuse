package io.github.matiyaaa.fuse.launch.online

/** One value an emulator keeps for playing with others: its file (null for its main one), section, key and value. */
data class OnlineValue(val file: String?, val section: String, val key: String, val value: String)

/** How an emulator plays with others, which decides what Fuse can do for it. */
enum class TogetherWay {
    /** RetroArch: one copy hosts, the others connect to it, started from its command line. */
    NETPLAY,

    /** PPSSPP: every copy reaches one ad hoc server, which any of them can run. */
    AD_HOC,

    /** Dolphin: one copy hosts from its NetPlay window and the others connect to its address. */
    NETPLAY_WINDOW,

    /** RPCS3 and shadPS4: games go online through the emulator's own service (RPCN, ShadNet), with the person's account. */
    ONLINE_SERVICE,

    /** PCSX2: its network adapter lets a game reach its own online service. */
    NETWORK_ADAPTER,

    /** Azahar and Eden: one player creates a room from its Multiplayer menu and the others connect to it. */
    ROOMS,

    /** Played together from the emulator's own menus only (local wireless, its own online service). */
    IN_EMULATOR,
}

/**
 * What playing together needs in each emulator, from the emulator's own source, as the values it
 * keeps in its own files (written through Fuse's settings changes, so each can be put back) or the
 * command line it reads:
 *
 * - RetroArch v1.22.2 (`retroarch.c`, `retroarch_parse_input`): `--host` hosts as player 1,
 *   `--connect=HOST` joins as player 2, `--port=PORT` (55435 by default, `config.def.h`
 *   `RARCH_DEFAULT_PORT`) and `--nick=NICK` for the name shown to the others. Both copies run the same
 *   core with the same game.
 * - PPSSPP v1.20.4 (`Core/Config.cpp`, `[Network]`): `EnableWlan` turns the PSP's wireless on,
 *   `proAdhocServer` is the ad hoc server every player reaches (`socom.cc` by default), and
 *   `EnableAdhocServer` runs PPSSPP's own server on port 27312 (`Core/HLE/sceNetAdhoc.cpp`,
 *   `__NetAdhocInit`; `proAdhoc.h` `SERVER_PORT`). The copy that hosts names its own address, not
 *   `localhost`: the server tells the others each player's address as it saw it
 *   (`proAdhocServer.cpp`, `login_user_stream`). `[SystemParam] NickName` is the name players see.
 *   A game with a revival server goes online through it once the wireless is on (`InfrastructureAutoDNS`,
 *   on by default) with `InfrastructureUsername`, which is the nickname only when that is 3 to 16
 *   letters, digits, `-` or `_` (`DefaultInfrastructureUsername`), else empty, and signing in fails.
 * - Dolphin 2609a (`Core/Config/NetplaySettings.cpp`, `[NetPlay]` in Dolphin.ini): `Nickname`,
 *   `TraversalChoice` (`direct` to reach an address, `traversal` to reach a host through Dolphin's
 *   traversal server), and `Address` or `HostCode`, whichever its Connect tab offers for that choice
 *   (`DolphinQt/NetPlay/NetPlaySetupDialog.cpp`). A host code is the 8 hex digits the traversal
 *   server gives a host (`Common/TraversalServer.cpp`, `GetRandomHostId`, `{:08x}`). WiiLink, an
 *   alternative provider for the Wii's discontinued WiiConnect24 channels (Forecast, the Nintendo
 *   Channel), is its `[Core] EnableWiiLink`, off by default (`Core/Config/MainSettings.cpp`,
 *   `MAIN_WII_WIILINK_ENABLE`; `DolphinQt/Settings/WiiPane.cpp`, under WiiLink's terms of service).
 * - RPCS3 v0.0.43 (`rpcs3/Emu/system_config.h`, `node_net`): `Net: Internet enabled: Connected` and
 *   `PSN status: RPCN`, with an account in `rpcn.yml` ([Rpcn]).
 * - shadPS4 v0.20.0 (`src/core/emulator_settings.h`, `NetworkSettings`): `connected_to_network` and
 *   `shad_net_enabled` in `config.json`'s `Network` group (its `General` group from 0.17 to 0.19)
 *   take games online through ShadNet, its own service. Each of its users signs in with
 *   `shadnet_npid`, `shadnet_password` and `shadnet_token` in `users.json` (`src/core/user_manager.h`),
 *   the password kept and sent as typed (`src/shadnet/client.cpp`, the Login request), so Fuse
 *   never writes it: the person signs in from shadPS4's own users.
 * - PCSX2 v2.8.2 (`pcsx2/Pcsx2Config.cpp`, `DEV9Options::LoadSave`, `[DEV9/Eth]`): `EthEnable`,
 *   `EthApi = Sockets` and `EthDevice = Auto`, which needs no administrator rights and answers the
 *   game's DHCP itself (`pcsx2/DEV9/sockets.cpp`, `InitInternalServer(..., true)`).
 * - Azahar 2125.0.1 (`src/citra_qt/configuration/config.cpp`, `ReadMultiplayerValues`,
 *   `[Multiplayer]` in qt-config.ini): `nickname` is the name Direct Connect to Room and the room
 *   list join as, with the room's `ip` and `port` (24872, `Network::DefaultRoomPort`); `room_nickname`
 *   is the name Create Room hosts as, and `host_type` 0 lists the room publicly, 1 leaves it
 *   unlisted (`multiplayer/host_room.ui`). A public room is announced to the room list first and
 *   isn't created when that fails (`HostRoomWindow::Host`); an unlisted one is joined by its
 *   address alone. A name is 4 to 20 letters, digits, spaces, `.`, `_` or `-`
 *   (`multiplayer/validation.h`). Each key is written with `key\default` beside it
 *   (`QtConfig::WriteSetting`), as Fuse writes every Azahar setting.
 * - Eden v0.2.1 (`src/qt_common/config/uisettings.h`, `Category::Multiplayer`): the same keys, in
 *   the same section, kept as its other settings are.
 */
object PlayTogether {
    const val RETROARCH_PORT = 55435
    const val PPSSPP_SERVER_PORT = 27312
    const val PPSSPP_PUBLIC_SERVER = "socom.cc"

    /** How [family] (`retroarch`, `ppsspp`, from `linux.ppsspp`) plays together; null when Fuse knows nothing of it. */
    fun way(family: String): TogetherWay? = when (family) {
        "retroarch", "retroarch-steam" -> TogetherWay.NETPLAY
        "ppsspp" -> TogetherWay.AD_HOC
        "dolphin" -> TogetherWay.NETPLAY_WINDOW
        "rpcs3", "shadps4" -> TogetherWay.ONLINE_SERVICE
        "pcsx2" -> TogetherWay.NETWORK_ADAPTER
        "azahar", "azaharplus", "lime3ds", "mandarine", "eden", "eden-nightly", "citron" -> TogetherWay.ROOMS
        "ryujinx", "melonds", "cemu" -> TogetherWay.IN_EMULATOR
        else -> null
    }

    /** RetroArch's arguments to host ([address] null) or join [address], as [nick]. */
    fun retroArchArgs(address: String?, port: Int = RETROARCH_PORT, nick: String?): List<String> = buildList {
        if (address == null) add("--host") else add("--connect=$address")
        if (port != RETROARCH_PORT) add("--port=$port")
        nickname(nick, 32)?.let { add("--nick=$it") }
    }

    /**
     * RetroArch's command [plan] with netplay's [args] put just before its `-L` (the core), so they
     * are read as options on every system's build; null when the command has no `-L` to place them by.
     */
    fun withNetplay(plan: io.github.matiyaaa.fuse.model.LaunchPlan, args: List<String>): io.github.matiyaaa.fuse.model.LaunchPlan? {
        val command = plan as? io.github.matiyaaa.fuse.model.LaunchPlan.Command ?: return null
        val at = command.argv.indexOf("-L").takeIf { it > 0 } ?: return null
        return command.copy(argv = command.argv.subList(0, at) + args + command.argv.subList(at, command.argv.size))
    }

    /** PPSSPP set to reach [server], running its own server when [hosting]. */
    fun ppsspp(server: String, hosting: Boolean, nick: String?): List<OnlineValue> = buildList {
        add(OnlineValue(null, "Network", "EnableWlan", "True"))
        add(OnlineValue(null, "Network", "EnableAdhocServer", if (hosting) "True" else "False"))
        add(OnlineValue(null, "Network", "proAdhocServer", server))
        // The PSP's own name, kept short and plain: a game reads it into its own room for it, and a name too long for that is refused (`sceUtility.cpp`, PSP_SYSTEMPARAM_ID_STRING_NICKNAME).
        nickname(nick, 16)?.let { add(OnlineValue(null, "SystemParam", "NickName", it)) }
    }

    /** The name PPSSPP signs in to games' revival servers as, from [name]: letters, digits, `-` and `_`, 3 to 16 of them; null when too little is left. */
    fun ppssppUsername(name: String?): String? =
        name?.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }?.take(16)?.takeIf { it.length >= 3 && it != "PPSSPP" }

    /** Dolphin set to connect straight to [address] (when joining), as [nick]. */
    fun dolphin(address: String?, nick: String?): List<OnlineValue> = buildList {
        nickname(nick, 30)?.let { add(OnlineValue(null, "NetPlay", "Nickname", it)) }
        if (address != null) {
            add(OnlineValue(null, "NetPlay", "TraversalChoice", "direct"))
            add(OnlineValue(null, "NetPlay", "Address", address))
        }
    }

    /** Dolphin set to join the game its traversal server knows by [code] (see [hostCode]), as [nick]. */
    fun dolphinCode(code: String, nick: String?): List<OnlineValue> = buildList {
        nickname(nick, 30)?.let { add(OnlineValue(null, "NetPlay", "Nickname", it)) }
        add(OnlineValue(null, "NetPlay", "TraversalChoice", "traversal"))
        add(OnlineValue(null, "NetPlay", "HostCode", code))
    }

    /** Dolphin's WiiConnect24 through WiiLink, [on] or off. */
    fun wiiLink(on: Boolean): OnlineValue = OnlineValue(null, "Core", "EnableWiiLink", if (on) "True" else "False")

    /** A host code as Dolphin's traversal server makes one: 8 hex digits, lower case as it writes them; null for anything else. */
    fun hostCode(text: String): String? = text.trim().lowercase().takeIf { Regex("^[0-9a-f]{8}$").matches(it) }

    /**
     * Azahar's or Eden's rooms, as [nick]: the name it joins and hosts as, a room it creates left
     * unlisted (found by its address, not on the public list), and, when joining, [address] waiting
     * in Direct Connect. Empty when [nick] makes no name a room takes and there is no address.
     */
    fun rooms(nick: String?, address: String?): List<OnlineValue> = buildList {
        roomName(nick)?.let {
            add(OnlineValue(null, "Multiplayer", "nickname", it))
            add(OnlineValue(null, "Multiplayer", "room_nickname", it))
        }
        add(OnlineValue(null, "Multiplayer", "host_type", "1"))
        if (address != null) add(OnlineValue(null, "Multiplayer", "ip", address))
    }

    /** A name a room takes (`multiplayer/validation.h`): 4 to 20 letters, digits, spaces, `.`, `_` or `-`; null when [name] leaves too little. */
    fun roomName(name: String?): String? =
        name?.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " ._-" }?.trim()?.take(20)?.trim()?.takeIf { it.length >= 4 }

    /** RPCS3's network on and through RPCN (the YAML section is `Net`). */
    val rpcs3: List<OnlineValue> = listOf(
        OnlineValue(null, "Net", "Internet enabled", "Connected"),
        OnlineValue(null, "Net", "PSN status", "RPCN"),
    )

    /**
     * shadPS4's network on and through ShadNet, in the group its [version] keeps them in (Fuse
     * Tune's reading of each release; a version Fuse can't tell is taken as the newest).
     */
    fun shadPs4(version: String?): List<OnlineValue> {
        val settings = io.github.matiyaaa.fuse.launch.settings.Tune.forVersion("shadps4", version)
        return listOf("connected_to_network", "shad_net_enabled").mapNotNull { key ->
            settings.firstOrNull { it.key == key && it.file == null }?.let { OnlineValue(null, it.section, key, "true") }
        }
    }

    /** PCSX2's network adapter on, through the computer's own connection. */
    val pcsx2: List<OnlineValue> = listOf(
        OnlineValue(null, "DEV9/Eth", "EthEnable", "true"),
        OnlineValue(null, "DEV9/Eth", "EthApi", "Sockets"),
        OnlineValue(null, "DEV9/Eth", "EthDevice", "Auto"),
    )

    /**
     * A person's name as an emulator can show it: letters, digits, spaces, `-` and `_`, at most
     * [max] characters; null when nothing of it is left.
     */
    fun nickname(name: String?, max: Int): String? =
        name?.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }?.trim()?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether [values] are what [current] holds (a switch's `True` and `true` alike). */
    fun ready(values: List<OnlineValue>, current: (OnlineValue) -> String?): Boolean =
        values.all { v -> current(v)?.trim()?.equals(v.value, ignoreCase = true) == true }

    /** An address another device can be reached at: an IPv4 address or a host name, no port or path. */
    fun address(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 253) return null
        return t.takeIf { Regex("^[A-Za-z0-9.-]+$").matches(it) && !it.startsWith('.') && !it.startsWith('-') }
    }
}
