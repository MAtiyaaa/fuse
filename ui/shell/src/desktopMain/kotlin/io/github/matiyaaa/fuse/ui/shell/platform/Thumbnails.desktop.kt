package io.github.matiyaaa.fuse.ui.shell.platform

import coil3.BitmapImage
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun saveThumbnail(image: coil3.Image, target: String, alpha: Boolean): Boolean {
    val bitmap = (image as? BitmapImage)?.bitmap ?: return false
    val data = Image.makeFromBitmap(bitmap).encodeToData(if (alpha) EncodedImageFormat.PNG else EncodedImageFormat.JPEG, 88) ?: return false
    java.io.File(target).writeBytes(data.bytes)
    return true
}
