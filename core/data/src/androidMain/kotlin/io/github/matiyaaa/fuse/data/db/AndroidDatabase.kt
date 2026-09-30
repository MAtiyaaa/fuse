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

    fun driver(context: Context, name: String = DEFAULT_NAME): SqlDriver = AndroidSqliteDriver(
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
