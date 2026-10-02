package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import kotlin.math.roundToInt

/*
 * Every settings row has the same anatomy (a MenuRow): an icon in its well, a title, an optional
 * detail line, and on the right what the row holds. The right side always says what selecting the
 * row does:
 *
 * - a switch flips in place (toggleRow),
 * - a value opens a choice list (choiceRow, textRow), a meter and a percentage for levels (percentRow),
 * - a chevron opens a page or a list of its own,
 * - nothing for information (infoRow) and for actions that run straight away or after a question
 *   (confirmRow, red when it deletes something).
 *
 * Long sections put their rows under small labels with [labelled]; rarely changed settings fold
 * into a [group] at the end of a section.
 */

/**
 * Puts the rows [rows] adds under one labelled group of a section page: the list draws a divider and
 * [label] above the first of them. A blank [label] draws the divider alone, which sets a dangerous
 * action apart from the settings above it.
 */
internal inline fun MutableList<MenuAction>.labelled(label: String, rows: MutableList<MenuAction>.() -> Unit) {
    val from = size
    rows()
    for (i in from until size) this[i] = this[i].copy(section = label)
}

/**
 * A group of rows that opens in place: its header shows [summary] and a chevron, and while open its
 * rows follow it, stepped in. Rarely changed settings live in groups at the end of a section.
 */
fun AppState.group(id: String, label: String, icon: ImageVector, summary: String? = null, detail: String? = null, rows: () -> List<MenuAction>): List<MenuAction> {
    val open = openGroups[id] == true
    val header = MenuAction("group.$id", label, icon, detail = detail, trailing = Trailing.Disclosure(open, summary), onSelect = { openGroups[id] = !open })
    return if (open) listOf(header) + rows().map { it.copy(indent = it.indent + 1) } else listOf(header)
}

/** A switch row. */
fun toggleRow(id: String, label: String, icon: ImageVector, on: Boolean, detail: String? = null, enabled: Boolean = true, set: (Boolean) -> Unit) =
    MenuAction(id, label, icon, detail = detail, trailing = Trailing.Switch(on), enabled = enabled, onSelect = { set(!on) })

/**
 * A row that shows its value and opens a choice list. The list opens under the row's own icon and
 * title, with the current value checked.
 */
fun <T> AppState.choiceRow(
    id: String,
    label: String,
    icon: ImageVector,
    current: T,
    options: List<Pair<T, String>>,
    detail: String? = null,
    optionDetail: (T) -> String? = { null },
    set: (T) -> Unit,
): MenuAction {
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: current.toString()
    return MenuAction(id, label, icon, detail = detail, trailing = Trailing.Value(currentLabel), onSelect = {
        choice = ChoiceSpec(
            title = label,
            message = detail,
            icon = icon,
            options = options.map { (value, name) ->
                MenuAction("$id.$name", name, null, detail = optionDetail(value), trailing = Trailing.Check(value == current), onSelect = {
                    set(value)
                    choice = null
                })
            },
        )
    })
}

/** The steps a level row offers: every tenth from none to full. */
private val levelSteps = (0..10).map { it / 10f }

/**
 * A 0..1 level (volume, dimming, opacity) offered as tenths, which suits a controller: a short meter
 * and its percentage show how much at a glance, and the choice list checks the nearest step.
 */
fun AppState.percentRow(id: String, label: String, icon: ImageVector, value: Float, detail: String? = null, set: (Float) -> Unit): MenuAction =
    choiceRow(
        id, label, icon,
        current = levelSteps.minBy { kotlin.math.abs(it - value) },
        options = levelSteps.map { it to "${(it * 100).roundToInt()}%" },
        detail = detail,
        set = set,
    ).copy(trailing = Trailing.Level(value.coerceIn(0f, 1f), "${(value * 100).roundToInt()}%"))

/** A row that asks for text (API keys, names). Secret values are never shown back. */
fun AppState.textRow(
    id: String,
    label: String,
    icon: ImageVector,
    shown: String?,
    detail: String? = null,
    initial: String = "",
    placeholder: String = "",
    set: (String) -> Unit,
): MenuAction = MenuAction(id, label, icon, detail = detail, trailing = Trailing.Value(shown ?: "Not set"), onSelect = {
    textInput = TextInputSpec(label, initial, placeholder) { set(it.trim()) }
})

/** A row that runs something after the user confirms. */
fun AppState.confirmRow(
    id: String,
    label: String,
    icon: ImageVector,
    title: String,
    message: String,
    confirmLabel: String,
    detail: String? = null,
    destructive: Boolean = false,
    run: () -> Unit,
): MenuAction = MenuAction(id, label, icon, detail = detail, destructive = destructive, onSelect = {
    confirm = ConfirmSpec(title, message, confirmLabel, destructive, run)
})

/** Informational row (no action). */
fun infoRow(id: String, label: String, value: String? = null, detail: String? = null, icon: ImageVector = FuseIcons.Info) =
    MenuAction(id, label, icon, detail = detail, trailing = value?.let { Trailing.Value(it) } ?: Trailing.None)
