package io.github.matiyaaa.fuse.desktop.system

/**
 * SteamOS's Game Mode, or any gamescope session: Steam runs one app on screen at a time and gives
 * the screen to whichever window belongs to it. Fuse goes full screen there, and starts Cartridge
 * as its own child (rather than through the desktop's link handler) so gamescope counts Cartridge's
 * window as part of Fuse and shows it, then hands the screen back when it closes.
 */
object GameMode {
    val active: Boolean by lazy { detect(System.getenv()) }

    /** True in a gamescope session: its Wayland display is set, or Steam says it is in Game Mode. */
    fun detect(env: Map<String, String>): Boolean =
        !env["GAMESCOPE_WAYLAND_DISPLAY"].isNullOrBlank() ||
            env["XDG_CURRENT_DESKTOP"].equals("gamescope", ignoreCase = true) ||
            env["XDG_SESSION_DESKTOP"].equals("gamescope", ignoreCase = true) ||
            env["SteamGamepadUI"] == "1"
}
