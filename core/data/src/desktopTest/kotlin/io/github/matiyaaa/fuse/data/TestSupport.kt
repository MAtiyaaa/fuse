package io.github.matiyaaa.fuse.data

import app.cash.sqldelight.db.SqlDriver
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.PlatformFolderScan
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanReport
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlinx.coroutines.Dispatchers

/** In-memory database with a controllable clock. All DB work runs on one IO thread at a time. */
class TestDb(val driver: SqlDriver = DesktopDatabase.openDriver(null)) : AutoCloseable {
    val db = FuseDatabase(driver)
    var now: Long = BASE_TIME
    val dispatcher = Dispatchers.IO.limitedParallelism(1)
    val data = FuseData(db, dispatcher) { now }

    override fun close() = driver.close()

    companion object {
        /** 2026-09-28 12:00:00 UTC, a Monday. */
        const val BASE_TIME = 1_790_596_800_000L
    }
}

/** Removes bracketed tags, like the library module's cleaner does. */
val testCleaner: (String) -> String = { it.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim() }

fun scanned(
    path: String,
    title: String = path.substringAfterLast('/').substringBeforeLast('.'),
    platform: String = "gba",
    sizeBytes: Long = 100,
    modifiedAt: Long = 1,
    source: Long = 1,
) = ScannedGame(
    platformId = PlatformId(platform),
    sourceId = LibrarySourceId(source),
    path = path,
    kind = LocationKind.FILE,
    launchPath = path,
    title = title,
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
)

fun folder(
    path: String,
    games: List<ScannedGame>,
    platform: String = "gba",
    complete: Boolean = true,
    modifiedAt: Long = 10,
) = PlatformFolderScan(LibrarySourceId(1), PlatformId(platform), path, modifiedAt, games, complete)

fun report(vararg folders: PlatformFolderScan) = ScanReport(folders.toList(), emptyList(), emptyList())
