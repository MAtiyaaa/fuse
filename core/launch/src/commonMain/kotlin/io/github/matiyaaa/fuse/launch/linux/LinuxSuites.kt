package io.github.matiyaaa.fuse.launch.linux

/**
 * Emulation setups that install and update emulators of their own, so Fuse can say whose an
 * install is and leave its updates to them. Everything here is from their own documentation:
 *
 * - EmuDeck ("File Management on SteamOS", emudeck.github.io): its own files in `~/.config/EmuDeck/`,
 *   and the emulators it installs as AppImages and program folders in `~/Applications`
 *   (`Cemu.AppImage`, `pcsx2-Qt.AppImage`, `Vita3K/Vita3K` and so on). Its Flatpaks are Flathub's,
 *   updated by Flatpak like any other.
 * - RetroDECK (retrodeck.readthedocs.io): one Flatpak, `net.retrodeck.retrodeck`, with its emulators
 *   inside it ("What is Included in RetroDECK?"), updated with it.
 *
 * Pure: it reads only through a [LinuxEnvironment].
 */
object LinuxSuites {
    const val EMUDECK = "EmuDeck"
    const val RETRODECK = "RetroDECK"

    /** RetroDECK's Flatpak. */
    const val RETRODECK_ID = "net.retrodeck.retrodeck"

    /**
     * The emulators RetroDECK's wiki lists as included, by Fuse's emulator ids (its Azahar, Cemu,
     * Dolphin, melonDS, PCSX2, PrimeHack, PPSSPP, RPCS3, Ruffle, Vita3K, xemu and XRoar; DuckStation
     * as its legacy one; RetroArch and MAME).
     */
    val RETRODECK_EMULATORS: Set<String> = setOf(
        "azahar", "cemu", "dolphin", "melonds", "pcsx2", "primehack", "ppsspp", "rpcs3", "ruffle", "vita3k", "xemu", "xroar",
        "duckstation", "retroarch", "mame",
    )

    /** True when EmuDeck has set this computer up (its own folder is there). */
    fun emuDeckHere(env: LinuxEnvironment): Boolean = env.exists("${env.homeDir.trimEnd('/')}/.config/EmuDeck")

    /** True when RetroDECK's Flatpak is among [flatpaks]. */
    fun retroDeckHere(flatpaks: Set<String>): Boolean = RETRODECK_ID in flatpaks

    /**
     * Who installed what [found] found, when it is one of these setups' own: EmuDeck for an AppImage
     * or program folder in `~/Applications` on a computer EmuDeck set up. Null for everything else
     * (a Flatpak is Flatpak's, whoever asked for it).
     */
    fun ownerOf(env: LinuxEnvironment, found: LinuxDetector.Found, emuDeck: Boolean = emuDeckHere(env)): String? {
        if (!emuDeck) return null
        if (found.kind != LinuxInstallKind.APPIMAGE && found.kind != LinuxInstallKind.FOLDER) return null
        val applications = "${env.homeDir.trimEnd('/')}/Applications/"
        return EMUDECK.takeIf { found.appId.startsWith(applications) }
    }

    /** The setups found on this computer, by name. */
    fun found(env: LinuxEnvironment, flatpaks: Set<String> = env.flatpakApps()): Set<String> = buildSet {
        if (emuDeckHere(env)) add(EMUDECK)
        if (retroDeckHere(flatpaks)) add(RETRODECK)
    }
}
