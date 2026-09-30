package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.designsystem.components.EditableText
import kotlinx.coroutines.launch

/**
 * Clipboard text made fit for a one-line field: line breaks, tabs and other control characters become
 * single spaces and the ends are trimmed. Keys are cleaned further when they are saved.
 */
internal fun cleanPasted(text: String): String =
    text.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ").trim()

/** Puts the clipboard's text at the caret (over a selection), or says there is nothing to paste. */
fun AppState.pasteInto(field: EditableText) {
    scope.launch {
        val text = platform.readClipboardText()?.let(::cleanPasted)
        if (text.isNullOrEmpty()) toasts.show("Nothing to paste") else field.insert(text)
    }
}
