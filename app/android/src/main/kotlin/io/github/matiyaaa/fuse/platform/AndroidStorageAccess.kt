package io.github.matiyaaa.fuse.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.storage.StoragePaths
import io.github.matiyaaa.fuse.storage.StorageVolumes
import io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Storage permission and pickers. Fuse reads libraries by path, which needs All files access on
 * Android 11+ (READ_EXTERNAL_STORAGE before). Folder picks are converted to real paths on internal
 * storage or an SD card; picked images are copied into Fuse's own files so they stay readable.
 */
class AndroidStorageAccess(
    context: Context,
    private val activities: ActivityHolder,
    private val volumes: StorageVolumes,
    private val scope: CoroutineScope,
) : StorageAccess {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<StorageState> = _state.asStateFlow()

    override fun refresh() {
        _state.value = read()
    }

    private fun read(): StorageState = if (volumes.hasFullAccess()) StorageState.GRANTED else StorageState.DENIED

    override fun request() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activities.startFirst(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${appContext.packageName}")),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}")),
            )
        } else {
            val requests = activities.requests ?: return
            scope.launch(Dispatchers.Main) {
                requests.requestPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                refresh()
            }
        }
    }

    override suspend fun pickFolder(title: String): String? {
        val requests = activities.requests ?: return null
        val uri = withContext(Dispatchers.Main) { requests.pickFolder() } ?: return null
        if (uri.authority != StoragePaths.EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val path = StoragePaths.pathForDocumentId(docId, volumes.mounted()) ?: return null
        return withContext(Dispatchers.IO) { path.takeIf { File(it).isDirectory } }
    }

    override suspend fun pickImage(title: String): String? {
        val requests = activities.requests ?: return null
        val uri = withContext(Dispatchers.Main) { requests.pickImage() } ?: return null
        return withContext(Dispatchers.IO) { copyImage(uri) }
    }

    private fun copyImage(uri: Uri): String? {
        val resolver = appContext.contentResolver
        val type = resolver.getType(uri)
        val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "img"
        val dir = File(appContext.filesDir, "media/custom")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val out = File(dir, "${UUID.randomUUID()}.$ext")
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { src -> out.outputStream().use { dst -> copyLimited(src, dst) } }
            out.absolutePath
        } catch (e: IOException) {
            out.delete()
            null
        } catch (e: SecurityException) {
            out.delete()
            null
        }
    }

    /** Copies at most 64 MB, so a mislabelled huge file can't fill Fuse's storage. */
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_IMAGE_BYTES) throw IOException("Image too large")
            output.write(buffer, 0, n)
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    }
}
