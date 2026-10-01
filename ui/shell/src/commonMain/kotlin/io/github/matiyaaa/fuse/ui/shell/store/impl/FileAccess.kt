package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.ByteSource

/** Random-access reader for ROM hashing (RetroAchievements). Null when the file can't be opened. */
internal expect fun openByteSource(path: String): ClosableByteSource?

/** The ROM inside a .zip (its only or largest file), read like a file; null for anything else. */
internal expect fun openZippedRom(path: String): ClosableByteSource?

internal interface ClosableByteSource : ByteSource, AutoCloseable

/** Free and total bytes of the volume holding [path], or null when unknown. */
internal expect fun volumeSpace(path: String): Pair<Long, Long>?
