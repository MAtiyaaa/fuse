package io.github.matiyaaa.fuse.integrations.obtainium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTextTest {
    private fun extract(pattern: String?, groups: String?, text: String) = VersionText.extract(pattern, groups, text).getOrThrow()

    @Test
    fun ppssppTemplateBuildsBothShapes() {
        val pattern = """\/(?:(?:([0-9]+)_([0-9]+)\/ppsspp\.)|(?:([0-9]+)_([0-9]+)_([0-9]+)\/ppsspp(\.)))apk$"""
        val groups = "\$1\$3.\$2\$4\$6\$5"
        assertEquals("1.19", extract(pattern, groups, "https://www.ppsspp.org/files/1_19/ppsspp.apk"))
        assertEquals("1.20.4", extract(pattern, groups, "https://www.ppsspp.org/files/1_20_4/ppsspp.apk"))
    }

    @Test
    fun templatesAddText() {
        assertEquals("2123.1-vanilla", extract("(.+)", "\$1-vanilla", "2123.1"))
        assertEquals("b12", extract("(b)eta-(.*)", "\$1\$2", "beta-12"))
        assertEquals("1.22.2", extract("""\d+\.\d+\.\d+""", null, "/stable/1.22.2/android/RetroArch_aarch64.apk"))
        assertEquals("3.1", extract("v?(.+)", "1", "v3.1"))
    }

    @Test
    fun theLastMatchCounts() {
        assertEquals("2.0", extract("""(\d+\.\d+)""", "\$1", "from 1.0 to 2.0"))
    }

    @Test
    fun noPatternMeansNoVersion() {
        assertNull(extract(null, "\$1", "anything"))
        assertNull(extract("", null, "anything"))
    }

    @Test
    fun aPatternThatFindsNothingOrCantBeReadFails() {
        assertTrue(VersionText.extract("""(\d+)""", "\$1", "no digits").isFailure)
        assertTrue(VersionText.extract("([unclosed", null, "x").isFailure)
    }

    @Test
    fun naturalOrderIsObtainiums() {
        val sorted = listOf("1.10", "1.9", "1.2", "1.9b").sortedWith(VersionText::compareAlphaNumeric)
        assertEquals(listOf("1.2", "1.9", "1.9b", "1.10"), sorted)
        assertTrue(VersionText.compareAlphaNumeric("scummvm-2026.3.0", "scummvm-2026.2.1") > 0)
    }

    @Test
    fun numericComparisonNeedsASharedShape() {
        assertEquals(1, VersionText.compareNumerically("v1.20.4", "v1.19.3"))
        assertEquals(0, VersionText.compareNumerically("1.2.3", "v1.2.3"))
        assertNull(VersionText.compareNumerically("nightly", "1.2"))
    }

    @Test
    fun standingIsHonest() {
        assertEquals(VersionStanding.CURRENT, VersionText.standing("1.19.3", "v1.19.3"))
        assertEquals(VersionStanding.BEHIND, VersionText.standing("1.19.3", "1.20"))
        assertEquals(VersionStanding.BEHIND, VersionText.standing("1.2", "1.2.1"))
        assertEquals(VersionStanding.CURRENT, VersionText.standing("2.6", "Cemu 2.6"))
        assertEquals(VersionStanding.CURRENT, VersionText.standing("1.21.0-nightly", "1.20.4"))
        // Different schemes are never turned into an update.
        assertEquals(VersionStanding.UNKNOWN, VersionText.standing("2609", "5.0-21088"))
        assertEquals(VersionStanding.UNKNOWN, VersionText.standing("master", "1.0"))
        assertEquals(VersionStanding.UNKNOWN, VersionText.standing("", "1.0"))
    }
}
