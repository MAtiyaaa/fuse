package io.github.matiyaaa.fuse.ui.shell.reach

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.ui.designsystem.icons.DeviceIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons

/** What kind of device one of the household's is, as its picture shows it. */
enum class DeviceKind(val words: String) {
    /** A handheld with a screen in each half (an AYN Thor, a 3DS). */
    DUAL_SCREEN("Two-screen handheld"),
    /** A handheld with its screen between its controls (a Steam Deck, an Ally, a Retroid). */
    HANDHELD("Handheld"),
    /** A computer under a television (an HTPC, a Shield, a living room PC). */
    TV("TV"),
    /** A server, a NAS, the computer that keeps the household's things. */
    SERVER("Server"),
    LAPTOP("Laptop"),
    DESKTOP("Computer"),
    PHONE("Phone"),
}

/**
 * The kind of device [name] on [platform] is: told by the names devices come with and people give
 * them ("AYN Thor", "steamdeck", "Living Room HTPC", "Media Server", "MacBook Air"), else by its
 * system (Android is most often a handheld, a computer a computer). Nothing here is asked of the
 * device itself, so it works for every device the household has ever had, old Fuse or new.
 */
fun deviceKind(name: String, platform: String): DeviceKind {
    val n = " " + name.lowercase().replace('-', ' ').replace('_', ' ') + " "
    fun has(vararg words: String) = words.any { it in n }
    val android = platform.equals("ANDROID", ignoreCase = true)
    return when {
        has("thor", "3ds", " ds ", "dual screen", "dualscreen", "duo ", " flip ds") -> DeviceKind.DUAL_SCREEN
        has(
            "deck", "ally", "legion go", "legiongo", "claw", "odin", "retroid", "pocket", "anbernic", "ayaneo",
            "gpd", "onexplayer", "onexfly", "miyoo", "trimui", "powkiddy", " rg3", " rg4", " rg5", "rgb30", "handheld",
            " switch", "zone ", " rp5", " rp4", " rp6", " ayn ",
        ) -> DeviceKind.HANDHELD
        has("htpc", " tv", "tv ", "shield", "living room", "livingroom", "lounge", "theater", "theatre", "media pc", "mediapc", "couch", "family room", " den ") -> DeviceKind.TV
        has("server", " nas", "nas ", "unraid", "truenas", "homelab", "home lab") -> DeviceKind.SERVER
        has("laptop", "macbook", "notebook", "thinkpad", "zenbook", "xps", "surface laptop", "chromebook", " book") -> DeviceKind.LAPTOP
        android && has("phone", "pixel", "galaxy s", "galaxy z", "oneplus", "xiaomi", "redmi", "moto", "nothing") -> DeviceKind.PHONE
        android -> DeviceKind.HANDHELD
        else -> DeviceKind.DESKTOP
    }
}

/** The picture for a kind of device. */
val DeviceKind.icon: ImageVector get() = when (this) {
    DeviceKind.DUAL_SCREEN -> DeviceIcons.DualScreenHandheld
    DeviceKind.HANDHELD -> DeviceIcons.Handheld
    DeviceKind.TV -> FuseIcons.Tv
    DeviceKind.SERVER -> FuseIcons.Server
    DeviceKind.LAPTOP -> FuseIcons.Laptop
    DeviceKind.DESKTOP -> FuseIcons.Monitor
    DeviceKind.PHONE -> FuseIcons.Smartphone
}

/** The picture for one of the household's devices: [name] on [platform]. */
fun deviceIcon(platform: String, name: String): ImageVector = deviceKind(name, platform).icon
