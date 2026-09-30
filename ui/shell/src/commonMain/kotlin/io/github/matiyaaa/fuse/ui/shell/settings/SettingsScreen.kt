package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone

/** One settings section: an id used in routes, a label and its rows. */
class SettingsSection(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val summary: String,
    val rows: @Composable (AppState) -> List<MenuAction>,
)

val settingsSections: List<SettingsSection> = listOf(
    SettingsSection("appearance", "Appearance", FuseIcons.Palette, "Theme, motion, glass, CRT", ::appearanceRows),
    SettingsSection("home", "Home", FuseIcons.Home, "Style, shelves, sections, Home screen", ::homeRows),
    SettingsSection("library", "Library", FuseIcons.Library, "Folders, scanning, names", ::libraryRows),
    SettingsSection("systems", "Systems", FuseIcons.Chip, "Per-system emulator, folders, BIOS", ::systemsRows),
    SettingsSection("emulators", "Emulators", FuseIcons.Joystick, "What Fuse found installed", ::emulatorRows),
    SettingsSection("media", "Media and Scraping", FuseIcons.Images, "Art sources, keys, matching, previews", ::mediaRows),
    SettingsSection("achievements", "Achievements", FuseIcons.Trophy, "RetroAchievements", ::achievementRows),
    SettingsSection("cartridge", "Cartridge", FuseIcons.CloudDownload, "Your RomM companion", ::cartridgeRows),
    SettingsSection("inputs", "Inputs", FuseIcons.Gamepad, "Buttons, layout, repeat", ::inputRows),
    SettingsSection("sound", "Sound", FuseIcons.Music, "Menu music and interface sounds", ::soundRows),
    SettingsSection("displays", "Displays", FuseIcons.DualScreen, "Second screen and launching", ::displayRows),
    SettingsSection("performance", "Performance and Power", FuseIcons.Gauge, "Profile and Low Power Mode", ::performanceRows),
    SettingsSection("network", "Network", FuseIcons.Wifi, "What Fuse connects to", ::networkRows),
    SettingsSection("phonelink", "Phone Link", FuseIcons.Smartphone, "Your library from a phone on the same Wi-Fi", ::phoneLinkRows),
    SettingsSection("storage", "Storage", FuseIcons.HardDrive, "File access and caches", ::storageRows),
    SettingsSection("privacy", "Privacy", FuseIcons.ShieldCheck, "No telemetry, where data goes", ::privacyRows),
    SettingsSection("updates", "Updates", FuseIcons.Download, "New versions of Fuse", ::updateRows),
    SettingsSection("about", "About", FuseIcons.Info, "Version, licences, setup", ::aboutRows),
)

/**
 * Settings as two panes: sections on the left, the section's settings on the right. Everything
 * applies immediately; there is no Save button. Settings that can differ per system or per game say
 * where their current value comes from.
 */
@Composable
fun SettingsScreen(app: AppState, initialSection: String?) {
    val sectionSel = remember { LinearSelection(settingsSections.indexOfFirst { it.id == initialSection }.coerceAtLeast(0)) }
    val rowSel = remember { LinearSelection() }
    var inRows by remember { mutableStateOf(initialSection != null) }
    val section = settingsSections[sectionSel.index]
    val rows = section.rows(app)
    rowSel.clamp(rows.size)

    LaunchedEffect(inRows, section.id) {
        app.hero = null
        app.hints = if (inRows) listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Sections"))
        else listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back"))
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (inRows) {
            when (e.action) {
                NavAction.LEFT, NavAction.BACK -> { inRows = false; NavResult.MOVED }
                else -> handleMenuAction(e, rows, rowSel)
            }
        } else {
            when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                    val r = sectionSel.move(e.action, settingsSections.size, vertical = true)
                    if (r == NavResult.MOVED) rowSel.index = 0
                    if (r == NavResult.IGNORED && e.action == NavAction.DOWN) NavResult.BLOCKED else r
                }
                NavAction.RIGHT, NavAction.SELECT -> { inRows = true; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            FText("Settings", Fuse.type.display)
            Spacer(Modifier.height(Space.l))
            MenuList(
                settingsSections.map { s ->
                    MenuAction(s.id, s.label, s.icon, onSelect = {
                        sectionSel.index = settingsSections.indexOf(s)
                        rowSel.index = 0
                        inRows = true
                        app.focusZone = FocusZone.CONTENT
                    })
                },
                sectionSel,
                showSelection = app.focusZone == FocusZone.CONTENT,
                dimSelection = inRows,
                modifier = Modifier.padding(bottom = Size.hintHeight),
            )
        }
        Spacer(Modifier.width(Space.xl))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            FText(section.label, Fuse.type.title)
            FText(section.summary, Fuse.type.body, color = Fuse.colors.textMuted)
            Spacer(Modifier.height(Space.l))
            Box(Modifier.weight(1f)) {
                Panel(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s)) {
                    MenuList(
                        rows.map { r -> r.copy(onSelect = { rowSel.index = rows.indexOf(r); inRows = true; r.onSelect() }) },
                        rowSel,
                        showSelection = inRows && app.focusZone == FocusZone.CONTENT,
                        modifier = Modifier.padding(Space.s),
                    )
                }
            }
        }
    }
}
