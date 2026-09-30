package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.LaunchMode
import io.github.matiyaaa.fuse.launch.LaunchTokens
import io.github.matiyaaa.fuse.launch.Sources
import io.github.matiyaaa.fuse.launch.TargetKind
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.ContentSupport
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.PlatformId

// Small builders that keep the Android catalogs readable. Internal to the module.

internal const val VIEW = "android.intent.action.VIEW"
internal const val MAIN = "android.intent.action.MAIN"
internal const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
internal const val CATEGORY_LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"

/** EX+ Alpha emulators (NES.emu, GBA.emu, ...) all use this activity. */
internal const val IMAGINE = "com.imagine.BaseActivity"
internal const val EXPLUS_HOME = "https://github.com/Rakashazi/emu-ex-plus-alpha"
internal const val FMS_HOME = "https://fms.komkon.org/"
internal const val FMS_ACTIVITY = "com.fms.emulib.TVActivity"

internal const val SAF = LaunchTokens.SAF
internal const val PROVIDER = LaunchTokens.PROVIDER
internal const val DOC = LaunchTokens.DOC
internal const val PATH = LaunchTokens.PATH
internal const val SERIAL = LaunchTokens.SERIAL
internal const val INJECT = LaunchTokens.INJECT

/** PlayStation-style title ids: four letters and five digits (PCSB00245, BLUS30001, CUSA12345). */
internal val PS_TITLE_ID = Regex("[A-Z]{4}[0-9]{5}")

/** Nintendo Switch title ids: 16 hex digits (Eden `CustomSettingsHandler.findGameByTitleId`). */
internal val SWITCH_TITLE_ID = Regex("[0-9A-Fa-f]{16}")

/** Positive decimal ids (GameNative `app_id`, which must also fit an int: checked when filled). */
internal val INT_ID = Regex("[1-9][0-9]{0,9}")

/** Steam app ids. */
internal val STEAM_ID = Regex("[1-9][0-9]*")

internal fun esde(rule: String): String = "${Sources.ESDE_ANDROID}: $rule"

internal fun play(pkg: String): String = "https://play.google.com/store/apps/details?id=$pkg"

internal fun app(pkg: String, activity: String? = null) = AndroidApp(pkg, activity)

/** The same activity for several packages, in order. */
internal fun apps(activity: String, vararg pkgs: String): List<AndroidApp> = pkgs.map { AndroidApp(it, activity) }

internal fun str(key: String, template: String): IntentExtra = IntentExtra.Text(key, template)
internal fun int(key: String, template: String): IntentExtra = IntentExtra.Number(key, template)
internal fun bool(key: String, value: Boolean): IntentExtra = IntentExtra.Flag(key, value)
internal fun array(key: String, vararg templates: String): IntentExtra = IntentExtra.TextArray(key, templates.toList())

/** An intent. [task] sets both CLEAR_TASK and CLEAR_TOP, the pair ES-DE uses most. */
internal fun intent(
    action: String? = null,
    category: String? = null,
    data: String? = null,
    extras: List<IntentExtra> = emptyList(),
    task: Boolean = false,
    clearTask: Boolean = task,
    clearTop: Boolean = task,
    noHistory: Boolean = false,
    mimeType: String? = null,
) = AndroidIntentSpec(action, category, mimeType, data, extras, clearTask, clearTop, noHistory)

internal fun anyFile(spec: AndroidIntentSpec, label: String = "File", platforms: Set<PlatformId>? = null) =
    LaunchMode(label, TargetKind.FILE, spec, platforms = platforms)

internal fun fileExt(spec: AndroidIntentSpec, vararg ext: String, label: String = "File") =
    LaunchMode(label, TargetKind.FILE, spec, extensions = ext.toSet())

internal fun idFile(
    spec: AndroidIntentSpec,
    vararg ext: String,
    label: String = "Id file",
    pattern: Regex? = null,
    platforms: Set<PlatformId>? = null,
) = LaunchMode(label, TargetKind.FILE, spec, extensions = ext.toSet(), platforms = platforms, idFile = true, idPattern = pattern)

internal fun titleId(spec: AndroidIntentSpec, label: String = "Title id", pattern: Regex? = null) =
    LaunchMode(label, TargetKind.TITLE_ID, spec, idPattern = pattern)

internal fun directory(spec: AndroidIntentSpec, label: String = "Directory") =
    LaunchMode(label, TargetKind.DIRECTORY, spec)

internal fun caps(
    folders: FolderSupport = FolderSupport.RESOLVES_FILE,
    dlc: ContentSupport = ContentSupport.NOT_APPLICABLE,
    updates: ContentSupport = ContentSupport.NOT_APPLICABLE,
    playlists: Boolean = false,
) = AdapterCapabilities(folders = folders, dlc = dlc, updates = updates, playlists = playlists)

/** Updates and DLC are installed inside the emulator (Switch, 3DS, PS3, Vita, Wii U, PSP). */
internal fun installedContent(folders: FolderSupport = FolderSupport.RESOLVES_FILE) =
    caps(folders = folders, dlc = ContentSupport.INSTALL_IN_EMULATOR, updates = ContentSupport.INSTALL_IN_EMULATOR)
