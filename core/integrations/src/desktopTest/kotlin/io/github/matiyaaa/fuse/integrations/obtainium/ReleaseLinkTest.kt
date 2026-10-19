package io.github.matiyaaa.fuse.integrations.obtainium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Addresses as people paste them, read as the repository whose releases Fuse follows. */
class ReleaseLinkTest {
    @Test
    fun gitHubInEveryShapeItIsPasted() {
        val want = ReleaseLink(ReleaseLink.Kind.GITHUB, "github.com", "PCSX2", "pcsx2")
        for (text in listOf(
            "github.com/PCSX2/pcsx2", "https://github.com/PCSX2/pcsx2", "https://github.com/PCSX2/pcsx2/",
            "https://www.github.com/PCSX2/pcsx2", "https://github.com/PCSX2/pcsx2.git", "https://github.com/PCSX2/pcsx2/releases",
            "https://github.com/PCSX2/pcsx2/releases/tag/v2.4.0", "https://github.com/PCSX2/pcsx2/tree/master?tab=readme#top", "  https://GitHub.com/PCSX2/pcsx2  ",
        )) assertEquals(want, ReleaseLink.parse(text), text)
        assertEquals("https://github.com/PCSX2/pcsx2/releases", want.releasesPage)
    }

    @Test
    fun forgejoAndGiteaServersAreTheirOwn() {
        assertEquals(ReleaseLink(ReleaseLink.Kind.FORGE, "git.eden-emu.dev", "eden-emu", "eden"), ReleaseLink.parse("https://git.eden-emu.dev/eden-emu/eden/releases"))
        assertEquals(ReleaseLink(ReleaseLink.Kind.FORGE, "codeberg.org", "someone", "handy"), ReleaseLink.parse("codeberg.org/someone/handy"))
        assertEquals("https://git.example.org:3000/a/b", ReleaseLink.parse("https://git.example.org:3000/a/b")?.url)
    }

    @Test
    fun whatIsntARepositoryIsRefused() {
        for (text in listOf(
            "", "not a link", "github.com/PCSX2", "https://github.com", "http://github.com/PCSX2/pcsx2", "ftp://example.org/a/b",
            "localhost/a/b", "https://github.com/PC SX2/pcsx2", "https://github.com/../etc", "file:///home/a/b",
        )) assertNull(ReleaseLink.parse(text), text)
    }
}
