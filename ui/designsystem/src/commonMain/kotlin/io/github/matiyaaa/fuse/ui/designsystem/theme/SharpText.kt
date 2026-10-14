package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.ui.text.PlatformTextStyle

/**
 * How text is rasterized where the platform's default is softer than it should be. On a computer
 * with a TV, Linux's default (slight hinting) leaves letter stems between pixels, so text looks a
 * little blurred next to the same screen on Android. Desktop hints fully, so stems land on whole
 * pixels; Android keeps its own.
 */
internal expect val sharpText: PlatformTextStyle?
