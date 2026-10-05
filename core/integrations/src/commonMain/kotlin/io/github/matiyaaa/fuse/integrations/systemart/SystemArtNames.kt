package io.github.matiyaaa.fuse.integrations.systemart

import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Maps Fuse platforms (RomM slugs) to Art Book Next system names, which are ES-DE system names.
 * PS5 and Switch 2 have no art in the pack at [SystemArtPack.REF].
 */
object SystemArtNames {

    /** Every system the pack has a logo, artwork and metadata for at [SystemArtPack.REF]. */
    val PACK: Set<String> = (
        "3do adam ags amiga amiga1200 amiga600 amigacd32 amstradcpc android androidapps androidgames " +
            "apple2 apple2gs arcade arcadia archimedes arduboy astrocade atari2600 atari5200 atari7800 " +
            "atari800 atarijaguar atarijaguarcd atarilynx atarist atarixe atomiswave bbcmicro c64 cdimono1 " +
            "cdtv chailove channelf coco colecovision consolearcade cps cps1 cps2 cps3 crvision daphne " +
            "desktop doom dos dragon32 dreamcast easyrpg electron epic famicom fba fbneo fds flash fm7 " +
            "fmtowns fpinball gamate gameandwatch gamecom gamegear gb gba gbc gc genesis gmaster gx4000 " +
            "intellivision j2me kodi laserdisc lcdgames lowresnx lutris lutro macintosh mame mark3 " +
            "mastersystem megacd megacdjp megadrive megadrivejp megaduck mess model2 model3 moto msu-md " +
            "msx msx1 msx2 msxturbor mugen multivision n3ds n64 n64dd naomi naomi2 naomigd nds neogeo " +
            "neogeocd neogeocdjp nes ngage ngp ngpc odyssey2 openbor oric palm pc pc88 pc98 pcarcade " +
            "pcengine pcenginecd pcfx pico8 playdate plus4 pokemini ports ps2 ps3 ps4 psp psvita psx " +
            "pv1000 quake samcoupe satellaview saturn saturnjp scummvm scv sega32x sega32xjp sega32xna " +
            "segacd sfc sg-1000 sgb snes snesna solarus spectravideo steam stv sufami supergrafx " +
            "supervision supracan switch symbian tanodragon tg-cd tg16 ti99 tic80 to8 triforce trs-80 " +
            "type-x uzebox vectrex vic20 videopac vircon32 virtualboy vpinball vsmile wasm4 wii wiiu " +
            "windows windows3x windows9x wonderswan wonderswancolor x1 x68000 xbox xbox360 xboxone " +
            "zmachine zx81 zxnext zxspectrum"
        ).split(' ').toSet()

    /**
     * Catalog ids that are not pack names themselves. DSi games use the DS art and the New 3DS the
     * 3DS art, because the pack has no separate systems for them.
     */
    val OVERRIDES: Map<String, String> = mapOf(
        "nintendo-dsi" to "nds",
        "3ds" to "n3ds",
        "new-nintendo-3ds" to "n3ds",
        "sfam" to "sfc",
        "ngc" to "gc",
        "pokemon-mini" to "pokemini",
        "dc" to "dreamcast",
        "sega32" to "sega32x",
        "sms" to "mastersystem",
        "sg1000" to "sg-1000",
        "neogeoaes" to "neogeo",
        "neo-geo-cd" to "neogeocd",
        "neo-geo-pocket" to "ngp",
        "neo-geo-pocket-color" to "ngpc",
        "turbografx-cd" to "tg-cd",
        "pc-fx" to "pcfx",
        "lynx" to "atarilynx",
        "jaguar" to "atarijaguar",
        "atari-st" to "atarist",
        "wonderswan-color" to "wonderswancolor",
        "win" to "windows",
        "zxs" to "zxspectrum",
        "acpc" to "amstradcpc",
        "msx2plus" to "msx2",
    )

    /**
     * The pack name for platform [id]: the explicit override, then the id itself, then the first of
     * [aliases] (normally `Platform.folderAliases`) that the pack knows. Null when the pack has no
     * art for the platform.
     */
    fun forPlatform(id: PlatformId, aliases: Set<String> = emptySet()): String? {
        val key = id.value.trim().lowercase()
        OVERRIDES[key]?.let { return it }
        if (key in PACK) return key
        return aliases.map { it.trim().lowercase() }.firstOrNull { it in PACK }
    }
}
