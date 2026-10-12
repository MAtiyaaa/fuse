package io.github.matiyaaa.fuse.data.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionsTest {
    @Test
    fun theDeckyPluginStartsWithoutEmulatorsAndPhoneLinkWithWhatItHad() {
        val none = ExtensionSettings()
        assertFalse(Extensions.allows(none, Extensions.DECKY, ExtensionScope.EMULATORS))
        assertTrue(Extensions.allows(none, Extensions.DECKY, ExtensionScope.CHANGE_LIBRARY))
        assertEquals(Extensions.PHONE_LINK.scopes.toSet(), Extensions.granted(none, Extensions.PHONE_LINK))
        // Never more than an extension can ask for: the plugin can't be given typing, nor Phone Link emulators.
        assertFalse(Extensions.allows(none, Extensions.DECKY, ExtensionScope.CONTROL))
        val forced = ExtensionSettings(mapOf("phone-link" to setOf(ExtensionScope.EMULATORS, ExtensionScope.LIBRARY)))
        assertEquals(setOf(ExtensionScope.LIBRARY), Extensions.granted(forced, Extensions.PHONE_LINK))
        assertEquals(forced, Extensions.set(forced, Extensions.PHONE_LINK, ExtensionScope.PLAY, true))
    }

    @Test
    fun aChoiceIsKeptUntilItMatchesTheDefaultsAgain() {
        val allowed = Extensions.set(ExtensionSettings(), Extensions.DECKY, ExtensionScope.EMULATORS, true)
        assertTrue(Extensions.allows(allowed, Extensions.DECKY, ExtensionScope.EMULATORS))
        assertEquals(setOf("decky"), allowed.granted.keys)
        // It survives saving, as the device's settings do.
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(allowed, json.decodeFromString(ExtensionSettings.serializer(), json.encodeToString(ExtensionSettings.serializer(), allowed)))
        // Back to the defaults: nothing is kept for it.
        assertEquals(ExtensionSettings(), Extensions.set(allowed, Extensions.DECKY, ExtensionScope.EMULATORS, false))
        // Taking away is kept too, and other extensions don't change.
        val noStatus = Extensions.set(ExtensionSettings(), Extensions.PHONE_LINK, ExtensionScope.STATUS, false)
        assertFalse(Extensions.allows(noStatus, Extensions.PHONE_LINK, ExtensionScope.STATUS))
        assertTrue(Extensions.allows(noStatus, Extensions.DECKY, ExtensionScope.STATUS))
        assertTrue("Settings, Accounts, Phone Link" in Extensions.refusal(Extensions.PHONE_LINK, ExtensionScope.STATUS))
    }
}
