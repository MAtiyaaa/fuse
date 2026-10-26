package io.github.matiyaaa.fuse.data

import app.cash.sqldelight.db.QueryResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuse 0.4.5: how SQLite reads each of Fuse's queries, as a gate. The index on `game.missing` is for
 * the few games whose files are gone; a read of the games that are there must never go through it
 * (nearly every game is there, so it narrows nothing, and SQLite then skips the index that would:
 * with it, a series' first game read every game once per series, seconds at 25,000 games).
 */
class QueryPlanTest {
    /** Every labelled query in the .sq files, with its parameters made literal so it can be planned. */
    private fun queries(): List<Pair<String, String>> {
        val dir = File("src/commonMain/sqldelight/io/github/matiyaaa/fuse/data/db")
        val labelled = Regex("""^(\w+):\n(.*?);\s*$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        return dir.listFiles { f -> f.extension == "sq" }!!.sortedBy { it.name }.flatMap { f ->
            labelled.findAll(f.readText()).mapNotNull { m ->
                val sql = m.groupValues[2]
                if (!Regex("""^\s*(SELECT|WITH)""", RegexOption.IGNORE_CASE).containsMatchIn(sql)) return@mapNotNull null
                val literal = sql.replace(Regex("""IN\s+(\?|:\w+)"""), "IN (1)").replace(Regex(""":\w+"""), "1").replace("?", "1")
                "${f.name} ${m.groupValues[1]}" to literal
            }.toList()
        }
    }

    private fun plan(t: TestDb, sql: String): List<String> = t.driver.executeQuery(null, "EXPLAIN QUERY PLAN $sql", { c ->
        val rows = ArrayList<String>()
        while (c.next().value) rows += c.getString(3).orEmpty()
        QueryResult.Value(rows)
    }, 0).value

    @Test
    fun noReadOfPresentGamesGoesThroughTheMissingIndex() = TestDb().use { t ->
        val all = queries()
        assertTrue(all.size > 100, "the queries were found (${all.size})")
        val through = all.filter { (_, sql) -> plan(t, sql).any { "game_missing" in it } }.map { it.first }
        // Only the list of games whose files are gone, which asks for exactly those.
        assertEquals(listOf("Game.sq missingGames"), through)
    }

    @Test
    fun aSeriesFirstGameIsFoundThroughTheSeries() = TestDb().use { t ->
        val (_, sql) = queries().single { it.first == "Collection.sq seriesLeads" }
        val plan = plan(t, sql)
        assertTrue(plan.any { "collection_game" in it || "sqlite_autoindex_collection_game_1" in it }, plan.toString())
        assertTrue(plan.none { "game_missing" in it }, plan.toString())
    }
}
