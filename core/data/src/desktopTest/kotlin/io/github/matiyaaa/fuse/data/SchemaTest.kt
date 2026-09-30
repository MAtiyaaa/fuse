package io.github.matiyaaa.fuse.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaTest {
    @Test
    fun schemaIsVersionOne() {
        assertEquals(1L, FuseDatabase.Schema.version)
    }

    @Test
    fun freshDatabaseHasEveryTableAndForeignKeys() = TestDb().use { t ->
        assertEquals(1L, DesktopDatabase.userVersion(t.driver))
        val tables = t.driver.strings("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')").toSet()
        val expected = setOf(
            "library_source", "game", "game_summary", "game_content", "game_disc", "game_genre", "folder_state",
            "media", "play_session", "game_collection", "collection_game", "setting", "app_override", "kv_cache",
            "title_cleanup_history",
        )
        assertTrue(tables.containsAll(expected), "missing: ${expected - tables}")
        assertEquals(listOf("1"), t.driver.strings("PRAGMA foreign_keys"))
    }

    @Test
    fun migratingToTheSameVersionIsANoOp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FuseDatabase.Schema.create(driver)
        FuseDatabase.Schema.migrate(driver, 1, FuseDatabase.Schema.version)
        val db = FuseDatabase(driver)
        db.librarySourceQueries.insert("/roms", "ROMs", LibrarySourceKind.ROMS_ROOT.name, 1)
        assertEquals(1, db.librarySourceQueries.selectAll().executeAsList().size)
        driver.close()
    }

    @Test
    fun fileDatabaseIsCreatedOnceThenReopened() = runBlocking {
        val dir = createTempDirectory("fuse-db").toFile()
        val path = File(dir, "nested/fuse.db").path
        DesktopDatabase.openDriver(path).use { driver ->
            FuseData(FuseDatabase(driver)).sources.add("/roms", "ROMs", LibrarySourceKind.ROMS_ROOT)
            assertEquals(listOf("wal"), driver.strings("PRAGMA journal_mode"))
        }
        DesktopDatabase.openDriver(path).use { driver ->
            assertEquals(1L, DesktopDatabase.userVersion(driver))
            assertEquals(listOf("/roms"), FuseData(FuseDatabase(driver)).sources.all().map { it.path })
            assertEquals(listOf("1"), driver.strings("PRAGMA foreign_keys"))
        }
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun databaseFromNewerFuseIsRefused() {
        val dir = createTempDirectory("fuse-db").toFile()
        val path = File(dir, "fuse.db").path
        JdbcSqliteDriver("jdbc:sqlite:$path", Properties()).use { it.execute(null, "PRAGMA user_version = 99", 0) }
        assertFailsWith<IllegalStateException> { DesktopDatabase.openDriver(path) }
        dir.deleteRecursively()
    }
}

fun SqlDriver.strings(sql: String): List<String> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val out = mutableListOf<String>()
        while (cursor.next().value) out += cursor.getString(0).orEmpty()
        QueryResult.Value(out)
    },
    parameters = 0,
).value
