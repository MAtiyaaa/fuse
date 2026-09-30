package io.github.matiyaaa.fuse.ui.shell.screenshots

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import java.io.File

/**
 * The library shown in the README screenshots. Every title is invented for Fuse; none is a real
 * game. There is no artwork: Fuse draws its own generated art from each title and system colour,
 * exactly as it does for a library that has not been scraped yet.
 */
internal object SampleLibrary {
    /** One game on disk: [discs] above one writes "(Disc n)" files that Fuse groups into one game. */
    data class Sample(
        val folder: String,
        val title: String,
        val ext: String,
        val discs: Int = 1,
        val favorite: Boolean = false,
        /** Extra files next to the game (updates, DLC), named in full. */
        val extras: List<String> = emptyList(),
    )

    /** A finished play session: it ended [endedHoursAgo] hours ago and lasted [minutes]. */
    data class Session(val title: String, val endedHoursAgo: Double, val minutes: Int)

    val games = listOf(
        Sample("snes", "Emberline Saga", "sfc", favorite = true),
        Sample("snes", "Tidebreak Tactics", "sfc", favorite = true),
        Sample("snes", "Moss and Mortar", "sfc"),
        Sample("snes", "Pocket Comet", "sfc"),
        Sample("snes", "Lanterns of Vell", "sfc"),
        Sample("snes", "Clockwork Canary", "sfc"),
        Sample("genesis", "Velocity Vandals", "md"),
        Sample("genesis", "Iron Petal", "md"),
        Sample("genesis", "Harbor Lights Rally", "md"),
        Sample("genesis", "Kestrel Nine", "md"),
        Sample("n64", "Cloudbound Racers", "z64"),
        Sample("n64", "Grotto Gardens", "z64"),
        Sample("n64", "Paper Kite Grand Prix", "z64"),
        Sample("psx", "Hollow Meridian", "chd", discs = 2, favorite = true),
        Sample("psx", "Static Bloom", "chd", discs = 3),
        Sample("psx", "Last Ferry Home", "chd"),
        Sample("psx", "Cinder Circuit", "chd"),
        Sample("ps2", "Glasswing Requiem", "iso"),
        Sample("ps2", "Northwind Relay", "iso"),
        Sample("ps2", "Quiet Engines", "iso"),
        Sample("ps2", "Signal Garden", "iso"),
        Sample("ngc", "Marble Monsoon", "rvz", favorite = true),
        Sample("ngc", "Lumen Drift", "rvz", favorite = true),
        Sample("ngc", "Starfold Academy", "rvz"),
        Sample("dc", "Velvet Orbit", "chd", discs = 2, favorite = true),
        Sample("dc", "Sunset Arcade Club", "chd", favorite = true),
        Sample("dc", "Coral Protocol", "chd"),
        Sample("gba", "Pixel Pilgrims", "gba"),
        Sample("gba", "Tinderbox Tales", "gba"),
        Sample("gba", "Moth and the Lamplighter", "gba"),
        Sample("gba", "Beacon Bay", "gba"),
        Sample("gba", "Sprout Squad", "gba"),
        Sample("nds", "Stylus Stories", "nds"),
        Sample("nds", "Folded Maps", "nds"),
        Sample("nds", "Honeycomb Heroes", "nds"),
        Sample(
            "switch", "Aurora Outpost", "nsp",
            extras = listOf("Aurora Outpost [UPD][v131072].nsp", "Aurora Outpost - Frontier Pack [DLC].nsp"),
        ),
        Sample("switch", "Tanuki Trails", "nsp"),
        Sample("switch", "Brightwater Farm", "nsp"),
        Sample("switch", "Overclocked Owls", "nsp"),
    )

    /**
     * Play history over the last few weeks, so Continue Playing, Recently Played and the playtime
     * widgets have something to show. Written through the same repository calls a real launch uses.
     */
    val sessions = listOf(
        Session("Velvet Orbit", 2.0, 95),
        Session("Velvet Orbit", 26.0, 70),
        Session("Velvet Orbit", 50.5, 110),
        Session("Velvet Orbit", 196.0, 60),
        Session("Aurora Outpost", 20.0, 80),
        Session("Aurora Outpost", 98.0, 120),
        Session("Aurora Outpost", 218.0, 45),
        Session("Cloudbound Racers", 30.0, 35),
        Session("Cloudbound Racers", 146.0, 25),
        Session("Marble Monsoon", 54.0, 60),
        Session("Marble Monsoon", 122.0, 90),
        Session("Hollow Meridian", 75.0, 140),
        Session("Hollow Meridian", 170.0, 100),
        Session("Hollow Meridian", 266.0, 90),
        Session("Emberline Saga", 100.0, 50),
        Session("Emberline Saga", 242.0, 65),
        Session("Pixel Pilgrims", 148.0, 30),
        Session("Glasswing Requiem", 220.0, 85),
        Session("Stylus Stories", 290.0, 40),
        Session("Velocity Vandals", 480.0, 45),
        Session("Tanuki Trails", 600.0, 120),
    )

    /** Collections the user made, by name, with their games. */
    val collections = listOf(
        "Couch Racers" to listOf("Cloudbound Racers", "Paper Kite Grand Prix", "Harbor Lights Rally", "Velocity Vandals"),
        "Long Adventures" to listOf("Emberline Saga", "Hollow Meridian", "Static Bloom", "Glasswing Requiem", "Lanterns of Vell"),
        "Pocket Picks" to listOf("Pixel Pilgrims", "Tinderbox Tales", "Stylus Stories", "Folded Maps"),
    )

    /**
     * A Channels board as a user would arrange it in Settings, Home: the default list without the
     * RetroAchievements and Cartridge widgets (neither is set up here), plus playtime, collections
     * and the clock.
     */
    val channelBoard: List<HomeWidget> = listOf(
        WidgetKind.CONTINUE_PLAYING,
        WidgetKind.FAVORITES,
        WidgetKind.SYSTEMS,
        WidgetKind.PLAYTIME_WEEK,
        WidgetKind.PLAYTIME_TOTAL,
        WidgetKind.RECENTLY_ADDED,
        WidgetKind.COLLECTIONS,
        WidgetKind.CLOCK,
    ).mapIndexed { i, k -> HomeWidget(id = k.name.lowercase(), kind = k, order = i) }

    val platformCount: Int get() = games.map { it.folder }.distinct().size

    /** Writes the sample files (tiny placeholders, never real game data) under [root]. */
    fun writeTo(root: File) {
        for (g in games) {
            val dir = File(root, g.folder).apply { mkdirs() }
            if (g.discs > 1) {
                for (disc in 1..g.discs) File(dir, "${g.title} (Disc $disc).${g.ext}").writeBytes(ByteArray(512))
            } else {
                File(dir, "${g.title}.${g.ext}").writeBytes(ByteArray(512))
            }
            for (extra in g.extras) File(dir, extra).writeBytes(ByteArray(256))
        }
    }

    /** Marks favourites, makes the collections and records the play sessions, oldest first. */
    suspend fun applyHistory(store: FuseStore, data: FuseData, cards: List<GameCard>, now: Long) {
        val byTitle = cards.associateBy { it.title }
        for (g in games.filter { it.favorite }) {
            val card = byTitle[g.title] ?: error("Sample game not found in the library: ${g.title}")
            store.library.setFavorite(card.id, true)
        }
        for ((name, titles) in collections) {
            val id = store.collections.create(name)
            for (title in titles) store.collections.add(id, (byTitle[title] ?: error("Sample game not found in the library: $title")).id)
        }
        val emulators = ScreenshotServices.InstalledEmulators
        fun emulatorFor(platform: PlatformId): EmulatorId? = emulators.firstOrNull { platform in it.platforms }?.id
        for (s in sessions.sortedByDescending { it.endedHoursAgo }) {
            val card = byTitle[s.title] ?: error("Sample game not found in the library: ${s.title}")
            val end = now - (s.endedHoursAgo * 3_600_000).toLong()
            val start = end - s.minutes * 60_000L
            val id = data.playSessions.start(card.id, emulatorFor(card.platformId), start)
            data.playSessions.end(id, end)
        }
    }
}
