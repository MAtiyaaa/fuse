package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Whether this item of a list or grid is the selected one, read so that moving the selection only
 * rebuilds the item it leaves and the one it reaches. Reading the selection's index straight in each
 * item rebuilds every item on screen at every move; [selected] (which reads it) is only worked out
 * here, and the item hears about it when its answer changes.
 */
@Composable
fun isSelected(selected: () -> Boolean): Boolean {
    val latest = rememberUpdatedState(selected)
    return remember { derivedStateOf { latest.value() } }.value
}
