package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec

/** A switch row. */
fun toggleRow(id: String, label: String, icon: ImageVector, on: Boolean, detail: String? = null, enabled: Boolean = true, set: (Boolean) -> Unit) =
    MenuAction(id, label, icon, detail = detail, trailing = Trailing.Switch(on), enabled = enabled, onSelect = { set(!on) })

/** A row that shows its value and opens a choice list. */
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
            options = options.map { (value, name) ->
                MenuAction("$id.$name", name, null, detail = optionDetail(value), trailing = Trailing.Check(value == current), onSelect = {
                    set(value)
                    choice = null
                })
            },
        )
    })
}

/** A 0..1 value offered as steps (controller friendly), shown as a percentage. */
fun AppState.percentRow(id: String, label: String, icon: ImageVector, value: Float, detail: String? = null, set: (Float) -> Unit): MenuAction =
    choiceRow(
        id, label, icon,
        current = (value * 20).toInt() / 20f,
        options = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f).map { it to "${(it * 100).toInt()}%" },
        detail = detail,
        set = set,
    )

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
