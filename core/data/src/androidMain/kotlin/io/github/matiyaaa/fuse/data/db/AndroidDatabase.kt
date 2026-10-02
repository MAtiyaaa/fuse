package io.github.matiyaaa.fuse.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Opens Fuse's database on Android. SQLiteOpenHelper tracks the schema version and runs
 * [FuseDatabase.Schema] create/migrate; a downgrade fails instead of wiping data. WAL is enabled in
 * `onConfigure`, and foreign keys in `onOpen`, after any migration (migrations may rebuild tables).
 */
object AndroidDatabase {
    const val DEFAULT_NAME = "fuse.db"

    fun open(context: Context, name: String = DEFAULT_NAME): FuseDatabase = FuseDatabase(driver(context, name))

    fun driver(context: Context, name: String = DEFAULT_NAME): SqlDriver {
        backupBeforeMigrating(context.applicationContext, name)
        return openDriver(context, name)
    }

    /**
     * Before an older database is migrated, a copy of it (and its write-ahead log) is kept beside it
     * as `<name>.before-v<version>.bak`, so a migration that goes wrong can never cost the library.
     * Nothing has the database open yet, so copying the files is a consistent snapshot.
     */
    private fun backupBeforeMigrating(context: Context, name: String) {
        val file = context.getDatabasePath(name)
        if (!file.isFile) return
        val version = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { it.version.toLong() }
        } catch (e: Exception) {
            return
        }
        val target = FuseDatabase.Schema.version
        if (version <= 0L || version >= target) return
        val backup = java.io.File(file.path + ".before-v$target.bak")
        try {
            file.copyTo(backup, overwrite = true)
            val wal = java.io.File(file.path + "-wal")
            val backupWal = java.io.File(backup.path + "-wal")
            if (wal.isFile) wal.copyTo(backupWal, overwrite = true) else backupWal.delete()
        } catch (e: java.io.IOException) {
            // Out of space: SQLiteOpenHelper still migrates in a transaction, which rolls back on failure.
            backup.delete()
        }
    }

    private fun openDriver(context: Context, name: String): SqlDriver = AndroidSqliteDriver(
        schema = FuseDatabase.Schema,
        context = context.applicationContext,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(FuseDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.enableWriteAheadLogging()
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        },
    )
}
