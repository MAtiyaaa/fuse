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

    /** Android retains offscreen hardware surfaces; desktop retains drawing commands instead. */
    @Volatile
    var cachedLayers: Boolean = false
}

/**
 * Static drawing retained as commands on GPU renderers, preserving vector detail under transforms.
 * Android may also retain a hardware surface. Desktop deliberately uses Auto compositing: Skiko
 * records a Skia picture rather than painting the commands into a CPU ImageBitmap before upload.
 * See JetBrains' GraphicsLayer.desktop implementation in compose-multiplatform-core.
 * Software rendering keeps the raster fallback, bounded by [SOFTWARE_CACHE_PIXELS].
 */
class FlatLayer(private val layer: GraphicsLayer?, private val cached: Boolean = true) {
    private var image: ImageBitmap? = null
    private var madeFor: IntSize = IntSize.Zero
    private var madeWith: Any? = Unset
    private var recorded = false
    private var madeDensity = Float.NaN
    private var madeFontScale = Float.NaN
    private var madeDirection: androidx.compose.ui.unit.LayoutDirection? = null

    /**
     * Draws [paint], recorded again only when the size or [key] changes (compared with equals: pass
     * a data class or a list of everything the drawing reads).
     */
    fun draw(scope: DrawScope, key: Any?, paint: DrawScope.() -> Unit) {
        val size = IntSize(scope.size.width.roundToInt(), scope.size.height.roundToInt())
        if (size.width <= 0 || size.height <= 0) return
        val changed = size != madeFor || key != madeWith || scope.density != madeDensity ||
            scope.fontScale != madeFontScale || scope.layoutDirection != madeDirection
        madeDensity = scope.density
        madeFontScale = scope.fontScale
        madeDirection = scope.layoutDirection
        val gpu = layer != null && !Drawing.cpu
        if (gpu) {
            val l = layer!!
            if (changed || !recorded || l.isReleased) {
                image = null
                l.compositingStrategy = if (cached && Drawing.cachedLayers) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                UiRenderTrace.recording {
                    l.record(scope, scope.layoutDirection, size) { paint() }
                }
                recorded = true
                madeFor = size
                madeWith = key
            }
            scope.drawLayer(l)
            return
        }
        // Uncached generated artwork and very large software surfaces redraw directly. Allocating
        // a new multi-megabyte bitmap on every live resize is worse than replaying cheap commands.
        if (!cached || size.width.toLong() * size.height > SOFTWARE_CACHE_PIXELS) {
            image = null
            recorded = false
            scope.paint()
            return
        }
        var picture = image
        if (picture == null || changed) {
            // A new size needs a new picture; the same size draws over the old one, cleared.
            picture = if (picture != null && size == madeFor) picture else ImageBitmap(size.width, size.height)
            val canvas = Canvas(picture)
            canvas.drawRect(0f, 0f, size.width.toFloat(), size.height.toFloat(), CLEAR)
            UiRenderTrace.rasterizing(size.width.toLong() * size.height * 4) {
                CanvasDrawScope().draw(scope, scope.layoutDirection, canvas, Size(size.width.toFloat(), size.height.toFloat())) { paint() }
            }
            image = picture
            recorded = false
            madeFor = size
            madeWith = key
        }
        scope.drawImage(picture)
    }

    private object Unset

    private companion object {
        const val SOFTWARE_CACHE_PIXELS = 2_097_152L
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
