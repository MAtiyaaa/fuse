package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle

// Greyscale edges (never coloured subpixels: a TV's pixels and scaling don't suit them), full
// hinting, and letters still placed between pixels so moving text glides rather than steps.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal actual val sharpText: PlatformTextStyle? = PlatformTextStyle(
    spanStyle = null,
    paragraphStyle = PlatformParagraphStyle(
        FontRasterizationSettings(
            smoothing = FontSmoothing.AntiAlias,
            hinting = FontHinting.Normal,
            subpixelPositioning = true,
            autoHintingForced = false,
        ),
    ),
)
