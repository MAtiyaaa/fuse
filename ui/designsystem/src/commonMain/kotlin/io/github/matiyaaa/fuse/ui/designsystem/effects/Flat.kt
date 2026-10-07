package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Screen-sized drawing that only changes when its inputs do (scrims, a resting room), drawn once
 * into a picture and then shown as that one picture every frame. A stack of full-screen gradients
 * costs a full screen of blending each; flattened, it costs one, which on a modest graphics chip
 * (or one drawing without any) is most of a frame. What is drawn is the same: the layers are
 * composed in the same order, once.
 *
 * [draw] makes the picture again when the size or [key] changes (compare with equals: pass a data
 * class or a list of everything the drawing reads), and otherwise draws the one already made.
 */
class FlatLayer {
    private var image: ImageBitmap? = null
    private var madeFor: IntSize = IntSize.Zero
    private var madeWith: Any? = Unset

    fun draw(scope: DrawScope, key: Any?, paint: DrawScope.() -> Unit) {
        val size = IntSize(scope.size.width.roundToInt(), scope.size.height.roundToInt())
        if (size.width <= 0 || size.height <= 0) return
        var picture = image
        if (picture == null || size != madeFor || key != madeWith) {
            // A new size needs a new picture; the same size draws over the old one, cleared.
            picture = if (picture != null && size == madeFor) picture else ImageBitmap(size.width, size.height)
            val canvas = Canvas(picture)
            canvas.drawRect(0f, 0f, size.width.toFloat(), size.height.toFloat(), CLEAR)
            CanvasDrawScope().draw(scope, scope.layoutDirection, canvas, Size(size.width.toFloat(), size.height.toFloat())) { paint() }
            image = picture
            madeFor = size
            madeWith = key
        }
        scope.drawImage(picture)
    }

    private object Unset

    private companion object {
        /** Clears a reused picture to transparent before it is drawn again. */
        val CLEAR = androidx.compose.ui.graphics.Paint().apply { blendMode = androidx.compose.ui.graphics.BlendMode.Clear }
    }
}
