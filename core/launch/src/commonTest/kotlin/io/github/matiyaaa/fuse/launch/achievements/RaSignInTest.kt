package io.github.matiyaaa.fuse.launch.achievements

import io.github.matiyaaa.fuse.launch.online.OnlineValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each emulator's RetroAchievements sign-in, as its own source keeps it. */
class RaSignInTest {
    private fun keys(values: List<OnlineValue>) = values.map { listOfNotNull(it.file, it.section, it.key, it.value).joinToString("|") }

    @Test
    fun eachEmulatorGetsItsOwnKeysAndNeverAPassword() {
        assertEquals(
            listOf("|cheevos_enable|true", "|cheevos_username|Ana", "|cheevos_token|AbCdEf0123456789"),
            keys(RaSignIn.values("retroarch", "Ana", "AbCdEf0123456789", 1_760_000_000)),
        )
        assertEquals(
            listOf("Achievements|Enabled|true", "Achievements|Username|Ana", "Achievements|Token|AbCdEf0123456789", "Achievements|LoginTimestamp|1760000000"),
            keys(RaSignIn.values("pcsx2", "Ana", "AbCdEf0123456789", 1_760_000_000)),
        )
        assertEquals(
            listOf("Cheevos|Enabled|true", "Cheevos|Username|Ana", "Cheevos|Token|ENC", "Cheevos|LoginTimestamp|1760000000"),
            keys(RaSignIn.values("duckstation", "Ana", "AbCdEf0123456789", 1_760_000_000, duckStationToken = "ENC")),
        )
        // PPSSPP keeps the token in a file of its own, never in its settings.
        assertEquals(
            listOf("Achievements|AchievementsEnable|True", "Achievements|AchievementsUserName|Ana"),
            keys(RaSignIn.values("ppsspp", "Ana", "AbCdEf0123456789", 1_760_000_000)),
        )
        assertEquals(
            listOf("RetroAchievements|Achievements|Enabled|True", "RetroAchievements|Achievements|Username|Ana", "RetroAchievements|Achievements|ApiToken|AbCdEf0123456789"),
            keys(RaSignIn.values("dolphin", "Ana", "AbCdEf0123456789", 1_760_000_000)),
        )
        for (family in RaSignIn.families) {
            assertTrue(RaSignIn.values(family, "Ana", "AbCdEf0123456789", 0, "ENC").none { it.key.contains("password", ignoreCase = true) }, family)
        }
        // Without the encrypted token DuckStation gets nothing, and an emulator Fuse doesn't sign in gets nothing either.
        assertEquals(emptyList(), RaSignIn.values("duckstation", "Ana", "AbCdEf0123456789", 0))
        assertEquals(emptyList(), RaSignIn.values("cemu", "Ana", "AbCdEf0123456789", 0))
    }

    @Test
    fun whoIsSignedInIsReadFromTheUserNameKey() {
        val files = mapOf("cheevos_username" to "Mo", "Username" to "Ana", "AchievementsUserName" to " ")
        assertEquals("Mo", RaSignIn.signedInAs("retroarch") { files[it.key] })
        assertEquals("Ana", RaSignIn.signedInAs("dolphin") { files[it.key] })
        assertNull(RaSignIn.signedInAs("ppsspp") { files[it.key] })
    }

    /**
     * DuckStation's encrypted token, against vectors made apart from Fuse: SHA-256 in Python's
     * hashlib (the machine key, then the user name, then 100 rounds) and AES-128-CBC by the
     * `openssl enc -aes-128-cbc -nopad` command, over the token padded with zeros.
     */
    @Test
    fun duckStationsTokenIsEncryptedExactlyAsItsOwnCodeDoes() {
        // Linux: /etc/machine-id with its line break.
        assertEquals("K/ha1WgM9TwJ/Qh9w8CgBQ==", RaSignIn.duckStationToken("AbCdEf0123456789", "Ana", "4c4c4544004a3510804b80c04f4d3432\n"))
        // Portable: no machine key at all.
        assertEquals("a/fG2d+CPUaPJxJZlmrkHw==", RaSignIn.duckStationToken("AbCdEf0123456789", "Ana", null))
        // Windows: MachineGuid with its closing NUL; a token longer than one block.
        val windows = "8f2a6c1e-5b3d-4e7a-9c0b-1d2e3f405162\u0000"
        assertEquals("tlo0IKrdSLvIs6rKqK0Jzn1SpxO4Qj1Y0OMQZ4p6h24=", RaSignIn.duckStationToken("ZyXwVuTsRqPoNmLk1234", "Mo", windows))
        // And read back as DuckStation reads it; another user name or computer reads nothing.
        assertEquals("ZyXwVuTsRqPoNmLk1234", RaSignIn.duckStationTokenOf("tlo0IKrdSLvIs6rKqK0Jzn1SpxO4Qj1Y0OMQZ4p6h24=", "Mo", windows))
        assertNull(RaSignIn.duckStationTokenOf("tlo0IKrdSLvIs6rKqK0Jzn1SpxO4Qj1Y0OMQZ4p6h24=", "Ana", windows))
        assertNull(RaSignIn.duckStationTokenOf("K/ha1WgM9TwJ/Qh9w8CgBQ==", "Ana", null))
    }

    @Test
    fun aTokenIsCheckedBeforeItGoesIntoAFile() {
        assertTrue(RaSignIn.validToken("AbCdEf0123456789"))
        assertTrue(!RaSignIn.validToken("has \"quote\""))
        assertTrue(!RaSignIn.validToken("short"))
        // A fingerprint stands for a token in Fuse's records, never the token itself.
        val print = RaSignIn.fingerprint("AbCdEf0123456789")
        assertEquals(64, print.length)
        assertTrue("AbCdEf0123456789" !in print)
    }
}
