package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Desktop files are picked per system and processor, from the names projects really publish. */
class DesktopAssetsTest {
    private fun assets(vararg names: String) = names.map { ReleaseAsset(it, "https://github.com/x/y/releases/download/v1/$it") }

    private val duckstation = assets(
        "DuckStation-x64.AppImage", "DuckStation-arm64.AppImage", "DuckStation-x64-SSE2.AppImage",
        "duckstation-windows-x64-release.zip", "duckstation-windows-x64-release-symbols.zip", "duckstation-windows-arm64-release.zip",
        "duckstation-mac-release.zip", "duckstation-linux-x64-release.flatpak",
    )

    @Test
    fun eachSystemGetsItsOwnBuild() {
        assertEquals("DuckStation-x64.AppImage", DesktopAssets.pick(duckstation, Host.LINUX, "x86_64")?.name)
        assertEquals("DuckStation-arm64.AppImage", DesktopAssets.pick(duckstation, Host.LINUX, "arm64")?.name)
        assertEquals("duckstation-windows-x64-release.zip", DesktopAssets.pick(duckstation, Host.WINDOWS, "x86_64")?.name)
        assertEquals("duckstation-windows-arm64-release.zip", DesktopAssets.pick(duckstation, Host.WINDOWS, "arm64")?.name)
        assertEquals("duckstation-mac-release.zip", DesktopAssets.pick(duckstation, Host.MACOS, "arm64")?.name)
    }

    @Test
    fun linuxTakesOnlyAppImages() {
        val pcsx2 = assets("pcsx2-v2.2.0-linux-appimage-x64-Qt.AppImage", "pcsx2-v2.2.0-linux-flatpak-x64-Qt.flatpak", "pcsx2-v2.2.0-windows-x64-Qt.7z", "pcsx2-v2.2.0-macos-Qt.tar.xz")
        assertEquals("pcsx2-v2.2.0-linux-appimage-x64-Qt.AppImage", DesktopAssets.pick(pcsx2, Host.LINUX, "x86_64")?.name)
        // A 7z and a tar.xz aren't fetched: Fuse only takes what it can use as published.
        assertNull(DesktopAssets.pick(pcsx2, Host.WINDOWS, "x86_64"))
        assertNull(DesktopAssets.pick(pcsx2, Host.MACOS, "x86_64"))
        assertNull(DesktopAssets.pick(assets("emu_1.0_amd64.deb", "emu-1.0.x86_64.rpm", "emu-linux.tar.gz"), Host.LINUX, "x86_64"))
    }

    @Test
    fun aZippedAppImageIsTakenButInstallersAndOtherSystemsAreNot() {
        val melon = assets("melonDS-1.0-appimage-x86_64.zip", "melonDS-1.0-windows-x86_64.zip", "melonDS-1.0-macOS-universal.zip")
        assertEquals("melonDS-1.0-appimage-x86_64.zip", DesktopAssets.pick(melon, Host.LINUX, "x86_64")?.name)
        assertEquals("melonDS-1.0-windows-x86_64.zip", DesktopAssets.pick(melon, Host.WINDOWS, "x86_64")?.name)
        assertEquals("melonDS-1.0-macOS-universal.zip", DesktopAssets.pick(melon, Host.MACOS, "x86_64")?.name)
        assertNull(DesktopAssets.pick(assets("Emu-Setup-x64.zip", "emu-darwin.zip"), Host.WINDOWS, "x86_64"))
        val cemu = assets("Cemu-2.6-x86_64.AppImage", "cemu-2.6-windows-x64.zip", "cemu-2.6-macos-12-x64.dmg")
        assertEquals("cemu-2.6-macos-12-x64.dmg", DesktopAssets.pick(cemu, Host.MACOS, "x86_64")?.name)
        assertEquals(DesktopAssetKind.DMG, DesktopAssets.kindOf("cemu-2.6-macos-12-x64.dmg", Host.MACOS))
    }
}
