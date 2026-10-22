package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.AndroidPort
import io.github.matiyaaa.fuse.model.AndroidPortStatus
import io.github.matiyaaa.fuse.model.AndroidPorts
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.ui.shell.store.PortMatch

/**
 * The Android ports as Fuse offers them: its own checked entries beside Open Ports Games' listings,
 * and which library games and installed apps each one is.
 */
internal object AndroidPortCatalog {
    /**
     * [checked] and [listed] as one list: a checked entry takes the place of a listing of the same
     * project, and takes what it doesn't say of its game (publisher, year, genre, pictures) from that
     * listing, else from a listing of the same game. Then a port without box art takes another's of
     * its game.
     */
    fun merge(checked: List<AndroidPort>, listed: List<AndroidPort>): List<AndroidPort> {
        val repos = checked.map { repoKey(it.repo) }.toSet()
        val ids = checked.map { it.id }.toSet()
        val byRepo = listed.groupBy { repoKey(it.repo) }
        val byGame = listed.groupBy { key(it.game) }
        val filled = checked.map { c ->
            val same = byRepo[repoKey(c.repo)]?.firstOrNull()
            val game = byGame[key(c.game)]?.firstOrNull { l -> l.systems.any(c.systems::contains) }
            c.withDetailsFrom(same, screenshot = true).withDetailsFrom(game, screenshot = false)
        }
        return shareCovers(filled + listed.filter { repoKey(it.repo) !in repos && it.id !in ids })
    }

    /** [this] with what it doesn't say of its game taken from [other]: its [screenshot] too when [other] is the same project. */
    private fun AndroidPort.withDetailsFrom(other: AndroidPort?, screenshot: Boolean): AndroidPort = if (other == null) this else copy(
        publisher = publisher ?: other.publisher,
        year = year ?: other.year,
        genre = genre ?: other.genre,
        cover = cover ?: other.cover,
        coverCredit = if (cover != null) coverCredit else other.coverCredit,
        screenshot = this.screenshot ?: other.screenshot.takeIf { screenshot },
    )

    /** [ports], each without box art given another's of the same game and system. */
    fun shareCovers(ports: List<AndroidPort>): List<AndroidPort> {
        val covers = HashMap<String, AndroidPort>()
        for (p in ports) if (p.cover != null) for (s in p.systems.ifEmpty { listOf("") }) covers.putIfAbsent("$s|${key(p.game)}", p)
        return ports.map { p ->
            if (p.cover != null) {
                p
            } else {
                p.systems.ifEmpty { listOf("") }.firstNotNullOfOrNull { s -> covers["$s|${key(p.game)}"] }
                    ?.let { o -> p.copy(cover = o.cover, coverCredit = o.coverCredit) } ?: p
            }
        }
    }

    /** A project's page, as two listings of it are told to be the same. */
    fun repoKey(url: String): String =
        AndroidPorts.githubRepo(url)?.let { (owner, name) -> "$owner/$name".lowercase() } ?: url.trim().trimEnd('/').lowercase()

    /**
     * A title as games and ports are compared: [TitleText.normalize]d, with a leading "the" and a
     * trailing "version" left out ("Pokemon - Red Version" and "Pokémon Red" are one game).
     */
    fun key(title: String): String = TitleText.normalize(title).removePrefix("the ").removeSuffix(" version").trim()

    /**
     * Every name [game] goes by: as shown, its metadata's, and its file's name cleaned of tags (and
     * of the bracketed ones the cleaner keeps, such as "(CGB+SGB Enhanced)").
     */
    fun titlesOf(game: Game): Set<String> = titlesOf(game.titles)

    /** [titlesOf] for a game's [t]itles. */
    fun titlesOf(t: GameTitles): Set<String> = buildSet {
        add(t.display)
        t.metadata?.let(::add)
        t.cleaned?.let(::add)
        val cleaned = DisplayNameCleaner.clean(t.original)
        add(cleaned)
        add(withoutBrackets(cleaned))
    }.map(::key).filter { it.isNotEmpty() }.toSet()

    /** The game names [port] plays, as [key]s. */
    fun names(port: AndroidPort): Set<String> = (listOf(port.game) + port.titles).map(::key).filter { it.isNotEmpty() }.toSet()

    /** How [game] is [port]'s game ([romXxh3]: its ROM's hash, when read), or null when it isn't. */
    fun match(port: AndroidPort, game: Game, romXxh3: String? = null): PortMatch? = match(port, game.platformId.value, game.titles, romXxh3)

    /** [match] for a game of [platform] with [titles]. */
    fun match(port: AndroidPort, platform: String, titles: GameTitles, romXxh3: String? = null): PortMatch? {
        if (platform !in port.systems) return null
        if (romXxh3 != null && port.roms.any { it.equals(romXxh3, ignoreCase = true) }) return PortMatch.ROM
        val names = names(port)
        return if (titlesOf(titles).any { it in names }) PortMatch.NAME else null
    }

    /** The order ports of one game are offered in: verified by the nightly check, then Fuse's checked, then by the ROM, then the steadiest. */
    fun order(port: AndroidPort, match: PortMatch?): Int =
        (if (port.verified != null) 0 else if (port.checked) 50 else 100) + (if (match == PortMatch.ROM) 0 else 10) + when (port.status) {
            AndroidPortStatus.STABLE -> 0
            AndroidPortStatus.BETA -> 1
            AndroidPortStatus.ALPHA -> 2
        }

    /** [Index.identify] over [ports]. */
    fun identify(packageName: String, label: String, ports: List<AndroidPort>, pinned: Map<String, String> = emptyMap()): List<AndroidPort> =
        Index(ports).identify(packageName, label, pinned)

    /** The one game [ports] all play (they may be forks of one project), or null when they differ. */
    fun oneGame(ports: List<AndroidPort>): AndroidPort? {
        val first = ports.firstOrNull() ?: return null
        val game = key(first.game)
        return first.takeIf { ports.all { key(it.game) == game && it.systems.toSet() == first.systems.toSet() } }
    }

    /**
     * The library [games] most like the app named [label], for "What game is this?": the ones sharing
     * the most words with its name, best first, and only those sharing one at least.
     */
    fun guessGames(label: String, games: List<Game>, limit: Int = 8): List<Game> = guess(label, games, limit) { it.titles }

    /** [guessGames] for any games, their titles read by [titles]. */
    fun <T> guess(label: String, games: List<T>, limit: Int = 8, titles: (T) -> GameTitles): List<T> {
        val words = words(label)
        if (words.isEmpty()) return emptyList()
        return games.mapNotNull { g ->
            val best = titlesOf(titles(g)).maxOfOrNull { t ->
                val theirs = words(t)
                val shared = (words intersect theirs).size
                if (shared == 0) 0.0 else shared.toDouble() / (words union theirs).size
            } ?: 0.0
            (g to best).takeIf { best > 0.0 }
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /**
     * The ports, looked up quickly: by id, by system, and an installed app by its package or its
     * whole name; and the library's games each one plays, once [games] are given.
     */
    class Index(val ports: List<AndroidPort>) {
        val byId: Map<String, AndroidPort> = ports.associateBy { it.id }
        private val byPackage: Map<String, List<AndroidPort>> = ports.filter { it.packageName != null }.groupBy { it.packageName!! }
        private val byName: Map<String, List<AndroidPort>> = buildMap<String, MutableList<AndroidPort>> {
            for (p in ports) for (n in setOf(key(p.name), key(withoutBrackets(p.name)))) if (n.length >= MIN_NAME) getOrPut(n) { mutableListOf() }.add(p)
        }
        private val bySystem: Map<String, List<AndroidPort>> = buildMap<String, MutableList<AndroidPort>> {
            for (p in ports) for (s in p.systems) getOrPut(s) { mutableListOf() }.add(p)
        }

        /** The ports that play games of [system]. */
        fun forSystem(system: String): List<AndroidPort> = bySystem[system].orEmpty()

        /**
         * The ports an installed app may be: by its package ([pinned]: packages Fuse saw ports install
         * as, by port id), else by its whole name ([label]) against the ports' names, their words in
         * brackets left out ("2 Ship 2 Harkinian (Android)"). Never by a part of a name.
         */
        fun identify(packageName: String, label: String, pinned: Map<String, String> = emptyMap()): List<AndroidPort> {
            byPackage[packageName]?.let { return it }
            pinned.entries.firstOrNull { it.value == packageName }?.let { (id, _) -> byId[id]?.let { return listOf(it) } }
            return byName[key(label)].orEmpty()
        }
    }

    /** The library's games by system and by each name they go by, to find a port's games at once. */
    class Games<T>(games: List<T>, platform: (T) -> String, titles: (T) -> GameTitles, private val id: (T) -> GameId) {
        private val byId: Map<GameId, T> = games.associateBy(id)
        private val byName: Map<String, Map<String, List<T>>> = games.groupBy(platform).mapValues { (_, list) ->
            buildMap<String, MutableList<T>> { for (g in list) for (n in titlesOf(titles(g))) getOrPut(n) { mutableListOf() }.add(g) }
        }

        /** The game with [game]'s id, when it is one of them. */
        fun byId(game: GameId): T? = byId[game]

        /** The games [port] plays, by name. */
        fun of(port: AndroidPort): List<T> {
            val names = names(port)
            return port.systems.flatMap { s -> byName[s]?.let { m -> names.flatMap { m[it].orEmpty() } }.orEmpty() }.distinctBy(id)
        }
    }

    private fun words(text: String): Set<String> = key(text).split(' ').filter { it.length > 1 && it !in SMALL_WORDS }.toSet()

    private fun withoutBrackets(name: String) = name.replace(Regex("\\s*(\\([^)]*\\)|\\[[^\\]]*\\])"), "").trim()

    private const val MIN_NAME = 4
    private val SMALL_WORDS = setOf("the", "of", "and", "a", "an", "for", "android", "port", "recomp", "recompiled", "mobile")
}
