package io.github.matiyaaa.fuse.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher every repository uses by default for database work. `Dispatchers.IO` on Android and
 * desktop, so no query ever runs on the main thread.
 */
expect val ioDispatcher: CoroutineDispatcher

/** Wall clock in epoch milliseconds, the default clock for repositories. */
expect fun currentTimeMillis(): Long
