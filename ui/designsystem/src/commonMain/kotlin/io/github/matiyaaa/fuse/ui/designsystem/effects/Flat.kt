package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * How Fuse is being drawn, set by the app. Drawing that never changes is kept differently for each.
 */
object Drawing {
    /** No graphics card to draw with (a desktop with a broken driver). */
    @Volatile
    var cpu: Boolean = false

    /**
     * The platform keeps a layer drawn by the graphics card as a finished picture until its content
     * changes (Android's hardware layers). Set by the Android app; elsewhere a layer is drawn again
     * every frame, so a picture painted once is kept instead.
     */
    @Volatile
    var cachedLayers: Boolean = false
}

/**
 * Drawing that only changes when its inputs do (scrims, a resting room, a tile's generated art),
 * recorded once and then shown as that recording every frame.
 *
 * On Android ([Drawing.cachedLayers]) it is a [GraphicsLayer]: the drawing is recorded once, the
 * graphics card draws it, and nothing is recorded or painted again until [draw]'s key or size
 * changes. A [cached] layer is also kept by the graphics card as one finished picture (a hardware
 * layer), so a stack of screen-sized gradients costs one picture a frame. Nothing is painted by the
 * processor into a bitmap there: that is slow on a phone, and it was what made 0.3.7.4 slower.
 *
 * Elsewhere (desktops, where a layer is drawn again every frame), the drawing is painted once into
 * a picture and that picture is shown instead.
 *
 * What is drawn is the same either way: the layers are composed in the same order, once.
 */
class FlatLayer(private val layer: GraphicsLayer?, private val cached: Boolean = true) {
    private var image: ImageBitmap? = null
    private var madeFor: IntSize = IntSize.Zero
    private var madeWith: Any? = Unset
    private var recorded = false

    /**
     * Draws [paint], recorded again only when the size or [key] changes (compared with equals: pass
     * a data class or a list of everything the drawing reads).
     */
    fun draw(scope: DrawScope, key: Any?, paint: DrawScope.() -> Unit) {
        val size = IntSize(scope.size.width.roundToInt(), scope.size.height.roundToInt())
        if (size.width <= 0 || size.height <= 0) return
        val changed = size != madeFor || key != madeWith
        val gpu = layer != null && Drawing.cachedLayers && !Drawing.cpu
        if (gpu) {
            val l = layer!!
            if (changed || !recorded || l.isReleased) {
                image = null
                l.compositingStrategy = if (cached) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                l.record(scope, scope.layoutDirection, size) { paint() }
                recorded = true
                madeFor = size
                madeWith = key
            }
            scope.drawLayer(l)
            return
        }
        var picture = image
        if (picture == null || changed) {
            // A new size needs a new picture; the same size draws over the old one, cleared.
            picture = if (picture != null && size == madeFor) picture else ImageBitmap(size.width, size.height)
            val canvas = Canvas(picture)
            canvas.drawRect(0f, 0f, size.width.toFloat(), size.height.toFloat(), CLEAR)
            CanvasDrawScope().draw(scope, scope.layoutDirection, canvas, Size(size.width.toFloat(), size.height.toFloat())) { paint() }
            image = picture
            recorded = false
            madeFor = size
            madeWith = key
        }
        scope.drawImage(picture)
    }

    private object Unset

    private companion object {
        /** Clears a reused picture to transparent before it is drawn again. */
        val CLEAR = Paint().apply { blendMode = BlendMode.Clear }
    }
}

/** A [FlatLayer] for this composable (see [FlatLayer.cached]). */
@Composable
fun rememberFlatLayer(cached: Boolean = true): FlatLayer {
    val layer = rememberGraphicsLayer()
    return remember(layer, cached) { FlatLayer(layer, cached) }
}
