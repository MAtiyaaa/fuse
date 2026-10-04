package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.shell.app.RemoteField
import io.github.matiyaaa.fuse.ui.shell.app.RemoteInput
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Phone Link's Remote: typing into the field open on the device and pressing its buttons. Edits
 * name the field they are for, so text meant for a field that has closed never lands in the next.
 * A password field's text is never sent out (see [RemoteField]).
 */
internal object RemoteApi {
    /** The phone's buttons, by the names the web app sends; only these are accepted. */
    private val buttons: Map<String, PadButton> = listOf(
        PadButton.A, PadButton.B, PadButton.X, PadButton.Y,
        PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2,
        PadButton.START, PadButton.SELECT, PadButton.MODE,
        PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT,
    ).associateBy { it.name }

    fun field(f: RemoteField?): JsonElement = if (f == null) JsonNull else buildJsonObject {
        put("id", f.id)
        put("title", f.title)
        put("text", f.text)
        put("secret", f.secret)
        put("length", f.length)
        put("placeholder", f.placeholder)
        put("done", f.doneLabel)
        put("cancellable", f.cancellable)
    }

    fun setText(body: JsonElement?): LinkApi.Reply {
        val o = body as? JsonObject ?: return bad("Send the field's id and text.")
        val id = o.int("id") ?: return bad("Send the field's id and text.")
        val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return bad("Send the field's id and text.")
        if (text.length > RemoteInput.MAX_TEXT) return bad("That is too long for this field.")
        return if (RemoteInput.setText(id, text)) ok() else gone()
    }

    fun submit(body: JsonElement?): LinkApi.Reply {
        val id = (body as? JsonObject)?.int("id") ?: return bad("Send the field's id.")
        return if (RemoteInput.submit(id)) ok() else gone()
    }

    fun cancel(body: JsonElement?): LinkApi.Reply {
        val id = (body as? JsonObject)?.int("id") ?: return bad("Send the field's id.")
        val f = RemoteInput.field.value
        if (f != null && f.id == id && !f.cancellable) return LinkApi.Reply(error("This field stays open on the device."), HttpStatusCode.Conflict)
        return if (RemoteInput.cancel(id)) ok() else gone()
    }

    fun pad(body: JsonElement?, allowed: Boolean): LinkApi.Reply {
        if (!allowed) return LinkApi.Reply(error("Using a phone as a controller is off on the device."), HttpStatusCode.Forbidden)
        val o = body as? JsonObject ?: return bad("Send a button and whether it is down.")
        val name = (o["button"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val button = buttons[name] ?: return bad("Unknown button.")
        val down = (o["down"] as? JsonPrimitive)?.booleanOrNull ?: return bad("Send a button and whether it is down.")
        RemoteInput.pad(button, down)
        return ok()
    }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun ok() = LinkApi.Reply(buildJsonObject { put("ok", true) })

    private fun gone() = LinkApi.Reply(error("That field has closed on the device."), HttpStatusCode.Conflict)

    private fun bad(message: String) = LinkApi.Reply(error(message), HttpStatusCode.BadRequest)

    private fun error(message: String) = buildJsonObject { put("error", message) }
}
