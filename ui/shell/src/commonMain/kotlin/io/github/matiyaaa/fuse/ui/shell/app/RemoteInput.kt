package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.PadButton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A text field open on the device, as a phone sees it: what it is for, its text, and the name of
 * its finishing key. [id] changes with every field, so an edit meant for a field that has since
 * closed never lands in the next one. A [secret] field's text is never sent to phones ([text] is
 * empty and [length] says how long it is); typing on the phone replaces it.
 */
data class RemoteField(
    val id: Int,
    val title: String,
    val text: String,
    val secret: Boolean,
    val length: Int,
    val placeholder: String,
    val doneLabel: String,
    /** True when the field can be closed without finishing (a dialog); false for Search's own field. */
    val cancellable: Boolean,
)

/** What a phone asked for, applied by [FuseApp] on the UI thread. */
sealed interface RemoteCommand {
    data class SetText(val id: Int, val text: String) : RemoteCommand
    data class Submit(val id: Int) : RemoteCommand
    data class Cancel(val id: Int) : RemoteCommand
    data class Pad(val button: PadButton, val down: Boolean) : RemoteCommand
}

/**
 * The bridge between Phone Link and the screen: the text field open now (so a phone can type into
 * it with its own keyboard, paste included) and buttons pressed on a phone used as a controller.
 * The server in :ui:link reads [field] and sends [RemoteCommand]s; the shell publishes the field
 * and carries the commands out. Nothing here knows about networks or sign-in.
 */
object RemoteInput {
    private val fieldState = MutableStateFlow<RemoteField?>(null)

    /** The text field open on the device, or null. */
    val field: StateFlow<RemoteField?> = fieldState.asStateFlow()

    private val commandFlow = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 128)

    /** Edits and presses from phones, in the order they came. */
    val commands: SharedFlow<RemoteCommand> = commandFlow

    private val phoneCount = MutableStateFlow(0)

    /** How many phones are following the device live right now (Phone Link's event stream open). */
    val phones: StateFlow<Int> = phoneCount.asStateFlow()

    private val padFamilyState = MutableStateFlow<io.github.matiyaaa.fuse.model.GlyphStyle?>(null)

    /**
     * The family of the controller in use on the device (a DualSense, an Xbox pad, a Pro
     * Controller), or null when it doesn't say: a phone used as a controller is labelled the same.
     */
    val padFamily: StateFlow<io.github.matiyaaa.fuse.model.GlyphStyle?> = padFamilyState.asStateFlow()

    /** The device's controller is now [family] (set by the shell from its input router). */
    fun padInUse(family: io.github.matiyaaa.fuse.model.GlyphStyle?) {
        padFamilyState.value = family
    }

    private var nextId = 1

    /** A field opened: phones get it, and its id for every edit that follows. */
    fun opened(title: String, text: String, secret: Boolean, placeholder: String, doneLabel: String, cancellable: Boolean): Int {
        val id = nextId++
        fieldState.value = RemoteField(id, title, if (secret) "" else text, secret, text.length, placeholder, doneLabel, cancellable)
        return id
    }

    /** The open field's text changed on the device (the on-screen keyboard, a hardware keyboard). */
    fun changed(id: Int, text: String) {
        fieldState.update { f -> if (f == null || f.id != id) f else f.copy(text = if (f.secret) "" else text, length = text.length) }
    }

    /** The field [id] closed. */
    fun closed(id: Int) {
        fieldState.update { f -> if (f?.id == id) null else f }
    }

    /** A phone typed: the field's whole text, so its own keyboard, autocorrect and paste all work. */
    fun setText(id: Int, text: String): Boolean = send(id, RemoteCommand.SetText(id, text.take(MAX_TEXT)))

    /** A phone pressed Done. */
    fun submit(id: Int): Boolean = send(id, RemoteCommand.Submit(id))

    /** A phone pressed Cancel. */
    fun cancel(id: Int): Boolean = send(id, RemoteCommand.Cancel(id))

    /** A button on a phone used as a controller went down or up. */
    fun pad(button: PadButton, down: Boolean): Boolean = commandFlow.tryEmit(RemoteCommand.Pad(button, down))

    /** A phone started or stopped following the device. */
    fun phoneAttached() = phoneCount.update { it + 1 }
    fun phoneDetached() = phoneCount.update { (it - 1).coerceAtLeast(0) }

    private fun send(id: Int, command: RemoteCommand): Boolean {
        if (fieldState.value?.id != id) return false
        return commandFlow.tryEmit(command)
    }

    /** The longest text a phone may put in a field. */
    const val MAX_TEXT = 4_000
}
