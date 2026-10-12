package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.matiyaaa.fuse.model.ColorVision
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ColorVisionTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    @Test
    fun statesStayReadableAndApartOnEveryTheme() {
        for (spec in ThemePresets.all) {
            val base = FuseColors.from(spec.palette)
            assertEquals(base, base.forVision(ColorVision.TYPICAL), spec.id)
            for (vision in listOf(ColorVision.RED_GREEN, ColorVision.BLUE_YELLOW)) {
                val c = base.forVision(vision)
                val states = listOf(c.success, c.warning, c.danger)
                for (state in states) {
                    // Icons and badges in these colours read against every panel (3:1, as for graphics).
                    for (surface in listOf(c.ink, c.surface, c.surfaceRaised)) {
                        assertTrue(contrast(state, surface) >= 3f, "${spec.id} $vision $state on $surface: ${contrast(state, surface)}")
                    }
                }
                // The three are told apart by lightness too, not hue alone.
                val lights = states.map { it.luminance() }
                for (i in lights.indices) for (j in lights.indices) if (i < j) {
                    assertTrue(contrast(states[i], states[j]) >= 1.15f, "${spec.id} $vision states $i and $j are too alike in lightness")
                }
                assertNotEquals(base.success, c.success)
            }
        }
    }
}
