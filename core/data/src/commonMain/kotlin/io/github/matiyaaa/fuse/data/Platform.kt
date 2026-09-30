package io.github.matiyaaa.fuse.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher every repository uses by default for database work, so no query ever runs on the main
 * thread. `Dispatchers.IO` on Android (the Android driver serializes writes itself); a single
 * dedicated thread on desktop, where the JDBC driver would otherwise open competing connections.
 */
expect val ioDispatcher: CoroutineDispatcher

/** Wall clock in epoch milliseconds, the default clock for repositories. */
expect fun currentTimeMillis(): Long
