package io.github.matiyaaa.fuse.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

/**
 * Opens Fuse's database on Linux/desktop with the JDBC SQLite driver.
 *
 * The schema version lives in `PRAGMA user_version`: a new file gets the current schema, an older
 * one is migrated in a transaction (with foreign keys off, as SQLite recommends for table rebuilds),
 * and a file written by a newer Fuse is refused rather than risk damaging the library. Runtime
 * connections have foreign keys on, WAL journaling and a busy timeout.
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
        JdbcSqliteDriver(url, properties(foreignKeys = false, wal = true)).use { prepare(it) }
        return JdbcSqliteDriver(url, properties(foreignKeys = true, wal = true))
    }

    /** Current `user_version` of the database behind [driver]. */
    fun userVersion(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
    ).value

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
        if (wal) {
            setProperty("journal_mode", "WAL")
            // A file database gives each thread its own connection. A deferred transaction that
            // reads and then writes fails with SQLITE_BUSY_SNAPSHOT when another connection wrote in
            // between (busy_timeout can't help), so transactions take the write lock up front and
            // wait for it instead. The in-memory database has a single connection and needs none.
            setProperty("transaction_mode", "IMMEDIATE")
        }
    }
}
