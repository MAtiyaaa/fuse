package io.github.matiyaaa.fuse.ui.designsystem.theme

import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.fuseline.FuselineMotion
import io.github.matiyaaa.fuse.ui.fuseline.MotionLevel

/*
 * Fuse's motion is Fuseline's (ui/fuseline): curves, springs, durations and the motion profile
 * live there. This file ties it to Fuse's settings: the person's motion choice and the render
 * quality the device can afford.
 */

/** The Fuseline motion level for a motion choice. */
val MotionProfile.level: MotionLevel
    get() = when (this) {
        MotionProfile.REDUCED -> MotionLevel.REDUCED
        MotionProfile.MINIMAL -> MotionLevel.MINIMAL
        MotionProfile.STANDARD -> MotionLevel.STANDARD
        MotionProfile.ENHANCED -> MotionLevel.ENHANCED
    }

/** Fuseline's motion for a motion choice. */
fun FuselineMotion(profile: MotionProfile): FuselineMotion = FuselineMotion(profile.level)

/**
 * Whether decorative, continuous motion may run: the profile allows ambient movement and the
 * performance profile isn't saving power. Gate loops (drift, shimmer passes) on this.
 */
fun FuselineMotion.ambientOn(quality: RenderQuality): Boolean = ambient && quality.animatedBackground

/**
 * Whether one-shot flourishes may run (reveals, the focus sweep, theme crossfades): anything but
 * Reduced motion, outside Low Power Mode.
 */
fun FuselineMotion.flourishOn(quality: RenderQuality): Boolean = !reduced && quality.animatedBackground
