package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.backup.BackupContent
import io.github.matiyaaa.fuse.data.backup.BackupFile
import io.github.matiyaaa.fuse.data.backup.BackupManifest
import io.github.matiyaaa.fuse.data.backup.BackupPart
import io.github.matiyaaa.fuse.data.backup.BackupRead
import io.github.matiyaaa.fuse.data.backup.RestoreReport
import io.github.matiyaaa.fuse.ui.shell.store.BackupMade
import io.github.matiyaaa.fuse.ui.shell.store.BackupOpened
import io.github.matiyaaa.fuse.ui.shell.store.BackupOps
import io.github.matiyaaa.fuse.ui.shell.store.BackupPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Backups over [io.github.matiyaaa.fuse.data.backup.BackupRepository]. Pictures the user chose go
 * in the file (each once, under `media/`); restored ones are written below Fuse's own data folder.
 * [settingsChange] runs a restore while the store holds its settings writes, then reloads them, so a
 * change still on its way can't put the old settings back afterwards.
 */
internal class DefaultBackupOps(
    private val ctx: StoreContext,
    private val settingsChange: suspend (suspend () -> RestoreReport) -> RestoreReport,
) : BackupOps {
    private val repo get() = ctx.data.backup

    /** The backup made just before this session's last restore. */
    private var before: BackupContent? = null
    private val undoable = MutableStateFlow(false)
    override val canUndo: StateFlow<Boolean> = undoable

    override suspend fun create(): BackupMade? = guarded {
        val content = repo.export()
        val paths = LinkedHashMap<String, String>()
        val pictures = LinkedHashMap<String, ByteArray>()
        val media = content.media.mapNotNull { m ->
            val path = m.file ?: return@mapNotNull m
            val name = paths[path] ?: ctx.services.readFile(path, BackupFile.MAX_PICTURE_BYTES)?.let { bytes ->
                BackupFile.pictureName(paths.size, path).also {
                    paths[path] = it
                    pictures[it] = bytes
                }
            }
            // A picture that can't be read (or is huge) keeps its link when it has one.
            when {
                name != null -> m.copy(file = name)
                m.remoteUrl != null -> m.copy(file = null)
                else -> null
            }
        }
        val packed = content.copy(media = media)
        val now = ctx.now()
        val manifest = BackupManifest(
            fuseVersion = ctx.services.appVersion,
            host = ctx.host.name,
            createdAt = now,
            games = packed.games.size,
            collections = packed.collections.size,
            sessions = packed.sessions.size,
            media = packed.media.size,
        )
        val bytes = withContext(Dispatchers.Default) { BackupFile.write(manifest, packed, pictures) }
        val day = (now + ctx.services.utcOffsetMillis()).floorDiv(TimeWords.DAY_MS)
        BackupMade(
            name = "Fuse backup ${TimeWords.isoDate(day)}.${BackupFile.EXTENSION}",
            bytes = bytes,
            games = manifest.games,
            collections = manifest.collections,
            sessions = manifest.sessions,
            pictures = manifest.media,
        )
    }

    override suspend fun open(bytes: ByteArray): BackupOpened {
        val read = withContext(Dispatchers.Default) { BackupFile.read(bytes) }
        val archive = when (read) {
            is BackupRead.Failed -> return BackupOpened.Failed(read.problem)
            is BackupRead.Opened -> read.archive
        }
        val (here, _) = guarded { repo.matches(archive.content) } ?: (0 to archive.content.games.size)
        val m = archive.manifest
        return BackupOpened.Ready(
            BackupPreview(
                archive = archive,
                createdAt = m.createdAt,
                fuseVersion = m.fuseVersion,
                host = m.host,
                games = archive.content.games.size,
                gamesHere = here,
                collections = archive.content.collections.size,
                sessions = archive.content.sessions.size,
                pictures = archive.content.media.size,
                hasSettings = archive.content.settings != null,
                newer = archive.newer,
            ),
        )
    }

    override suspend fun restore(backup: BackupPreview, parts: Set<BackupPart>): RestoreReport? = guarded {
        // How things are now, kept first, so settings, look and Home can go back.
        val previous = repo.export()
        val files = if (BackupPart.LIBRARY in parts) placePictures(backup) else emptyMap()
        val report = settingsChange { repo.restore(backup.archive.content, parts, files) }
        before = previous
        undoable.value = BackupPart.SETTINGS in parts || BackupPart.APPEARANCE in parts
        report
    }

    override suspend fun undo(): Boolean {
        val previous = before ?: return false
        guarded { settingsChange { repo.restore(previous, setOf(BackupPart.SETTINGS, BackupPart.APPEARANCE)) } } ?: return false
        before = null
        undoable.value = false
        return true
    }

    /** Writes the backup's pictures below Fuse's data folder; their names there, by their names in the backup. */
    private suspend fun placePictures(backup: BackupPreview): Map<String, String> {
        val stamp = backup.createdAt
        return backup.archive.media.mapNotNull { (name, bytes) ->
            val ext = name.substringAfterLast('.', "img")
            val index = name.substringAfterLast('/').substringBefore('.')
            ctx.services.keepFile("media/restored/$stamp-$index.$ext", bytes)?.let { name to it }
        }.toMap()
    }

    private suspend fun <T> guarded(work: suspend () -> T): T? = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
