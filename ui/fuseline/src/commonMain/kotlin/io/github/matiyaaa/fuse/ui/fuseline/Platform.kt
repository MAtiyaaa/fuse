package io.github.matiyaaa.fuse.ui.fuseline

/** The calling thread, as a number: how the frame driver tells its own thread's reads from any other's. */
internal expect fun currentThreadId(): Long
