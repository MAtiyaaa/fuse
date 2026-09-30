package io.github.matiyaaa.fuse.services

import android.Manifest
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.view.Display
import androidx.core.content.FileProvider
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.launch.DualScreenPlatforms
import io.github.matiyaaa.fuse.launch.LaunchTokens
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.launch.android.AndroidIntentAdapter
import io.github.matiyaaa.fuse.launch.android.AndroidIntentPlan
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.storage.SourceRoot
import io.github.matiyaaa.fuse.storage.StoragePaths
import io.github.matiyaaa.fuse.storage.StorageVolumes
import io.github.matiyaaa.fuse.ui.shell.store.GameLauncher
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the launcher tells Fuse's second-screen companion around a game that draws on both screens. */
interface DualScreenHandoff {
    /** On the main thread, just before a game that uses one screen starts. */
    fun beforeGame()

    /** On the main thread, just before such a game starts: the second screen is the game's now. */
    fun beforeDualScreenGame()

    /** The game did not start after all. */
    fun dualScreenGameFailed()

    /** On the main thread, just before a game or app opens on the second screen: it takes that screen. */
    fun beforeSecondScreenLaunch() = beforeDualScreenGame()
}

/**
 * Starts emulators and apps from [ResolvedLaunch] plans, following the contract documented on
 * [AndroidIntentPlan]: Android-only tokens are filled here, activity candidates are verified, and
 * the launch animates out of Fuse with a clip reveal. Emulator settings are never changed. Games for
 * dual-screen systems get the second screen: the companion closes first (see [DualScreenHandoff]).
 */
class AndroidGameLauncher(
    context: Context,
    private val activities: ActivityHolder,
    private val volumes: StorageVolumes,
    /** Library sources, to find the per-system folder for `{SAF}` URIs. */
    private val sources: suspend () -> List<SourceRoot>,
    /** The platform id of the library game at a path, when there is one. */
    private val platformAt: suspend (path: String) -> String? = { null },
    private val dualScreen: DualScreenHandoff? = null,
) : GameLauncher {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val providerAuthority = "${appContext.packageName}.files"

    override suspend fun run(launch: ResolvedLaunch, displayId: Int?): RunResult = when (val plan = launch.plan) {
        is LaunchPlan.AndroidIntent -> {
            val full = launch.androidIntent ?: AndroidIntentPlan(intent = plan, activityCandidates = listOfNotNull(plan.activity))
            val both = dualScreen != null && usesBothScreens(launch, plan.target)
            start(full, displayId ?: full.launchDisplayId, both)
        }
        is LaunchPlan.OpenAppOnly -> openApp(plan.appId, displayId)
        is LaunchPlan.Unsupported -> RunResult.Failed(plan.reason)
        is LaunchPlan.Command -> RunResult.Failed("This launch is for Linux and can't run on Android.")
    }

    override suspend fun openApp(appId: String): RunResult = openApp(appId, null)

    /** The first display that isn't the built-in one, preferring presentation displays. */
    override fun secondaryDisplayId(): Int? {
        val displays = appContext.getSystemService(DisplayManager::class.java) ?: return null
        val presentation = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        return (presentation ?: displays.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY })?.displayId
    }

    /**
     * Whether the game is for a system with two screens. The adapter answers when it only runs such
     * systems (or none); otherwise the library game at the target path does.
     */
    private suspend fun usesBothScreens(launch: ResolvedLaunch, target: LaunchTarget): Boolean {
        val platforms = launch.adapter?.platforms.orEmpty().map { it.value }
        if (platforms.isNotEmpty()) {
            if (platforms.none { it in DUAL_SCREEN_PLATFORMS }) return false
            if (platforms.all { it in DUAL_SCREEN_PLATFORMS }) return true
        }
        val path = when (target) {
            is LaunchTarget.File -> target.path
            is LaunchTarget.Directory -> target.path
            is LaunchTarget.Playlist -> target.path
            is LaunchTarget.Shortcut -> target.path
            is LaunchTarget.TitleId, is LaunchTarget.App -> return false
        }
        val platform = withContext(Dispatchers.IO) {
            try {
                platformAt(path) ?: File(path).parent?.let { platformAt(it) }
            } catch (e: Exception) {
                null
            }
        }
        return platform in DUAL_SCREEN_PLATFORMS
    }

    private suspend fun openApp(appId: String, displayId: Int?): RunResult {
        val (pkg, activity) = AndroidIntentAdapter.splitAppId(appId.trim())
        if (pkg.isEmpty()) return RunResult.Failed("No app was named.")
        val prepared = withContext(Dispatchers.IO) {
            if (PackageSupport.packageInfo(pm, pkg) == null) return@withContext Prepared.Result(RunResult.NotInstalled)
            val intent = if (activity != null && PackageSupport.isExportedActivity(pm, pkg, activity)) {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, activity))
            } else {
                pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            }
            if (intent == null) {
                Prepared.Result(RunResult.Failed("${appName(pkg)} has no screen Fuse can open."))
            } else {
                Prepared.Launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return when (prepared) {
            is Prepared.Launch -> startIntent(prepared.intent, displayId, appName(pkg))
            is Prepared.Result -> prepared.result
            is Prepared.OpenInstead -> RunResult.Failed(prepared.reason)
        }
    }

    private suspend fun start(plan: AndroidIntentPlan, displayId: Int?, bothScreens: Boolean): RunResult {
        val spec = plan.intent
        val pkg = spec.packageName
        val name = appName(pkg)
        val prepared = withContext(Dispatchers.IO) {
            if (PackageSupport.packageInfo(pm, pkg) == null) return@withContext Prepared.Result(RunResult.NotInstalled)
            missingFile(spec.target)?.let { return@withContext Prepared.Result(RunResult.Failed(it)) }
            val candidates = plan.activityCandidates.ifEmpty { listOfNotNull(spec.activity) }
            val verified = candidates.firstOrNull { PackageSupport.isExportedActivity(pm, pkg, it) }
            val activity = when {
                verified != null -> verified
                candidates.isEmpty() -> null
                plan.requiresActivityCheck -> return@withContext Prepared.OpenInstead(
                    "$name doesn't have the game screen Fuse expected, so Fuse opened the app instead.",
                )
                // Not verified but not required: let Android decide (it reports a missing activity).
                else -> candidates.first()
            }
            try {
                Prepared.Launch(buildIntent(plan, activity))
            } catch (e: LaunchProblem) {
                Prepared.Result(RunResult.Failed(e.message ?: "Fuse couldn't prepare this launch."))
            }
        }
        return when (prepared) {
            is Prepared.Result -> prepared.result
            is Prepared.OpenInstead -> when (val opened = openApp(pkg, displayId)) {
                is RunResult.Started -> RunResult.OpenedAppInstead(prepared.reason)
                else -> opened
            }
            is Prepared.Launch -> startIntent(prepared.intent, displayId, name, if (bothScreens) Screens.BOTH else Screens.GAME)
        }
    }

    /**
     * Says so when the game's file is no longer where the library has it (moved, renamed, card
     * removed), instead of letting the emulator report a file that "doesn't exist". Only checked
     * when Fuse can see all files; otherwise the file may just be hidden from Fuse.
     */
    private fun missingFile(target: LaunchTarget): String? {
        val path = when (target) {
            is LaunchTarget.File -> target.path
            is LaunchTarget.Directory -> target.path
            is LaunchTarget.Playlist -> target.path
            else -> return null
        }
        if (!canSeeAllFiles() || path.startsWith(appContext.cacheDir.absolutePath)) return null
        val file = File(path)
        if (file.exists()) return null
        return "${file.name} isn't in ${file.parent ?: "its folder"} any more. If you moved or renamed it, rescan the library."
    }

    private fun canSeeAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            appContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private sealed interface Prepared {
        data class Launch(val intent: Intent) : Prepared
        data class OpenInstead(val reason: String) : Prepared
        data class Result(val result: RunResult) : Prepared
    }

    private class LaunchProblem(message: String) : Exception(message)

    private suspend fun buildIntent(plan: AndroidIntentPlan, activity: String?): Intent {
        val spec = plan.intent
        val pkg = spec.packageName
        val base = if (activity == null) {
            pm.getLaunchIntentForPackage(pkg)?.apply { data = null }
                ?: throw LaunchProblem("${appName(pkg)} has no screen Fuse can open.")
        } else {
            Intent().setComponent(ComponentName(pkg, activity))
        }
        val intent = Intent(base)
        spec.action?.let { intent.action = it }
        spec.category?.let { intent.addCategory(it) }
        val tokens = TokenValues(spec.target, spec.packageName)
        val data = spec.data?.let { tokens.fill(it, allowProvider = true) }?.let(Uri::parse)
        when {
            data != null && plan.mimeType != null -> intent.setDataAndType(data, plan.mimeType)
            data != null -> intent.data = data
            plan.mimeType != null -> intent.type = plan.mimeType
        }
        for ((key, value) in spec.stringExtras) intent.putExtra(key, tokens.fill(value, allowProvider = false))
        for ((key, values) in spec.arrayExtras) intent.putExtra(key, values.map { tokens.fill(it, allowProvider = false) }.toTypedArray())
        for ((key, value) in spec.boolExtras) intent.putExtra(key, value)
        for ((key, value) in plan.intExtras) intent.putExtra(key, value)

        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (spec.clearTask) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
        if (spec.clearTop) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (plan.noHistory) flags = flags or Intent.FLAG_ACTIVITY_NO_HISTORY
        intent.addFlags(flags)
        // The read grant covers the URI in clipData, so a shared file passed in an extra works too.
        val grant = data?.takeIf { plan.grantReadUri || tokens.usedProvider } ?: tokens.sharedUri?.let(Uri::parse)
        if (grant != null) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("", grant)
        }
        return intent
    }

    /** Fills the tokens only the Android app can resolve; see [LaunchTokens.androidResolved]. */
    private inner class TokenValues(private val target: LaunchTarget, private val pkg: String) {
        var usedProvider = false
            private set

        /** A FileProvider URI handed over in an extra ([LaunchTokens.DOC]); granted through clipData. */
        var sharedUri: String? = null
            private set
        private var saf: String? = null

        suspend fun fill(template: String, allowProvider: Boolean): String {
            var out = template
            if (LaunchTokens.EXTDATA in out) out = out.replace(LaunchTokens.EXTDATA, volumes.primaryRoot)
            if (LaunchTokens.INTDATA in out) out = out.replace(LaunchTokens.INTDATA, internalDataRoot())
            if (LaunchTokens.SAF in out) out = out.replace(LaunchTokens.SAF, safUri())
            if (LaunchTokens.DOC in out) out = out.replace(LaunchTokens.DOC, documentUri())
            if (LaunchTokens.PROVIDER in out) {
                if (!allowProvider) throw LaunchProblem("This launch passes a shared file in an extra, which Android does not allow.")
                out = out.replace(LaunchTokens.PROVIDER, providerUri())
                usedProvider = true
            }
            // No check for other "{...}" here: file names may contain braces, e.g. "Game {USA}.zip".
            return out
        }

        private fun targetPath(): String = when (target) {
            is LaunchTarget.File -> target.path
            is LaunchTarget.Directory -> target.path
            is LaunchTarget.Playlist -> target.path
            is LaunchTarget.Shortcut -> target.path
            is LaunchTarget.TitleId, is LaunchTarget.App -> throw LaunchProblem("${appName(pkg)} needs a file for this launch.")
        }

        private suspend fun safUri(): String {
            saf?.let { return it }
            val path = targetPath()
            val mounted = volumes.mounted()
            val ids = StoragePaths.safIds(path, sources(), mounted) ?: throw LaunchProblem(
                if (path.startsWith(appContext.cacheDir.absolutePath) || path.startsWith(appContext.filesDir.absolutePath)) {
                    "${appName(pkg)} opens games through folders you granted it, so it can't read the playlist Fuse made. " +
                        "Turn off playlist generation for this platform, or put an .m3u next to the discs."
                } else {
                    "${appName(pkg)} can only open games on internal storage or an SD card."
                },
            )
            val tree = DocumentsContract.buildTreeDocumentUri(StoragePaths.EXTERNAL_STORAGE_AUTHORITY, ids.first)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, ids.second).toString()
            saf = uri
            return uri
        }

        /** Fuse's own share for a single-file image, the SAF document for anything else. */
        private suspend fun documentUri(): String {
            val path = (target as? LaunchTarget.File)?.path
            val single = path != null && FsPath.extension(path).lowercase() in LaunchTokens.singleFileImages
            if (!single || !File(path!!).canRead()) return safUri()
            val uri = providerUri()
            sharedUri = uri
            return uri
        }

        private fun providerUri(): String {
            val file = File(targetPath())
            if (!file.canRead()) {
                throw LaunchProblem("Fuse needs All files access to hand ${file.name} to ${appName(pkg)}.")
            }
            return try {
                FileProvider.getUriForFile(appContext, providerAuthority, file).toString()
            } catch (e: IllegalArgumentException) {
                throw LaunchProblem("${file.name} is in a place Fuse can't share with ${appName(pkg)}.")
            }
        }
    }

    /** What a launch means for the second screen: an app, a game on one screen, or a game on both. */
    private enum class Screens { APP, GAME, BOTH }

    private suspend fun startIntent(
        intent: Intent,
        displayId: Int?,
        name: String,
        screens: Screens = Screens.APP,
    ): RunResult = withContext(Dispatchers.Main) {
        val second = displayId != null && displayId != Display.DEFAULT_DISPLAY
        when {
            screens == Screens.BOTH -> dualScreen?.beforeDualScreenGame()
            // A game or app sent to the second screen keeps it to itself: the companion steps aside.
            second -> dualScreen?.beforeSecondScreenLaunch()
            screens == Screens.GAME -> dualScreen?.beforeGame()
            else -> Unit
        }
        val result = startOn(intent, displayId, name)
        if ((screens == Screens.BOTH || second) && result !is RunResult.Started) dualScreen?.dualScreenGameFailed()
        result
    }

    private fun startOn(intent: Intent, displayId: Int?, name: String): RunResult = try {
        startWith(intent, options(displayId))
        RunResult.Started(null)
    } catch (e: ActivityNotFoundException) {
        RunResult.NotInstalled
    } catch (e: SecurityException) {
        if (displayId != null) {
            // Android refused the other screen: start on this one instead.
            try {
                startWith(intent, options(null))
                RunResult.Started(null)
            } catch (e2: ActivityNotFoundException) {
                RunResult.NotInstalled
            } catch (e2: RuntimeException) {
                RunResult.Failed("Android didn't let Fuse start $name.")
            }
        } else {
            RunResult.Failed("Android didn't let Fuse start $name.")
        }
    } catch (e: RuntimeException) {
        RunResult.Failed("Fuse couldn't start $name.")
    }

    private fun options(displayId: Int?): ActivityOptions {
        val options = activities.revealOptions() ?: ActivityOptions.makeBasic()
        if (displayId != null) options.launchDisplayId = displayId
        return options
    }

    private fun startWith(intent: Intent, options: ActivityOptions) {
        val activity = activities.current
        if (activity != null) {
            activity.startActivity(intent, options.toBundle())
        } else {
            appContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        }
    }

    /**
     * ES-DE's `%INTERNALDATA%`: `/data/user/<user>`, the root of every app's private data for this
     * user. Emulators use it to find their own folders; Fuse never reads there.
     */
    @Suppress("SdCardPath")
    private fun internalDataRoot(): String = "/data/user/${android.os.Process.myUid() / PER_USER_RANGE}"

    private fun appName(pkg: String): String =
        PackageSupport.packageInfo(pm, pkg)?.let { PackageSupport.label(pm, it) } ?: pkg

    private companion object {
        /** Android's uid range per user (UserHandle.PER_USER_RANGE). */
        const val PER_USER_RANGE = 100_000

        /** Systems with two screens, as platform ids. */
        val DUAL_SCREEN_PLATFORMS: Set<String> = DualScreenPlatforms.ids.map { it.value }.toSet()
    }
}
