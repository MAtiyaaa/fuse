package io.github.matiyaaa.fuse.launch.extras

import io.github.matiyaaa.fuse.launch.achievements.RaCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * RPCS3's patch file from rpcs3.net, fetched as RPCS3's Patch Manager fetches it
 * (rpcs3/rpcs3qt/patch_manager_dialog.cpp, `download_update` and `handle_json`): asked for with its
 * patch engine's version (1.2, Utilities/bin_patch.h `patch_engine_version`) and the SHA-256 of the
 * `patches/patch.yml` already there, so the site can answer that nothing is newer. Its answer is
 * JSON: `return_code` 0 with `version`, `sha256` and the whole `patch` file, 1 when there is
 * nothing newer, or below 0 for a failure. The patch is kept only when its version is the engine's
 * and its text has the SHA-256 the site gave. RPCS3 keeps the file it replaces as `patch.yml.old`.
 */
object Rpcs3PatchFile {
    /** RPCS3's patch engine version the site's patches are for. */
    const val ENGINE_VERSION = "1.2"
    const val FILE = "patch.yml"
    const val OLD = "patch.yml.old"

    /** What rpcs3.net said. */
    sealed interface Answer {
        /** A newer patch file, checked against its SHA-256. */
        data class Newer(val text: String) : Answer

        /** The file here is the latest. */
        data object UpToDate : Answer

        /** It couldn't be had, in words for the person. */
        data class Failed(val reason: String) : Answer
    }

    /** The address to ask, with the SHA-256 of the patch file already there when there is one. */
    fun url(current: String?): String =
        "https://rpcs3.net/compatibility?patch&api=v1&v=$ENGINE_VERSION" + (current?.let { "&sha256=" + sha256Hex(it) } ?: "")

    /** The lower-case hexadecimal SHA-256 of [text]'s UTF-8 bytes, as RPCS3 compares them. */
    fun sha256Hex(text: String): String = RaCrypto.sha256(text.encodeToByteArray()).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** What the site's [body] says (see [Rpcs3PatchFile]). */
    fun read(body: String): Answer {
        val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return Answer.Failed("rpcs3.net's answer wasn't readable.")
        return when (val code = o["return_code"]?.jsonPrimitive?.intOrNull ?: -255) {
            1 -> Answer.UpToDate
            0 -> {
                val version = o["version"]?.jsonPrimitive?.contentOrNull
                val hash = o["sha256"]?.jsonPrimitive?.contentOrNull
                val patch = o["patch"]?.jsonPrimitive?.contentOrNull
                when {
                    version != ENGINE_VERSION -> Answer.Failed("rpcs3.net's patches are for a newer RPCS3. Update RPCS3, then try again.")
                    hash.isNullOrBlank() || patch.isNullOrEmpty() -> Answer.Failed("rpcs3.net's answer had no patches in it.")
                    !hash.equals(sha256Hex(patch), ignoreCase = true) -> Answer.Failed("The patches didn't arrive whole. Nothing was changed.")
                    !looksLikePatches(patch) -> Answer.Failed("What arrived isn't an RPCS3 patch file. Nothing was changed.")
                    else -> Answer.Newer(patch)
                }
            }
            -1 -> Answer.Failed("rpcs3.net has no patches for this RPCS3 yet.")
            -2 -> Answer.Failed("rpcs3.net is down for maintenance. Try again later.")
            else -> Answer.Failed("rpcs3.net couldn't send the patches (code $code).")
        }
    }

    /** A patch file has a top-level `Version` that is the engine's (Utilities/bin_patch.cpp, `patch_engine::load`). */
    private fun looksLikePatches(text: String): Boolean = VERSION_LINE.containsMatchIn(text)

    private val VERSION_LINE = Regex("^Version:\\s*[\"']?${Regex.escape(ENGINE_VERSION)}[\"']?\\s*$", RegexOption.MULTILINE)
}
