package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.StoreVariant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Store: emulators and gaming apps from the Obtainium Emulation Pack, installed, updated and
 * removed through Android's own installer, which always asks the user. Only where Fuse can install
 * apps (Android); everywhere else [supported] is false and none of it is shown.
 */
interface AppStoreOps {
    /** True where the Store exists at all. */
    val supported: Boolean

    val state: StateFlow<StoreState>

    /** Short news to show the user as it happens ("Dolphin is installed."). */
    val notices: SharedFlow<String>

    /** The Store is being shown: reads the catalogue (cached, then fresh when it is old) and what is installed. */
    fun open()

    /** Fetches the catalogue again now. */
    fun refresh()

    /** Follows [variant] of the pack from now on; its catalogue replaces the other one's. */
    suspend fun chooseVariant(variant: StoreVariant)

    /** Looks up the newest release of the app with [key] (when not looked up recently, or when [force]d). */
    fun check(key: String, force: Boolean = false)

    /** Looks up the newest release of every installed app. */
    fun checkInstalled(force: Boolean = false)

    /** Downloads the newest release of [key] and hands it to Android's installer (install or update). */
    fun install(key: String)

    /** Every update, one after another; Android asks about each one. */
    fun updateAll()

    /** Stops [key]'s download, or forgets its failure. */
    fun cancel(key: String)

    /** Asks Android to uninstall [key] (Android asks the user to confirm). */
    fun uninstall(key: String)

    /** Opens the installed app; false when it can't be opened. */
    fun launch(key: String): Boolean

    /** Opens Android's "Install unknown apps" page for Fuse. */
    fun allowInstalls()

    /** The Store's key for the app installed as [packageName], when the Store offers it. */
    fun keyFor(packageName: String): String? = null

    /** The installed app's own icon, or null when it isn't installed. */
    fun iconModel(key: String): Any?

    /** Called when Fuse comes back to the front: what is installed may have changed. */
    fun onResume()

    /** Sets (or with null, removes) the GitHub token used for update checks. */
    suspend fun setGitHubToken(token: String?)

    /**
     * Adds the app whose releases are at the GitHub address [url] to the Store, under [category]
     * ("Other" unless the user picks one). It must be an app this device can install: a release
     * with an APK on Android, with this computer's build elsewhere. Null when it was added, else why not.
     */
    suspend fun addCustom(url: String, category: String = OTHER): String? = "The Store can't add apps here."

    /** Takes an app the user added out of the Store again (an installed copy stays installed). */
    suspend fun removeCustom(key: String) = Unit

    /**
     * Follows the catalogue of another GitHub repository that publishes the pack's files the same
     * way (a fork), or the Obtainium Emulation Pack again with null. Null when it worked, else why not.
     */
    suspend fun setPackRepo(url: String?): String? = "The catalogue can't be changed here."

    companion object {
        /** Where added apps go unless the user picks another category. */
        const val OTHER = "Other"

        /** The category of the apps Fuse offers itself, beside the pack's. */
        const val COMPANIONS = "Fuse companions"
    }

    object None : AppStoreOps {
        override val supported: Boolean = false
        override val state: StateFlow<StoreState> = MutableStateFlow(StoreState())
        override val notices: SharedFlow<String> = MutableSharedFlow()
        override fun open() = Unit
        override fun refresh() = Unit
        override suspend fun chooseVariant(variant: StoreVariant) = Unit
        override fun check(key: String, force: Boolean) = Unit
        override fun checkInstalled(force: Boolean) = Unit
        override fun install(key: String) = Unit
        override fun updateAll() = Unit
        override fun cancel(key: String) = Unit
        override fun uninstall(key: String) = Unit
        override fun launch(key: String): Boolean = false
        override fun allowInstalls() = Unit
        override fun iconModel(key: String): Any? = null
        override fun onResume() = Unit
        override suspend fun setGitHubToken(token: String?) = Unit
    }
}

/** Everything the Store shows, in one state. */
@Immutable
data class StoreState(
    /** The pack edition followed; null until the user chose one. */
    val variant: StoreVariant? = null,
    /** The edition that suits this device (Dual-Screen where there is a second screen). */
    val recommended: StoreVariant = StoreVariant.STANDARD,
    /** The catalogue (the cached one until a fresh one arrives); null when there is none yet. */
    val catalogue: StoreCatalogue? = null,
    val refreshing: Boolean = false,
    /** Why the last refresh failed (the cached catalogue is then shown); null when it worked. */
    val refreshProblem: String? = null,
    /** Installed apps of the catalogue, by key. */
    val installed: Map<String, InstalledApp> = emptyMap(),
    /** What is known about each app's newest release, by key. */
    val releases: Map<String, ReleaseCheck> = emptyMap(),
    /** Installs, updates and uninstalls in progress (or failed), by key. */
    val jobs: Map<String, StoreJob> = emptyMap(),
    /** Android lets Fuse install apps ("Install unknown apps"). */
    val canInstall: Boolean = true,
    /** A GitHub token is set for update checks. */
    val hasGitHubToken: Boolean = false,
    /** Icons the apps publish themselves (see AppIconFinder), by key, for apps not installed yet. */
    val icons: Map<String, String> = emptyMap(),
    /** The Store of a computer: programs from their projects' releases, not Android apps. */
    val desktop: Boolean = false,
    /** Where a computer's Store puts programs. */
    val folder: String? = null,
    /** The repository the catalogue follows when it isn't the Obtainium Emulation Pack. */
    val packRepo: String? = null,
) {
    fun app(key: String): StoreApp? = catalogue?.apps?.firstOrNull { it.key == key }

    /** How [key]'s installed version stands against its newest release. */
    fun standing(key: String): Standing {
        val installed = installed[key] ?: return Standing.NOT_INSTALLED
        val release = (releases[key] as? ReleaseCheck.Ready)?.release ?: return Standing.UNKNOWN
        return StoreStanding.of(installed, release)
    }

    /** Installed apps with a newer release Fuse can install, in catalogue order. */
    val updates: List<StoreApp>
        get() = catalogue?.apps.orEmpty().filter { app ->
            app.availability == Availability.INSTALLABLE && app.key in installed && standing(app.key) == Standing.UPDATE &&
                (releases[app.key] as? ReleaseCheck.Ready)?.release?.file != null
        }
}

/** How an app stands on this device. */
enum class Standing { NOT_INSTALLED, CURRENT, UPDATE, UNKNOWN }

/** The catalogue: the pack's apps (in its order) and categories, the release it came from, and when it was fetched. */
@Immutable
data class StoreCatalogue(
    val variant: StoreVariant,
    val apps: List<StoreApp>,
    val categories: List<StoreCategory>,
    /** The pack's release tag ("v7.18.0"); null when it came from the main branch. */
    val packVersion: String?,
    val fetchedAt: Long,
    val sourceUrl: String,
)

/** A category of the pack, in its colour (ARGB). */
@Immutable
data class StoreCategory(val name: String, val color: Long?) {
    val trackOnly: Boolean get() = name.equals("Track Only", ignoreCase = true)
}

/** What the Store can do with an app. */
enum class Availability {
    /** Fuse finds its releases and installs them. */
    INSTALLABLE,

    /** Only followed: the pack lists it so its releases can be watched; there is nothing to install. */
    TRACK_ONLY,

    /** Its releases come from somewhere Fuse doesn't read; it is installed by hand from its page. */
    MANUAL,
}

/**
 * An app of the catalogue. [key] identifies it within the edition (an app's fork in the other
 * edition is another key). [packageName] is what it installs as: the pack's id when that is a
 * package name, else the package Fuse saw it install as the first time ([pinned]).
 */
@Immutable
data class StoreApp(
    val key: String,
    val id: String,
    val packageName: String?,
    val pinned: Boolean,
    val name: String,
    val author: String,
    val about: String?,
    val categories: List<String>,
    val sourceUrl: String,
    val sourceHost: String,
    val sourceKind: SourceKind,
    val availability: Availability,
    /** The pack lets the app's package name differ from its id. */
    val allowIdChange: Boolean,
    /** Systems it plays, as far as Fuse knows the app. */
    val systems: List<PlatformId>,
    /** The colour of its first category (ARGB), for its monogram. */
    val color: Long?,
    /** What Fuse does with it once it is installed. */
    val inFuse: InFuse = InFuse.NOTHING,
    /** The user added it by its address; it can be taken out again. */
    val custom: Boolean = false,
)

/** What Fuse does with a Store app once it is installed. */
enum class InFuse {
    /** An emulator Fuse starts games in. */
    LAUNCHES_GAMES,
    /** An emulator other apps can't start games in: Fuse opens it, and you pick the game there. */
    OPENS_APP,
    /** Game streaming: listed under Streaming in Apps. */
    STREAMING,
    /** A frontend, driver or helper: listed under Tools in Apps. */
    TOOL,
    /** Something Fuse doesn't use (the pack itself, driver lists). */
    NOTHING,
}

enum class SourceKind { GITHUB, WEB, OTHER }

/** An app of the catalogue as Android has it installed. [record] is set when Fuse installed it. */
@Immutable
data class InstalledApp(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String,
    val record: InstallRecord? = null,
)

/** What Fuse installed: the upstream [version] and [file] it was, and the version code Android gave it. */
@Immutable
data class InstallRecord(val version: String?, val file: String?, val versionCode: Long)

/** What is known about an app's newest release. */
sealed interface ReleaseCheck {
    data object Checking : ReleaseCheck

    /** [stale]: shown from the cache while a fresh look failed. */
    data class Ready(val release: StoreRelease, val checkedAt: Long, val stale: Boolean = false) : ReleaseCheck

    /** [retryAt] (epoch millis) when GitHub's limit for checks is used up. */
    data class Failed(val message: String, val retryAt: Long? = null) : ReleaseCheck
}

/** An app's newest release: its version (null when its source names none), date, notes and the file this device would install. */
@Immutable
data class StoreRelease(
    val version: String?,
    val publishedAt: String?,
    val notes: String?,
    val pageUrl: String,
    /** The APK Fuse would install, or null. */
    val file: StoreFile?,
    /** Why there is nothing to install when there isn't (a zip, a split bundle), for apps that aren't track-only. */
    val manual: String?,
)

@Immutable
data class StoreFile(val name: String, val url: String, val sizeBytes: Long?, val digest: String?)

/**
 * An install, update or uninstall in progress, or one that failed. A job lives in the Store, not on
 * a page: it carries on while the user is elsewhere in Fuse.
 */
sealed interface StoreJob {
    /** Waiting for another download to finish (two run at once). */
    data object Waiting : StoreJob

    /** Finding the newest release's file. */
    data object Resolving : StoreJob

    /** Downloading: [progress] 0..1 when the size is known. */
    data class Downloading(val progress: Float?, val bytes: Long, val total: Long?) : StoreJob

    /** Checking the download is the app the catalogue lists. */
    data object Verifying : StoreJob

    /** Android must first allow Fuse to install apps; the job carries on once it does. */
    data object NeedsPermission : StoreJob

    /** With Android: its confirmation is open or waits its turn. */
    data class Installing(val waitingTurn: Boolean) : StoreJob

    data object Uninstalling : StoreJob

    /** It didn't work: [message] says why; [retry] when trying again can help. */
    data class Failed(val message: String, val retry: Boolean, val uninstallFirst: Boolean = false) : StoreJob

    /** True while the job is still going. */
    val active: Boolean get() = this !is Failed
}
