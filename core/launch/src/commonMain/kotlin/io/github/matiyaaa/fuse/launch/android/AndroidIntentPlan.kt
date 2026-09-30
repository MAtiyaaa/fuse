package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.LaunchTokens
import io.github.matiyaaa.fuse.model.LaunchPlan
import kotlinx.serialization.Serializable

/**
 * Everything the Android app needs to start an emulator activity. It wraps the model's
 * [LaunchPlan.AndroidIntent] and adds what that type cannot carry yet (int extras, NO_HISTORY, the
 * activity candidates and checks). Candidate for moving into `:core:model`.
 *
 * Contract for the Android app:
 * - Tokens left in [LaunchPlan.AndroidIntent.data], string extras and array extras are filled on
 *   Android: [LaunchTokens.SAF] with the SAF tree document URI of the target, where the tree is the
 *   per-system ROM folder (`DocumentsContract.buildDocumentUriUsingTree`, as ES-DE's `%ROMSAF%`);
 *   [LaunchTokens.PROVIDER] with Fuse's FileProvider URI (and [grantReadUri] means add
 *   `FLAG_GRANT_READ_URI_PERMISSION`); [LaunchTokens.EXTDATA] with `/storage/emulated/<user>` and
 *   [LaunchTokens.INTDATA] with `/data/user/<user>`. No other tokens remain.
 * - Start the first entry of [activityCandidates] that exists and is exported
 *   (`PackageManager.getActivityInfo(...).exported`). When [requiresActivityCheck] is true this check is
 *   mandatory: the package name is shared by unrelated apps (spoofed builds) or this is a fork matched by
 *   family. If none exists, open the app instead and say why.
 * - When [LaunchPlan.AndroidIntent.activity] is null (native apps without an activity), use
 *   `getLaunchIntentForPackage`.
 * - Apply [LaunchPlan.AndroidIntent.clearTask]/[LaunchPlan.AndroidIntent.clearTop]/[noHistory] as
 *   `FLAG_ACTIVITY_CLEAR_TASK`/`CLEAR_TOP`/`NO_HISTORY`, and always `FLAG_ACTIVITY_NEW_TASK`.
 * - Put [intExtras] with `putExtra(String, Int)`; [mimeType] with `setDataAndType` when set.
 * - [launchDisplayId] goes to `ActivityOptions.setLaunchDisplayId` when not null.
 */
@Serializable
data class AndroidIntentPlan(
    val intent: LaunchPlan.AndroidIntent,
    /** Int extras (GameNative `app_id`). */
    val intExtras: Map<String, Int> = emptyMap(),
    val noHistory: Boolean = false,
    /** Fully qualified activity classes to try, in order. */
    val activityCandidates: List<String> = emptyList(),
    val requiresActivityCheck: Boolean = false,
    val mimeType: String? = null,
    /** True when the data is a Fuse FileProvider URI that needs a read grant. */
    val grantReadUri: Boolean = false,
    val launchDisplayId: Int? = null,
    /** True when the emulator was matched as a fork of a known family, not by exact package. */
    val isFamilyMatch: Boolean = false,
) {
    /** Tokens the Android app still has to fill (a subset of [LaunchTokens.androidResolved]). */
    val unresolvedTokens: Set<String>
        get() = buildSet {
            intent.data?.let { addAll(LaunchTokens.tokensIn(it)) }
            intent.stringExtras.values.forEach { addAll(LaunchTokens.tokensIn(it)) }
            intent.arrayExtras.values.forEach { list -> list.forEach { addAll(LaunchTokens.tokensIn(it)) } }
        }
}

/** A plan plus, for real intents, the full [AndroidIntentPlan]. */
data class AndroidPlan(val plan: LaunchPlan, val intent: AndroidIntentPlan? = null)

/** Implemented by Android adapters so [io.github.matiyaaa.fuse.launch.LaunchResolver] can return the full intent. */
interface AndroidIntentPlanner {
    /** Same decision as [io.github.matiyaaa.fuse.launch.EmulatorAdapter.plan], with the typed intent when there is one. */
    fun planAndroid(request: LaunchRequest): AndroidPlan
}
