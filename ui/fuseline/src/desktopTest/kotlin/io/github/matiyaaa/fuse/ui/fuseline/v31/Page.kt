package io.github.matiyaaa.fuse.ui.fuseline.v31

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope

/**
 * False inside a page that is kept ready but not shown (a tab you were on, so coming back to it is
 * instant). Loops there stop ticking, [PageEffect]s wait, and input layers stand aside.
 */
val LocalPageActive = compositionLocalOf { true }

/**
 * A [LaunchedEffect] that runs only while its page is shown, and runs again (from the start) each
 * time the page is shown again: for a page's hints, its art and anything else it tells the rest of
 * Fuse, which a page kept in the background must not overwrite.
 */
@Composable
fun PageEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val active = LocalPageActive.current
    LaunchedEffect(active, *keys) { if (active) block() }
}
