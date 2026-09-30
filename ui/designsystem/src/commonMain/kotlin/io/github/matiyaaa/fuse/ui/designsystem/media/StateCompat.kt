package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

/** Collects a painter state flow without pulling in lifecycle dependencies. */
@Composable
internal fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()
