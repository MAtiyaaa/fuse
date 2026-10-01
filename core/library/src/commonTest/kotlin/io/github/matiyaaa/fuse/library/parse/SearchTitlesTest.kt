package io.github.matiyaaa.fuse.library.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTitlesTest {

    /** File names as Fuse keeps them (no extension), each with the name it should be searched by. */
    private val cases = listOf(
        // No-Intro and Redump.
        "Legend of Zelda, The - A Link to the Past (USA) (Rev 1)" to "The Legend of Zelda - A Link to the Past",
        "Super Mario World (USA)" to "Super Mario World",
        "Final Fantasy VII (Europe) (En,Fr,De)" to "Final Fantasy VII",
        "Star Fox 2 (Japan) (Beta)" to "Star Fox 2",
        "Super Mario 64 (USA) (Proto)" to "Super Mario 64",
        "Super Mario Bros. (World) (Virtual Console)" to "Super Mario Bros.",
        "Final Fantasy VII (USA) (Disc 1)" to "Final Fantasy VII",
        "Sonic CD (USA) (Track 01)" to "Sonic CD",
        "Sonic the Hedgehog (USA, Europe)" to "Sonic the Hedgehog",
        "Tetris (World)" to "Tetris",
        "Tetris (Tengen) (USA)" to "Tetris",
        "Pokemon - Red Version (USA, Europe) (SGB Enhanced)" to "Pokemon - Red Version",
        "Metroid Fusion (USA) (Rev 1) [!]" to "Metroid Fusion",
        "Castlevania - Symphony of the Night (USA) (Disc 1 of 2)" to "Castlevania - Symphony of the Night",
        "Grand Theft Auto - Vice City (USA) (v2.00)" to "Grand Theft Auto - Vice City",
        "Cool Spot (USA) (Beta) (1993-05-14)" to "Cool Spot",
        "Adventures of Lolo, The (USA) (Rev A)" to "The Adventures of Lolo",
        "Pokemon Pinball (USA) (Rumble Version)" to "Pokemon Pinball",
        "Mega Man X (USA) (Hack by Somebody)" to "Mega Man X",
        // GoodTools.
        "Super Mario World (U) [!]" to "Super Mario World",
        "Super Mario World (J) [T+Eng1.0_Translator]" to "Super Mario World",
        "Super Mario World (E) (M5) [o1]" to "Super Mario World",
        "Contra (UE)" to "Contra",
        "Contra (JU) [b1]" to "Contra",
        "Contra (U) [h1C]" to "Contra",
        "Contra (U) (PRG1)" to "Contra",
        "Contra (J) [T-Eng by Someone]" to "Contra",
        "Super Mario World (Hack) by Kaizo" to "Super Mario World",
        "Mega Man 2 (U) [h3]" to "Mega Man 2",
        // TOSEC.
        "Lemmings (1991)(Psygnosis)(US)[cr Skid Row][a]" to "Lemmings",
        "Turrican II v1.1 (1991)(Rainbow Arts)[h]" to "Turrican II",
        "Lemmings (1991)(Psygnosis)(Disk 1 of 2)" to "Lemmings",
        "Speedball 2 (1990)(Bitmap Brothers)(Disk 1 of 2)[cr Fairlight]" to "Speedball 2",
        // Scene releases and PC installers.
        "Super.Mario.World.USA.SNES-XYZ" to "Super Mario World",
        "Dredge.v1.4.2-GOG" to "Dredge",
        "DREDGE_v1.2.0_(64bit)" to "DREDGE",
        "setup_dredge_1.0.0" to "Dredge",
        "setup_hollow_knight_1.5.78.11833_(64bit)_(51539)" to "Hollow Knight",
        "Dredge-SKIDROW" to "Dredge",
        "Dredge [FitGirl Repack]" to "Dredge",
        "Dredge.Deluxe.Edition-TENOKE" to "Dredge Deluxe Edition",
        "Hollow_Knight_v1.5.78" to "Hollow Knight",
        "Celeste.Build.1234" to "Celeste",
        "The.Witcher.3.Wild.Hunt.GOTY-GOG" to "The Witcher 3 Wild Hunt GOTY",
        "Cyberpunk.2077.v2.1-GOG" to "Cyberpunk 2077",
        "Half-Life.2.Episode.One-RELOADED" to "Half-Life 2 Episode One",
        "Stardew.Valley.1.6.8" to "Stardew Valley",
        "Baldurs.Gate.3.v4.1.1.3767641-GOG" to "Baldurs Gate 3",
        "S.T.A.L.K.E.R.Shadow.of.Chernobyl-RELOADED" to "STALKER Shadow of Chernobyl",
        "Elden.Ring.v1.02.3-CODEX" to "Elden Ring",
        "Hades v1.38290 (GOG)" to "Hades",
        "Cuphead Ver 1.1" to "Cuphead",
        "Cuphead Version 1.2" to "Cuphead",
        "Celeste Build 1234" to "Celeste",
        // Serials and title ids at the start, in the middle or at the end.
        "SLUS-01234 - Crash Bandicoot" to "Crash Bandicoot",
        "Crash Bandicoot [SCUS-94900]" to "Crash Bandicoot",
        "SCES_123.45.Crash Bandicoot" to "Crash Bandicoot",
        "SLUS_200.62.Grand Theft Auto 3" to "Grand Theft Auto 3",
        "Crash Bandicoot SCUS-94900 (USA)" to "Crash Bandicoot",
        "ULUS10041 - Lumines" to "Lumines",
        "UCUS-98612 Daxter" to "Daxter",
        "BLUS30443 - Demon's Souls" to "Demon's Souls",
        "NPUB30910 - Journey" to "Journey",
        "PCSE00120 Persona 4 Golden" to "Persona 4 Golden",
        "GALE01 - Super Smash Bros Melee" to "Super Smash Bros Melee",
        "Super Mario Galaxy [RMGE01]" to "Super Mario Galaxy",
        "0004000000055D00 Pokemon Y" to "Pokemon Y",
        "Pokemon Y [0004000000055D00]" to "Pokemon Y",
        "Pokemon Y 0004000000055D00" to "Pokemon Y",
        "The Legend of Zelda Breath of the Wild [01007EF00011E000][v0] (13.2 GB)" to "The Legend of Zelda Breath of the Wild",
        "Zelda BOTW [01007EF00011E000][US][v196608]" to "Zelda BOTW",
        "Hades [0100535012974000][v0][NSP]" to "Hades",
        "Hollow Knight [DLC]" to "Hollow Knight",
        "Hollow Knight [Update][v65536]" to "Hollow Knight",
        "BLUS30001-[Metal Gear Solid 4]" to "Metal Gear Solid 4",
        "Gran Turismo 5 [BCES00569]" to "Gran Turismo 5",
        // List numbers and codes in front, and numbers that are the name.
        "001 - Super Mario Bros" to "Super Mario Bros",
        "0042 Mario" to "Mario",
        "#12 - Contra" to "Contra",
        "1. Contra" to "Contra",
        "[001] Contra" to "Contra",
        "(001) Contra" to "Contra",
        "12.Pepsiman" to "Pepsiman",
        "001_Pepsiman" to "Pepsiman",
        "A123 - Pepsiman" to "Pepsiman",
        "12 Contra" to "12 Contra",
        "1942" to "1942",
        "007 GoldenEye" to "007 GoldenEye",
        "1080 Snowboarding" to "1080 Snowboarding",
        "3D Dot Game Heroes" to "3D Dot Game Heroes",
        "10-Yard Fight (USA)" to "10-Yard Fight",
        "1943 - The Battle of Midway (USA)" to "1943 - The Battle of Midway",
        // Regions written as words.
        "Contra USA" to "Contra",
        "Contra_EUR" to "Contra",
        "Contra PAL" to "Contra",
        "Contra NTSC-U" to "Contra",
        "Contra (NTSC-J)" to "Contra",
        "Contra JPN" to "Contra",
        "Super Mario World" to "Super Mario World",
        "Among Us" to "Among Us",
        // Copies, sizes, discs, versions, hacks and translations.
        "Contra (1)" to "Contra",
        "Contra - Copy" to "Contra",
        "Contra - Copy (2)" to "Contra",
        "Copy of Contra" to "Contra",
        "Contra copy 2" to "Contra",
        "Contra (1.03 GB)" to "Contra",
        "Contra [1.2GB]" to "Contra",
        "Contra 700MB" to "Contra",
        "Final Fantasy VII Disc 1" to "Final Fantasy VII",
        "Final Fantasy VII CD1" to "Final Fantasy VII",
        "Monkey Island Disk A" to "Monkey Island",
        "Contra [T-En by X]" to "Contra",
        "Contra v1.0.2" to "Contra",
        "Contra (Rev A)" to "Contra",
        "Contra (V1.1)" to "Contra",
        "Contra Ver. 1.1" to "Contra",
        // Separators.
        "Super_Mario_World" to "Super Mario World",
        "Super.Mario.World" to "Super Mario World",
        "Castlevania_-_Symphony_of_the_Night_(USA)" to "Castlevania - Symphony of the Night",
        "Super   Mario    World" to "Super Mario World",
        "Legend_of_Zelda,_The_-_A_Link_to_the_Past_(USA)" to "The Legend of Zelda - A Link to the Past",
        // Camel case, split only where it is clearly two words.
        "SuperMarioWorld" to "Super Mario World",
        "HollowKnight" to "Hollow Knight",
        "FireRed" to "Fire Red",
        "PokemonFireRed" to "Pokemon Fire Red",
        "Mario64" to "Mario 64",
        "NBA2K" to "NBA2K",
        "DmC" to "DmC",
        "NieR" to "NieR",
        "McDonaldLand" to "McDonald Land",
        // Names that look like junk but are the name.
        "Dr. Mario (World)" to "Dr. Mario",
        "Kingdom Hearts HD 2.5 ReMIX" to "Kingdom Hearts HD 2.5 ReMIX",
        "Mega Man Battle Network 4.5 - Real Operation (Japan)" to "Mega Man Battle Network 4.5 - Real Operation",
        "Spider-Man (USA)" to "Spider-Man",
        "Pac-Man (USA) (Namco)" to "Pac-Man",
        "Sonic & Knuckles (World)" to "Sonic & Knuckles",
        "Pokemon - FireRed Version (USA, Europe)" to "Pokemon - FireRed Version",
        "Cyberpunk 2077" to "Cyberpunk 2077",
        "S.T.A.L.K.E.R." to "S.T.A.L.K.E.R.",
        "F.E.A.R. 2 - Project Origin" to "F.E.A.R. 2 - Project Origin",
        "Grand Theft Auto V" to "Grand Theft Auto V",
        "Tom Clancy's Splinter Cell (USA)" to "Tom Clancy's Splinter Cell",
        "Doom 3 BFG Edition" to "Doom 3 BFG Edition",
        "Mega Man X3 (USA)" to "Mega Man X3",
        "R4 - Ridge Racer Type 4 (USA)" to "R4 - Ridge Racer Type 4",
        "WarioWare, Inc. - Mega Microgame\$! (USA)" to "WarioWare, Inc. - Mega Microgame\$!",
        // More from real collections.
        "Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible)" to "Pokemon - Gold Version",
        "Legend of Zelda, The - Ocarina of Time - Master Quest (USA) (GameCube)" to "The Legend of Zelda - Ocarina of Time - Master Quest",
        "Resident Evil 2 (USA) (Disc 1) (Leon)" to "Resident Evil 2",
        "Metal Gear Solid (USA) (Disc 1) (Rev 1)" to "Metal Gear Solid",
        "Mortal Kombat II (U) [!]" to "Mortal Kombat II",
        "Sonic The Hedgehog 2 (W) [!]" to "Sonic The Hedgehog 2",
        "Final_Fantasy_VI_(J)_[T+Eng]" to "Final Fantasy VI",
        "Chrono Trigger (USA) [Hack by X v1.2]" to "Chrono Trigger",
        "Mega_Man_X_v1.1_USA" to "Mega Man X",
        "Street.Fighter.II.Turbo.SNES" to "Street Fighter II Turbo",
        "Mario Kart 8 Deluxe [0100152000022000][v0]" to "Mario Kart 8 Deluxe",
        "Animal Crossing - New Horizons [01006F8002326000][v0] (8.2 GB)" to "Animal Crossing - New Horizons",
        "Disgaea 5 Complete [01004B100AF18000][v0][US]" to "Disgaea 5 Complete",
        "Shovel.Knight.Treasure.Trove.v4.1-GOG" to "Shovel Knight Treasure Trove",
        "Grand_Theft_Auto_San_Andreas_(USA)_(v1.03)" to "Grand Theft Auto San Andreas",
        "SLES-50330 Ratchet & Clank" to "Ratchet & Clank",
        "Tekken 3 - Track 02" to "Tekken 3",
        "Spyro the Dragon (USA) (Demo)" to "Spyro the Dragon",
        "007 - Nightfire (USA)" to "007 - Nightfire",
        "2048 (Unl)" to "2048",
        "Contra III - The Alien Wars (USA)" to "Contra III - The Alien Wars",
        "Wolfenstein 3D (1992)(id Software)" to "Wolfenstein 3D",
        "Portal 2 [GOG]" to "Portal 2",
        "Ori and the Blind Forest - Definitive Edition (GOG)" to "Ori and the Blind Forest - Definitive Edition",
        "Katamari Damacy REROLL v1.0" to "Katamari Damacy REROLL",
        "hollow knight" to "Hollow Knight",
        "the legend of zelda" to "The Legend of Zelda",
        "MegaMan X" to "MegaMan X",
        "Kirby's Dream Land (USA, Europe)" to "Kirby's Dream Land",
        "Ys I & II (Japan)" to "Ys I & II",
        // Nothing but tags or codes.
        "(USA)" to "(USA)",
        "[Dredge]" to "Dredge",
        "BLUS30001" to "BLUS30001",
    )

    @Test
    fun everyNamingStyleGivesTheName() {
        assertTrue(cases.size >= 80)
        val wrong = cases.mapNotNull { (file, expected) ->
            SearchTitles.clean(file).takeIf { it != expected }?.let { "\"$file\" -> \"$it\", expected \"$expected\"" }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun alternativesOfferWhatTheTitleKept() {
        assertEquals(listOf("Contra"), SearchTitles.alternatives("12 Contra"))
        assertEquals(listOf("GoldenEye"), SearchTitles.alternatives("007 GoldenEye"))
        assertEquals("Earth Bound", SearchTitles.clean("EarthBound (USA)"))
        assertEquals(listOf("EarthBound"), SearchTitles.alternatives("EarthBound (USA)"))
        assertEquals(emptyList(), SearchTitles.alternatives("Super Mario World (USA)"))
        assertEquals(emptyList(), SearchTitles.alternatives("1942"))
        assertEquals(emptyList(), SearchTitles.alternatives("1080 Snowboarding"))
    }

    @Test
    fun cleaningIsStable() {
        // A search title cleaned again stays the same, so a stored one can be passed through safely.
        val unstable = cases.map { it.second }.filter { SearchTitles.clean(it) != it }
        assertTrue(unstable.isEmpty(), unstable.joinToString("\n"))
    }
}
