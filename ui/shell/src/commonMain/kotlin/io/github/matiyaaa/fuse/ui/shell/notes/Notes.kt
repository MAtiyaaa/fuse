package io.github.matiyaaa.fuse.ui.shell.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * A line of release notes: a heading, a list item or plain text. A list item that opens with a bold
 * phrase ("**Search in Settings.** A search row...") keeps the phrase apart as its [lead].
 */
internal data class NoteLine(val text: String, val kind: Kind, val lead: String? = null) {
    enum class Kind { HEADING, ITEM, TEXT }
}

/** One part of release notes: a heading ("New", "Changed", "Fixed") and what is under it. */
internal data class NoteSection(val title: String?, val lines: List<NoteLine>)

private val image = Regex("""!\[[^\]]*\]\([^)]*\)""")
private val link = Regex("""\[([^\]]*)\]\([^)]*\)""")
private val marks = Regex("""(\*\*|__|`|~~)""")
private val tag = Regex("""<[^>]+>""")
private val leadPattern = Regex("""^\*\*(.+?)\*\*\s*(.*)$""")
private val numbered = Regex("""^\d+[.)] """)

private fun plain(s: String) = s.replace(tag, "").replace(image, "").replace(link) { it.groupValues[1] }.replace(marks, "").trim()

/**
 * Release notes (Markdown, as GitHub and docs/releases keep them) as plain lines: headings and list
 * items kept as such, a list item's wrapped lines joined back to it, links as their text, pictures
 * and markup left out. A table's rows become items, each led by its first cell with the others named
 * by their column ("1 tweens: Fuseline 3 0.82 µs, Fuseline 2 0.90 µs"); code blocks (commands for
 * developers) are left out. At most [max] lines.
 */
internal fun noteLines(markdown: String, max: Int = Int.MAX_VALUE): List<NoteLine> {
    val out = mutableListOf<NoteLine>()
    // A paragraph or list item is read whole first, so text wrapped over several lines stays one.
    var open: StringBuilder? = null
    var openKind = NoteLine.Kind.TEXT
    var inCode = false
    var header: List<String>? = null
    fun close() {
        val raw = open?.toString()?.trim() ?: return
        open = null
        if (raw.isEmpty()) return
        // A bold phrase that runs on into its sentence ("**Add any app**, on any system") stays in it.
        val m = (if (openKind == NoteLine.Kind.ITEM) leadPattern.find(raw) else null)?.takeUnless { it.groupValues[2].firstOrNull() in listOf(',', ';', ')') }
        val lead = m?.groupValues?.get(1)?.let(::plain)?.trimEnd('.', ':')?.takeIf { it.isNotEmpty() }
        val text = plain(if (m != null) m.groupValues[2] else raw)
        if (text.isNotEmpty() || lead != null) out += NoteLine(text, openKind, lead)
    }
    for (rawLine in markdown.lineSequence()) {
        val line = rawLine.trimEnd()
        val t = line.trim()
        if (t.startsWith("```")) {
            close()
            inCode = !inCode
            continue
        }
        if (inCode) continue
        if (!t.startsWith("|")) header = null
        when {
            t.startsWith("|") -> {
                close()
                val cells = t.trim('|').split('|').map { plain(it) }
                val columns = header
                when {
                    columns == null -> header = cells
                    cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } } -> Unit
                    else -> {
                        val named = cells.drop(1).mapIndexedNotNull { i, c ->
                            val name = columns.getOrNull(i + 1).orEmpty()
                            c.takeIf { it.isNotEmpty() }?.let { if (name.isEmpty()) it else "$name $it" }
                        }
                        out += NoteLine(named.joinToString(", "), NoteLine.Kind.ITEM, cells.firstOrNull()?.takeIf { it.isNotEmpty() })
                    }
                }
            }
            t.isEmpty() -> close()
            t.all { it == '-' || it == '=' || it == '*' || it == '_' } -> close()
            t.startsWith("#") -> {
                close()
                plain(t.trimStart('#')).takeIf { it.isNotEmpty() }?.let { out += NoteLine(it, NoteLine.Kind.HEADING) }
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") || numbered.containsMatchIn(t) -> {
                close()
                open = StringBuilder(if (numbered.containsMatchIn(t)) t.substringAfter(' ') else t.drop(2))
                openKind = NoteLine.Kind.ITEM
            }
            open != null -> open!!.append(' ').append(t)
            else -> {
                open = StringBuilder(t)
                openKind = NoteLine.Kind.TEXT
            }
        }
    }
    close()
    return out.take(max)
}

/**
 * Release notes in their parts, for the release notes page: what comes before the first heading is
 * the opening part (no title); the notes' own title ("# Fuse 0.2.7 - The Swap & Clean Update") is
 * left out, since the page shows it larger.
 */
internal fun noteSections(markdown: String): List<NoteSection> {
    val body = markdown.lineSequence().dropWhile { it.isBlank() }.toList().let { lines ->
        if (lines.firstOrNull()?.startsWith("# ") == true) lines.drop(1) else lines
    }.joinToString("\n")
    val sections = mutableListOf<NoteSection>()
    var title: String? = null
    var lines = mutableListOf<NoteLine>()
    for (l in noteLines(body)) {
        if (l.kind == NoteLine.Kind.HEADING) {
            if (lines.isNotEmpty() || title != null) sections += NoteSection(title, lines)
            title = l.text
            lines = mutableListOf()
        } else {
            lines += l
        }
    }
    if (lines.isNotEmpty() || title != null) sections += NoteSection(title, lines)
    return sections.filter { it.lines.isNotEmpty() }
}

/** Release notes in a quiet well: headings, items with a dot, and text, as an app's Store page shows them. */
@Composable
internal fun NotesWell(lines: List<NoteLine>, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.text.copy(alpha = if (c.isDark) 0.04f else 0.03f)).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        for (l in lines) when (l.kind) {
            NoteLine.Kind.HEADING -> FText(l.text, Fuse.type.bodyStrong, maxLines = 2, modifier = Modifier.padding(top = Space.s))
            NoteLine.Kind.ITEM -> Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 9.dp, end = Space.s).size(5.dp).clip(CircleShape).background(c.textMuted))
                FText(listOfNotNull(l.lead?.let { "$it." }, l.text.ifEmpty { null }).joinToString(" "), Fuse.type.body, color = c.text.copy(alpha = 0.86f), maxLines = 4)
            }
            NoteLine.Kind.TEXT -> FText(l.text, Fuse.type.body, color = c.text.copy(alpha = 0.86f), maxLines = 6)
        }
    }
}
