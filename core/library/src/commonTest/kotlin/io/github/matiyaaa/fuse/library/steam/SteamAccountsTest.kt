package io.github.matiyaaa.fuse.library.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SteamAccountsTest {
    @Test
    fun retainsMultipleRememberedIdentitiesWithoutAuthenticationClaims() {
        val accounts = SteamAccounts.parse("""
            "users" {
                "76561198000000001" { "PersonaName" "Alice" "MostRecent" "1" "Timestamp" "1700000000" }
                "76561198000000002" { "PersonaName" "Bob" "MostRecent" "0" }
                "invalid" { "PersonaName" "Ignored" }
            }
        """.trimIndent(), "/steam")
        assertEquals(listOf("Alice", "Bob"), accounts.map { it.personaName })
        assertEquals(1700000000L, accounts.first().lastSeenSeconds)
        assertFalse(accounts.last().mostRecent)
    }

    @Test
    fun manifestLastOwnerIsInstallationProvenance() {
        val game = SteamLibraryReader.manifest("""
            "AppState" { "appid" "620" "name" "Portal 2" "StateFlags" "4"
              "installdir" "Portal 2" "LastOwner" "76561198000000001" }
        """.trimIndent(), "/steam")
        assertEquals(setOf("76561198000000001"), game?.lastInstalledBy)
    }
}
