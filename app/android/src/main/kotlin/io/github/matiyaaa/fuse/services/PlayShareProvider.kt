package io.github.matiyaaa.fuse.services

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.github.matiyaaa.fuse.data.db.AndroidDatabase

/**
 * Fuse's play sessions for Cartridge, so Cartridge's Start screen (Continue playing, Recently played, This
 * week, play time) and its RomM play sessions count games played from Fuse too. Read-only:
 * `content://io.github.matiyaaa.fuse.play/sessions?since=<epoch ms>`, one row per session Fuse saw (launch
 * to return, as [io.github.matiyaaa.fuse.model.PlaySession] records it), newest first, at most 2000.
 * The contract is Cartridge's `docs/FUSE_BRIDGE.md` ("Play sessions from Fuse").
 *
 * Each row carries only what Cartridge needs to find the game in its own library: the RomM rom id Cartridge
 * reported (when it downloaded the game), the file path, the titles and the platform. No settings,
 * accounts, collections or anything else of the library is shared.
 *
 * Behind [READ_PLAY]. A normal permission defined by Fuse is only granted to an app installed after Fuse,
 * so Fuse also grants Cartridge read access itself at every start ([grantToCartridge]), as Cartridge does
 * for Fuse's access to its status provider.
 *
 * The database is read with Android's own SQLite, read-only, per query: the provider can be asked before
 * [io.github.matiyaaa.fuse.FuseApplication] has opened it, and it never writes.
 */
class PlayShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.pathSegments.firstOrNull() != SESSIONS) return null
        val since = uri.getQueryParameter("since")?.toLongOrNull() ?: 0L
        val out = MatrixCursor(COLUMNS)
        val file = context?.getDatabasePath(AndroidDatabase.DEFAULT_NAME) ?: return out
        if (!file.isFile) return out
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(SQL, arrayOf(since.toString())).use { c ->
                    while (c.moveToNext()) {
                        out.addRow(
                            arrayOf<Any?>(
                                c.getLong(0),
                                if (c.isNull(1)) null else c.getLong(1),
                                c.getString(2),
                                c.getString(3),
                                c.getString(4),
                                c.getString(5),
                                c.getString(6),
                                c.getLong(7),
                                if (c.isNull(8)) null else c.getLong(8),
                                c.getString(9),
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // An older database (before play sessions) or one being migrated right now: nothing this time.
        }
        return out
    }

    override fun getType(uri: Uri): String? =
        if (uri.pathSegments.firstOrNull() == SESSIONS) "vnd.android.cursor.dir/vnd.io.github.matiyaaa.fuse.play.session" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")

    companion object {
        const val READ_PLAY = "io.github.matiyaaa.fuse.permission.READ_PLAY"
        private const val SESSIONS = "sessions"

        /** Cartridge's package (its Android build keeps the upstream application id). */
        private val CARTRIDGE_PACKAGES = listOf("io.github.abdu2304.cartridge")

        val COLUMNS = arrayOf("session_id", "rom_id", "path", "launch_path", "title", "title_original", "platform", "started_at", "ended_at", "source")

        // The title as Fuse shows it (GameTitles.display): custom, else metadata, else the cleaned one when
        // Clean Display Names is on, else the file's own name.
        private const val SQL = """
            SELECT s.id, g.romm_rom_id, g.path, g.launch_path,
                COALESCE(g.title_custom, g.title_metadata, CASE WHEN g.use_cleaned = 1 THEN g.title_cleaned END, g.title_original),
                g.title_original, COALESCE(g.platform_override, g.platform_id), s.started_at, s.ended_at, s.source
            FROM play_session s JOIN game g ON g.id = s.game_id
            WHERE s.started_at >= ?
            ORDER BY s.started_at DESC
            LIMIT 2000
        """

        /**
         * Lets Cartridge read the provider even when it was installed before Fuse (see the class comment).
         * Called at every process start; a grant for a package that isn't installed is simply refused.
         */
        fun grantToCartridge(context: Context) {
            val root = Uri.parse("content://${context.packageName}.play/")
            for (pkg in CARTRIDGE_PACKAGES) {
                try {
                    context.grantUriPermission(pkg, root, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                } catch (e: Exception) {
                    // Cartridge isn't installed.
                }
            }
        }
    }
}
