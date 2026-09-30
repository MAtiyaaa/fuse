package io.github.matiyaaa.fuse.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import java.io.File

/** Mounted shared-storage volumes (internal storage, SD cards, USB drives) as [Volume]s. */
class StorageVolumes(context: Context) {
    private val appContext = context.applicationContext
    private val storage = appContext.getSystemService(StorageManager::class.java)

    /**
     * Whether Fuse can read every file on shared storage: All files access on Android 11+,
     * READ_EXTERNAL_STORAGE before.
     */
    fun hasFullAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Internal shared storage for this user, `/storage/emulated/<user>`. */
    val primaryRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    /** Mounted volumes, internal storage first. Queried on every call because cards come and go. */
    fun mounted(): List<Volume> {
        val primary = Volume(primaryRoot, StoragePaths.PRIMARY_ID, isPrimary = true)
        val others = try {
            storage?.storageVolumes.orEmpty().mapNotNull { v -> v.toVolume() }
        } catch (e: SecurityException) {
            emptyList()
        }
        return listOf(primary) + others.filter { !it.isPrimary && it.root != primary.root }.distinctBy { it.root }
    }

    private fun StorageVolume.toVolume(): Volume? {
        if (isPrimary) return null
        val mountState = state
        if (mountState != Environment.MEDIA_MOUNTED && mountState != Environment.MEDIA_MOUNTED_READ_ONLY) return null
        val id = uuid ?: return null
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            directory?.absolutePath ?: "/storage/$id"
        } else {
            "/storage/$id"
        }
        if (!File(root).exists()) return null
        return Volume(root, id, isPrimary = false)
    }
}
