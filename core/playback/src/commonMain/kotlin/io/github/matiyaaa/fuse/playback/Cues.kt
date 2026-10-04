package io.github.matiyaaa.fuse.playback

/** A run of subtitle text in one style. [color] is ARGB; null takes the cue's or the reader's. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Long? = null,
)

/**
 * How a text cue looks where its file says so (ASS styles, WebVTT and SRT tags). Sizes are fractions
 * of the picture's height, so a cue looks the same at any size; null leaves it to the reader's
 * settings (size, colour and outline in the player's subtitle settings).
 */
data class CueStyle(
    val size: Float? = null,
    val color: Long? = null,
    val outlineColor: Long? = null,
    /** Outline width as a fraction of the picture's height. */
    val outline: Float? = null,
    val shadow: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Distance from the edge it is aligned to, as a fraction of the picture's height. */
    val marginV: Float? = null,
    /** A filled box behind the text (ASS border style 3). */
    val box: Long? = null,
)

/** Something to show from [startMs] until [endMs] on the item's own clock. */
sealed interface Cue {
    val startMs: Long
    val endMs: Long
}

/**
 * Lines of text. [align] is a numpad position (1 bottom left to 9 top right, 2 bottom centre, the
 * usual); [posX] and [posY] (fractions of the picture) pin it to a point instead, as ASS `\pos` does.
 */
data class TextCue(
    override val startMs: Long,
    override val endMs: Long,
    val lines: List<List<Span>>,
    val align: Int = 2,
    val posX: Float? = null,
    val posY: Float? = null,
    val style: CueStyle = CueStyle(),
    /** Later layers draw over earlier ones (ASS). */
    val layer: Int = 0,
) : Cue {
    val plainText: String get() = lines.joinToString("\n") { line -> line.joinToString("") { it.text } }
}

/**
 * A subtitle that comes as a picture (Blu-ray PGS, DVD): ARGB [pixels], [width] by [height], placed
 * at [left], [top] with [right] and [bottom] as fractions of the picture.
 */
class BitmapCue(
    override val startMs: Long,
    override val endMs: Long,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) : Cue

/**
 * A subtitle file's cues, ordered, answering "what shows at this moment" quickly however long the
 * film. Cues may overlap (two speakers, a sign and a line).
 */
class CueTimeline(cues: List<Cue>) {
    val cues: List<Cue> = cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))

    /** The latest end among the cues up to each index, so a search can stop as soon as nothing earlier can still show. */
    private val reach = LongArray(this.cues.size).also { r ->
        var m = Long.MIN_VALUE
        this.cues.forEachIndexed { i, c ->
            m = maxOf(m, c.endMs)
            r[i] = m
        }
    }

    /** The cues showing at [ms] (start inclusive, end exclusive), in file order. */
    fun at(ms: Long): List<Cue> {
        // The last cue that has started.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= ms) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (last < 0) return emptyList()
        val out = ArrayList<Cue>(2)
        var i = last
        while (i >= 0 && reach[i] > ms) {
            val c = cues[i]
            if (c.endMs > ms) out += c
            i--
        }
        out.reverse()
        return out
    }

    val isEmpty: Boolean get() = cues.isEmpty()

    companion object {
        val EMPTY = CueTimeline(emptyList())
    }
}
