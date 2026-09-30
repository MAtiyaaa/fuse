package io.github.matiyaaa.fuse.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
