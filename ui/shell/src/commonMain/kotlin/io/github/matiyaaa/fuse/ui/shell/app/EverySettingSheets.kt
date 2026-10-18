package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.launch.settings.SettingSource
import io.github.matiyaaa.fuse.launch.settings.TuneChoice
import io.github.matiyaaa.fuse.launch.settings.TuneKind
import io.github.matiyaaa.fuse.launch.settings.TuneSetting
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.EverySettingView
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import io.github.matiyaaa.fuse.ui.shell.store.TuneValue
import kotlinx.coroutines.launch

/**
 * Every Setting (Fuse Tune): all of an emulator's settings, as its own code has them, for one game
 * or for every game. It opens on the emulator's own parts (its sections), each opening on its
 * settings; [section] opens one straight away, and [query] shows what a search found instead. Back
 * goes up a level at a time, and from the parts to [from] (the sheet it was opened from), if any.
 */
fun AppState.everySettingSheet(
    game: GameId?,
    gameTitle: String?,
    emulator: EmulatorId?,
    section: String? = null,
    query: String? = null,
    from: (() -> Unit)? = null,
) {
    contextMenu = null
    val place = Place(game, gameTitle, emulator, from)
    scope.launch {
        when (val view = store.library.everyEmulatorSetting(game, emulator)) {
            is EverySettingView.Unavailable -> showProblem(Problem(view.title, view.reason, ProblemKind.EMULATOR, Severity.INFO, reassurance = null))
            is EverySettingView.Ready -> when {
                query != null -> searchResults(place, view, query)
                section != null && view.sections.any { it.id == section } -> sectionSheet(place, view, section)
                else -> everySettingParts(place, view)
            }
        }
    }
}

/** Where Every Setting is open: for which game (or every game), in which emulator, and what opened it. */
private class Place(val game: GameId?, val title: String?, val emulator: EmulatorId?, val from: (() -> Unit)?) {
    val forGame: Boolean get() = game != null
}

private fun EverySettingView.Ready.where(place: Place): String =
    if (place.forGame) "for ${place.title ?: game ?: "this game"}" else "for every game"

private fun AppState.everySettingParts(place: Place, view: EverySettingView.Ready) {
    val search = MenuAction(
        "search", "Search Every Setting", FuseIcons.Search,
        detail = "By the name ${view.emulator} gives it",
        trailing = Trailing.Chevron,
        onSelect = { askSearch(place, view) },
    )
    val parts = view.sections.map { s ->
        val own = s.values.count { it.source == if (place.forGame) SettingSource.GAME else SettingSource.EVERY_GAME }
        MenuAction(
            "part.${s.id}", s.title, FuseIcons.Settings2,
            detail = when {
                place.forGame && own > 0 -> if (own == 1) "1 of this game's own" else "$own of this game's own"
                else -> null
            },
            trailing = Trailing.Value(s.values.size.toString()),
            section = "Parts",
            onSelect = { sectionSheet(place, view, s.id) },
        )
    }
    choice = ChoiceSpec(
        icon = FuseIcons.LayoutList,
        title = "Every ${view.emulator} setting ${view.where(place)}",
        message = buildString {
            append("All ${view.count} of them, by the names ${view.emulator}'s own code gives them")
            view.release?.let { append(", as its release $it has them") }
            append(". Changes apply the next time it starts. Put Back undoes any of them.")
            view.gameFileNote?.let { append(' ').append(it) }
        },
        options = listOf(search) + parts,
        onBack = place.from,
    )
}

private fun AppState.askSearch(place: Place, view: EverySettingView.Ready) {
    choice = null
    textInput = TextInputSpec(
        title = "Search ${view.emulator}'s settings",
        initial = "",
        placeholder = "Resolution",
        capitalize = false,
        doneLabel = "Search",
        message = "Any part of a setting's name, or of the part it is in.",
        onCancel = { everySettingParts(place, view) },
    ) { text ->
        textInput = null
        if (text.isBlank()) everySettingParts(place, view) else searchResults(place, view, text.trim())
    }
}

private fun AppState.searchResults(place: Place, view: EverySettingView.Ready, query: String) {
    val q = query.lowercase()
    val found = view.sections.flatMap { s ->
        s.values.filter { v -> listOf(v.setting.key, settingName(v.setting), s.title).any { q in it.lowercase() } }.map { s to it }
    }
    val shown = found.take(MAX_FOUND)
    val again = { everySettingSheet(place.game, place.title, place.emulator, query = query, from = place.from) }
    choice = ChoiceSpec(
        icon = FuseIcons.Search,
        title = if (found.isEmpty()) "Nothing called \u201c$query\u201d" else "\u201c$query\u201d in ${view.emulator}'s settings",
        message = when {
            found.isEmpty() -> "No setting of ${view.emulator}'s has that in its name. Try fewer letters, or another word for it."
            found.size > shown.size -> "The first ${shown.size} of ${found.size}. A longer search finds fewer."
            else -> null
        },
        options = listOf(
            MenuAction("again", "Search Again", FuseIcons.Search, trailing = Trailing.Chevron, onSelect = { askSearch(place, view) }),
        ) + shown.map { (s, v) -> row(place, view, v, section = s.title, back = again, here = { searchResults(place, view, query) }) },
        onBack = { everySettingParts(place, view) },
    )
}

private fun AppState.sectionSheet(place: Place, view: EverySettingView.Ready, id: String) {
    val s = view.sections.firstOrNull { it.id == id } ?: return
    val again = { everySettingSheet(place.game, place.title, place.emulator, section = id, from = place.from) }
    choice = ChoiceSpec(
        icon = FuseIcons.Settings2,
        title = "${s.title} ${view.where(place)}",
        message = "As ${view.emulator}'s own code names and orders them.",
        options = listOf(
            MenuAction("parts", "Every Part", FuseIcons.ChevronLeft, detail = "${view.sections.size} parts, ${view.count} settings", onSelect = { everySettingParts(place, view) }),
        ) + s.values.map { row(place, view, it, section = null, back = again, here = { sectionSheet(place, view, id) }) },
        onBack = { everySettingParts(place, view) },
    )
}

/**
 * One setting as a row: what it is now, and choosing it changes it the way its kind is changed.
 * After a change [back] brings the list in again (with the new value); leaving without one, [here]
 * shows it again as it was, at once.
 */
private fun AppState.row(
    place: Place,
    view: EverySettingView.Ready,
    v: TuneValue,
    section: String?,
    back: () -> Unit,
    here: () -> Unit,
): MenuAction {
    val s = v.setting
    val forGame = place.forGame
    val name = view.emulator
    val apply: (String?) -> Unit = { value ->
        choice = null
        scope.launch {
            val ok = store.library.setEveryEmulatorSetting(place.game, place.emulator, s.id, value)
            if (!ok) toasts.show("Couldn't change $name's settings", ToastKind.WARNING)
            back()
        }
    }
    val detail = when (v.source) {
        SettingSource.GAME -> "This game's own. Every game: ${shown(s, v.everyGame)}"
        SettingSource.EVERY_GAME -> if (forGame) "Same as every game" else s.default?.takeIf { d -> v.value?.let { !same(s, d, it) } == true }?.let { "$name's default: ${shown(s, it)}" }
        SettingSource.DEFAULT -> if (forGame) "Same as every game" else if (v.value == null) "Worked out by $name" else "$name's default"
    }
    // A switch for every game changes at once; one for a game can also follow every game again.
    val on = v.value?.lowercase() == "true"
    val instant = s.kind == TuneKind.SWITCH && !forGame && v.value != null
    return MenuAction(
        "tune.${s.id}", settingName(s), kindIcon(s.kind),
        detail = detail,
        trailing = if (instant) Trailing.Switch(on) else Trailing.Value(shown(s, v.value).let { if (it.length > MAX_SHOWN) it.take(MAX_SHOWN - 1) + "…" else it }),
        section = section,
        onSelect = {
            when {
                instant -> apply(if (on) "false" else "true")
                s.kind == TuneKind.SWITCH || s.kind == TuneKind.CHOICE -> picker(view, v, forGame, apply, here)
                else -> typed(view, v, forGame, apply, here)
            }
        },
    )
}

/** The values a switch or a choice can take, the one in use checked. */
private fun AppState.picker(view: EverySettingView.Ready, v: TuneValue, forGame: Boolean, apply: (String?) -> Unit, back: () -> Unit) {
    val s = v.setting
    val own = if (forGame) v.source == SettingSource.GAME else v.source == SettingSource.EVERY_GAME
    val values = if (s.kind == TuneKind.SWITCH) listOf(TuneChoice("true", "On"), TuneChoice("false", "Off")) else s.choicesOn(platform.host)
    val default = s.default
    // The default among the values says so itself; a row of its own only when it isn't one of them.
    val defaultShown = default != null && values.any { same(s, it.value, default) }
    choice = ChoiceSpec(
        icon = kindIcon(s.kind),
        title = settingName(s),
        message = about(view, s, forGame),
        onBack = back,
        options = listOfNotNull(undo(view, v, forGame, own, apply).takeIf { forGame || !defaultShown }) + values.map { c ->
            MenuAction(
                "value.${c.value}", choiceName(s, c), kindIcon(s.kind),
                detail = if (default != null && same(s, c.value, default)) "${view.emulator}'s default" else null,
                trailing = Trailing.Check(own && v.value != null && same(s, c.value, v.value)),
                onSelect = { apply(c.value) },
            )
        },
    )
}

/** A number, a decimal or text, typed: checked against what the emulator reads before it is written. */
private fun AppState.typed(view: EverySettingView.Ready, v: TuneValue, forGame: Boolean, apply: (String?) -> Unit, back: () -> Unit) {
    val s = v.setting
    val own = if (forGame) v.source == SettingSource.GAME else v.source == SettingSource.EVERY_GAME
    choice = null
    textInput = TextInputSpec(
        title = settingName(s),
        initial = if (own) v.value.orEmpty() else "",
        placeholder = v.value ?: s.default ?: "",
        capitalize = false,
        numbers = s.kind == TuneKind.NUMBER || s.kind == TuneKind.DECIMAL,
        doneLabel = "Set",
        onCancel = back,
        message = buildString {
            append(about(view, s, forGame))
            when (s.kind) {
                TuneKind.NUMBER -> when {
                    s.min != null && s.max != null -> append(" A whole number from ${s.min} to ${s.max}.")
                    s.min != null -> append(" A whole number, ${s.min} or more.")
                    else -> append(" A whole number.")
                }
                TuneKind.DECIMAL -> append(" A number, like 1.5.")
                else -> Unit
            }
            append(if (forGame) " Leave it empty to follow every game's." else " Leave it empty for ${view.emulator}'s default.")
        },
    ) { text ->
        textInput = null
        when {
            text.isBlank() && s.kind != TuneKind.TEXT -> apply(null)
            text.isEmpty() -> apply(null)
            s.accepts(text.trim().takeIf { s.kind != TuneKind.TEXT } ?: text, platform.host) -> apply(if (s.kind == TuneKind.TEXT) text else text.trim())
            else -> {
                toasts.show("${view.emulator} doesn't read “${text.trim()}” for ${settingName(s)}", ToastKind.WARNING)
                back()
            }
        }
    }
}

/** For a game, following every game's again; for every game, going back to the emulator's default. */
private fun undo(view: EverySettingView.Ready, v: TuneValue, forGame: Boolean, own: Boolean, apply: (String?) -> Unit): MenuAction? = when {
    forGame -> MenuAction(
        "follow", "Same as Every Game", FuseIcons.Layers, detail = shown(v.setting, v.everyGame),
        trailing = Trailing.Check(!own), onSelect = { apply(null) },
    )
    v.source == SettingSource.EVERY_GAME -> MenuAction(
        "default", "${view.emulator}'s Default", FuseIcons.RotateCcw,
        detail = v.setting.default?.let { shown(v.setting, it) } ?: "Worked out by ${view.emulator} when it starts",
        onSelect = { apply(null) },
    )
    else -> null
}

private fun about(view: EverySettingView.Ready, s: TuneSetting, forGame: Boolean): String {
    val where = s.path.joinToString(" / ")
    val scope = if (forGame) "For this game in ${view.emulator}." else "For every game in ${view.emulator}."
    return "$scope Kept as ${s.key} under $where."
}

/** What [value] is called for [s]: its choice's name, On or Off, or itself; nothing set and no default is Automatic. */
private fun shown(s: TuneSetting, value: String?): String {
    if (value == null) return "Automatic"
    return when (s.kind) {
        TuneKind.SWITCH -> if (value.equals("true", ignoreCase = true)) "On" else "Off"
        TuneKind.CHOICE -> s.choices.firstOrNull { same(s, it.value, value) }?.let { choiceName(s, it) } ?: value
        TuneKind.TEXT -> value.ifEmpty { "Empty" }
        else -> value
    }
}

/** Two values the emulator reads the same: `2`, `2.0` and `2.000000`; `true` and `True`. */
private fun same(s: TuneSetting, a: String, b: String): Boolean {
    if (a.equals(b, ignoreCase = s.kind != TuneKind.TEXT)) return true
    if (s.kind != TuneKind.NUMBER && s.kind != TuneKind.DECIMAL && s.kind != TuneKind.CHOICE) return false
    val x = a.trim().toDoubleOrNull() ?: return false
    val y = b.trim().toDoubleOrNull() ?: return false
    return x == y
}

/** A setting's name as words: `upscale_multiplier` is Upscale Multiplier, `wideScreenHack` Wide Screen Hack. */
internal fun settingName(s: TuneSetting): String = words(s.key)

/** A choice's name: the emulator's own words, or the name its code gives the value, as words. */
private fun choiceName(s: TuneSetting, c: TuneChoice): String {
    val label = c.label ?: return c.value
    // Dolphin's and PCSX2's are the names of members of the code's enums (ForceWide, DX11).
    return if (s.family in setOf("dolphin", "pcsx2")) words(label) else label
}

internal fun words(name: String): String {
    if (' ' in name.trim()) return name.trim()
    // PPSSPP's few keys still named by their field (iShowStatusFlags).
    val plain = Regex("^[bifsu](?=[A-Z][a-z])").replace(name, "")
    val spaced = plain.replace('_', ' ').replace('.', ' ')
        .replace(Regex("(?<=[a-z])(?=[A-Z0-9])"), " ")
        .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
    return spaced.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

private fun kindIcon(kind: TuneKind) = when (kind) {
    TuneKind.SWITCH -> FuseIcons.SquareCheck
    TuneKind.NUMBER, TuneKind.DECIMAL -> FuseIcons.Hash
    TuneKind.TEXT -> FuseIcons.Type
    TuneKind.CHOICE -> FuseIcons.CircleDot
}

/** The most search results shown at once. */
private const val MAX_FOUND = 150

/** The most letters of a value shown beside its setting. */
private const val MAX_SHOWN = 24
