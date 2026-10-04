package io.github.matiyaaa.fuse.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual fun argbBitmap(width: Int, height: Int, pixels: IntArray): ImageBitmap {
    // Little-endian ARGB ints are BGRA bytes, which is Skia's N32 on these machines.
    val bytes = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(pixels, 0, width * height)
    return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes.array(), width * 4).toComposeImageBitmap()
}
