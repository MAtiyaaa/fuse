package io.github.matiyaaa.fuse

import android.app.ActivityManager
import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.AndroidDatabase
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.FuseHttpConfig
import io.github.matiyaaa.fuse.platform.AndroidPlatformUi
import io.github.matiyaaa.fuse.platform.AppIconFetcher
import io.github.matiyaaa.fuse.services.AndroidFuseServices
import io.github.matiyaaa.fuse.ui.shell.platform.fuseImageLoader
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where app start-up is. Activities show a plain splash until [Ready]. */
sealed interface Startup {
    data object Loading : Startup
    data class Ready(val store: FuseStore) : Startup
    data class Failed(val message: String) : Startup
}

/**
 * Owns everything that lives as long as the process: the database, the HTTP client, the platform
 * services, the image loader and the store. The store is created once, in the background, and
 * published through [startup].
 */
class FuseApplication : Application(), SingletonImageLoader.Factory {
    /**
     * App-wide scope handed to the store. A failing background task is logged by type only (messages
     * can contain request URLs with API keys) and does not take Fuse down.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.w(TAG, "Background task failed: ${t.javaClass.name}")
        },
    )

    lateinit var activities: ActivityHolder
        private set
    lateinit var http: HttpClient
        private set
    lateinit var services: AndroidFuseServices
        private set

    val platformUi: AndroidPlatformUi by lazy { AndroidPlatformUi(this, activities, appScope, services.volumes) }

    private val _startup = MutableStateFlow<Startup>(Startup.Loading)
    val startup: StateFlow<Startup> = _startup.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        activities = ActivityHolder(this)
        registerActivityLifecycleCallbacks(activities)
        activities.onFuseResumed = { platformUi.onFuseResumed() }

        val data = FuseData(AndroidDatabase.open(this))
        http = FuseHttp.client(OkHttp.create(), FuseHttpConfig(appVersion = BuildConfig.VERSION_NAME))
        services = AndroidFuseServices(this, data, http, appScope, activities)

        appScope.launch {
            _startup.value = try {
                Startup.Ready(createFuseStore(services, appScope))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Fuse could not start: ${t.javaClass.name}")
                Startup.Failed("Fuse could not load its library and settings (${t.javaClass.simpleName}).")
            }
        }
    }

    /** Suspends until the store exists; null when start-up failed. */
    suspend fun awaitStore(): FuseStore? = when (val s = startup.first { it !is Startup.Loading }) {
        is Startup.Ready -> s.store
        else -> null
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val am = getSystemService(ActivityManager::class.java)
        val lowMemory = am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192
        return fuseImageLoader(context, cacheDir.absolutePath, http, lowMemory) {
            add(AppIconFetcher.Factory(this@FuseApplication))
            add(AppIconFetcher.IconKeyer())
        }
    }

    private companion object {
        const val TAG = "Fuse"
    }
}
