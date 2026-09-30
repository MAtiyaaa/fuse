package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * RetroArch cores per platform and host, default first. Every core name appears in ES-DE's
 * es_systems.xml for the matching system (Android `cores/<core>_libretro_android.so`, Linux
 * `%CORE_RETROARCH%/<core>_libretro.so`, master [Sources.ESDE_COMMIT]). The order puts the common
 * choices first; the user picks another per platform or game (ScopedSettings.RetroArchCore).
 */
object RetroArchCores {
    private val shared: Map<String, List<String>> = mapOf(
        "psx" to listOf("swanstation", "pcsx_rearmed", "mednafen_psx_hw", "mednafen_psx"),
        "ps2" to listOf("pcsx2", "pcee2"),
        "psp" to listOf("ppsspp"),
        "nds" to listOf("melonds", "desmume", "melondsds", "desmume2015", "noods", "skyemu"),
        "nintendo-dsi" to listOf("melondsds", "melonds"),
        "gb" to GB,
        "gbc" to GB,
        "gba" to listOf("mgba", "gpsp", "vbam", "vba_next", "noods", "skyemu", "mesen2"),
        "nes" to NES,
        "famicom" to NES,
        "fds" to listOf("fceumm", "nestopia", "mesen", "mesen2", "rustynes"),
        "snes" to SNES,
        "sfam" to SNES,
        "ngc" to listOf("dolphin"),
        "wii" to listOf("dolphin"),
        "wiiu" to listOf("cemu"),
        "virtualboy" to listOf("mednafen_vb"),
        "pokemon-mini" to listOf("pokemini"),
        "dc" to listOf("flycast"),
        "saturn" to listOf("yabasanshiro", "mednafen_saturn", "yabause"),
        "genesis" to MD,
        "segacd" to MD,
        "sega32" to listOf("picodrive", "blastem"),
        "sms" to listOf("genesis_plus_gx", "picodrive", "smsplus", "gearsystem", "genesis_plus_gx_wide", "blastem", "mesen2"),
        "gamegear" to listOf("genesis_plus_gx", "picodrive", "gearsystem", "smsplus", "genesis_plus_gx_wide", "blastem", "mesen2"),
        "sg1000" to listOf("genesis_plus_gx", "gearsystem", "genesis_plus_gx_wide", "blastem", "bluemsx"),
        "neo-geo-cd" to listOf("neocd", "geolith"),
        "neo-geo-pocket" to listOf("mednafen_ngp", "race"),
        "neo-geo-pocket-color" to listOf("mednafen_ngp", "race"),
        "tg16" to PCE,
        "turbografx-cd" to PCE,
        "atari2600" to listOf("stella", "stella2014", "stella2023", "tia"),
        "atari5200" to listOf("a5200", "atari800"),
        "atari7800" to listOf("prosystem"),
        "lynx" to listOf("handy", "mednafen_lynx", "gearlynx", "holani"),
        "jaguar" to listOf("virtualjaguar"),
        "wonderswan" to listOf("mednafen_wswan", "mesen2"),
        "wonderswan-color" to listOf("mednafen_wswan", "mesen2"),
        "dos" to listOf("dosbox_pure", "dosbox_core", "dosbox_svn", "virtualxt"),
        "scummvm" to listOf("scummvm"),
        "msx" to listOf("bluemsx", "fmsx"),
        "c64" to listOf("vice_x64", "vice_x64sc", "vice_xscpu64", "vice_x128"),
        "amiga" to listOf("puae", "puae2021", "amiberry"),
        "3do" to listOf("opera"),
        "colecovision" to listOf("gearcoleco", "bluemsx", "jollycv", "blastem"),
        "intellivision" to listOf("freeintv"),
        "vectrex" to listOf("vecx"),
    )

    private val ARCADE_TAIL = listOf("mame2010", "mame2003", "mame2000", "hbmame", "fbalpha2012", "geolith", "flycast", "dice", "supermodel")

    /** Android differences: the GLES3 Mupen64Plus-Next build and MAME's `mamearcade` core name. */
    val android: Map<PlatformId, List<String>> = (
        shared + mapOf(
            "n64" to listOf("mupen64plus_next_gles3", "parallel_n64"),
            "3ds" to listOf("citra", "citra2018"),
            "new-nintendo-3ds" to listOf("citra", "citra2018"),
            "arcade" to listOf("fbneo", "mame2003_plus", "mamearcade") + ARCADE_TAIL,
            "neogeoaes" to listOf("fbneo", "geolith", "mamearcade"),
        )
        ).mapKeys { PlatformId(it.key) }

    /** Linux differences: desktop core names and a few extra cores ES-DE lists only on Linux. */
    val linux: Map<PlatformId, List<String>> = (
        shared + mapOf(
            "n64" to listOf("mupen64plus_next", "parallel_n64"),
            "3ds" to listOf("azahar", "citra", "citra2018"),
            "new-nintendo-3ds" to listOf("azahar", "citra", "citra2018"),
            "arcade" to listOf("fbneo", "mame2003_plus", "mame") + ARCADE_TAIL + "kronos",
            "neogeoaes" to listOf("fbneo", "geolith", "mame"),
            "neo-geo-cd" to listOf("neocd", "fbneo", "geolith"),
            "saturn" to listOf("yabasanshiro", "mednafen_saturn", "kronos", "yabause"),
            "amiga" to listOf("puae", "puae2021", "fsuae", "amiberry"),
            "c64" to listOf("vice_x64", "vice_x64sc", "vice_xscpu64", "vice_x128", "frodo"),
            "atari7800" to listOf("prosystem", "mame"),
            "intellivision" to listOf("freeintv", "mame"),
            "vectrex" to listOf("vecx", "mame"),
        )
        ).mapKeys { PlatformId(it.key) }

    fun coresFor(host: Host, platform: PlatformId): List<String> =
        (if (host == Host.ANDROID) android else linux)[platform].orEmpty()

    fun defaultCore(host: Host, platform: PlatformId): String? = coresFor(host, platform).firstOrNull()

    /** File name of [core] as RetroArch for Android stores it in its `cores` folder. */
    fun androidCoreFile(core: String): String = "${core}_libretro_android.so"

    /** File name of [core] on Linux. */
    fun linuxCoreFile(core: String): String = "${core}_libretro.so"
}

private val GB = listOf("gambatte", "sameboy", "mgba", "gearboy", "tgbdual", "mesen2", "bsnes", "vbam", "skyemu")
private val NES = listOf("fceumm", "nestopia", "mesen", "mesen2", "quicknes", "rustynes")
private val SNES = listOf(
    "snes9x", "bsnes", "snes9x2010", "snes9x2005_plus", "bsnes_hd_beta", "bsnes_mercury_accuracy", "mednafen_supafaust", "mesen2",
)
private val MD = listOf("genesis_plus_gx", "picodrive", "genesis_plus_gx_wide", "blastem", "clownmdemu")
private val PCE = listOf("mednafen_pce_fast", "mednafen_pce", "mednafen_supergrafx", "geargrafx", "mesen2")
