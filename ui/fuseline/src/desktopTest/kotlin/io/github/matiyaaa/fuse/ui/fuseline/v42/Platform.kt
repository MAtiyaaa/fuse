package io.github.matiyaaa.fuse.ui.fuseline.v42

/** The calling thread, as a number: how the frame driver tells its own thread's reads from any other's. */
@Suppress("DEPRECATION")
internal fun currentThreadId(): Long = Thread.currentThread().id
