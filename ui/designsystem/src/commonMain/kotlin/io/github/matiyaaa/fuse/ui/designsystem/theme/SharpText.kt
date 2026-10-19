package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.ui.text.PlatformTextStyle

/**
 * How text is rasterized where the platform's default is softer than it should be. On a computer
 * with a TV, Linux's default (slight hinting) leaves letter stems between pixels, so text looks a
 * little blurred next to the same screen on Android. Desktop hints fully, so stems land on whole
 * pixels; Android keeps its own.
 */
internal expect val sharpText: PlatformTextStyle?

/**
 * Whether light text needs the help Android gives it. Android's text renderer applies a gamma boost
 * that thickens light letters on a dark room; Skia on Linux blends their edges straight, so the same
 * text in the same weight came out thinner and greyer, worst on a TV across the room. There reading
 * text is drawn a weight heavier and muted text a little brighter, so it reads as it does on Android.
 */
internal expect val strongerText: Boolean
