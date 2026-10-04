package io.github.matiyaaa.fuse.ui.shell.store

import kotlinx.coroutines.flow.Flow

/**
 * What the Store needs from the system to install apps: which apps are installed, a place to
 * download to, and the system's own installer and uninstaller (which always ask the user). Android
 * only; elsewhere [FuseServices.packages] is null and there is no Store.
 */
interface PackageBridge {
    /** The device's processor types, best first ("arm64-v8a", "armeabi-v7a"). */
    val abis: List<String>

    /** The device has a second, built-in screen (the Dual-Screen edition suits it). */
    val hasSecondScreen: Boolean

    /** Package names as apps are installed, updated or removed (any app; the Store picks its own). */
    val changes: Flow<String>

    /** [packageName] as installed, or null when it isn't. */
    suspend fun installed(packageName: String): InstalledPackage?

    /** True when the system lets Fuse install apps (Android's "Install unknown apps" for Fuse). */
    fun canInstall(): Boolean

    /** Opens the system's page to let Fuse install apps. */
    fun requestInstallPermission()

    /**
     * A new file to download into, below Fuse's own cache (never a user folder), named after
     * [fileName]. Null when it can't be made. [freeBytes] tells how much room there is.
     */
    suspend fun newDownload(fileName: String): DownloadSink?

    /** Bytes free where downloads go, or null when unknown. */
    fun freeBytes(): Long?

    /** What the APK at [path] is, read by the system's package manager; null when it isn't a readable APK. */
    suspend fun inspect(path: String): ArchiveInfo?

    /**
     * Hands the APK at [path] to the system installer and waits for the outcome. [onTurn] is called
     * when the system's confirmation is shown. The file is the bridge's to delete afterwards.
     */
    suspend fun install(path: String, onTurn: () -> Unit): PackageOutcome

    /** Asks the system to uninstall [packageName] (it asks the user) and waits for the outcome. */
    suspend fun uninstall(packageName: String): PackageOutcome

    /** Opens [packageName]; false when it has nothing to open. */
    fun launch(packageName: String): Boolean

    /** An image model for [packageName]'s icon ([AppIconModel] on Android). */
    fun iconModel(packageName: String): Any?
}

/** An installed app as the system reports it. */
data class InstalledPackage(val packageName: String, val versionName: String?, val versionCode: Long, val label: String)

/** An APK file as the system reads it. */
data class ArchiveInfo(val packageName: String, val versionName: String?, val versionCode: Long)

/** How an install or uninstall ended. */
sealed interface PackageOutcome {
    data object Done : PackageOutcome

    /** The user said no (or closed the system's confirmation). */
    data object Cancelled : PackageOutcome

    /** It failed: [message] says why; [conflict] when the installed app is signed by someone else. */
    data class Failed(val message: String, val conflict: Boolean = false) : PackageOutcome
}

/** A file being downloaded into. */
interface DownloadSink {
    /** Where the file is. */
    val path: String

    suspend fun write(bytes: ByteArray, count: Int)

    /** Closes the file and returns the lower-case hex SHA-256 of everything written. */
    suspend fun finish(): String

    /** Closes and deletes the file. */
    suspend fun discard()
}

/**
 * Puts programs the Store fetched in place on a computer (Linux, Windows, macOS), where Fuse looks
 * for emulators: an AppImage made runnable in ~/Applications, a Windows zip unpacked into
 * ~/Emulators, a macOS app copied into ~/Applications. Only what upstream publishes is used: nothing
 * is repackaged, and no installer is ever run.
 */
interface DesktopInstaller {
    val host: io.github.matiyaaa.fuse.model.Host

    /** This computer's processor ("x86_64" or "arm64"). */
    val arch: String

    /** Where programs go, shown on the Store's page. */
    val folder: String

    /** A new file to download into, below Fuse's own cache. */
    suspend fun newDownload(fileName: String): DownloadSink?

    /** Bytes free where downloads go, or null when unknown. */
    fun freeBytes(): Long?

    /**
     * Puts the download at [file] (published as [fileName], a [kind]) in place as [name], replacing
     * [previous] (the path an earlier install left) when given. Returns the program's path, or
     * throws an exception whose message says why it couldn't.
     */
    suspend fun install(name: String, file: String, fileName: String, kind: io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind, previous: String?): String

    /** Removes a program Fuse put in place (its file, its folder or its app). */
    suspend fun remove(path: String): Boolean

    suspend fun exists(path: String): Boolean

    /** Opens the program at [path]; false when it can't. */
    fun launch(path: String): Boolean
}
