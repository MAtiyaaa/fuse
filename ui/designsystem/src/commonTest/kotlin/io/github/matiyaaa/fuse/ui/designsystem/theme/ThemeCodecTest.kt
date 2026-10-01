package io.github.matiyaaa.fuse.ui.designsystem.theme

import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.Contrast
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.model.ThemeLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeCodecTest {
    private fun parse(text: String) = ThemeCodec.parse(text, ThemePresets::find, ThemePresets.Fuse)

    private fun ok(text: String): ThemeCodec.Imported = assertIs<ThemeCodec.Imported>(parse(text))

    @Test
    fun aNameAloneIsFuseUnderANewName() {
        val t = ok("""{"name": "Mine"}""")
        assertEquals("Mine", t.spec.name)
        assertEquals("custom.mine", t.spec.id)
        assertEquals(ThemePresets.Fuse.palette, t.spec.palette)
        assertTrue(t.notes.isEmpty())
    }

    @Test
    fun itStartsFromTheThemeItExtends() {
        val t = ok("""{"name": "Cold glass", "extends": "glass", "colors": {"accent": "#7FE0C8"}}""")
        assertTrue(t.spec.glass.enabled)
        assertEquals(0xFF7FE0C8, t.spec.palette.accent)
        assertEquals(0x337FE0C8, t.spec.palette.accentSoft)
        val unknown = ok("""{"name": "X", "extends": "nope"}""")
        assertEquals(ThemePresets.Fuse.palette, unknown.spec.palette)
        assertTrue(unknown.notes.single().contains("nope"))
    }

    @Test
    fun coloursComeInEveryWebForm() {
        assertEquals(0xFFFFAA33, ThemeCodec.parseColor("#FA3"))
        assertEquals(0xFF112233, ThemeCodec.parseColor("112233"))
        assertEquals(0x80112233, ThemeCodec.parseColor("#11223380"))
        assertNull(ThemeCodec.parseColor("#12"))
        assertNull(ThemeCodec.parseColor("red"))
        assertEquals("#112233", ThemeCodec.hex(0xFF112233))
        assertEquals("#11223380", ThemeCodec.hex(0x80112233))
    }

    @Test
    fun brokenFilesAreRefusedWithAReason() {
        assertIs<ThemeCodec.Failed>(parse("not json"))
        assertIs<ThemeCodec.Failed>(parse("[1, 2]"))
        assertIs<ThemeCodec.Failed>(parse("""{"colors": {}}"""))
        assertIs<ThemeCodec.Failed>(parse("""{"name": "big", "pad": "${"x".repeat(70_000)}"}"""))
    }

    @Test
    fun unknownPartsAreSkippedAndSaid() {
        val t = ok("""{"name": "Odd", "sparkles": true, "corners": "wobbly", "background": "lava", "colors": {"accent": "orange"}}""")
        assertEquals(ThemePresets.Fuse.geometry, t.spec.geometry)
        assertEquals(ThemePresets.Fuse.background, t.spec.background)
        assertEquals(ThemePresets.Fuse.palette.accent, t.spec.palette.accent)
        assertEquals(3, t.notes.size)
    }

    @Test
    fun hardToReadTextIsRepaired() {
        val t = ok("""{"name": "Murky", "colors": {"background": "#101010", "text": "#202020", "textMuted": "#222222"}}""")
        val p = t.spec.palette
        assertTrue(Contrast.ratio(p.textPrimary, p.background) >= 4.5)
        assertTrue(Contrast.ratio(p.textSecondary, p.surface) >= 3.0)
        assertTrue(t.notes.any { it.contains("text colour") })
        // Surfaces are always solid.
        val glassy = ok("""{"name": "See through", "colors": {"surface": "#11223300"}}""")
        assertEquals(0xFF112233, glassy.spec.palette.surface)
    }

    @Test
    fun aBrightBackgroundMakesALightTheme() {
        val t = ok("""{"name": "Paper", "colors": {"background": "#F7F3EA", "surface": "#FFFFFF", "text": "#1A1A1A", "textMuted": "#555555", "focus": "#1A1A1A"}}""")
        assertTrue(!t.spec.palette.dark)
        assertTrue(t.notes.isEmpty(), t.notes.toString())
    }

    @Test
    fun numbersAreKeptInRange() {
        val t = ok("""{"name": "Loud", "background": {"style": "stars", "intensity": 9, "speed": -1}, "glass": {"opacity": 0.05}}""")
        assertEquals(BackgroundStyle.STARS, t.spec.background)
        assertEquals(1.5f, t.spec.ambient.intensity)
        assertEquals(0f, t.spec.ambient.speed)
        assertEquals(0.3f, t.spec.glass.surfaceOpacity)
        assertTrue(t.spec.glass.enabled)
        assertEquals(3, t.notes.size)
    }

    @Test
    fun everyBackgroundLoadsByName() {
        for (style in BackgroundStyle.entries) {
            val name = style.name.lowercase()
            val plain = ok("""{"name": "Bg $name", "background": "$name"}""")
            assertEquals(style, plain.spec.background, name)
            assertTrue(plain.notes.isEmpty(), "$name: ${plain.notes}")
            // In the object form too, and whatever the case.
            val full = ok("""{"name": "Bg $name", "background": {"style": "${name.uppercase()}", "intensity": 0.8, "speed": 0.5, "secondary": "#FFC46B"}}""")
            assertEquals(style, full.spec.background, name)
            assertEquals(0.8f, full.spec.ambient.intensity)
            assertEquals(0xFFFFC46B, full.spec.ambient.secondary)
            assertTrue(full.notes.isEmpty(), "$name: ${full.notes}")
        }
    }

    @Test
    fun aThemeFileCanUseTheNewBackgrounds() {
        val t = ok(
            """{"fuseTheme": 1, "name": "Night Bloom", "extends": "blossom",
               "colors": {"accent": "#FFB3D1"},
               "background": {"style": "fireflies", "intensity": 1.2, "speed": 0.7, "secondary": "#7FE0B0"}}""",
        )
        assertEquals(BackgroundStyle.FIREFLIES, t.spec.background)
        assertEquals(1.2f, t.spec.ambient.intensity)
        assertEquals(0.7f, t.spec.ambient.speed)
        assertEquals(0xFF7FE0B0, t.spec.ambient.secondary)
        // The rest comes from Blossom.
        assertEquals(ThemePresets.Blossom.geometry, t.spec.geometry)
        assertEquals(ThemePresets.Blossom.palette.background, t.spec.palette.background)
        assertTrue(t.notes.isEmpty(), t.notes.toString())
        // Encoding writes the style's name, and it reads back.
        assertTrue(ThemeCodec.encode(t.spec).contains("\"style\": \"fireflies\""))
        assertEquals(BackgroundStyle.FIREFLIES, ok(ThemeCodec.encode(t.spec, extends = "blossom")).spec.background)
    }

    @Test
    fun builtInThemesAreDistinctAndInOrder() {
        val all = ThemePresets.all
        assertEquals(ThemePresets.Fuse, all.first(), "Fuse, the default, comes first")
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids are unique")
        assertEquals(all.size, all.map { it.name.lowercase() }.toSet().size, "names are unique")
        assertEquals(all.size, all.map { it.tagline }.toSet().size, "taglines are unique")
        // Dark rooms first, then the bright ones.
        val firstBright = all.indexOfFirst { !it.palette.dark }
        assertTrue(all.drop(firstBright).none { it.palette.dark }, "bright themes come after every dark one")
        assertTrue(all.count { !it.palette.dark } >= 4, "a real choice of bright themes")
        assertEquals(all, ThemePresets.dark + ThemePresets.bright)
        // Ids people may have selected never change.
        for (id in listOf("fuse", "glass", "starlight", "crossbar", "orbital", "wave", "blades", "channels", "crt", "daylight")) {
            assertEquals(id, ThemePresets.find(id)?.id, id)
        }
        // Every background style is shown by at least one built-in theme, the room behind art aside.
        val shown = all.map { it.background }.toSet()
        assertTrue(BackgroundStyle.entries.filter { it != BackgroundStyle.HERO && it != BackgroundStyle.SOLID }.all { it in shown })
    }

    @Test
    fun pitchIsATrueBlackRoomThatNeverMoves() {
        val pitch = ThemePresets.Pitch
        assertEquals(0xFF000000, pitch.palette.background)
        assertTrue(pitch.palette.dark)
        assertTrue(!pitch.background.moves)
        assertEquals(0f, pitch.ambient.intensity)
        assertTrue(!pitch.glass.enabled && !pitch.crt.enabled)
    }

    @Test
    fun aNewerFileStillLoadsWithANote() {
        val t = ok("""{"fuseTheme": 9, "name": "Future", "corners": "pill"}""")
        assertEquals(CornerFamily.PILL, t.spec.geometry)
        assertTrue(t.notes.single().contains("newer"))
    }

    @Test
    fun idsAreSafeSlugs() {
        assertEquals("custom.midnight-ember-2", ok("""{"name": "  Midnight   Ember #2! "}""").spec.id)
        assertEquals("custom.night", ok("""{"name": "Anything", "id": "custom.Night"}""").spec.id)
        assertEquals("custom.theme", ok("""{"name": "???"}""").spec.id)
        // Names lose control characters and are kept short.
        assertEquals(40, ok("""{"name": "${"n".repeat(60)}"}""").spec.name.length)
    }

    @Test
    fun everyPresetRoundTripsAndReadsWell() {
        for (preset in ThemePresets.all) {
            val back = ok(ThemeCodec.encode(preset))
            assertTrue(back.notes.isEmpty(), "${preset.id}: ${back.notes}")
            assertEquals(preset.copy(id = "custom.${preset.id}"), back.spec, preset.id)
            val p = preset.palette
            assertTrue(Contrast.ratio(p.textPrimary, p.background) >= 4.5, preset.id)
            assertTrue(Contrast.ratio(p.textPrimary, p.surface) >= 4.5, preset.id)
            assertTrue(Contrast.ratio(p.textSecondary, p.surface) >= 3.0, preset.id)
            assertTrue(Contrast.ratio(p.onAccent, p.accent) >= 4.5, preset.id)
            assertTrue(Contrast.ratio(p.focusRing, p.background) >= 3.0, preset.id)
        }
    }

    @Test
    fun sharedLinksBecomeTheirFiles() {
        assertEquals(
            "https://raw.githubusercontent.com/someone/themes/main/ember.json",
            ThemeLinks.normalize("https://github.com/someone/themes/blob/main/ember.json"),
        )
        assertEquals("https://gist.githubusercontent.com/someone/abc123/raw", ThemeLinks.normalize("https://gist.github.com/someone/abc123"))
        assertEquals("https://example.com/t.json", ThemeLinks.normalize(" https://example.com/t.json "))
        assertNull(ThemeLinks.normalize("http://example.com/t.json"))
    }
}
