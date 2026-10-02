package io.github.matiyaaa.fuse.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume as AndroidStorageVolume
import androidx.core.content.ContextCompat
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.ui.shell.store.VolumeMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android's drives as Fuse sees them: internal shared storage and each mounted SD card or USB
 * drive, identified the way Android identifies them (the volume UUID, which also names the drive's
 * folder, `/storage/ABCD-1234`). Android tells when media is mounted or removed, so [watch] follows
 * its broadcasts instead of polling.
 */
class AndroidVolumes(context: Context) : VolumeMonitor {
    private val appContext = context.applicationContext
    private val storage = appContext.getSystemService(StorageManager::class.java)

    override suspend fun volumes(): List<StorageVolume> = withContext(Dispatchers.IO) {
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val primary = StorageVolume(
            id = PRIMARY_ID,
            label = "Internal storage",
            mountPaths = listOf(primaryRoot),
            kind = VolumeKind.INTERNAL,
            totalBytes = space(primaryRoot).second,
            freeBytes = space(primaryRoot).first,
        )
        val others = try {
            storage?.storageVolumes.orEmpty().mapNotNull { it.toModel() }
        } catch (e: SecurityException) {
            emptyList()
        }
        listOf(primary) + others.filter { it.mountPath != primaryRoot }.distinctBy { it.id }
    }

    private fun AndroidStorageVolume.toModel(): StorageVolume? {
        if (isPrimary) return null
        val mountState = state
        val readOnly = mountState == Environment.MEDIA_MOUNTED_READ_ONLY
        if (mountState != Environment.MEDIA_MOUNTED && !readOnly) return null
        val id = uuid ?: return null
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) directory?.absolutePath ?: "/storage/$id" else "/storage/$id"
        if (!File(root).exists()) return null
        val description = runCatching { getDescription(appContext) }.getOrNull()?.trim().orEmpty()
        // Android names USB drives "USB drive" or after the maker ("SanDisk USB drive"); anything
        // else removable is a card.
        val kind = when {
            description.contains("USB", ignoreCase = true) -> VolumeKind.USB
            isRemovable -> VolumeKind.SD_CARD
            else -> VolumeKind.FIXED
        }
        val (free, total) = space(root)
        return StorageVolume(
            id = "android:$id",
            label = description.ifEmpty { if (kind == VolumeKind.USB) "USB drive" else "SD card" },
            mountPaths = listOf(root),
            kind = kind,
            removable = isRemovable,
            readOnly = readOnly,
            totalBytes = total,
            freeBytes = free,
        )
    }

    private fun space(path: String): Pair<Long, Long> = try {
        val stat = StatFs(path)
        stat.availableBytes to stat.totalBytes
    } catch (e: IllegalArgumentException) {
        0L to 0L
    }

    override fun watch(onChange: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChange()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            // Media broadcasts carry the volume as a file: URI and are only matched with this scheme.
            addDataScheme("file")
        }
        // System broadcasts reach a receiver that other apps can't.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return AutoCloseable { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    private companion object {
        const val PRIMARY_ID = "android:primary"
    }
}
