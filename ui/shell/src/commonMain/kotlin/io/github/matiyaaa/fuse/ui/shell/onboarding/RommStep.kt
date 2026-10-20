package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.runtime.Composable
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.home.CARTRIDGE_TINT

/**
 * The stage for where else games come from, told as one card with the other ways under it as small
 * pills. With Fuse One here, Fuse One leads (recommended: its library is every game on the
 * household's devices); without it, RomM leads. After an answer the chosen way leads. Each card says
 * in three short lines what that choice means, so the buttons below need no more explaining. The
 * cards once fanned out behind each other and cut each other's words off; now nothing overlaps.
 */
@Composable
internal fun RommChoiceStage(
    chosen: String,
    connected: Boolean,
    cartridgeReady: Boolean,
    hasRomm: Boolean,
    hasCartridge: Boolean,
    hasFuseOne: Boolean = false,
    fuseOneOn: Boolean = false,
) {
    val c = Fuse.colors
    val cartridgeTint = CARTRIDGE_TINT.toColor()
    val kinds = listOfNotNull(
        "FUSEONE".takeIf { hasFuseOne },
        "FUSE".takeIf { hasRomm },
        "CARTRIDGE".takeIf { hasCartridge },
        "NONE".takeIf { !hasFuseOne || chosen == "NONE" },
    )
    val ways = kinds.mapIndexed { key, kind ->
        when (kind) {
            "CARTRIDGE" -> Way(
                key, "Cartridge", if (chosen == "CARTRIDGE") if (cartridgeReady) "Ready" else "Chosen" else "Companion app",
                cartridgeTint, FuseMarks.Cartridge,
                listOf(FuseIcons.Download to "Downloads for Fuse", FuseIcons.Server to "Your RomM server", FuseIcons.Link2 to "A separate app"),
                on = chosen == "CARTRIDGE" && cartridgeReady, filled = false,
            )
            "FUSEONE" -> Way(
                key, "Fuse One", when { fuseOneOn -> "On"; chosen == "FUSEONE" -> "Chosen"; else -> "Recommended" },
                c.accent, FuseMarks.FuseOne,
                listOf(FuseIcons.Library to "All your devices", FuseIcons.Download to "Fetch to play", FuseIcons.Server to "No server"),
                on = fuseOneOn, filled = true,
            )
            "FUSE" -> Way(
                key, "RomM", when { connected -> "Connected"; chosen == "FUSE" -> "Chosen"; hasFuseOne -> "Alongside, too"; else -> "Recommended" },
                c.accent, FuseIcons.LibraryBig,
                listOf(FuseIcons.Server to "Straight to RomM", FuseIcons.Download to "Downloads in Fuse", FuseIcons.Save to "Its save sync, if chosen"),
                on = connected, filled = !hasFuseOne,
            )
            else -> Way(
                key, "Neither", if (chosen == "NONE") "Chosen" else "This device only",
                c.textMuted, FuseIcons.HardDrive,
                listOf(FuseIcons.FolderOpen to "Your own folders", FuseIcons.Gamepad to "Plays as it is", FuseIcons.Clock to "RomM later, if ever"),
                on = chosen == "NONE", filled = false,
            )
        }
    }
    LeadStage(
        ways, kinds.indexOf(chosen).takeIf { it >= 0 } ?: 0,
        when (chosen) {
            "FUSEONE" -> if (fuseOneOn) "Fuse Library is included" else "Fuse One, with a host at home"
            "FUSE" -> if (connected) "RomM is connected" else "RomM, built in"
            "CARTRIDGE" -> if (cartridgeReady) "Cartridge is ready" else "Cartridge, alongside Fuse"
            "NONE" -> "Just this device"
            else -> if (hasFuseOne) "Fuse One is recommended" else "Three ways to bring in your games"
        },
        label = "romm choice stage",
    )
}
