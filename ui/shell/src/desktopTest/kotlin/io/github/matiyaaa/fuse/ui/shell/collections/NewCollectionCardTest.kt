package io.github.matiyaaa.fuse.ui.shell.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The "New collection" card goes back to exactly how it looked once the pointer leaves it, or the
 * controller's focus moves on: no hover tint, lift, edge or shadow is left behind.
 */
@OptIn(ExperimentalTestApi::class)
class NewCollectionCardTest {
    private var selected by mutableStateOf(false)
    private var overlay by mutableStateOf(false)

    private fun frames(block: androidx.compose.ui.test.ComposeUiTest.(capture: () -> BufferedImage, hoverIn: () -> Unit, hoverOut: () -> Unit, settle: () -> Unit) -> Unit) =
        runDesktopComposeUiTest(480, 360) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                    FuseTheme {
                        Box(Modifier.fillMaxSize().background(Fuse.colors.ink).padding(40.dp)) {
                            NewCollectionCard(selected, artHeight = 120.dp, onClick = { selected = true; overlay = true }, modifier = Modifier.width(180.dp))
                        }
                        // The name dialog that opens over the card, as in the app.
                        if (overlay) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                    }
                }
            }
            val settle = {
                mainClock.advanceTimeBy(2_000)
                waitForIdle()
            }
            settle()
            block(
                {
                    val f = onRoot().captureToImage().toAwtImage()
                    BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { drawImage(f, 0, 0, null); dispose() } }
                },
                { onRoot().performMouseInput { moveTo(Offset(150f, 140f)) } },
                { onRoot().performMouseInput { moveTo(Offset(470f, 350f)) } },
                settle,
            )
        }

    /** Pixels that differ by more than a little, so antialiasing noise doesn't count. */
    private fun changed(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            val d = abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
            if (d > 12) n++
        }
        return n
    }

    private fun save(name: String, img: BufferedImage) {
        // Kept with the build's reports, to look at when this card changes.
        val dir = File("build/reports/new-collection-card").apply { mkdirs() }
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test
    fun hoverLeavesNothingBehind() = frames { capture, hoverIn, hoverOut, settle ->
        val rest = capture()
        hoverIn()
        settle()
        val hovered = capture()
        hoverOut()
        settle()
        val after = capture()
        save("rest", rest)
        save("hovered", hovered)
        save("after-hover", after)
        assertTrue(changed(rest, hovered) > 200, "hovering shows")
        assertTrue(changed(rest, after) == 0, "after the pointer leaves, ${changed(rest, after)} pixels still differ")
    }

    @Test
    fun aDialogOpenedFromTheCardLeavesNothingBehind() = frames { capture, hoverIn, hoverOut, settle ->
        val rest = capture()
        hoverIn()
        settle()
        // Clicked: it is chosen, and the dialog opens over it while the pointer stays where it is.
        onRoot().performMouseInput { click(Offset(150f, 140f)) }
        settle()
        overlay = false
        settle()
        // The dialog is done, focus is still on the card; then the pointer leaves and focus moves on.
        hoverOut()
        settle()
        selected = false
        settle()
        val after = capture()
        save("after-dialog", after)
        assertTrue(changed(rest, after) == 0, "after the dialog, ${changed(rest, after)} pixels still differ")
    }

    @Test
    fun focusLeavesNothingBehind() = frames { capture, hoverIn, hoverOut, settle ->
        val rest = capture()
        selected = true
        settle()
        val focused = capture()
        selected = false
        settle()
        val after = capture()
        save("focused", focused)
        save("after-focus", after)
        assertTrue(changed(rest, focused) > 200, "focus shows")
        assertTrue(changed(rest, after) == 0, "after focus moves on, ${changed(rest, after)} pixels still differ")
        // Hover while focused, then both leave.
        selected = true
        hoverIn()
        settle()
        hoverOut()
        selected = false
        settle()
        assertTrue(changed(rest, capture()) == 0, "after hover and focus both leave")
    }
}
