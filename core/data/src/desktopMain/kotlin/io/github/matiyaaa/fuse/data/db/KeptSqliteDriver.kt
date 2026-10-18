package io.github.matiyaaa.fuse.data.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * SQLDelight's JDBC SQLite driver, with each thread's connection kept open until the driver closes.
 *
 * `JdbcSqliteDriver` closes a file database's connection after every query run outside a
 * transaction, so the next one opens the file again, applies the connection's settings and reads
 * the whole schema back before its statement can be prepared. With Fuse's schema that made each
 * small read cost several times the read itself, and a big library's screens run thousands of them
 * (0.76 ms a read against 0.009 ms with the connection kept, on a 4-core computer). Here a thread
 * opens its connection once; queries and transactions behave as in `JdbcSqliteDriver`
 * (one connection per thread, `BEGIN`/`END`/`ROLLBACK TRANSACTION`, the same listeners). All of
 * Fuse's work runs on the database thread (ioDispatcher), so that is one connection in practice.
 */
internal class KeptSqliteDriver(private val url: String, private val properties: Properties) : JdbcDriver() {
    private val connections = ThreadLocal<Connection>()
    private val opened = ConcurrentLinkedQueue<Connection>()
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    /** The database's file, looked at now and then: a kept connection would go on using one deleted under it. */
    private val file = java.io.File(url.removePrefix("jdbc:sqlite:").substringBefore('?'))
    @Volatile private var lookedAt = 0L

    override fun getConnection(): Connection {
        val kept = connections.get()
        if (kept != null && transaction == null) {
            val now = System.nanoTime()
            if (now - lookedAt > LOOK_EVERY_NANOS) {
                lookedAt = now
                // Gone (erased, or a test's folder cleaned up): let go, so the next read fails as it
                // would have opening the file afresh, instead of reading and writing a deleted file.
                if (!file.exists()) {
                    connections.remove()
                    opened.remove(kept)
                    runCatching { kept.close() }
                    return DriverManager.getConnection(url, properties).also { connections.set(it); opened += it }
                }
            }
        }
        return kept ?: DriverManager.getConnection(url, properties).also { connections.set(it); opened += it }
    }

    // Kept open for the thread's next query: closing it is what made every read open the file again.
    override fun closeConnection(connection: Connection) {
        check(connections.get() == connection) { "Connections must be closed on the thread that opened them" }
    }

    override fun close() {
        while (true) runCatching { (opened.poll() ?: break).close() }
    }

    override fun Connection.beginTransaction() = run("BEGIN TRANSACTION")

    override fun Connection.endTransaction() = run("END TRANSACTION")

    override fun Connection.rollbackTransaction() = run("ROLLBACK TRANSACTION")

    private fun Connection.run(sql: String) {
        prepareStatement(sql).use { it.execute() }
    }

    private companion object {
        const val LOOK_EVERY_NANOS = 1_000_000_000L
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners.getOrPut(it, ::linkedSetOf).add(listener) } }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.remove(listener) } }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val notify = linkedSetOf<Query.Listener>()
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.let(notify::addAll) } }
        notify.forEach(Query.Listener::queryResultsChanged)
    }
}
