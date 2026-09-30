package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore

/**
 * Content for a second screen (dual-screen handhelds, an external display). The Android app shows
 * it in its own activity on the other display; it is touch only and never takes controller input.
 */
@Composable
fun CompanionApp(store: FuseStore, platform: PlatformUi, mode: DualScreenMode) {
    Box(Modifier.fillMaxSize().background(Fuse.colors.ink))
}
