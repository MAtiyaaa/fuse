package io.github.matiyaaa.fuse.ui.shell.screenshots

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import java.io.File

/**
 * The library shown in the README screenshots: well-known games, as a real library names them
 * (No-Intro and Redump file names, Switch title ids), with the details a scraper fills in. The files
 * are empty placeholders, never game data. Their art is not stored here: Fuse fetches it while the
 * screenshots are made (see [ReadmeScreenshots]), as it does on a device.
 */
internal object SampleLibrary {
    /**
     * One game on disk. [file] is the name without its extension; with [discs] above one it is
     * written as "<file> (Disc n)" files that Fuse groups into one game. [title] and [details] are
     * what a scraper would fill in. [steamGridDbId] pins the game on SteamGridDB when its name alone
     * finds the wrong one.
     */
    data class Sample(
        val folder: String,
        val file: String,
        val ext: String,
        val title: String,
        val details: GameMetadata,
        val discs: Int = 1,
        val favorite: Boolean = false,
        /** Extra files next to the game (updates, DLC), named in full. */
        val extras: List<String> = emptyList(),
        val steamGridDbId: Long? = null,
    ) {
        /** Every file the game is made of, relative to the library folder. */
        val files: List<String>
            get() = if (discs > 1) (1..discs).map { "$folder/$file (Disc $it).$ext" } else listOf("$folder/$file.$ext")
    }

    /** A finished play session: it ended [endedHoursAgo] hours ago and lasted [minutes]. */
    data class Session(val title: String, val endedHoursAgo: Double, val minutes: Int)

    private fun about(
        year: Int,
        developer: String,
        publisher: String,
        genres: List<String>,
        players: String,
        description: String,
        franchise: String? = null,
    ) = GameMetadata(
        description = description,
        releaseYear = year,
        developer = developer,
        publisher = publisher,
        genres = genres,
        franchise = franchise,
        players = players,
        source = MetadataSource.LOCAL,
    )

    private const val ZELDA = "The Legend of Zelda"
    private const val MARIO = "Super Mario"
    private const val MARIO_KART = "Mario Kart"
    private const val METROID = "Metroid"
    private const val SONIC = "Sonic the Hedgehog"
    private const val POKEMON = "Pokémon"

    val games = listOf(
        Sample(
            "switch", "The Legend of Zelda Breath of the Wild [01007EF00011E000][v0]", "nsp", "The Legend of Zelda: Breath of the Wild",
            about(
                2017, "Nintendo EPD", "Nintendo", listOf("Action-adventure", "Open world"), "1",
                "Link wakes from a hundred-year sleep in a Hyrule left to the wild. Climb anything, glide " +
                    "from any height and take on the shrines and Divine Beasts in any order you like.",
                ZELDA,
            ),
            extras = listOf(
                "The Legend of Zelda Breath of the Wild [01007EF00011E800][v786432].nsp",
                "The Legend of Zelda Breath of the Wild [01007EF00011F001][v0].nsp",
            ),
        ),
        Sample(
            "switch", "Super Mario Odyssey [0100000000010000][v0]", "nsp", "Super Mario Odyssey",
            about(2017, "Nintendo EPD", "Nintendo", listOf("Platformer"), "1-2", "Mario and his living hat, Cappy, sail the Odyssey from kingdom to kingdom to stop Bowser's wedding.", MARIO),
        ),
        Sample(
            "switch", "Mario Kart 8 Deluxe [0100152000022000][v0]", "nsp", "Mario Kart 8 Deluxe",
            about(2017, "Nintendo EPD", "Nintendo", listOf("Racing"), "1-4", "Anti-gravity karting across every Mario Kart 8 track, with Battle mode rebuilt.", MARIO_KART),
        ),
        Sample(
            "switch", "Animal Crossing New Horizons [01006F8002326000][v0]", "nsp", "Animal Crossing: New Horizons",
            about(2020, "Nintendo EPD", "Nintendo", listOf("Life simulation"), "1-4", "Move to a deserted island and make it home, one day at a time."),
        ),
        Sample(
            "switch", "Hades", "nsp", "Hades",
            about(2020, "Supergiant Games", "Supergiant Games", listOf("Action", "Roguelike"), "1", "Zagreus fights his way out of the Underworld, with help from the gods of Olympus."),
        ),
        Sample(
            "snes", "Super Mario World (USA)", "sfc", "Super Mario World",
            about(1990, "Nintendo EAD", "Nintendo", listOf("Platformer"), "1-2", "Mario and Luigi meet Yoshi on a journey across Dinosaur Land.", MARIO),
        ),
        Sample(
            "snes", "Legend of Zelda, The - A Link to the Past (USA)", "sfc", "The Legend of Zelda: A Link to the Past",
            about(1991, "Nintendo EAD", "Nintendo", listOf("Action-adventure"), "1", "Link crosses between the Light World and the Dark World to stop Agahnim.", ZELDA),
            favorite = true,
        ),
        Sample(
            "snes", "Super Metroid (Japan, USA) (En,Ja)", "sfc", "Super Metroid",
            about(1994, "Nintendo R&D1", "Nintendo", listOf("Action-adventure"), "1", "Samus returns to Zebes to take back the last Metroid.", METROID),
        ),
        Sample(
            "snes", "Chrono Trigger (USA)", "sfc", "Chrono Trigger",
            about(1995, "Square", "Square", listOf("Role-playing"), "1", "Crono and his friends travel through time to stop a disaster before it happens."),
            favorite = true,
        ),
        Sample(
            "snes", "Donkey Kong Country (USA) (Rev 2)", "sfc", "Donkey Kong Country",
            about(1994, "Rare", "Nintendo", listOf("Platformer"), "1-2", "Donkey and Diddy Kong chase King K. Rool across the island to win back their bananas."),
        ),
        Sample(
            "snes", "Super Mario Kart (USA)", "sfc", "Super Mario Kart",
            about(1992, "Nintendo EAD", "Nintendo", listOf("Racing"), "1-2", "The first Mario Kart: eight racers, four cups and Battle mode.", MARIO_KART),
        ),
        Sample(
            "genesis", "Sonic The Hedgehog 2 (World) (Rev A)", "md", "Sonic the Hedgehog 2",
            about(1992, "Sega Technical Institute", "Sega", listOf("Platformer"), "1-2", "Sonic and Tails race to stop Dr. Robotnik and his Death Egg.", SONIC),
        ),
        Sample(
            "genesis", "Streets of Rage 2 (USA)", "md", "Streets of Rage 2",
            about(1992, "Sega", "Sega", listOf("Beat 'em up"), "1-2", "Axel, Blaze, Max and Skate take back the city from Mr. X."),
        ),
        Sample(
            "genesis", "Gunstar Heroes (USA)", "md", "Gunstar Heroes",
            about(1993, "Treasure", "Sega", listOf("Run and gun"), "1-2", "Red and Blue fight to keep four gems from bringing back the robot god."),
        ),
        Sample(
            "genesis", "Sonic The Hedgehog 3 (USA)", "md", "Sonic the Hedgehog 3",
            about(1994, "Sega Technical Institute", "Sega", listOf("Platformer"), "1-2", "Sonic and Tails crash on Angel Island and meet Knuckles.", SONIC),
        ),
        Sample(
            "n64", "Super Mario 64 (USA)", "z64", "Super Mario 64",
            about(1996, "Nintendo EAD", "Nintendo", listOf("Platformer"), "1", "Mario jumps into the paintings of Peach's castle to find the Power Stars.", MARIO),
        ),
        Sample(
            "n64", "Legend of Zelda, The - Ocarina of Time (USA) (Rev 2)", "z64", "The Legend of Zelda: Ocarina of Time",
            about(1998, "Nintendo EAD", "Nintendo", listOf("Action-adventure"), "1", "Link travels through time to keep the Triforce from Ganondorf.", ZELDA),
        ),
        Sample(
            "n64", "Mario Kart 64 (USA)", "z64", "Mario Kart 64",
            about(1996, "Nintendo EAD", "Nintendo", listOf("Racing"), "1-4", "Four-player karting in 3D, from Luigi Raceway to Rainbow Road.", MARIO_KART),
        ),
        Sample(
            "n64", "GoldenEye 007 (USA)", "z64", "GoldenEye 007",
            about(1997, "Rare", "Nintendo", listOf("First-person shooter"), "1-4", "James Bond stops the GoldenEye satellite, and four friends share one screen."),
        ),
        Sample(
            "psx", "Final Fantasy VII (USA)", "chd", "Final Fantasy VII",
            about(1997, "Square", "Sony Computer Entertainment", listOf("Role-playing"), "1", "Cloud and AVALANCHE take on Shinra, and then Sephiroth."),
            discs = 3, favorite = true,
        ),
        Sample(
            "psx", "Metal Gear Solid (USA)", "chd", "Metal Gear Solid",
            about(1998, "Konami Computer Entertainment Japan", "Konami", listOf("Action", "Stealth"), "1", "Solid Snake slips into Shadow Moses to stop a nuclear launch."),
            discs = 2,
        ),
        Sample(
            "psx", "Castlevania - Symphony of the Night (USA)", "chd", "Castlevania: Symphony of the Night",
            about(1997, "Konami", "Konami", listOf("Action-adventure"), "1", "Alucard explores Dracula's castle as it rises again."),
        ),
        Sample(
            "psx", "Crash Bandicoot (USA)", "chd", "Crash Bandicoot",
            about(1996, "Naughty Dog", "Sony Computer Entertainment", listOf("Platformer"), "1", "Crash spins his way across three islands to stop Dr. Neo Cortex."),
        ),
        Sample(
            "psx", "Spyro the Dragon (USA)", "chd", "Spyro the Dragon",
            about(1998, "Insomniac Games", "Sony Computer Entertainment", listOf("Platformer"), "1", "Spyro frees the dragons Gnasty Gnorc turned to crystal."),
        ),
        Sample(
            "ps2", "Shadow of the Colossus (USA)", "iso", "Shadow of the Colossus",
            about(2005, "Team Ico", "Sony Computer Entertainment", listOf("Action-adventure"), "1", "A young man crosses a forbidden land to fell sixteen colossi."),
        ),
        Sample(
            "ps2", "Grand Theft Auto - San Andreas (USA)", "iso", "Grand Theft Auto: San Andreas",
            about(2004, "Rockstar North", "Rockstar Games", listOf("Action-adventure", "Open world"), "1", "CJ comes home to Los Santos, and a state of three cities."),
        ),
        Sample(
            "ps2", "Ratchet & Clank (USA)", "iso", "Ratchet & Clank",
            about(2002, "Insomniac Games", "Sony Computer Entertainment", listOf("Platformer", "Shooter"), "1", "A Lombax mechanic and a small robot set out to save the galaxy."),
        ),
        Sample(
            "ps2", "God of War (USA)", "iso", "God of War",
            about(2005, "SCE Santa Monica Studio", "Sony Computer Entertainment", listOf("Action-adventure"), "1", "Kratos serves the gods of Olympus one last time, to bring down Ares."),
        ),
        Sample(
            "ngc", "Super Smash Bros. Melee (USA) (En,Ja) (Rev 2)", "rvz", "Super Smash Bros. Melee",
            about(2001, "HAL Laboratory", "Nintendo", listOf("Fighting"), "1-4", "Nintendo's stars brawl it out, four at once."),
            favorite = true,
        ),
        Sample(
            "ngc", "Legend of Zelda, The - The Wind Waker (USA)", "rvz", "The Legend of Zelda: The Wind Waker",
            about(2002, "Nintendo EAD", "Nintendo", listOf("Action-adventure"), "1", "Link sets sail across the Great Sea to find his sister.", ZELDA),
            favorite = true,
        ),
        Sample(
            "ngc", "Metroid Prime (USA)", "rvz", "Metroid Prime",
            about(2002, "Retro Studios", "Nintendo", listOf("Action-adventure", "First-person"), "1", "Samus explores Tallon IV through her visor.", METROID),
        ),
        Sample(
            "ngc", "Super Mario Sunshine (USA)", "rvz", "Super Mario Sunshine",
            about(2002, "Nintendo EAD", "Nintendo", listOf("Platformer"), "1", "Mario and F.L.U.D.D. clean up Isle Delfino.", MARIO),
        ),
        Sample(
            "dc", "Sonic Adventure (USA)", "chd", "Sonic Adventure",
            about(1998, "Sonic Team", "Sega", listOf("Platformer"), "1", "Sonic and five friends race to stop Chaos and Dr. Eggman.", SONIC),
        ),
        Sample(
            "dc", "Crazy Taxi (USA)", "chd", "Crazy Taxi",
            about(2000, "Hitmaker", "Sega", listOf("Racing"), "1", "Pick up a fare and get them there, any way you can."),
            favorite = true,
        ),
        Sample(
            "dc", "Soulcalibur (USA)", "chd", "Soulcalibur",
            about(1999, "Namco", "Namco", listOf("Fighting"), "1-2", "Warriors from around the world fight for the legendary sword."),
        ),
        Sample(
            "dc", "Skies of Arcadia (USA)", "chd", "Skies of Arcadia",
            about(2000, "Overworks", "Sega", listOf("Role-playing"), "1", "Vyse and his sky pirates race the Valuan Empire for the Moon Crystals."),
            discs = 2, favorite = true,
        ),
        Sample(
            "gba", "Pokemon - Emerald Version (USA, Europe)", "gba", "Pokémon Emerald",
            about(2004, "Game Freak", "Nintendo", listOf("Role-playing"), "1", "Set out across Hoenn as Team Magma and Team Aqua wake Groudon and Kyogre.", POKEMON),
        ),
        Sample(
            "gba", "Metroid Fusion (USA)", "gba", "Metroid Fusion",
            about(2002, "Nintendo R&D1", "Nintendo", listOf("Action-adventure"), "1", "Samus, changed by the X parasite, is hunted by her own double.", METROID),
        ),
        Sample(
            "gba", "Legend of Zelda, The - The Minish Cap (USA)", "gba", "The Legend of Zelda: The Minish Cap",
            about(2004, "Capcom", "Nintendo", listOf("Action-adventure"), "1", "Link shrinks to the size of the Minish to save Princess Zelda.", ZELDA),
        ),
        Sample(
            "gba", "Advance Wars (USA)", "gba", "Advance Wars",
            about(2001, "Intelligent Systems", "Nintendo", listOf("Strategy"), "1-4", "Lead the Orange Star army, turn by turn, across the map."),
        ),
        Sample(
            "gba", "Mario Kart - Super Circuit (USA)", "gba", "Mario Kart: Super Circuit",
            about(2001, "Intelligent Systems", "Nintendo", listOf("Racing"), "1-4", "Mario Kart in your pocket, with every Super Mario Kart track to unlock.", MARIO_KART),
        ),
        Sample(
            "nds", "New Super Mario Bros. (USA)", "nds", "New Super Mario Bros.",
            about(2006, "Nintendo EAD", "Nintendo", listOf("Platformer"), "1-2", "Mario returns to two dimensions to rescue Peach from Bowser Jr.", MARIO),
        ),
        Sample(
            "nds", "Mario Kart DS (USA, Australia)", "nds", "Mario Kart DS",
            about(2005, "Nintendo EAD", "Nintendo", listOf("Racing"), "1-8", "Thirty-two tracks, Mission mode and racing with up to eight friends.", MARIO_KART),
        ),
        Sample(
            "nds", "Pokemon - HeartGold Version (USA)", "nds", "Pokémon HeartGold",
            about(2009, "Game Freak", "Nintendo", listOf("Role-playing"), "1", "Johto and Kanto again, with your Pokémon walking along behind you.", POKEMON),
        ),
        Sample(
            "nds", "Professor Layton and the Curious Village (USA)", "nds", "Professor Layton and the Curious Village",
            about(2007, "Level-5", "Nintendo", listOf("Puzzle"), "1", "Professor Layton and Luke solve the riddles of St. Mystere."),
        ),
    )

    /**
     * Play history over the last few weeks, so Continue Playing, Recently Played and the playtime
     * widgets have something to show. Written through the same repository calls a real launch uses.
     */
    val sessions = listOf(
        Session("The Legend of Zelda: Breath of the Wild", 2.0, 95),
        Session("The Legend of Zelda: Breath of the Wild", 26.0, 70),
        Session("The Legend of Zelda: Breath of the Wild", 50.5, 110),
        Session("The Legend of Zelda: Breath of the Wild", 196.0, 60),
        Session("Final Fantasy VII", 20.0, 80),
        Session("Final Fantasy VII", 98.0, 120),
        Session("Final Fantasy VII", 218.0, 45),
        Session("Super Mario 64", 30.0, 35),
        Session("Super Mario 64", 146.0, 25),
        Session("Metroid Prime", 54.0, 60),
        Session("Metroid Prime", 122.0, 90),
        Session("Chrono Trigger", 75.0, 140),
        Session("Chrono Trigger", 170.0, 100),
        Session("Chrono Trigger", 266.0, 90),
        Session("Hades", 100.0, 50),
        Session("Hades", 242.0, 65),
        Session("Pokémon Emerald", 148.0, 30),
        Session("Shadow of the Colossus", 220.0, 85),
        Session("Mario Kart DS", 290.0, 40),
        Session("Sonic the Hedgehog 2", 480.0, 45),
        Session("Super Mario World", 600.0, 120),
    )

    /** Collections the user made, by name, with their games. */
    val collections = listOf(
        "Couch Multiplayer" to listOf("Super Smash Bros. Melee", "Mario Kart 64", "GoldenEye 007", "Mario Kart 8 Deluxe", "Soulcalibur"),
        "Long Adventures" to listOf("Final Fantasy VII", "Chrono Trigger", "Skies of Arcadia", "The Legend of Zelda: Breath of the Wild", "Pokémon HeartGold"),
        "Pocket Picks" to listOf("Pokémon Emerald", "Metroid Fusion", "Advance Wars", "Professor Layton and the Curious Village"),
    )

    /**
     * A Channels board as a user would arrange it in Settings, Home: the default list without the
     * RetroAchievements and Cartridge widgets (neither is set up here), plus playtime, collections
     * and the clock.
     */
    val channelBoard: List<HomeWidget> = listOf(
        Triple(WidgetKind.CONTINUE_PLAYING, 2, 2),
        Triple(WidgetKind.CLOCK, 1, 1),
        Triple(WidgetKind.PLAYTIME_TOTAL, 1, 1),
        Triple(WidgetKind.PLAYTIME_WEEK, 2, 1),
        Triple(WidgetKind.SYSTEMS, 2, 1),
        Triple(WidgetKind.RECENTLY_ADDED, 2, 1),
        Triple(WidgetKind.FAVORITES, 2, 1),
        Triple(WidgetKind.COLLECTIONS, 2, 1),
    ).mapIndexed { i, (k, w, h) -> HomeWidget(id = k.name.lowercase(), kind = k, order = i, width = w, height = h) }

    val platformCount: Int get() = games.map { it.folder }.distinct().size

    /** Writes the sample files (tiny placeholders, never real game data) under [root]. */
    fun writeTo(root: File) {
        for (g in games) {
            File(root, g.folder).mkdirs()
            for (f in g.files) File(root, f).writeBytes(ByteArray(512))
            for (extra in g.extras) File(root, "${g.folder}/$extra").writeBytes(ByteArray(256))
        }
    }

    /**
     * Finds each sample's game by its file, then gives it the details a scraper would (its proper
     * title among them) and pins it on SteamGridDB when [Sample.steamGridDbId] says so. Returns the
     * games by title.
     */
    suspend fun applyDetails(data: FuseData, root: File): Map<String, GameId> {
        val byPath = data.games.paths().associate { (id, path) -> File(path).canonicalPath to id }
        return games.associate { g ->
            val id = g.files.firstNotNullOfOrNull { byPath[File(root, it).canonicalPath] }
                ?: error("Sample game not found in the library: ${g.files.first()}")
            data.games.applyMetadata(id, g.details, titleFromMetadata = g.title, onlyFillEmpty = false)
            if (g.steamGridDbId != null) data.games.updateLinks(id) { it.copy(steamGridDbGameId = g.steamGridDbId) }
            g.title to id
        }
    }

    /** Marks favourites, makes the collections and records the play sessions, oldest first. */
    suspend fun applyHistory(store: FuseStore, data: FuseData, ids: Map<String, GameId>, now: Long) {
        fun id(title: String) = ids[title] ?: error("Sample game not found in the library: $title")
        for (g in games.filter { it.favorite }) store.library.setFavorite(id(g.title), true)
        for ((name, titles) in collections) {
            val collection = store.collections.create(name)
            for (title in titles) store.collections.add(collection, id(title))
        }
        val emulators = ScreenshotServices.InstalledEmulators
        val platforms = games.associate { it.title to PlatformId(it.folder) }
        fun emulatorFor(title: String): EmulatorId? = emulators.firstOrNull { platforms.getValue(title) in it.platforms }?.id
        for (s in sessions.sortedByDescending { it.endedHoursAgo }) {
            val end = now - (s.endedHoursAgo * 3_600_000).toLong()
            val start = end - s.minutes * 60_000L
            val session = data.playSessions.start(id(s.title), emulatorFor(s.title), start)
            data.playSessions.end(session, end)
        }
    }
}
