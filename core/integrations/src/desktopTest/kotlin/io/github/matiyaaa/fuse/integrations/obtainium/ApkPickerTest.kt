package io.github.matiyaaa.fuse.integrations.obtainium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApkPickerTest {
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private fun links(vararg names: String) = names.map { ApkLink(it, "https://example.org/$it") }
    private fun chosen(c: ApkChoice) = assertIs<ApkChoice.Install>(c).apk.name

    @Test
    fun theDevicesBuildIsChosen() {
        val files = links("app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk", "app-x86_64-release.apk")
        assertEquals("app-arm64-v8a-release.apk", chosen(ApkPicker.choose(files, PackRules(filterByArch = true), 0, arm64)))
    }

    @Test
    fun abiAliasesCount() {
        val files = links("Emu-aarch64.apk", "Emu-armv7.apk")
        assertEquals("Emu-aarch64.apk", chosen(ApkPicker.choose(files, PackRules(filterByArch = true), 0, arm64)))
    }

    @Test
    fun thePacksFilterAndItsInverse() {
        val files = links("azahar-vanilla.apk", "azahar-googleplay.apk")
        assertEquals("azahar-vanilla.apk", chosen(ApkPicker.choose(files, PackRules(apkFilter = "vanilla"), 0, arm64)))
        assertEquals("azahar-googleplay.apk", chosen(ApkPicker.choose(files, PackRules(apkFilter = "vanilla", invertApkFilter = true), 0, arm64)))
    }

    @Test
    fun universalWinsAmongEquals() {
        val files = links("app-legacy.apk", "app-universal.apk")
        assertEquals("app-universal.apk", chosen(ApkPicker.choose(files, PackRules(), 0, arm64)))
    }

    @Test
    fun thePreferredIndexDecidesTheRest() {
        val files = links("a.apk", "b.apk", "c.apk")
        assertEquals("b.apk", chosen(ApkPicker.choose(files, PackRules(), 1, arm64)))
        assertEquals("a.apk", chosen(ApkPicker.choose(files, PackRules(), 9, arm64)))
    }

    @Test
    fun zipsAndBundlesAreManual() {
        assertIs<ApkChoice.Manual>(ApkPicker.choose(links("game.zip"), PackRules(includeZips = true), 0, arm64))
        assertIs<ApkChoice.Manual>(ApkPicker.choose(links("game.apks"), PackRules(), 0, arm64))
        assertIs<ApkChoice.Manual>(ApkPicker.choose(emptyList(), PackRules(), 0, arm64))
    }

    @Test
    fun aFilterThatCantBeReadMatchesNothing() {
        assertIs<ApkChoice.Manual>(ApkPicker.choose(links("a.apk"), PackRules(apkFilter = "(broken"), 0, arm64))
    }
}
