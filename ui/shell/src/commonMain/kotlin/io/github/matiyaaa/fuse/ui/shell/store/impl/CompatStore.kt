package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlaySessionSource
import io.github.matiyaaa.fuse.ui.shell.store.Compat
import io.github.matiyaaa.fuse.ui.shell.store.CompatNote
import io.github.matiyaaa.fuse.ui.shell.store.CompatOps
import io.github.matiyaaa.fuse.ui.shell.store.CompatProblem
import io.github.matiyaaa.fuse.ui.shell.store.CompatStatus
import io.github.matiyaaa.fuse.ui.shell.store.CompatVerdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Compatibility reports, kept in Fuse's own database on this device: the person's note for each game
 * they said something about, beside what Fuse saw (sessions started from Fuse here). A game is
 * playable only on the person's word.
 */
internal class DefaultCompatOps(private val ctx: StoreContext) : CompatOps {
    private val json = Json { ignoreUnknownKeys = true }

    /** Goes up with every note said or forgotten, so open pages read it again. */
    private val said = MutableStateFlow(0)

    override fun of(game: GameId): Flow<Compat> = combine(ctx.data.playSessions.sessions(game), said) { sessions, _ ->
        val here = sessions.filter { it.source in HERE && it.endedAt != null }
        Compat.of(note(game), here.size, here.sumOf { it.durationSeconds ?: 0L })
    }

    private suspend fun note(game: GameId): CompatNote? =
        ctx.data.cache.entry(NS, game.value.toString())?.valueJson?.let { runCatching { json.decodeFromString(CompatNote.serializer(), it) }.getOrNull() }

    override suspend fun set(game: GameId, verdict: CompatVerdict?, problems: Set<CompatProblem>) {
        if (verdict == null) {
            ctx.data.cache.remove(NS, game.value.toString())
        } else {
            val last = ctx.data.playSessions.sessions(game).first().filter { it.source in HERE }.maxByOrNull { it.startedAt }
            val note = CompatNote(
                verdict = verdict,
                problems = if (verdict == CompatVerdict.WITH_PROBLEMS) problems else emptySet(),
                emulator = last?.emulatorId?.let { ctx.registry[it]?.name ?: it.value },
                fuseVersion = ctx.services.appVersion,
                device = ctx.services.host.name.lowercase().replaceFirstChar { it.uppercase() },
                at = ctx.now(),
            )
            ctx.data.cache.put(NS, game.value.toString(), json.encodeToString(CompatNote.serializer(), note), ctx.now(), ttlMs = null)
        }
        said.update { it + 1 }
    }

    override suspend fun report(): String {
        val notes = ctx.data.database.kvCacheQueries.inNamespace(NS).executeAsList().mapNotNull { row ->
            val id = row.key.toLongOrNull() ?: return@mapNotNull null
            runCatching { json.decodeFromString(CompatNote.serializer(), row.value_json) }.getOrNull()?.let { GameId(id) to it }
        }.toMap()
        val games = ctx.data.games.observeAll(includeHidden = true).first().associateBy { it.id }
        val rows = notes.keys.mapNotNull { id -> games[id]?.let { g -> Triple(g, notes[id], Compat.of(notes[id], 0, 0)) } }
            .sortedWith(compareBy({ it.third.status.ordinal }, { it.first.displayTitle.lowercase() }))
        return buildString {
            appendLine("Fuse ${ctx.services.appVersion}, how games run on this ${ctx.services.host.name.lowercase().replaceFirstChar { it.uppercase() }} device")
            appendLine("Your own notes, kept on this device. Nothing here was sent anywhere.")
            appendLine()
            if (rows.isEmpty()) appendLine("No game has a note yet. A game's options have How It Runs Here.")
            for (status in CompatStatus.entries) {
                val of = rows.filter { it.third.status == status }
                if (of.isEmpty()) continue
                appendLine("${status.label} (${of.size})")
                for ((g, note, _) in of) {
                    val system = ctx.platforms.byId(g.platformId)?.shortName ?: g.platformId.value
                    val parts = listOfNotNull(
                        note?.problems?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label.lowercase() }?.let { "problems: $it" },
                        note?.emulator?.let { "with $it" },
                        note?.fuseVersion?.takeIf { it.isNotEmpty() }?.let { "Fuse $it" },
                    )
                    appendLine("  ${g.displayTitle} ($system)" + if (parts.isEmpty()) "" else ": " + parts.joinToString(", "))
                }
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    private companion object {
        const val NS = "compat.notes"

        /** Sessions this device saw start and end itself (not imported, not another device's). */
        val HERE = setOf(PlaySessionSource.FUSE_LAUNCH, PlaySessionSource.USAGE_STATS, PlaySessionSource.STEAM)
    }
}
