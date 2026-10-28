package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.launch.settings.Hotkeys
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.HotkeyEmulator
import kotlinx.coroutines.launch

/** Each hotkey's mark, in the settings row and its sheet. */
internal fun hotkeyIcon(a: Hotkeys.Action): ImageVector = when (a) {
    Hotkeys.Action.QUIT -> FuseIcons.Power
    Hotkeys.Action.SAVE_STATE -> FuseIcons.Save
    Hotkeys.Action.LOAD_STATE -> FuseIcons.History
    Hotkeys.Action.MENU -> FuseIcons.PanelTop
}

/** The emulators of [here] an action is written into, by name. */
private fun emulatorsFor(a: Hotkeys.Action, here: List<HotkeyEmulator>): List<String> =
    here.filter { a in Hotkeys.actionsFor(it.family) }.map { it.name }

/** "DuckStation, PCSX2 and RetroArch". */
internal fun namesInWords(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * What the hotkeys row says: the layout, and where it goes ("RetroArch: quit" for one that takes
 * only the quit combo).
 */
internal fun hotkeysWords(here: List<HotkeyEmulator>): String {
    val all = here.filter { Hotkeys.actionsFor(it.family).size == Hotkeys.Action.entries.size }.map { it.name }
    val quitOnly = here.map { it.name } - all.toSet()
    val where = listOfNotNull(
        all.takeIf { it.isNotEmpty() }?.let { "In ${namesInWords(it)}" },
        quitOnly.takeIf { it.isNotEmpty() }?.let { "${namesInWords(it)}: quit" },
    ).joinToString("; ").ifEmpty { "In DuckStation and PCSX2 once one is set up here" }
    return "Select + Start quits, Select + R1 saves a state, Select + L1 loads it. $where. Your own hotkeys stay, and off takes Fuse's out"
}

/**
 * The hotkeys as Fuse writes them, each with its buttons and which of the emulators [here] it is
 * in: the layout to learn once and use in every one of them.
 */
internal fun hotkeysSheet(app: AppState, here: List<HotkeyEmulator>) {
    app.choice = ChoiceSpec(
        title = "Hotkeys in every emulator",
        icon = FuseIcons.Gamepad,
        message = "Hold Select and press the other button. Your own hotkeys in each emulator keep working beside these.",
        options = Hotkeys.Action.entries.map { a ->
            val inThese = emulatorsFor(a, here)
            MenuAction(
                "hotkey.${a.name}", a.label, hotkeyIcon(a),
                detail = if (inThese.isEmpty()) "Not in the emulators set up here" else "In ${namesInWords(inThese)}",
                trailing = Trailing.Value(a.buttons),
                enabled = inThese.isNotEmpty(),
                onSelect = {},
            )
        },
    )
}

/**
 * Turns the same hotkeys in every emulator [on] or off: the preference first, then each emulator
 * here written (or put back) at once, so an emulator started outside Fuse has them too; a toast
 * says where they went, or where the person's own binding was kept.
 */
internal fun setHotkeys(app: AppState, on: Boolean) {
    app.store.updatePrefs { it.copy(gameHotkeys = on) }
    app.scope.launch {
        val done = runCatching { app.store.library.applyHotkeys(on) }.getOrDefault(emptyList())
        val names = done.map { it.emulator }
        val kept = done.filter { it.kept.isNotEmpty() }
        app.toasts.show(
            when {
                !on && names.isEmpty() -> "Hotkeys off"
                !on -> "Fuse's hotkeys taken out of ${namesInWords(names)}"
                names.isEmpty() -> "Hotkeys on. They go into each emulator once it has run once"
                kept.isNotEmpty() -> "Hotkeys in ${namesInWords(names)}. ${kept.first().emulator} keeps your own ${kept.first().kept.first().lowercase()} combo"
                else -> "Hotkeys in ${namesInWords(names)}"
            },
        )
    }
}
