package io.github.matiyaaa.fuse.ui.shell.platform

import android.graphics.Bitmap
import coil3.BitmapImage

internal actual fun saveThumbnail(image: coil3.Image, target: String, alpha: Boolean): Boolean {
    val bitmap = (image as? BitmapImage)?.bitmap ?: return false
    // Hardware bitmaps can't be read back; those stay uncopied.
    if (bitmap.config == Bitmap.Config.HARDWARE) return false
    java.io.FileOutputStream(target).use { out ->
        return bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
    }
}
