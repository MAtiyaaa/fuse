package io.github.matiyaaa.fuse.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.matiyaaa.fuse.ui.designsystem.Probe

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Fuse") { Probe() }
}
