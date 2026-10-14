package io.github.matiyaaa.fuse.launch.patches

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One patch in a shadPS4 patch file: a `Metadata` element, which shadPS4 applies when its
 * `isEnabled` is "true" ([index] is its place among the file's Metadata elements).
 */
data class Shadps4Patch(
    val index: Int,
    val name: String,
    val note: String?,
    val author: String?,
    val appVer: String?,
    val appElf: String?,
    val enabled: Boolean,
    /** Every line is a pattern search (`mask`, `mask_jump32`): shadPS4 applies it whatever the game's version. */
    val anyVersion: Boolean,
)

/**
 * shadPS4's patches, as its memory patcher reads them (src/common/memory_patcher.cpp): each folder in
 * `user/patches` is a patch repository whose `files.json` names, for each XML file, the serials it is
 * for; the first file naming the game's serial is read, and each `Metadata` in it with
 * `isEnabled="true"`, made for the game's `eboot.bin` and its `APP_VER`, is applied (pattern patches
 * whatever the version). shadPS4's patch manager turns one on or off by that attribute, and so does
 * Fuse, changing nothing else in the file.
 */
object Shadps4Patches {
    const val EBOOT = "eboot.bin"

    /** The XML file in a repository's [filesJson] that is for [serial], or null. */
    fun fileFor(filesJson: String, serial: String): String? {
        val root = runCatching { Json.parseToJsonElement(filesJson) as? JsonObject }.getOrNull() ?: return null
        return root.entries.firstOrNull { (_, serials) ->
            (serials as? JsonArray)?.any { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() == serial } == true
        }?.key
    }

    /** The patches in [xml], in file order. */
    fun parse(xml: String): List<Shadps4Patch> = metadata(xml).mapIndexed { i, m ->
        val body = m.body
        val types = LINE.findAll(body).map { attributes(it.value)["Type"].orEmpty() }.toList()
        val a = m.attributes
        Shadps4Patch(
            index = i,
            name = a["Name"]?.takeIf { it.isNotBlank() } ?: "Patch ${i + 1}",
            note = a["Note"]?.takeIf { it.isNotBlank() },
            author = a["Author"]?.takeIf { it.isNotBlank() },
            appVer = a["AppVer"]?.takeIf { it.isNotBlank() },
            appElf = a["AppElf"]?.takeIf { it.isNotBlank() },
            enabled = a["isEnabled"] == "true",
            anyVersion = types.isNotEmpty() && types.all { it == "mask" || it == "mask_jump32" },
        )
    }

    /** Whether shadPS4 would apply [p] to a game at [appVersion] (unknown: every version). */
    fun applies(p: Shadps4Patch, appVersion: String?): Boolean =
        (p.appElf == null || p.appElf == EBOOT) && (appVersion == null || p.anyVersion || p.appVer == null || p.appVer == appVersion)

    /** [xml] with patch [index]'s `isEnabled` set to [on], everything else as it was; null when there is no such patch. */
    fun setEnabled(xml: String, index: Int, on: Boolean): String? {
        val m = metadata(xml).getOrNull(index) ?: return null
        val tag = xml.substring(m.tagStart, m.tagEnd)
        val value = if (on) "true" else "false"
        val existing = ENABLED.find(tag)
        val changed = if (existing != null) {
            val q = existing.groupValues[1]
            tag.replaceRange(existing.range, "isEnabled=$q$value$q")
        } else {
            val close = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
            tag.substring(0, close).trimEnd() + " isEnabled=\"$value\"" + tag.substring(close)
        }
        return xml.substring(0, m.tagStart) + changed + xml.substring(m.tagEnd)
    }

    private class Meta(val tagStart: Int, val tagEnd: Int, val attributes: Map<String, String>, val body: String)

    private fun metadata(xml: String): List<Meta> {
        val clean = blankComments(xml)
        return META.findAll(clean).map { m ->
            val end = m.range.last + 1
            val selfClosing = m.value.endsWith("/>")
            val bodyEnd = if (selfClosing) end else clean.indexOf("</Metadata>", end).let { if (it < 0) clean.length else it }
            Meta(m.range.first, end, attributes(m.value), if (selfClosing) "" else clean.substring(end, bodyEnd))
        }.toList()
    }

    /** Comments blanked out with spaces, so offsets stay those of [xml]. */
    private fun blankComments(xml: String): String {
        val sb = StringBuilder(xml)
        var at = 0
        while (true) {
            val start = xml.indexOf("<!--", at)
            if (start < 0) break
            val end = xml.indexOf("-->", start + 4).let { if (it < 0) xml.length else it + 3 }
            for (i in start until end) if (sb[i] != '\n') sb.setCharAt(i, ' ')
            at = end
        }
        return sb.toString()
    }

    private fun attributes(tag: String): Map<String, String> =
        ATTRIBUTE.findAll(tag).associate { it.groupValues[1] to unescape(it.groupValues[3]) }

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private val META = Regex("""<Metadata\b[^>]*>""")
    private val LINE = Regex("""<Line\b[^>]*>""")
    private val ATTRIBUTE = Regex("""([A-Za-z_][\w.\-]*)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)
    private val ENABLED = Regex("""\bisEnabled\s*=\s*(["'])(.*?)\1""")
}
