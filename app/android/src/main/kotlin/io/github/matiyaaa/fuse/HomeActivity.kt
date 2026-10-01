package io.github.matiyaaa.fuse

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Fuse as the Home app: what Android starts for the Home button (through `.HomeAlias`). It only
 * hands over to [MainActivity], which lives in a normal task of its own, so Fuse shows in recent
 * apps like any app, with a real preview (Android never lists the Home task there). It is never
 * drawn and finishes at once. When the hand-over fails it stays, so Home can't loop.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handOver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handOver()
    }

    private fun handOver() {
        // A fresh intent, never this one: carrying CATEGORY_HOME would make MainActivity Home again.
        val fuse = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_HOME, true)
        try {
            startActivity(fuse, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        } catch (e: RuntimeException) {
            return
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
