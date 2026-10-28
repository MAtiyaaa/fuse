package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.launch.patches.IniText

/**
 * Fuse's one layout of hotkeys, the same in every emulator it is written into: Select held with
 * another button does what that emulator's own hotkey for it does. Each emulator's bindings are
 * written as it writes them itself, checked against its own code:
 *
 * - DuckStation (src/core/hotkeys.cpp, src/util/sdl_input_source.cpp) and PCSX2 (pcsx2/Hotkeys.cpp,
 *   pcsx2/Input/SDLInputSource.cpp) bind the first SDL controller's buttons (`SDL-0/Back`), a chord's
 *   buttons joined by `&` (InputManager's SplitChord). A hotkey can have several bindings, one line
 *   each, so Fuse's goes beside the person's own and theirs keep working.
 * - RetroArch has a quit combo of its own, read from whichever controller is used
 *   (`input_quit_gamepad_combo`, numbered as input_defines.h's input_combo_type). It is one value, so
 *   it is given only where none is set; its other hotkeys bind a driver's raw button numbers, which
 *   differ between controllers, so they are left to RetroArch.
 */
object Hotkeys {
    /** What a hotkey does, and the buttons it is on. */
    enum class Action(val label: String, val buttons: String) {
        QUIT("Quit the game", "Select + Start"),
        SAVE_STATE("Save a state", "Select + R1"),
        LOAD_STATE("Load that state", "Select + L1"),
        MENU("The emulator's menu", "Select + top button"),
    }

    /**
     * One binding Fuse writes: [value] for [key] in [section] of the emulator's file for every game.
     * [beside]: it goes beside the person's own bindings for the key; otherwise it is the key's one
     * value, given only where nothing is set (absent, or one of [unset]).
     */
    data class Binding(
        val action: Action,
        val section: String,
        val key: String,
        val value: String,
        val beside: Boolean,
        val unset: Set<String> = emptySet(),
    )

    /** The emulators Fuse writes hotkeys into, by family. */
    val families: Set<String> = setOf("duckstation", "pcsx2", "retroarch")

    fun forEmulator(family: String): List<Binding> = when (family) {
        "duckstation" -> chords(
            Action.QUIT to "PowerOff", Action.SAVE_STATE to "SaveSelectedSaveState",
            Action.LOAD_STATE to "LoadSelectedSaveState", Action.MENU to "OpenPauseMenu",
        )
        "pcsx2" -> chords(
            Action.QUIT to "ShutdownVM", Action.SAVE_STATE to "SaveStateToSlot",
            Action.LOAD_STATE to "LoadStateFromSlot", Action.MENU to "OpenPauseMenu",
        )
        // input_combo_type: NONE is 0, then DOWN_Y_L_R, L3_R3, L1_R1_START_SELECT, START_SELECT (4).
        "retroarch" -> listOf(Binding(Action.QUIT, "", "input_quit_gamepad_combo", "4", beside = false, unset = setOf("0", "")))
        else -> emptyList()
    }

    /** The actions [family] gets from Fuse, in order. */
    fun actionsFor(family: String): List<Action> = forEmulator(family).map { it.action }.distinct()

    /**
     * What [apply] did: the notes of what Fuse wrote (to keep, so [restore] takes out exactly that),
     * the actions left on the person's own binding, and whether the file changed.
     */
    class Applied(val added: Set<String>, val records: Set<String>, val kept: Set<Action>, val changed: Boolean)

    /**
     * Writes [family]'s bindings into [file], its file for every game. A binding beside the person's
     * goes in once, noted in [added] (`section`, `key`, `value`, and `s` or `sb` when Fuse made its
     * section); a one-value one goes through [SettingsEdit] where nothing is set, noted in [records].
     * A key holding the person's own value keeps it ([Applied.kept]). Writing again changes nothing.
     */
    fun apply(family: String, file: SettingsText, added: Set<String>, records: Set<String>): Applied {
        var notes = added
        var edits = records
        val kept = LinkedHashSet<Action>()
        var changed = false
        for (b in forEmulator(family)) {
            if (b.beside) {
                val ini = file as? IniText ?: continue
                val made = when {
                    ini.hasSection(b.section) -> null
                    ini.separatesNewSection() -> "sb"
                    else -> "s"
                }
                if (ini.add(b.section, b.key, b.value)) {
                    notes = notes + listOfNotNull(b.section, b.key, b.value, made).joinToString("\t")
                    changed = true
                }
                continue
            }
            val now = file.values(b.section, b.key).lastOrNull()
            when {
                now == b.value -> Unit
                now == null || now.trim() in b.unset -> {
                    edits = SettingsEdit.change(file, edits, b.section, b.key, b.value)
                    changed = true
                }
                else -> kept += b.action
            }
        }
        return Applied(notes, edits, kept, changed)
    }

    /**
     * Takes out of [file] what [apply] wrote: each noted binding still there, then a section Fuse
     * made once nothing is left in it, then each one-value key as [SettingsEdit.restore] puts it back
     * (one the person changed since stays theirs).
     */
    fun restore(file: SettingsText, added: Set<String>, records: Set<String>) {
        val notes = added.map { it.split('\t') }.filter { it.size in 3..4 }
        val ini = file as? IniText
        if (ini != null) {
            for (n in notes) ini.remove(n[0], n[1], n[2])
            for (n in notes) n.getOrNull(3)?.let { ini.removeEmptySection(n[0], blankBefore = it == "sb") }
        }
        SettingsEdit.restore(file, records)
    }

    private const val HOTKEYS = "Hotkeys"
    private const val PAD = "SDL-0"

    /** The button each action is on with Select, as SDL names it (SDL_GAMEPAD_BUTTON_*'s names in both). */
    private val button = mapOf(
        Action.QUIT to "Start",
        Action.SAVE_STATE to "RightShoulder",
        Action.LOAD_STATE to "LeftShoulder",
        // The north face button: "Y" is the name both read (PCSX2 keeps it as a legacy name).
        Action.MENU to "Y",
    )

    private fun chords(vararg keys: Pair<Action, String>): List<Binding> = keys.map { (action, key) ->
        Binding(action, HOTKEYS, key, "$PAD/Back & $PAD/${button.getValue(action)}", beside = true)
    }
}
