package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle

// Greyscale edges (never coloured subpixels: a TV's pixels and scaling don't suit them), and
// letters still placed between pixels so moving text glides rather than steps. On Linux, light
// hinting: FreeType's full hinting pulls every stroke onto the pixel grid and bends the letters'
// shapes, and Fuse's type read as rough and low quality there (Windows and macOS hint their own way).
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal actual val sharpText: PlatformTextStyle? = PlatformTextStyle(
    spanStyle = null,
    paragraphStyle = PlatformParagraphStyle(
        FontRasterizationSettings(
            smoothing = FontSmoothing.AntiAlias,
            hinting = if (System.getProperty("os.name").orEmpty().lowercase().startsWith("linux")) FontHinting.Slight else FontHinting.Normal,
            subpixelPositioning = true,
            autoHintingForced = false,
        ),
    ),
)
