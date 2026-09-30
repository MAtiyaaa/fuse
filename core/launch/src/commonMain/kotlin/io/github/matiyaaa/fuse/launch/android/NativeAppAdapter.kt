package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.Sources
import io.github.matiyaaa.fuse.launch.path
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.launch.platforms
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Native Android apps and games for the `android` platform: an ES-DE `.app` file holds `package` or
 * `package/activity` (ES-DE `%ANDROIDAPP%=%FILEINJECT%`), or the target is a [LaunchTarget.App].
 * Without an activity the Android app uses `getLaunchIntentForPackage`.
 */
object NativeAppAdapter : EmulatorAdapter, AndroidIntentPlanner {
    const val MAIN = "android.intent.action.MAIN"
    const val LAUNCHER = "android.intent.category.LAUNCHER"

    override val id = EmulatorId("android-app")
    override val name = "Android app"
    override val host = Host.ANDROID
    override val platforms: Set<PlatformId> = platforms("android")
    override val capabilities = AdapterCapabilities(folders = FolderSupport.NONE)
    override val limitations = listOf(
        "Starts the app named in the .app file (package or package/activity). Fuse needs to see other apps " +
            "(a <queries> launcher intent or QUERY_ALL_PACKAGES) to start them.",
    )
    override val source = "${Sources.ESDE_ANDROID}: androidapps/androidgames %ANDROIDAPP%=%FILEINJECT%; ES-DE FileData.cpp"
    override val confidence = Confidence.VERIFIED_ESDE
    override val homepage: String? = null
    override val idFileExtensions = setOf("app")
    override val builtIn = true

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean =
        target is LaunchTarget.App || Paths.extension(target.path.orEmpty()) == "app"

    override fun plan(request: LaunchRequest): LaunchPlan = planAndroid(request).plan

    override fun planAndroid(request: LaunchRequest): AndroidPlan {
        val target = request.target
        val text = when {
            target is LaunchTarget.App -> target.id
            Paths.extension(target.path.orEmpty()) == "app" -> request.options.injectedText
            else -> return AndroidPlan(LaunchPlan.Unsupported(id, "Only .app files start Android apps."))
        }
        val ref = text?.let(ShortcutParser::parseAppFile)
            ?: return AndroidPlan(
                LaunchPlan.Unsupported(
                    id,
                    if (text == null) "Fuse needs the content of ${Paths.fileName(target.path.orEmpty())} to start the app."
                    else "The .app file must contain a package name, optionally followed by /activity.",
                ),
            )
        val activity = ref.activityClass
        val intent = LaunchPlan.AndroidIntent(
            emulatorId = id,
            packageName = ref.packageName,
            activity = activity,
            action = MAIN,
            category = LAUNCHER,
            data = null,
            target = LaunchTarget.App(if (activity == null) ref.packageName else "${ref.packageName}/$activity"),
        )
        return AndroidPlan(
            intent,
            AndroidIntentPlan(
                intent = intent,
                activityCandidates = listOfNotNull(activity),
                launchDisplayId = request.options.displayId,
            ),
        )
    }
}
