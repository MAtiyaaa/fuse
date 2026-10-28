package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.MoveInRow
import io.github.matiyaaa.fuse.data.repo.PlayState
import io.github.matiyaaa.fuse.integrations.match.TitleNormalizer
import io.github.matiyaaa.fuse.integrations.retroachievements.RaWanted
import io.github.matiyaaa.fuse.library.movein.EsDeCollection
import io.github.matiyaaa.fuse.library.movein.HereGame
import io.github.matiyaaa.fuse.library.movein.MoveInFind
import io.github.matiyaaa.fuse.library.movein.MoveInFinder
import io.github.matiyaaa.fuse.library.movein.MoveInMatch
import io.github.matiyaaa.fuse.library.movein.MoveInSource
import io.github.matiyaaa.fuse.library.movein.MovedGame
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PlayStatus
import io.github.matiyaaa.fuse.ui.shell.store.MoveInReport
import io.github.matiyaaa.fuse.ui.shell.store.MoveInView
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Moving in: what the person's other launchers here say about their games ([MoveInFinder]),
 * matched to the games in this library ([MoveInMatch]) and brought in without undoing anything
 * set in Fuse: a favourite or a hidden game is added, never taken away; play time only grows, and
 * goes on the games as imported time (where from is kept, so it never counts as time Fuse saw);
 * last played only moves forward; a completed game or a rating fills the Journal only where it is
 * empty; collections are made, or added to when one of that name exists. What it changed is kept
 * so it can all be put back ([undo]).
 */
internal class MoveInWork(
    private val ctx: StoreContext,
    /** The person's Want to Play list on RetroAchievements and their name there, when they have an account. */
    private val wantToPlay: suspend () -> Pair<String, List<RaWanted>>? = { null },
) {
    private val data get() = ctx.data
    private val json = Json { ignoreUnknownKeys = true }

    /** What one move in changed, to put back: each game as it was, and the collections it made or added to. */
    @Serializable
    private data class Undo(
        val at: Long,
        val states: List<PlayState>,
        val made: List<Long> = emptyList(),
        val added: Map<Long, List<Long>> = emptyMap(),
    )

    /** A game's changes from every launcher brought in, together. */
    private class Change {
        var favorite = false
        var hidden = false
        var seconds = 0L
        var source: String? = null
        var lastPlayedAt: Long? = null
        var status: PlayStatus? = null
        var rating = 0
    }

    private class Plan(val changes: Map<GameId, Change>, val collections: List<Pair<String, List<GameId>>>, val before: Map<GameId, PlayState>)

    private fun finder(): MoveInFinder {
        val home = ctx.services.emulators.homeDir?.replace('\\', '/')?.trimEnd('/').orEmpty()
        return MoveInFinder(
            ctx.services.fs, ctx.host.name, home, ctx.services.utcOffsetMillis(),
            appData = "$home/AppData/Roaming", localAppData = "$home/AppData/Local",
        )
    }

    /** What [sources] say, a launcher found in two places (ES-DE's folder and gamelists beside the games) as one. */
    private suspend fun find(sources: Set<MoveInSource>): List<MoveInFind> {
        val roots = data.sources.all().filter { it.enabled }.map { it.path }
        val steam = if (MoveInSource.STEAM in sources) runCatching { ctx.services.locations.steamRoots().roots }.getOrDefault(emptyList()) else emptyList()
        val here = runCatching { finder().find(sources, roots, steam) }.getOrDefault(emptyList())
            .groupBy { it.source }
            .map { (source, finds) -> MoveInFind(source, finds.joinToString(", ") { it.where }, finds.flatMap { it.games }, finds.flatMap { it.collections }) }
        val wanted = if (MoveInSource.RETROACHIEVEMENTS in sources) wanted() else null
        return here + listOfNotNull(wanted)
    }

    /**
     * The person's Want to Play list on RetroAchievements, each game found here by RetroAchievements'
     * id for it, else by its name on that console when only one game here has it, as Want to play.
     */
    private suspend fun wanted(): MoveInFind? {
        val (user, list) = runCatching { wantToPlay() }.getOrNull() ?: return null
        if (list.isEmpty()) return null
        val rows = data.games.moveInRows()
        val byRa = rows.filter { it.raGameId != null }.groupBy { it.raGameId!! }
        val consoleOf = rows.map { it.platform }.distinct().associateWith { ctx.platform(PlatformId(it))?.retroAchievementsConsoleId }
        val byName = HashMap<Pair<Int, String>, MutableSet<MoveInRow>>()
        for (r in rows) {
            val console = consoleOf[r.platform] ?: continue
            val names = (r.titles + r.titles.map { DisplayNameCleaner.clean(it) })
                .map { TitleNormalizer.normalize(it) }.filter { it.isNotEmpty() }.toSet()
            for (n in names) byName.getOrPut(console to n) { LinkedHashSet() } += r
        }
        val games = list.mapNotNull { w ->
            // Hacks and subsets are other games than the one here.
            if (w.title.startsWith("~") || w.title.contains("[Subset", ignoreCase = true)) return@mapNotNull null
            val row = w.id?.let { byRa[it]?.singleOrNull() }
                ?: w.title.split(" | ").firstNotNullOfOrNull { n -> byName[w.consoleId to TitleNormalizer.normalize(n)]?.singleOrNull() }
            row?.let { MovedGame(it.path, status = PlayStatus.WANT) }
        }
        return MoveInFind(MoveInSource.RETROACHIEVEMENTS, "$user on RetroAchievements", games.distinctBy { it.path })
    }

    private suspend fun plan(finds: List<MoveInFind>): Plan {
        val here = data.games.moveInRows().map { HereGame(it.id.value, it.platform, it.path, it.launchPath, it.steamAppId) }
        val platformOf = { name: String -> ctx.platforms.resolveFolder(name)?.id?.value }
        val changes = LinkedHashMap<GameId, Change>()
        val collections = ArrayList<Pair<String, List<GameId>>>()
        for (f in finds) {
            for ((moved, id) in MoveInMatch.match(f.games, here, platformOf)) {
                val c = changes.getOrPut(GameId(id)) { Change() }
                c.favorite = c.favorite || moved.favorite
                c.hidden = c.hidden || moved.hidden
                if (moved.seconds > c.seconds) {
                    c.seconds = moved.seconds
                    c.source = f.source.label
                }
                c.lastPlayedAt = listOfNotNull(c.lastPlayedAt, moved.lastPlayedAt).maxOrNull()
                c.status = c.status ?: moved.status
                c.rating = maxOf(c.rating, moved.rating)
            }
            for (col in f.collections) {
                val wanted = col.paths.map { MovedGame(it, system = EsDeCollection.systemOf(it)) }
                val ids = MoveInMatch.match(wanted, here, platformOf).values.distinct().map(::GameId)
                if (ids.isNotEmpty()) collections += col.name to ids
            }
        }
        val before = data.games.playState(changes.keys).associateBy { it.id }
        return Plan(changes, collections, before)
    }

    /** What [plan] would change of the games as they are now. */
    private fun report(plan: Plan): MoveInReport {
        var favourites = 0
        var hidden = 0
        var timed = 0
        var journal = 0
        var games = 0
        for ((id, c) in plan.changes) {
            val was = plan.before[id] ?: continue
            val fav = c.favorite && !was.favorite
            val hide = c.hidden && !was.hidden
            val time = c.seconds > was.importedSeconds || (c.lastPlayedAt != null && (was.lastPlayedAt ?: 0) < c.lastPlayedAt!!)
            val marks = (c.status != null && was.journal.status == null) || (c.rating > 0 && was.journal.rating == 0)
            if (fav) favourites++
            if (hide) hidden++
            if (time) timed++
            if (marks) journal++
            if (fav || hide || time || marks) games++
        }
        return MoveInReport(games, favourites, hidden, timed, journal, plan.collections.size)
    }

    /** Every launcher found here, with what moving in from it alone would bring. */
    suspend fun look(): List<MoveInView> = find(MoveInSource.entries.toSet()).map { f ->
        MoveInView(f.source, f.where, report(plan(listOf(f))))
    }.filter { !it.report.isEmpty }

    /** Brings in what [sources] say; what it changed. */
    suspend fun bringIn(sources: Set<MoveInSource>): MoveInReport {
        val plan = plan(find(sources))
        val report = report(plan)
        if (report.isEmpty) return report
        val touched = ArrayList<PlayState>()
        for ((id, c) in plan.changes) {
            val was = plan.before[id] ?: continue
            var changed = false
            if (c.favorite && !was.favorite) { data.games.setFavorite(id, true); changed = true }
            if (c.hidden && !was.hidden) { data.games.setHidden(id, true); changed = true }
            if (c.seconds > was.importedSeconds) {
                data.playSessions.importPlaytime(id, c.seconds, c.source ?: "Another launcher", c.lastPlayedAt)
                changed = true
            } else if (c.lastPlayedAt != null && (was.lastPlayedAt ?: 0) < c.lastPlayedAt!!) {
                data.games.playedBy(id, c.lastPlayedAt!!)
                changed = true
            }
            val j = was.journal
            val status = j.status ?: c.status
            val rating = j.rating.takeIf { it > 0 } ?: c.rating
            if (status != j.status || rating != j.rating) {
                data.games.setJournal(id, j.copy(status = status, statusAt = if (status != j.status) c.lastPlayedAt ?: ctx.now() else j.statusAt, rating = rating))
                changed = true
            }
            if (changed) touched += was
        }
        // Collections: one of the same name the person already has is added to, never doubled.
        val mine = data.collections.observeManual().first()
        val made = ArrayList<Long>()
        val added = HashMap<Long, List<Long>>()
        for ((name, ids) in plan.collections) {
            val existing = mine.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (existing == null) {
                val id = data.collections.create(name)
                data.collections.addGames(id, ids)
                made += id.value
            } else {
                val inIt = data.collections.observeGames(io.github.matiyaaa.fuse.data.repo.CollectionKey.Manual(existing.id)).first().map { it.id }.toSet()
                val fresh = ids.filter { it !in inIt }
                if (fresh.isEmpty()) continue
                data.collections.addGames(existing.id, fresh)
                added[existing.id.value] = fresh.map { it.value }
            }
        }
        data.cache.put(NS, UNDO, json.encodeToString(Undo.serializer(), Undo(ctx.now(), touched, made, added)), ctx.now(), UNDO_KEPT_MS)
        ctx.userChanged()
        return report
    }

    /** When the last move in that can still be put back was made, or null. */
    suspend fun last(): Long? = undoRecord()?.at

    /** Puts back what the last move in changed: each game as it was, its collections taken away again. */
    suspend fun undo(): Boolean {
        val u = undoRecord() ?: return false
        data.games.restorePlayState(u.states)
        for (id in u.made) data.collections.delete(CollectionId(id))
        for ((col, games) in u.added) data.collections.removeGames(CollectionId(col), games.map(::GameId))
        data.cache.remove(NS, UNDO)
        ctx.userChanged()
        return true
    }

    private suspend fun undoRecord(): Undo? = data.cache.getOrNull(NS, UNDO, ctx.now())?.let { runCatching { json.decodeFromString(Undo.serializer(), it) }.getOrNull() }

    private companion object {
        const val NS = "movein"
        const val UNDO = "undo"

        /** A move in can be put back for a month. */
        const val UNDO_KEPT_MS = 30L * 86_400_000L
    }
}
