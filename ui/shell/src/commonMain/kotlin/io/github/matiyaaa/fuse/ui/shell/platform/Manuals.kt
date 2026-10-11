package io.github.matiyaaa.fuse.ui.shell.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Opens game manuals kept beside games (a PDF, or a CBZ of page pictures) and draws their pages,
 * from the file itself: nothing is fetched, so a manual reads offline.
 */
interface ManualReader {
    /** The manual at [path], or why it can't be read here. */
    suspend fun open(path: String): ManualOpened
}

/** What opening a manual came to. */
sealed interface ManualOpened {
    class Ready(val document: ManualDocument) : ManualOpened

    /** It can't be read: [reason] in words for the person. */
    data class Unreadable(val reason: String) : ManualOpened
}

/** An open manual: its pages, drawn on request. Closed when the viewer leaves. */
interface ManualDocument {
    val pageCount: Int

    /**
     * Page [index] drawn to fit within [maxWidthPx] by [maxHeightPx] (its own shape kept), or null
     * if that page can't be drawn.
     */
    suspend fun page(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap?

    fun close()
}

/** The file kinds the reader opens. */
val ManualExtensions = setOf("pdf", "cbz")
