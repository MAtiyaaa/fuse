package io.github.matiyaaa.fuse.library.patch

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * xdelta patches as xdelta3 itself makes them (3.2.2, built from its own source with liblzma:
 * `xdelta3 -e -s source target patch`), applied by Fuse's own reader and compared byte for byte
 * with what xdelta3 makes from them. The game is made up: 64 KB from a fixed generator, and the
 * patched game has 16 bytes changed, 300 inserted and 4 KB moved to its end; the wordy one has
 * 600 letters (from another generator) put in four places, so xdelta3's own compression of its
 * sections has something to do in every window.
 */
@OptIn(ExperimentalEncodingApi::class)
class XdeltaPatchesTest {
    private val source: ByteArray = run {
        var x = 2026L
        ByteArray(65_536) { x = (x * 1_103_515_245L + 12_345L) and 0x7FFF_FFFFL; (x shr 16).toByte() }
    }

    private val target: ByteArray =
        source.copyOfRange(0, 1_000) + ByteArray(16) { 0xAA.toByte() } + source.copyOfRange(1_016, 20_000) +
            "FUSE".repeat(75).encodeToByteArray() + source.copyOfRange(20_000, 65_536) + source.copyOfRange(30_000, 34_000)

    private val letters: ByteArray = run {
        var x = 7L
        ByteArray(2_400) { x = (x * 1_103_515_245L + 12_345L) and 0x7FFF_FFFFL; ('a'.code + ((x shr 16) % 26).toInt()).toByte() }
    }

    private val wordy: ByteArray =
        source.copyOfRange(0, 8_000) + letters.copyOfRange(0, 600) + source.copyOfRange(8_000, 24_000) + letters.copyOfRange(600, 1_200) +
            source.copyOfRange(24_000, 40_000) + letters.copyOfRange(1_200, 1_800) + source.copyOfRange(40_000, 56_000) +
            letters.copyOfRange(1_800, 2_400) + source.copyOfRange(56_000, 65_536)

    private fun patch(vararg base64: String) = Base64.decode(base64.joinToString(""))

    /** `-S none`: one window, xdelta3's application header and its Adler-32 of the result. */
    private val plain = patch(
        "1sPEAASBGXRhcmdldC5iaW4jMmYwYTgyMTZjY2RkZmM1YWUyZWNmYzk2NWJlOWQxODNjNDFhMWNmOGE1YTAyMjk0YzgwNjZm" +
        "MGIyOTJkOTQwZS8vc291cmNlLmJpbiMwZWVlMTBhODBlMDc5OTg5NjI2ZTBmZDBjNjM0ZWE1Y2Y0ZWE5Y2E0OTQ1NmFkN2Qy" +
        "YTEwN2FkMGVjOTk3ZGUxLwWEgAAALYShTAAFFAnKHqAzqkZVU0UTh2gAEBOBlCgFI4IoQ4LjYGOfIACHeASBlCjOEA==",
    )

    /** `-S none -W 16384`: the game in several windows, each copying from the original. */
    private val windows = patch(
        "1sPEAASBGXRhcmdldC5iaW4jMmYwYTgyMTZjY2RkZmM1YWUyZWNmYzk2NWJlOWQxODNjNDFhMWNmOGE1YTAyMjk0YzgwNjZm" +
        "MGIyOTJkOTQwZS8vc291cmNlLmJpbiMwZWVlMTBhODBlMDc5OTg5NjI2ZTBmZDBjNjM0ZWE1Y2Y0ZWE5Y2E0OTQ1NmFkN2Qy" +
        "YTEwN2FkMGVjOTk3ZGUxLwWBgAAAF4GAAAABCAOgdAXLqhOHaAAQE/gIAId4Bf1UgYAAHYGAAAAECgRHEzygRlVTRROcIAUj" +
        "gigT4TQABJwgBYGAAIH9VBCBgAAAAAQB1pEPCBOBgAAABYGAAIL9VBCBgAAAAAQBruH1OBOBgAAABYKVUIHqMBOhTAAABgNe" +
        "Hll9I4IsE58ggiwA",
    )

    /** `-S none -n`: no checksum. */
    private val unchecked = patch(
        "1sPEAASBGXRhcmdldC5iaW4jMmYwYTgyMTZjY2RkZmM1YWUyZWNmYzk2NWJlOWQxODNjNDFhMWNmOGE1YTAyMjk0YzgwNjZm" +
        "MGIyOTJkOTQwZS8vc291cmNlLmJpbiMwZWVlMTBhODBlMDc5OTg5NjI2ZTBmZDBjNjM0ZWE1Y2Y0ZWE5Y2E0OTQ1NmFkN2Qy" +
        "YTEwN2FkMGVjOTk3ZGUxLwGEgAAAKYShTAAFFAmqRlVTRROHaAAQE4GUKAUjgihDguNgY58gAId4BIGUKM4Q",
    )

    /** `-S lzma` (its instructions compressed again) and `-S djw` (nothing worth compressing, so nothing is). */
    private val lzma = patch(
        "1sPEAAUCgRl0YXJnZXQuYmluIzJmMGE4MjE2Y2NkZGZjNWFlMmVjZmM5NjViZTlkMTgzYzQxYTFjZjhhNWEwMjI5NGM4MDY2" +
        "ZjBiMjkyZDk0MGUvL3NvdXJjZS5iaW4jMGVlZTEwYTgwZTA3OTk4OTYyNmUwZmQwYzYzNGVhNWNmNGVhOWNhNDk0NTZhZDdk" +
        "MmExMDdhZDBlYzk5N2RlMS8FhIAAAEmEoUwCBTAJyh6gM6pGVVNFFP03elhaAAAA/xLZQQIAIQEMAAAAj5hBnAEAExOHaAAQ" +
        "E4GUKAUjgihDguNgY58gAId4BIGUKM4Q",
    )
    private val djw = patch(
        "1sPEAAUBgRl0YXJnZXQuYmluIzJmMGE4MjE2Y2NkZGZjNWFlMmVjZmM5NjViZTlkMTgzYzQxYTFjZjhhNWEwMjI5NGM4MDY2" +
        "ZjBiMjkyZDk0MGUvL3NvdXJjZS5iaW4jMGVlZTEwYTgwZTA3OTk4OTYyNmUwZmQwYzYzNGVhNWNmNGVhOWNhNDk0NTZhZDdk" +
        "MmExMDdhZDBlYzk5N2RlMS8FhIAAAC2EoUwABRQJyh6gM6pGVVNFE4doABATgZQoBSOCKEOC42BjnyAAh3gEgZQozhA=",
    )

    /**
     * `-S lzma -W 16384` of the wordy game: five windows, each one's data compressed again and the third's
     * instructions too, through one LZMA stream per kind of section that runs through every window.
     */
    private val lzmaWindows = patch(
        "1sPEAAUCgRh3b3JkeS5iaW4jMDEwNjRmNDAyM2M1ZTlkMDZhZTJlYWNkNDBmYjRhOGE4YjU5ODcxNmNkMjcwZGRjYTU3Yzgy" +
        "YTQ0ZTE1ODFiNi8vc291cmNlLmJpbiMwZWVlMTBhODBlMDc5OTg5NjI2ZTBmZDBjNjM0ZWE1Y2Y0ZWE5Y2E0OTQ1NmFkN2Qy" +
        "YTEwN2FkMGVjOTk3ZGUxLwX7KACEAYGAAAGDaQkDLSbUtIRY/Td6WFoAAAD/EtlBAgAhAQwAAACPmEGc4AJXAchdADaZSEhS" +
        "w4aMN7Gjzahx2y49rkRz41tfEaE5AiazpRFOIJV+hG881VkOu5M1WTJrCxa6z7VpYJcrNuo0kUabFzqvnzxTKM6DGGarC/uV" +
        "HWXOniQdg8dPLOCeJblwwNT8/69HYW9u2xNRBaoOF0kfNbWiohvRNG3I2/hjOfuFDJoctqqsfUnyA7RBQMHPNtv65oUCoiPO" +
        "9veQFH4TlyUZrrET2xFSJnB6ahu/ZgOocbELisyjUHde17Wt+AAmTK8cPiABezZhT7drXDANTzqqsJmzwSLHTArnZyz4Q34y" +
        "25+WXDhGBdAkB5NUyjiLWmmpQmHITJdR6bv73Iz0i6xKEj5zs68POFqSN8zYAuyK6Ui6ZPewjPLu49ktVLn17BwuPrCP+q9u" +
        "7wHizcGYt722pDyLCkFgTOOU+Xw2kBJMlifcJjeSarX7mes8Sw1iUiaHzKNShFMmL97JvlySLc0ox83wXQ7tOFLVszEAoF0O" +
        "qB8HzlVo8XaCym2tzol65jeGBrqp0jdoRy0N7VQeDNYaPomyivTsVC9R6l1bw2EGiVeH+4T+5A0bjhLTfQ9ge29Zf5/eX/sJ" +
        "q2wYkFu7FJy+a5sRDjyxHGnNABO+QAGEWBO8aAC+QAX7KPsogzWBgAABgx0JA4qxVbqEWIACVwGVADS0IFcwhLGrz1AN6yeh" +
        "V+mLfCoR6sA7wny22ltRsjSFhV0Hi8ZuJ8WGL/81ErhRqrTlK++2AwhkZXQL/nUV4YgWVHzaXiSolE6Wr6s4N3mB7x0+H/7W" +
        "c1h7h5wSumZZw1stIHiOVLgQg1DFxhq5M2wklXRucHdYnklVzvBnPoBodfX1mgyz7LnVeUJWHqK4UtzX/BfWGCUjp/vdJiEP" +
        "md66EKhnMMx6D2njKd7itsUI+M6Db2AO4+MKCuBEEArCBgLswOaiV9vkmQl8GvYFeUaODINfV8zmDAercnbCV3zV6EFudy52" +
        "5LXwfKR4s3URJxxddDckf0QA2Fs3LD1Uy7tNOAHz9oZYFZrHje0sjZKdnNrNRwEA+RSBbEwuCe/Agt3xqeqzmMmt7z0K9Ia3" +
        "aWZmZEtkqE4y3UTvHQdiPO2W60nt2+QstNxGRim/dU7CPEN+5CcgMWieZl0eP1QpNM2DsJdvpi95T9+5w0bvP4TY4Zn3Y9of" +
        "5LcgjazqDnetd7E1FOR8coPoJH5lhZx4ml4wqK0ecxPAGAGEWBO7EADAGAX7KIH2UINQgYAAA4MWKQXO0cRGhFSAAlMBjgAH" +
        "60wk49a9aD/TW26AEFWEKJtifKCIKEACGJyUEXE1/sTldjj8nTjsS8N8fCMZBAHgnMIK5cFD3AeuYsWsjkP7hfjoc+Er6pcW" +
        "/pKVRrD4qXUwfs8360Kf6iKh/7DhCj35lbbABJRwVfFw8BlVZCBk5ZwPXfnM9WBwWwZKXkM/x8Ckc1qw/iokHSZbJD59PUSV" +
        "qV6vai3uec/Ac/GUaXngmVm7NJarUk16EE10EebTx94F2Y7Vzsy/98RvNILbffd5pqMYhAEBfdw1IpUVK5kYEYBQC4x9tvab" +
        "jRt994llV1RG9ex5RBioZ/+kW8rOiGF6fKEFZCHQC3DOPUWT3lSZALZ9DBIpgIGG73SGHezGmU6ceuFzolKFTxMORbmzSr/B" +
        "Xr4/TjXQ7OUC+hOaYcoOgJkaqOwg74+1h/zcBpr9bjVC6fWVuD7OGFCUqzYHifTIiDF3G6/dpaijWcRpdAurZCvayOGMl4K+" +
        "0jHcNmgM23aUoqB087zK+TJXuNmUakEGiiddoWJCLKSFlkDJRg39N3pYWgAAAP8S2UECACEBDAAAAI+YQZwBAAwTwXABgmEk" +
        "AYFzE7k4AIEEwXAF+yiC8XiDLIGAAAGDFAkDDXj4oYRYgAJXAYwAqtqzbQgtwU5wNmgWAu1J53oTXSMhM5lv4/2ekCFCmDC9" +
        "C4kI8cUgcwsJ9XPYP/A77b90hsEsoqboBIZJM36/9Grqg7aRi+UpVg+XTE5voEORszlGtBLoVh8euVrbz7gCSINSSG6truCr" +
        "6Z0yEI98BuDU2xlB/N9giKZx+SpKcGa6G4T2dD2CirulxodYX9s3w0ywQPGXwQJbHeS3vkdHU4OWaWrVGJKFxvHZGMZoYWOP" +
        "Yl3syGgL1jjLWbPw+jc7lOCa9ZLxD0p4FkkBdAn76iG7H718SGBJqew0HYg2paqOspjJAs0Jr56Qw1eU/wpoJ9NRGbu00dH6" +
        "fnlG9Jb5O0cc78WbIx0yIbbwvRmCLW1X26IqyG/c/jo8cRfZNqH7BhR8TaXy196gFI/gGPZQYfP8ZGMNzAluCrNF5S4gZLoS" +
        "sUrVr4ygwBZnscpEZu7rqryBFbTXw8RcTBK8xVQ9h6ZNvJ8vjw7NBtB4DFk1D0ltosmz6m+W2tSka1CO9TjxDu4RkvaNw6jm" +
        "vhcAE8NIAYRYE7dgAMNIBZJgg+0gDpJgAAADAc+Rn/ETkmAA",
    )

    /** `-S djw -W 16384` of the wordy game: each window's data compressed with xdelta3's own DJW. */
    private val djwWindows = patch(
        "1sPEAAUBgRh3b3JkeS5iaW4jMDEwNjRmNDAyM2M1ZTlkMDZhZTJlYWNkNDBmYjRhOGE4YjU5ODcxNmNkMjcwZGRjYTU3Yzgy" +
        "YTQ0ZTE1ODFiNi8vc291cmNlLmJpbiMwZWVlMTBhODBlMDc5OTg5NjI2ZTBmZDBjNjM0ZWE1Y2Y0ZWE5Y2E0OTQ1NmFkN2Qy" +
        "YTEwN2FkMGVjOTk3ZGUxLwX7KACDCIGAAAGCcAkDLSbUtIRYAGRiADDsHqVWq9U+g+oNhALiFrbhD/XNTzDLrPdEX7iKTjSc" +
        "stFMOuLoOP9Cr8tL8UimsNSvmIxBGeBCaSg8dRc5Ls/gstCL+9NcB9hB/rIG4RSAU5AA57A7nDN6005Flmno2lvg6X7tcD9K" +
        "nobTQ/Nct/cZmwqiGL9iFHoUxp+SLu80OQSGw5R3Kc5kG84ScQ/KsuqGvTkExzbZVUb7laAViKrTDbe3ucbiC1pROJlMqJEX" +
        "R0m/T9I20ekaIQXfuthm4RjKmPQGChnFBTj7GHQdrNiRUfUOmAT1Fsu5mwP2zF/OnCG5e4I3JvGKpTh4lP6A+QN9/+Pv8cLX" +
        "te/ZPxAv2Etdu52O9/nE5d3+a71MChU1GuXa4Ztk740X+g4nTv0cyKUeqdR/hgVHR9ArQvPPbc/Lrcz+2X5uw5ibJCkCJh9E" +
        "ZNvO+8HB3rIll6/rMYdcVu+Etn3DyaUXbMzh3pj/4O+5bJ+l8ZGNTfsFE75AAYRYE7xoAL5ABfso+yiDBoGAAAGCbgkDirFV" +
        "uoRYAGRiADDsc1GVxmdQjm3bXxL6KRBWTidvUfK+eI0/ChGuY8boWN8azyzuB7MoVKc3MfNJTXGjjocWcH3EPaof51030a06" +
        "GKidG+3NbI1MjTfRsBF5Q0rBHKMcbNON9ZEI0U06nDV9wHHdUDOTLFrfg26J2iAX7utOFa9Log7b5nwuuc6rwGrlVPJ4VG5I" +
        "UStEN7kRxjLwmNXDtf3QY3A8XIGM1gF0O1b+JPn4MGGFHAzf5zX1J8lUn1Tp+yQ4R6jK/xKVZdqQXUKEGp1VyA/SPqkuvyT4" +
        "TliwzYzk3WPaMS2+EM97PD46imLG8rUdTCO6CFmNPFO422zVeco18JOxN7htN60RZYZiuons5bSKWGHt442D5N08tf+F2jLM" +
        "J2vYz9by4h+vgx5eZq3z3VR5Jzc//p7irlrMnwBGbU1sIEjEZ9z3LeAckgBrY+ZRpAcv4eetEhcXHemors2m79Ofe2iimDOw" +
        "LaEucFSdABPAGAGEWBO7EADAGAX7KIH2UIMLgYAAAYJtDQXO0cRGhFQAJGYAELqvLc3MExg8rzV3sPQjH6eERc0bK2t52wb8" +
        "tUML4n/JRjyj33AkTavP/63/nVJPDFFb7+E+VyWM+r4RYAUJejWNhWMu0KEhYvL/QK7G+IVkG9xBLrbfkHfiZxBn3ODLtKdm" +
        "r6bORiYFOYUdh31flVV3HDzKQM1ZVgkKkdeQXLvFhq705HQLW8nZOgy2x/1e/f0fWn2aZteMTJNmrH7ovemQhV/4/wtT5UaM" +
        "NKPmOSvBE1K+P0lUC/21HC5YNmvT4F4DcnnHttKn1vb9dotlwGxz+dfhnrfvSD/Q/dki05HTE+Sk878PeU7WsXb8NNpG50PF" +
        "mNGmn1aaEUhoudu894+3ALd4SWoCQkP8Z4502/bzGp6js46yMLhADkMvtiXjXH3Pnmx93T2rcWitKLqL7L11oVowhXTm0wYY" +
        "glTBJB2R0ii7puQw0No7ZsmxJqHlLR7u3ZkU8iMmOFvDySw9ZX8zBOOpYq4TwXABgmEkAYFzE7k4AIEEwXAF+yiC8XiDCIGA" +
        "AAGCcAkDDXj4oYRYAGRiADDs2ysrSfkMbvIxty6i/kZE/wyK0rI0ozxeN6ZvmttTqTK10zmGlbwx1HDZVdbO0VefbPGxhJAl" +
        "tK41AQRfKucv+uDU3wK7yrF9aitrsuaQUE/3vZhLWelgfpKHz7KkpNUEs4z5xO910w+qdqMzTnp2vbAIj47M9+2ktJK5gZsR" +
        "L2D7cJb/OBEKdF1h2mSA3ojFpFzUFHBzu+BtG2XBf7O5NkGPMKY/C5mLzlfuSnu46Q9EJdAGMaDmPEn+7pewJ+oTiUEZ9+x2" +
        "TK/neZG6XLAlpxeVVgMhPYbHIP7vtmX0Rov2jpC8vyaDqbzTG9MLN2gmrIoLP8wqKYD1fHrfEjbhnyU4c0fX7ab+5oypdfLV" +
        "7bNzFxxRPDKh7zs7i8x0caLGwyEs2GHEZDXXdG15DqwdN5+xheR5Z1K44TUD23tcGDcMrnKjtFzdrS9YtfjHeYTrzNrDqgOQ" +
        "svtgL4X2uL+CzxwQ9Tzba+4RE8NIAYRYE7dgAMNIBZJgg+0gDpJgAAADAc+Rn/ETkmAA",
    )

    @Test
    fun xdelta3sOwnPatchesMakeExactlyWhatXdelta3Makes() {
        assertEquals(PatchFormat.XDELTA, RomPatches.formatOf(plain))
        for (p in listOf(plain, windows)) {
            val made = assertIs<PatchOutcome.Patched>(RomPatches.apply(source, p))
            assertContentEquals(target, made.bytes)
            assertTrue(made.checked, "every part carried xdelta3's checksum, and matched it")
            assertFalse(made.headerRemoved)
        }
        val loose = assertIs<PatchOutcome.Patched>(RomPatches.apply(source, unchecked))
        assertContentEquals(target, loose.bytes)
        assertFalse(loose.checked, "made with -n: nothing to check against")
    }

    @Test
    fun sectionsXdelta3CompressedWithItsLzmaAreReadThroughEveryWindow() {
        val single = assertIs<PatchOutcome.Patched>(RomPatches.apply(source, lzma))
        assertContentEquals(target, single.bytes)
        assertTrue(single.checked)
        val many = assertIs<PatchOutcome.Patched>(RomPatches.apply(source, lzmaWindows))
        assertContentEquals(wordy, many.bytes)
        assertTrue(many.checked)
        // A DJW patch whose parts weren't worth compressing has nothing Fuse can't read.
        assertContentEquals(target, assertIs<PatchOutcome.Patched>(RomPatches.apply(source, djw)).bytes)
        // For another version, xdelta3's check still says so.
        val other = source.copyOf().also { it[30_000] = (it[30_000] + 1).toByte() }
        assertIs<PatchOutcome.NotForThisGame>(RomPatches.apply(other, lzmaWindows))
    }

    @Test
    fun aPatchForAnotherVersionFailsItsOwnCheckAndNothingIsMade() {
        val other = source.copyOf().also { it[25_000] = (it[25_000] + 1).toByte() }
        val outcome = assertIs<PatchOutcome.NotForThisGame>(RomPatches.apply(other, plain))
        assertTrue("another version" in outcome.reason, outcome.reason)
        // A game shorter than the part the patch copies from is another version too.
        assertIs<PatchOutcome.NotForThisGame>(RomPatches.apply(source.copyOfRange(0, 40_000), plain))
    }

    @Test
    fun aCopierHeaderIsLeftOffWhenThePatchIsForTheHeaderlessGame() {
        val withHeader = ByteArray(512) + source
        val made = assertIs<PatchOutcome.Patched>(RomPatches.apply(withHeader, plain))
        assertTrue(made.headerRemoved)
        assertContentEquals(target, made.bytes)
    }

    @Test
    fun whatFuseCantReadIsSaidAndNeverGuessedAt() {
        val no = assertIs<PatchOutcome.Unreadable>(RomPatches.apply(source, djwWindows))
        assertTrue("DJW" in no.reason && "-S lzma" in no.reason, no.reason)
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(source, plain.copyOfRange(0, plain.size - 20)))
        val damaged = plain.copyOf().also { it[it.size - 30] = (it[it.size - 30] + 1).toByte() }
        assertFalse(RomPatches.apply(source, damaged) is PatchOutcome.Patched)
        // A compressed part cut short or changed is never taken for a game.
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(source, lzmaWindows.copyOfRange(0, lzmaWindows.size - 40)))
        for (at in listOf(400, 900, 1_500)) {
            val changed = lzmaWindows.copyOf().also { it[at] = (it[at].toInt() xor 0x5A).toByte() }
            assertFalse(RomPatches.apply(source, changed) is PatchOutcome.Patched, "changed at $at")
        }
    }

    @Test
    fun theCodeTableIsRfc3284s() {
        // Entries named in RFC 3284 section 5.6: RUN, an ADD of 1, the first COPY, the double instructions.
        assertContentEquals(intArrayOf(2, 0, 0, 0), Vcdiff.entry(0))
        assertContentEquals(intArrayOf(1, 1, 0, 0), Vcdiff.entry(2))
        assertContentEquals(intArrayOf(3, 0, 0, 0), Vcdiff.entry(19))
        assertContentEquals(intArrayOf(3, 18, 0, 0), Vcdiff.entry(34))
        assertContentEquals(intArrayOf(1, 1, 3, 4), Vcdiff.entry(163))
        assertContentEquals(intArrayOf(1, 4, 11, 4), Vcdiff.entry(246))
        assertContentEquals(intArrayOf(3, 4, 1, 1), Vcdiff.entry(247))
        assertContentEquals(intArrayOf(11, 4, 1, 1), Vcdiff.entry(255))
        assertEquals(0x11E60398L, Vcdiff.adler32("Wikipedia".encodeToByteArray(), 0, 9), "RFC 1950's sum, as worked through for \"Wikipedia\"")
    }
}
