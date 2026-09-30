package io.github.matiyaaa.fuse.data

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * One dedicated thread for the database. The JDBC driver opens one SQLite connection per thread,
 * and SQLite connections that write at the same time fail with SQLITE_BUSY (or BUSY_SNAPSHOT for a
 * transaction that read first). Keeping every query and transaction on this thread means one
 * connection and no lock contention; queries take milliseconds, so nothing waits noticeably.
 */
actual val ioDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
    Thread(task, "fuse-db").apply { isDaemon = true }
}.asCoroutineDispatcher()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
