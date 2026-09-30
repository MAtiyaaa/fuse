package io.github.matiyaaa.fuse.ui.shell.app

import kotlinx.coroutines.launch

/**
 * Clipboard text made fit for a one-line field: line breaks, tabs and other control characters become
 * single spaces and the ends are trimmed. Keys are cleaned further when they are saved.
 */
internal fun cleanPasted(text: String): String =
    text.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ").trim()

/** Appends the clipboard's text to a field ([current] and [set]), or says there is nothing to paste. */
fun AppState.pasteInto(current: () -> String, set: (String) -> Unit) {
    scope.launch {
        val text = platform.readClipboardText()?.let(::cleanPasted)
        if (text.isNullOrEmpty()) toasts.show("Nothing to paste") else set(current() + text)
    }
}
