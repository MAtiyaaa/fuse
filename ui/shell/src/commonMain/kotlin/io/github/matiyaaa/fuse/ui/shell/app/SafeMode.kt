package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.CrtSettings
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/**
 * Fuse started in safe mode: its own theme, no effects, video, music, capture combo or automatic
 * work (scans, art fills, integrations), so whatever made it fail can be fixed from inside it.
 * Nothing saved changes: leaving safe mode puts everything back as it was.
 */
data class SafeMode(val reason: Reason, val failedStarts: Int = 0) {
    enum class Reason {
        /** The last starts never got as far as a settled interface. */
        REPEATED_FAILURES,

        /** The user asked: the `--safe-mode` flag, the launcher shortcut or the recovery file. */
        REQUESTED,
    }
}

/**
 * Counts starts that never settled. [begin] counts one more when the interface starts; [settle]
 * clears the count once Fuse has run a while or gone to the background normally (a game started).
 * When the previous [threshold] starts all failed to settle, [begin] answers that this one should
 * be safe. A process the system starts in the background, with no interface, is never counted: call
 * [begin] from the interface only.
 *
 * Reading and writing the count must never fail a start, so errors count as "no failed starts".
 */
class StartupGuard(
    private val load: () -> Int,
    private val save: (Int) -> Unit,
    private val threshold: Int = THRESHOLD,
) {
    private var begun = false

    /** How many starts before this one never settled. */
    var failedBefore: Int = 0
        private set

    /** Records this start; true when it should be safe. Counts once per process however often it is called. */
    fun begin(): Boolean {
        if (!begun) {
            begun = true
            failedBefore = runCatching(load).getOrDefault(0).coerceAtLeast(0)
            runCatching { save(failedBefore + 1) }
        }
        return failedBefore >= threshold
    }

    /** This start got far enough: the next one starts from a clean count. */
    fun settle() {
        runCatching { save(0) }
    }

    companion object {
        const val THRESHOLD = 3

        /** How long the interface must run before a start counts as settled. */
        const val SETTLE_MS = 15_000L
    }
}

/**
 * [this] as safe mode shows it: Fuse's own theme with reduced motion and no glass, CRT, video,
 * music, performance overlay or capture combo, in Low Power Mode. Never written back.
 */
fun UiPrefs.inSafeMode(): UiPrefs = copy(
    themeId = ThemePresets.Fuse.id,
    motion = MotionProfile.REDUCED,
    glass = GlassSettings(enabled = false),
    crt = CrtSettings(enabled = false),
    videoPreview = false,
    lowPower = true,
    performanceOverlay = false,
    music = music.copy(enabled = false),
    captureCombo = false,
)
