package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.backup.BackupRepository
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.repo.AppOverrideRepository
import io.github.matiyaaa.fuse.data.repo.CacheRepository
import io.github.matiyaaa.fuse.data.repo.CollectionRepository
import io.github.matiyaaa.fuse.data.repo.FolderStateRepository
import io.github.matiyaaa.fuse.data.repo.GameRepository
import io.github.matiyaaa.fuse.data.repo.LibraryIndexer
import io.github.matiyaaa.fuse.data.repo.LibrarySourceRepository
import io.github.matiyaaa.fuse.data.repo.MediaRepository
import io.github.matiyaaa.fuse.data.repo.ProfileStateRepository
import io.github.matiyaaa.fuse.data.repo.OwnedChangesRepository
import io.github.matiyaaa.fuse.data.repo.PlaySessionRepository
import io.github.matiyaaa.fuse.data.repo.TitleCleanupRepository
import io.github.matiyaaa.fuse.data.search.SearchRepository
import io.github.matiyaaa.fuse.data.settings.ScopedSettingsRepository
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Every repository over one [FuseDatabase], created lazily. Open the database with
 * `AndroidDatabase` or `DesktopDatabase` and keep one instance for the app's lifetime.
 */
class FuseData(
    val database: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    val sources by lazy { LibrarySourceRepository(database, dispatcher, clock) }
    val games by lazy { GameRepository(database, dispatcher, clock) }
    val indexer by lazy { LibraryIndexer(database, dispatcher) }
    val folderState by lazy { FolderStateRepository(database, dispatcher) }
    val media by lazy { MediaRepository(database, dispatcher, clock) }
    val playSessions by lazy { PlaySessionRepository(database, dispatcher, clock) }
    val collections by lazy { CollectionRepository(database, dispatcher, clock) }
    val apps by lazy { AppOverrideRepository(database, dispatcher) }
    val cache by lazy { CacheRepository(database, dispatcher) }
    val titleCleanup by lazy { TitleCleanupRepository(database, dispatcher, clock) }
    val settings by lazy { SettingsStore(database, dispatcher, clock) }
    val scopedSettings by lazy { ScopedSettingsRepository(database, settings, dispatcher, clock) }
    val backup by lazy { BackupRepository(database, dispatcher, clock) }
    val search by lazy { SearchRepository(database, dispatcher) }
    val owned by lazy { OwnedChangesRepository(database, dispatcher, clock) }
    val profileState by lazy { ProfileStateRepository(database, dispatcher, clock) }
}
