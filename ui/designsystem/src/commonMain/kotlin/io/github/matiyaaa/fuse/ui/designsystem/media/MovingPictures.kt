package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.ComponentRegistry
import coil3.Extras
import coil3.request.ImageRequest
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.picturesMove

/**
 * Pictures that move: an animated GIF (or animated WebP) chosen as a game's or a system's picture,
 * its logo, or the background behind everything. Android's image loader plays them by itself; on a
 * computer Fuse decodes their frames ([MovingPicture]) and the art showing one plays it while it is
 * on screen. Under Reduced motion, in Low Power Mode and on the second screen while a game runs they
 * hold their first frame ([still]).
 */
object MovingPictures {
    /** Asks the image loader for a picture's first frame only. */
    val Still = Extras.Key(default = false)

    /**
     * Marks [builder] for [model]'s first frame only, cached apart from the moving one, when [model]
     * may move at all ([mayMove]): other art keeps the cache key it is warmed with.
     */
    fun still(builder: ImageRequest.Builder, model: Any?): ImageRequest.Builder = builder.apply {
        if (!mayMove(model)) return@apply
        extras[Still] = true
        memoryCacheKeyExtra("fuse.still", "1")
    }

    /** Whether [model] is a GIF or a WebP, which may move. */
    fun mayMove(model: Any?): Boolean =
        (model as? String)?.substringBefore('?')?.let { it.endsWith(".gif", ignoreCase = true) || it.endsWith(".webp", ignoreCase = true) } == true

    /** Whether [header] (a file's first bytes) starts a GIF, or a WebP with frames. */
    fun isMoving(header: ByteArray): Boolean {
        fun at(i: Int, text: String) = header.size >= i + text.length && text.indices.all { header[i + it] == text[it].code.toByte() }
        if (at(0, "GIF87a") || at(0, "GIF89a")) return true
        // A WebP with the extended header says whether it is animated in its flags (bit 2).
        return at(0, "RIFF") && at(8, "WEBP") && at(12, "VP8X") && header.size > 20 && header[20].toInt() and 0x02 != 0
    }

    /** Whether [model] names a GIF: drawn in its own colours, never recoloured as a plain logo is. */
    fun looksMoving(model: Any?): Boolean =
        (model as? String)?.substringBefore('?')?.endsWith(".gif", ignoreCase = true) == true
}

/** A picture whose frames Fuse plays itself (a GIF on a computer); [play] runs until cancelled. */
interface MovingPicture {
    suspend fun play()
}

/** The image loader's way of reading moving pictures on this platform. */
expect fun ComponentRegistry.Builder.addMovingPictures()

/** Plays [image] while this is in the composition, when it moves and pictures may move. */
@Composable
internal fun PlayWhileShown(image: Any?) {
    val moving = image as? MovingPicture ?: return
    if (!Fuse.motion.picturesMove(Fuse.quality)) return
    LaunchedEffect(moving) { moving.play() }
}
