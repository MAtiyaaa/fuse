package io.github.matiyaaa.fuse

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.matiyaaa.fuse.capture.AndroidCaptureLibrary
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.AndroidDatabase
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.FuseHttpConfig
import io.github.matiyaaa.fuse.link.PhoneLinkServer
import io.github.matiyaaa.fuse.platform.AndroidPlatformUi
import io.github.matiyaaa.fuse.platform.AppIconFetcher
import io.github.matiyaaa.fuse.services.AndroidFuseServices
import io.github.matiyaaa.fuse.ui.shell.platform.fuseImageLoader
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where app start-up is. Activities show a plain splash until [Ready]. */
sealed interface Startup {
    data object Loading : Startup
    data class Ready(
        val store: FuseStore,
        val phoneLink: PhoneLinkControl? = null,
        val safeMode: io.github.matiyaaa.fuse.ui.shell.app.SafeMode? = null,
    ) : Startup
    data class Failed(val message: String) : Startup
}

/**
 * Owns everything that lives as long as the process: the database, the HTTP client, the platform
 * services, the image loader and the store. The store is created once, in the background, and
 * published through [startup].
 */
class FuseApplication : Application(), SingletonImageLoader.Factory {
    /** The last crash and recent background errors, in private files (see [CrashLog]). */
    val crashLog: CrashLog by lazy { CrashLog(java.io.File(filesDir, "crash"), BuildConfig.VERSION_NAME) }

    /**
     * App-wide scope handed to the store. A failing background task is logged by type only (messages
     * can contain request URLs with API keys), recorded in [crashLog] and does not take Fuse down.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.w(TAG, "Background task failed: ${t.javaClass.name}")
            crashLog.recordNonFatal(t)
        },
    )

    lateinit var activities: ActivityHolder
        private set
    lateinit var http: HttpClient
        private set
    lateinit var services: AndroidFuseServices
        private set

    val platformUi: AndroidPlatformUi by lazy { AndroidPlatformUi(this, activities, appScope, services.storageVolumes, crashLog) }

    /** The second-screen companion, shared by the main screen and the game launcher. */
    val companions: CompanionScreens by lazy { CompanionScreens(this) }

    private val _startup = MutableStateFlow<Startup>(Startup.Loading)
    val startup: StateFlow<Startup> = _startup.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        crashLog.install()
        activities = ActivityHolder(this)
        registerActivityLifecycleCallbacks(activities)
        activities.onFuseResumed = { platformUi.onFuseResumed() }

        val data = FuseData(AndroidDatabase.open(this))
        http = FuseHttp.client(OkHttp.create(), FuseHttpConfig(appVersion = BuildConfig.VERSION_NAME))
        services = AndroidFuseServices(this, data, http, appScope, activities, companions)

    }

    /**
     * Counts interface starts that never settled, in private preferences written synchronously, so
     * a crash right after still finds the count (see [StartupGuard]).
     */
    private val guard by lazy {
        val prefs = getSharedPreferences("startup", MODE_PRIVATE)
        io.github.matiyaaa.fuse.ui.shell.app.StartupGuard(
            load = { prefs.getInt("unsettled", 0) },
            save = { n -> prefs.edit().putInt("unsettled", n).commit() },
        )
    }

    @Volatile private var started = false

    /**
     * Starts the store, once, when an interface first shows: the process can also start in the
     * background (an install result, the system checking Fuse), and only interface starts count
     * towards safe mode. [askedSafe] is the launcher shortcut "Start in safe mode".
     */
    @Synchronized
    fun beginInterface(askedSafe: Boolean) {
        if (started) return
        started = true
        val failing = guard.begin()
        val safe = when {
            askedSafe -> io.github.matiyaaa.fuse.ui.shell.app.SafeMode(io.github.matiyaaa.fuse.ui.shell.app.SafeMode.Reason.REQUESTED)
            failing -> io.github.matiyaaa.fuse.ui.shell.app.SafeMode(io.github.matiyaaa.fuse.ui.shell.app.SafeMode.Reason.REPEATED_FAILURES, guard.failedBefore)
            else -> null
        }
        appScope.launch {
            _startup.value = try {
                val store = createFuseStore(services, appScope, safeMode = safe != null)
                // Safe mode runs nothing that listens on the network.
                Startup.Ready(store, if (safe == null) startPhoneLink(store) else null, safe)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Fuse could not start: ${t.javaClass.name}")
                crashLog.recordNonFatal(t)
                Startup.Failed(
                    "Fuse couldn't open your library and settings. Nothing was deleted. " +
                        "Long-press Fuse's icon and choose Start in safe mode, or restart the device and try again.",
                )
            }
            (_startup.value as? Startup.Ready)?.store?.let(::followLowPower)
        }
    }

    /** This start ran long enough, or Fuse went to the background normally: the next start counts from zero. */
    fun settled() = guard.settle()

    /**
     * Phone Link's server. It follows the switch in Settings and lives as long as the process, so a
     * phone stays connected while a game runs. A failure here never stops Fuse from starting.
     */
    private fun startPhoneLink(store: FuseStore): PhoneLinkControl? = try {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        PhoneLinkServer(store, services.secrets, appScope, name, BuildConfig.VERSION_NAME, readUri = ::readContent, captures = AndroidCaptureLibrary(this)).also { it.start() }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "Phone Link could not start: ${t.javaClass.name}")
        crashLog.recordNonFatal(t)
        null
    }

    /** Art behind a content URI (a folder picked with the system picker), for Phone Link's image route. */
    private suspend fun readContent(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    // Art only: anything this large isn't worth sending to a phone.
                    if (out.size() > MAX_CONTENT_BYTES) return@use null
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    /** Suspends until the store exists; null when start-up failed. */
    suspend fun awaitStore(): FuseStore? = when (val s = startup.first { it !is Startup.Loading }) {
        is Startup.Ready -> s.store
        else -> null
    }

    /** Whether the device itself is short of memory; Low Power Mode adds to it (see [followLowPower]). */
    private val deviceLowMemory: Boolean by lazy {
        val am = getSystemService(ActivityManager::class.java)
        am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192
    }

    /** The low-memory setting the current singleton image loader was built with. */
    @Volatile private var imageLoaderLowMemory: Boolean? = null

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val lowPower = (startup.value as? Startup.Ready)?.store?.prefs?.value?.lowPower == true
        return buildImageLoader(context, deviceLowMemory || lowPower, reuse = null)
    }

    private fun buildImageLoader(context: PlatformContext, lowMemory: Boolean, reuse: ImageLoader?): ImageLoader {
        imageLoaderLowMemory = lowMemory
        val loader = fuseImageLoader(context, cacheDir.absolutePath, http, lowMemory, svgDensity = resources.displayMetrics.density) {
            add(AppIconFetcher.RefMapper())
            add(AppIconFetcher.Factory(this@FuseApplication))
            add(AppIconFetcher.IconKeyer())
        }
        // One disk cache per folder: a rebuilt loader keeps the one already open.
        val disk = reuse?.diskCache ?: return loader
        return loader.newBuilder().diskCache(disk).build()
    }

    /**
     * Low Power Mode shrinks the image memory cache and the largest decoded bitmap, like a low-memory
     * device. The singleton loader is rebuilt when the mode changes; images already on screen keep
     * the old one until they are drawn again.
     */
    private fun followLowPower(store: FuseStore) {
        appScope.launch {
            store.prefs.map { it.lowPower }.distinctUntilChanged().collect { lowPower ->
                val lowMemory = deviceLowMemory || lowPower
                val built = imageLoaderLowMemory ?: return@collect // Not built yet: newImageLoader reads the mode.
                if (built == lowMemory) return@collect
                withContext(Dispatchers.Main) {
                    val old = SingletonImageLoader.get(this@FuseApplication)
                    SingletonImageLoader.setUnsafe(buildImageLoader(this@FuseApplication, lowMemory, reuse = old))
                    old.memoryCache?.clear()
                }
            }
        }
    }

    private companion object {
        const val TAG = "Fuse"
        const val MAX_CONTENT_BYTES = 16 * 1024 * 1024
    }
}
