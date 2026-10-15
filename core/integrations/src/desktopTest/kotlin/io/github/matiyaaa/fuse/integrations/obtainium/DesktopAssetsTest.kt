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
    fun linuxPrefersAppImages() {
        val pcsx2 = assets("pcsx2-v2.2.0-linux-appimage-x64-Qt.AppImage", "pcsx2-v2.2.0-linux-flatpak-x64-Qt.flatpak", "pcsx2-v2.2.0-windows-x64-Qt.7z", "pcsx2-v2.2.0-macos-Qt.tar.xz")
        assertEquals("pcsx2-v2.2.0-linux-appimage-x64-Qt.AppImage", DesktopAssets.pick(pcsx2, Host.LINUX, "x86_64")?.name)
        // A 7z and a tar.xz aren't fetched: Fuse only takes what it can use as published.
        assertNull(DesktopAssets.pick(pcsx2, Host.WINDOWS, "x86_64"))
        assertNull(DesktopAssets.pick(pcsx2, Host.MACOS, "x86_64"))
        // Distribution packages are never taken; a tarball of the program is, when there is nothing better.
        assertNull(DesktopAssets.pick(assets("emu_1.0_amd64.deb", "emu-1.0.x86_64.rpm"), Host.LINUX, "x86_64"))
        assertEquals("emu-linux.tar.gz", DesktopAssets.pick(assets("emu_1.0_amd64.deb", "emu-1.0.x86_64.rpm", "emu-linux.tar.gz"), Host.LINUX, "x86_64")?.name)
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

    /** The Linux files the Store's emulators really publish (checked October 2026). */
    @Test
    fun linuxTakesTheBuildEachEmulatorPublishes() {
        // Eden's v0.2.1, as its Forgejo lists it: each build's zsync beside it, the version in the name.
        val eden = assets(*listOf("aarch64", "amd64", "legacy", "rog-ally", "steamdeck").flatMap { b ->
            listOf("clang-pgo", "gcc-standard").flatMap { c -> listOf("Eden-Linux-$b-$c.AppImage.zsync", "Eden-Linux-v0.2.1-$b-$c.AppImage") }
        }.toTypedArray(), "Eden-Windows-v0.2.1-amd64-clang-pgo.zip", "Eden-Windows-v0.2.1-rog-ally-clang-pgo.zip", "Eden-macOS-v0.2.1.dmg")
        fun edenFor(build: String) = Regex("^Eden-Linux-(v[^-]+-)?$build-clang-pgo\\.AppImage$")
        assertEquals("Eden-Linux-v0.2.1-amd64-clang-pgo.AppImage", DesktopAssets.pick(eden, Host.LINUX, "x86_64", edenFor("amd64"))?.name)
        assertEquals("Eden-Linux-v0.2.1-steamdeck-clang-pgo.AppImage", DesktopAssets.pick(eden, Host.LINUX, "x86_64", edenFor("steamdeck"))?.name)
        assertEquals("Eden-Linux-v0.2.1-aarch64-clang-pgo.AppImage", DesktopAssets.pick(eden, Host.LINUX, "arm64", edenFor("aarch64"))?.name)
        assertEquals("Eden-macOS-v0.2.1.dmg", DesktopAssets.pick(eden, Host.MACOS, "arm64")?.name)

        val ppsspp = assets("PPSSPP-v1.20.4-anylinux-aarch64.AppImage", "PPSSPP-v1.20.4-anylinux-aarch64.AppImage.zsync",
            "PPSSPP-v1.20.4-anylinux-x86_64.AppImage", "PPSSPP-v1.20.4-anylinux-x86_64.AppImage.zsync", "PPSSPP-v1.20.4-Windows-x64.zip", "ppsspp-1.20.4.tar.xz")
        assertEquals("PPSSPP-v1.20.4-anylinux-x86_64.AppImage", DesktopAssets.pick(ppsspp, Host.LINUX, "x86_64")?.name)

        val xenia = assets("xenia_canary_linux.tar.xz", "xenia_canary_windows.zip")
        assertEquals("xenia_canary_linux.tar.xz", DesktopAssets.pick(xenia, Host.LINUX, "x86_64")?.name)
        assertEquals(DesktopAssetKind.TAR, DesktopAssets.kindOf("xenia_canary_linux.tar.xz", Host.LINUX))
        assertEquals("xenia_canary_windows.zip", DesktopAssets.pick(xenia, Host.WINDOWS, "x86_64")?.name)

        val ruffle = assets("ruffle-0.7.1-linux-aarch64.tar.gz", "ruffle-0.7.1-linux-i686.tar.gz", "ruffle-0.7.1-linux-x86_64.tar.gz",
            "ruffle-0.7.1-macos-universal.tar.gz", "ruffle-0.7.1-reproducible-source.zip", "ruffle-0.7.1-windows-x86_64.zip")
        assertEquals("ruffle-0.7.1-linux-x86_64.tar.gz", DesktopAssets.pick(ruffle, Host.LINUX, "x86_64")?.name)

        val mandarine = assets("mandarine-linux-appimage-20260415-3f023d0.tar.gz", "mandarine-unified-source-20260415-3f023d0.tar.xz", "mandarine-windows-msvc-20260415-3f023d0.zip")
        assertEquals("mandarine-linux-appimage-20260415-3f023d0.tar.gz", DesktopAssets.pick(mandarine, Host.LINUX, "x86_64")?.name)

        val panda = assets("android-arm64.zip", "Linux-Qt.zip", "Linux-SDL.zip", "Windows-Qt.zip")
        assertEquals("Linux-Qt.zip", DesktopAssets.pick(panda, Host.LINUX, "x86_64", Regex("Linux-Qt"))?.name)

        val dosbox = assets("dosbox-staging-linux-x86_64-v0.83.0.tar.xz", "dosbox-staging-macOS-v0.83.0.dmg", "dosbox-staging-windows-x64-v0.83.0.zip")
        assertEquals("dosbox-staging-linux-x86_64-v0.83.0.tar.xz", DesktopAssets.pick(dosbox, Host.LINUX, "x86_64")?.name)

        assertEquals(DesktopAssetKind.SEVEN_ZIP, DesktopAssets.kindOf("RetroArch.7z", Host.LINUX))
    }

    /** An AppImage still wins over a tarball of the same build. */
    @Test
    fun anAppImageBeatsAnArchive() {
        val both = assets("emu-linux-x86_64.tar.gz", "emu-x86_64.AppImage")
        assertEquals("emu-x86_64.AppImage", DesktopAssets.pick(both, Host.LINUX, "x86_64")?.name)
    }
}
