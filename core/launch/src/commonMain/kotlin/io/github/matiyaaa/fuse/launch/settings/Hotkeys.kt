package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.model.Host

/**
 * Fuse's one layout of hotkeys, the same in every emulator it is written into: Select held with
 * another button does what that emulator's own hotkey for it does. Each emulator's bindings are
 * written as it writes them itself, checked against its own code:
 *
 * - DuckStation (src/core/hotkeys.cpp, src/util/sdl_input_source.cpp) and PCSX2 (pcsx2/Hotkeys.cpp,
 *   pcsx2/Input/SDLInputSource.cpp) bind the first SDL controller's buttons (`SDL-0/Back`), a chord's
 *   buttons joined by `&` (InputManager's SplitChord). A hotkey can have several bindings, one line
 *   each, so Fuse's goes beside the person's own and theirs keep working.
 * - PPSSPP (Core/KeyMap.cpp) keeps each action's mappings in `controls.ini` as one comma-separated
 *   list, a combo's keys joined by `:`, each key a device and a key code (`10-196`). The codes are
 *   the platform's: SDL's buttons as PPSSPP numbers them on Linux and macOS (SDL/SDLJoystick.cpp),
 *   an XInput pad's on Windows (Windows/XinputDevice.cpp), Android's own key codes there. Once its
 *   `[ControlMapping]` is in the file, PPSSPP takes every action's mappings from it alone, so Fuse
 *   adds to a section PPSSPP wrote itself, never makes one.
 * - Dolphin binds hotkeys to a controller by its full name (`SDL/0/Xbox Controller`), which differs
 *   from one controller to the next, so its are left to Dolphin.
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
     * One binding Fuse writes: [value] for [key] in [section] of the emulator's file for every game,
     * or of [file] beside it. [beside]: it goes beside the person's own bindings for the key, on a
     * line of its own, or as one more item of the key's list when [listed]; otherwise it is the
     * key's one value, given only where nothing is set (absent, or one of [unset]).
     */
    data class Binding(
        val action: Action,
        val section: String,
        val key: String,
        val value: String,
        val beside: Boolean,
        val unset: Set<String> = emptySet(),
        val file: String? = null,
        val listed: Boolean = false,
    )

    /** The emulators Fuse writes hotkeys into, by family. */
    val families: Set<String> = setOf("duckstation", "pcsx2", "ppsspp", "retroarch")

    /** [family]'s bindings on [host], whose controllers' buttons some emulators number their own way. */
    fun forEmulator(family: String, host: Host = Host.LINUX): List<Binding> = when (family) {
        "duckstation" -> chords(
            Action.QUIT to "PowerOff", Action.SAVE_STATE to "SaveSelectedSaveState",
            Action.LOAD_STATE to "LoadSelectedSaveState", Action.MENU to "OpenPauseMenu",
        )
        "pcsx2" -> chords(
            Action.QUIT to "ShutdownVM", Action.SAVE_STATE to "SaveStateToSlot",
            Action.LOAD_STATE to "LoadStateFromSlot", Action.MENU to "OpenPauseMenu",
        )
        "ppsspp" -> ppsspp(host)
        // input_combo_type: NONE is 0, then DOWN_Y_L_R, L3_R3, L1_R1_START_SELECT, START_SELECT (4).
        "retroarch" -> listOf(Binding(Action.QUIT, "", "input_quit_gamepad_combo", "4", beside = false, unset = setOf("0", "")))
        else -> emptyList()
    }

    /** The files beside the one for every game that [family]'s bindings go in (PPSSPP's `controls.ini`). */
    fun filesFor(family: String): Set<String?> = forEmulator(family).map { it.file }.toSet()

    /** The actions [family] gets from Fuse, in order. */
    fun actionsFor(family: String): List<Action> = forEmulator(family).map { it.action }.distinct()

    /**
     * What [apply] did: the notes of what Fuse wrote (to keep, so [restore] takes out exactly that),
     * the actions left on the person's own binding, and whether the file changed.
     */
    class Applied(val added: Set<String>, val records: Set<String>, val kept: Set<Action>, val changed: Boolean, val unwritable: Boolean = false)

    /**
     * Writes [family]'s bindings into [file], its file for every game. A binding beside the person's
     * goes in once, noted in [added] (`section`, `key`, `value`, and `s` or `sb` when Fuse made its
     * section); a one-value one goes through [SettingsEdit] where nothing is set, noted in [records].
     * A key holding the person's own value keeps it ([Applied.kept]). Writing again changes nothing.
     * Only the bindings of [only] (a file beside the one for every game, or null for that one) are
     * written; [Applied.unwritable] when a section they need isn't there for Fuse to add to.
     */
    fun apply(family: String, file: SettingsText, added: Set<String>, records: Set<String>, host: Host = Host.LINUX, only: String? = null): Applied {
        var notes = added
        var edits = records
        val kept = LinkedHashSet<Action>()
        var changed = false
        var unwritable = false
        for (b in forEmulator(family, host).filter { it.file == only }) {
            if (b.listed) {
                val ini = file as? IniText ?: continue
                if (!ini.hasSection(b.section)) {
                    unwritable = true
                    continue
                }
                val items = ini.values(b.section, b.key).lastOrNull().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (b.value in items) continue
                ini.set(b.section, b.key, (items + b.value).joinToString(","))
                notes = notes + listOf(b.section, b.key, b.value, LISTED).joinToString("\t")
                changed = true
                continue
            }
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
        return Applied(notes, edits, kept, changed, unwritable)
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
            for (n in notes) {
                if (n.getOrNull(3) != LISTED) {
                    ini.remove(n[0], n[1], n[2])
                    continue
                }
                // One item of a list: the rest of it, the person's, stays.
                val items = ini.values(n[0], n[1]).lastOrNull() ?: continue
                val rest = items.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != n[2] }
                if (rest.size != items.split(',').map { it.trim() }.count { it.isNotEmpty() }) ini.set(n[0], n[1], rest.joinToString(","))
            }
            for (n in notes) n.getOrNull(3)?.takeIf { it != LISTED }?.let { ini.removeEmptySection(n[0], blankBefore = it == "sb") }
        }
        SettingsEdit.restore(file, records)
    }

    private const val HOTKEYS = "Hotkeys"
    private const val LISTED = "l"

    /** PPSSPP's file of mappings, beside `ppsspp.ini`, and its section (Core/KeyMap.cpp's SaveToIni). */
    const val PPSSPP_CONTROLS = "controls.ini"
    private const val PPSSPP_SECTION = "ControlMapping"

    /**
     * PPSSPP's: its virtual keys' names (KeyMap.cpp's key_names), each on the first pad as [host]
     * numbers it, Select first. Linux and macOS: SDL's Back, Start, shoulders and north button as
     * NKCODE_BUTTON_9, 10, 6, 5 and 1 on DEVICE_ID_PAD_0 (10). Windows: an XInput pad (20) with
     * NKCODE_BUTTON_SELECT, START, L1, R1 and Y. Android: the pad (10) with Android's own codes, which
     * are those.
     */
    private fun ppsspp(host: Host): List<Binding> {
        val (device, select, codes) = when (host) {
            Host.WINDOWS -> Triple(20, NK_SELECT, NK_PAD)
            Host.ANDROID -> Triple(10, NK_SELECT, NK_PAD)
            else -> Triple(10, NK_BUTTON_9, NK_SDL)
        }
        val keys = listOf(Action.QUIT to "Exit App", Action.SAVE_STATE to "Save State", Action.LOAD_STATE to "Load State", Action.MENU to "Pause")
        return keys.map { (action, key) ->
            Binding(action, PPSSPP_SECTION, key, "$device-$select:$device-${codes.getValue(action)}", beside = true, file = PPSSPP_CONTROLS, listed = true)
        }
    }

    /** NKCODE_BUTTON_SELECT, then START, R1, L1 and Y (Common/Input/KeyCodes.h). */
    private const val NK_SELECT = 109
    private val NK_PAD = mapOf(Action.QUIT to 108, Action.SAVE_STATE to 103, Action.LOAD_STATE to 102, Action.MENU to 100)

    /** NKCODE_BUTTON_9 (SDL's Back), then 10, 5, 6 and 1 (its Start, shoulders and north button). */
    private const val NK_BUTTON_9 = 196
    private val NK_SDL = mapOf(Action.QUIT to 197, Action.SAVE_STATE to 192, Action.LOAD_STATE to 193, Action.MENU to 188)
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
