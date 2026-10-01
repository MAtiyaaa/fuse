package io.github.matiyaaa.fuse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Display

/**
 * Fuse's card in Android's recent apps while Fuse is the Home app. Android never lists the Home app
 * there, so every game or app Fuse starts on this screen gets a small task of Fuse's filed right
 * behind it ([RecentsCardActivity]): recents, or the quick-switch gesture, then go straight back to
 * Fuse. When Fuse isn't the Home app it is in recents by itself and nothing extra is started.
 */
object RecentsCard {
    /** Whether Fuse is the Home app right now. */
    fun fuseIsHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }
        } catch (e: RuntimeException) {
            null
        }
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Starts [intent] from [activity]. With Fuse as the Home app and the launch on this screen, Fuse's
     * card goes in first, so it sits right behind [intent] in recents. [options] apply to [intent].
     */
    fun start(activity: Activity, intent: Intent, options: Bundle?, displayId: Int?) {
        val here = displayId == null || displayId == Display.DEFAULT_DISPLAY
        if (!here || !fuseIsHome(activity)) {
            activity.startActivity(intent, options)
            return
        }
        val card = Intent(activity, RecentsCardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivities(arrayOf(card, intent), options)
    }
}

/**
 * The card [RecentsCard] files behind games and apps. It is never seen: when the user picks it in
 * recent apps, it brings Fuse (the Home app) to the front and finishes. Its task stays in recents
 * for next time.
 */
class RecentsCardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backToFuse()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        backToFuse()
    }

    private fun backToFuse() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fuse = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(if (RecentsCard.fuseIsHome(this)) home else fuse)
        } catch (e: RuntimeException) {
            runCatching { startActivity(fuse) }
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
