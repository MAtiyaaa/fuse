package io.github.matiyaaa.fuse.services

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cartridge through its read-only status provider (Cartridge 0.9.10+) and `cartridge://` deep links.
 * Older versions, or a provider that refuses Fuse (the READ_STATUS permission is only granted when
 * Cartridge was installed before Fuse), are reported as installed without the bridge.
 */
class AndroidCartridgeBridge(
    context: Context,
    private val activities: ActivityHolder,
) : CartridgeBridge {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val resolver = appContext.contentResolver

    override suspend fun read(): CartridgeStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val info = PackageSupport.packageInfo(pm, CartridgeProtocol.PACKAGE_NAME)
            ?: return@withContext CartridgeStatus(installed = false, checkedAt = now)
        val version = info.versionName
        if (!CartridgeProtocol.supportsBridge(version)) return@withContext CartridgeProtocol.installedWithoutBridge(version, now)
        try {
            val status = rows(CartridgeProtocol.STATUS_URI).firstOrNull()
                ?: return@withContext CartridgeProtocol.installedWithoutBridge(version, now)
            val recent = rows(CartridgeProtocol.RECENT_URI)
            CartridgeProtocol.statusFromRow(status, recent, version, now)
        } catch (e: SecurityException) {
            CartridgeProtocol.installedWithoutBridge(version, now)
        } catch (e: RuntimeException) {
            // The provider crashed or went away mid-query.
            CartridgeProtocol.installedWithoutBridge(version, now)
        }
    }

    private fun rows(uri: String): List<Map<String, Any?>> {
        val cursor = resolver.query(uri.toUri(), null, null, null, null) ?: return emptyList()
        return cursor.use { c ->
            val out = ArrayList<Map<String, Any?>>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val row = HashMap<String, Any?>(c.columnCount * 2)
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        else -> null
                    }
                }
                out += row
            }
            out
        }
    }

    override fun open(route: CartridgeRoute, link: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri())
            .setPackage(CartridgeProtocol.PACKAGE_NAME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (PackageSupport.resolveActivity(pm, intent) == null) return false
        return try {
            activities.start(intent, activities.revealOptions()?.toBundle())
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Observes the status provider, and Cartridge being installed, updated or removed (which also
     * changes the status). The observer is registered again whenever the package changes, because it
     * cannot be registered while Cartridge is missing.
     */
    override fun watch(onChange: () -> Unit): AutoCloseable {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        var observing = false

        fun register() {
            if (observing) {
                try {
                    resolver.unregisterContentObserver(observer)
                } catch (e: RuntimeException) {
                    // Already gone.
                }
                observing = false
            }
            observing = try {
                resolver.registerContentObserver(CartridgeProtocol.STATUS_URI.toUri(), true, observer)
                true
            } catch (e: SecurityException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        val packages = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                register()
                onChange()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
            addDataSchemeSpecificPart(CartridgeProtocol.PACKAGE_NAME, PatternMatcher.PATTERN_LITERAL)
        }
        ContextCompat.registerReceiver(appContext, packages, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        register()

        return AutoCloseable {
            try {
                appContext.unregisterReceiver(packages)
            } catch (e: IllegalArgumentException) {
                // Not registered.
            }
            if (observing) resolver.unregisterContentObserver(observer)
            observing = false
        }
    }
}
