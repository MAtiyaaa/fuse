package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.LaunchMode
import io.github.matiyaaa.fuse.launch.TitleIdMode
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.serialization.Serializable

/**
 * A package/activity pair, like one ES-DE find-rule `<entry>`. Pairs are tried in order and the first
 * installed one wins. [activity] may start with '.', meaning relative to [pkg].
 */
@Serializable
data class AndroidApp(val pkg: String, val activity: String?) {
    /** Fully qualified activity class, or null when only the package is known. */
    val activityClass: String?
        get() = activity?.let { if (it.startsWith('.')) pkg + it else it }
}

/** A typed intent extra. Values are templates using [io.github.matiyaaa.fuse.launch.LaunchTokens]. */
sealed interface IntentExtra {
    val key: String

    /** `putExtra(key, String)` (ES-DE `%EXTRA_key%`). */
    data class Text(override val key: String, val template: String) : IntentExtra

    /** `putExtra(key, int)` (ES-DE `%EXTRAINTEGER_key%`). The filled value must parse as an Int. */
    data class Number(override val key: String, val template: String) : IntentExtra

    /** `putExtra(key, boolean)` (ES-DE `%EXTRABOOL_key%`). */
    data class Flag(override val key: String, val value: Boolean) : IntentExtra

    /** `putExtra(key, String[])` (ES-DE `%EXTRAARRAY_key%`). */
    data class TextArray(override val key: String, val templates: List<String>) : IntentExtra
}

/**
 * How to build the intent for one launch mode, as data. Mirrors ES-DE's Android command variables:
 * `%ACTION%`, `%CATEGORY%`, `%MIMETYPE%`, `%DATA%`, typed extras and the three activity flags.
 */
data class AndroidIntentSpec(
    val action: String? = null,
    val category: String? = null,
    val mimeType: String? = null,
    /** Intent data template. `{PROVIDER}` may only be used here (ES-DE: data only). */
    val data: String? = null,
    val extras: List<IntentExtra> = emptyList(),
    val clearTask: Boolean = false,
    val clearTop: Boolean = false,
    val noHistory: Boolean = false,
)

/**
 * One Android emulator or launcher, as data. [AndroidIntentAdapter] turns it into an
 * [io.github.matiyaaa.fuse.launch.EmulatorAdapter].
 *
 * @param apps package/activity candidates in ES-DE find-rule order.
 * @param modes launch modes, first match wins (see [io.github.matiyaaa.fuse.launch.select]).
 * @param openAppOnlyReason set when there is no documented per-game launch: the adapter only opens the app.
 * @param supersededBy ids of other entries that, when detected on the same package, make this one redundant
 *   (mainline Winlator vs. Winlator Cmod Glibc, which share `com.winlator`).
 */
data class AndroidEmulatorDef(
    val id: String,
    val name: String,
    val apps: List<AndroidApp>,
    val platforms: Set<PlatformId>,
    val modes: List<LaunchMode<AndroidIntentSpec>>,
    val source: String,
    val confidence: Confidence,
    val homepage: String? = null,
    val capabilities: AdapterCapabilities = AdapterCapabilities(folders = FolderSupport.RESOLVES_FILE),
    val limitations: List<String> = emptyList(),
    val titleIdMode: TitleIdMode = TitleIdMode.NONE,
    val openAppOnlyReason: String? = null,
    val usesRetroArchCores: Boolean = false,
    val installHint: String? = null,
    val supersededBy: Set<String> = emptySet(),
) {
    /** Every distinct package this entry can be installed as. */
    val packages: List<String> get() = apps.map { it.pkg }.distinct()
}
