package io.github.matiyaaa.fuse

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResult
import java.lang.ref.WeakReference

/**
 * Requests that need an activity's result registry (pickers, the Home role dialog, runtime
 * permissions). [MainActivity] implements them; each returns null or false when cancelled.
 */
interface ActivityRequests {
    suspend fun pickFolder(): Uri?
    suspend fun pickImage(): Uri?
    suspend fun pickAudio(): Uri?
    suspend fun requestRole(intent: Intent): Boolean
    suspend fun requestPermission(permission: String): Boolean

    /** Android's screen recording prompt; the granted result, or null when the user said no. */
    suspend fun requestScreenCapture(intent: Intent): ActivityResult?
}

/**
 * Tracks Fuse's activities so app-wide services can reach the one on screen: pickers and dialogs
 * need an activity, window brightness applies to Fuse's own window, and launches animate from it.
 * Only weak references are kept.
 */
class ActivityHolder(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private var mainRef: WeakReference<MainActivity>? = null
    private var companionRef: WeakReference<CompanionActivity>? = null
    private var homeCompanionRef: WeakReference<CompanionHomeActivity>? = null
    private var resumedRef: WeakReference<Activity>? = null

    /** Called when an activity of Fuse resumes (Home role, storage and volume state refresh then). */
    var onFuseResumed: (() -> Unit)? = null

    val main: MainActivity? get() = mainRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    /** The companion Fuse started itself (see [CompanionScreens]). */
    val companion: CompanionActivity? get() = companionRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /** The companion Android started as the second screen's Home, while Fuse is the Home app. */
    val homeCompanion: CompanionHomeActivity? get() = homeCompanionRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /**
     * The activity to start things from: the main one. Never the companion, because an activity
     * started from it would open on the second screen.
     */
    val current: Activity? get() = main

    /** Whether one of Fuse's activities is in front right now. */
    val isFuseResumed: Boolean get() = resumedRef?.get() != null

    val requests: ActivityRequests? get() = main

    /**
     * Starts [intent] from the current activity, or from the application with NEW_TASK when Fuse has
     * no activity. Returns false when nothing handles it or Android refuses.
     */
    fun start(intent: Intent, options: Bundle? = null): Boolean {
        val activity = current
        return try {
            if (activity != null) {
                activity.startActivity(intent, options)
            } else {
                app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            }
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Starts the first of [intents] that something handles. */
    fun startFirst(vararg intents: Intent): Boolean = intents.any { start(it) }

    /** Options for a clip-reveal from the centre of Fuse's window, or null when no window is shown. */
    fun revealOptions(): ActivityOptions? {
        val view = current?.window?.decorView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        return ActivityOptions.makeClipRevealAnimation(view, view.width / 2, view.height / 2, 0, 0)
    }

    val context: Context get() = current ?: app

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        when (activity) {
            is MainActivity -> mainRef = WeakReference(activity)
            is CompanionHomeActivity -> homeCompanionRef = WeakReference(activity)
            is CompanionActivity -> companionRef = WeakReference(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumedRef = WeakReference(activity)
        onFuseResumed?.invoke()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (mainRef?.get() === activity) mainRef = null
        if (companionRef?.get() === activity) companionRef = null
        if (homeCompanionRef?.get() === activity) homeCompanionRef = null
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
