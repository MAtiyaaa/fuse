package io.github.matiyaaa.fuse.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * Opens Fuse's database on Linux/desktop with the JDBC SQLite driver.
 *
 * The schema version lives in `PRAGMA user_version`: a new file gets the current schema, an older
 * one is migrated in a transaction (with foreign keys off, as SQLite recommends for table rebuilds),
 * and a file written by a newer Fuse is refused rather than risk damaging the library, unless that
 * newer Fuse's copy from before it updated the library is there ([restoreForOlderFuse]). Runtime
 * connections have foreign keys on, WAL journaling and a busy timeout, and stay open ([KeptSqliteDriver]).
 */
object DesktopDatabase {
    /** Opens (creating or migrating) the database at [path], or an in-memory one when null. */
    fun open(path: String?): FuseDatabase = FuseDatabase(openDriver(path))

    /** Like [open] but returns the driver, which the caller closes. */
    fun openDriver(path: String?): SqlDriver {
        if (path == null) {
            // One shared connection: creating the schema with foreign keys on is safe for a new database.
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties(foreignKeys = true, wal = false))
            prepare(driver)
            return driver
        }
        File(path).absoluteFile.parentFile?.mkdirs()
        val url = "jdbc:sqlite:$path"
        restoreForOlderFuse(path)
        JdbcSqliteDriver(url, properties(foreignKeys = false, wal = true)).use { driver ->
            backupBeforeMigrating(driver, path)
            prepare(driver)
        }
        // The connection stays open between queries (KeptSqliteDriver says why).
        return KeptSqliteDriver(url, properties(foreignKeys = true, wal = true))
    }

    /** Current `user_version` of the database behind [driver]. */
    fun userVersion(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
    ).value

    /**
     * What happened when a library a newer Fuse had updated was opened by this one, in words, once;
     * null when nothing did. See [restoreForOlderFuse].
     */
    @Volatile
    var restoredNote: String? = null

    /**
     * A library a newer Fuse updated, opened by this older one (after going back from an update):
     * the copy that newer Fuse made before updating it ([backupPath]) is in this Fuse's own form, so it
     * comes back, and the newer library is kept beside it (`<name>.v<version>.kept`), never deleted.
     * Without such a copy nothing changes, and the library is refused as before.
     */
    internal fun restoreForOlderFuse(path: String, schema: Long = FuseDatabase.Schema.version) {
        val db = File(path)
        if (!db.isFile) return
        val current = JdbcSqliteDriver("jdbc:sqlite:$path", properties(foreignKeys = false, wal = true)).use(::userVersion)
        if (current <= schema) return
        val backup = File(backupPath(path, current))
        if (!backup.isFile) return
        val backupVersion = JdbcSqliteDriver("jdbc:sqlite:${backup.path}", Properties()).use(::userVersion)
        if (backupVersion != schema) return
        var kept = File("$path.v$current.kept")
        var n = 2
        while (kept.exists()) kept = File("$path.v$current.kept-${n++}")
        // The newer library with its write-ahead log, so the kept copy opens as it was.
        for (suffix in listOf("", "-wal", "-shm")) {
            val from = File(path + suffix)
            if (from.exists()) Files.move(from.toPath(), File(kept.path + suffix).toPath())
        }
        Files.copy(backup.toPath(), db.toPath())
        restoredNote = "Your library is as it was before a newer Fuse updated it. What changed since is kept in ${kept.name}, beside it."
    }

    /** Where the copy of the database at [path] made before migrating to [version] is kept. */
    fun backupPath(path: String, version: Long = FuseDatabase.Schema.version): String = "$path.before-v$version.bak"

    /**
     * Before an older database is migrated, a consistent copy of it is written next to it
     * ([backupPath]), so a migration that goes wrong can never cost the library. One copy is kept per
     * target version. A failed copy stops the migration rather than risk it unprotected.
     */
    private fun backupBeforeMigrating(driver: SqlDriver, path: String) {
        val current = userVersion(driver)
        if (current <= 0L || current >= FuseDatabase.Schema.version) return
        val backup = File(backupPath(path))
        if (backup.exists()) backup.delete()
        // VACUUM INTO writes a complete, consistent copy, write-ahead log included.
        driver.execute(null, "VACUUM INTO '${backup.absolutePath.replace("'", "''")}'", 0)
        check(backup.isFile && backup.length() > 0) { "Could not copy the library before updating it" }
    }

    private fun prepare(driver: SqlDriver) {
        val schema = FuseDatabase.Schema
        val current = userVersion(driver)
        check(current <= schema.version) {
            "Database version $current is newer than this Fuse (${schema.version}); refusing to open it"
        }
        if (current == schema.version) return
        FuseDatabase(driver).transaction {
            if (current == 0L) schema.create(driver) else schema.migrate(driver, current, schema.version)
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
    }

    private fun properties(foreignKeys: Boolean, wal: Boolean) = Properties().apply {
        setProperty("foreign_keys", foreignKeys.toString())
        setProperty("busy_timeout", "5000")
        // All database work runs on one thread (see ioDispatcher), so there is one connection in
        // use and transactions never compete for the write lock.
        if (wal) setProperty("journal_mode", "WAL")
    }
}
