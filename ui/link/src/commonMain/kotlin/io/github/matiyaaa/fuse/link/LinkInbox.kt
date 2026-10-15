package io.github.matiyaaa.fuse.link

/**
 * Where files a phone sends through Phone Link are gathered, one delivery at a time: a Switch's
 * save backups (a JKSV folder, Checkpoint's saves folder, or ZIPs of them), shared from the phone,
 * for Fuse to read like a card. A delivery is read only once it is whole ([finish]); paths can't
 * leave it, and it is bounded in files and bytes.
 */
interface LinkInbox {
    /** A new delivery's id. */
    fun begin(): String

    /** [path] (relative, '/'-separated) of [delivery], [size] bytes, open for writing; null when the path or size isn't allowed. */
    fun open(delivery: String, path: String, size: Long): LinkSink?

    /** The delivery, whole: where to read it from (its one folder or one ZIP, else the delivery), or null when nothing came. */
    fun finish(delivery: String): String?
}

/** One file of a delivery, being written. */
interface LinkSink {
    fun write(bytes: ByteArray, count: Int)

    /** Done: true when it holds the size it was opened for; a short file is removed. */
    fun close(): Boolean
}
