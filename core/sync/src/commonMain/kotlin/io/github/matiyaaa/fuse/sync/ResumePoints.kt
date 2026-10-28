package io.github.matiyaaa.fuse.sync

/**
 * A game's resume state here: the save state its emulator starts it from to carry on where it was
 * left ([path]), when that was written ([modifiedAt], null where the system doesn't say) and the
 * state's [format].
 */
data class ResumePoint(val path: String, val modifiedAt: Long?, val format: String)

/**
 * Finds the resume state among a game's save spots, for the emulators that keep one by themselves
 * and can be started from it: DuckStation's `<serial>_resume.sav` (written on exit unless turned
 * off), PCSX2's `<serial> (<CRC>).resume.p2s` (written on shutdown when that is on) and RetroArch's
 * `<content>.state.auto` (its automatic state). Numbered slots are the person's own and never count:
 * a resume is where they stopped, not where they chose to keep.
 */
object ResumePoints {
    fun find(spots: List<SaveSpot>, env: SaveEnvironment): ResumePoint? {
        for (spot in spots) {
            if (spot.kind != SaveKind.STATE || !spot.available) continue
            val path = when {
                spot.format == DUCKSTATION -> spot.pathFor("resume.sav")
                spot.format == PCSX2 -> spot.root?.let { root ->
                    env.list(root)
                        .filter { n -> (spot.only == null || n.startsWith(spot.only)) && n.endsWith(PCSX2_RESUME) }
                        // A game with two CRCs (an update, a different dump): the one played last.
                        .maxByOrNull { env.modified("$root/$it") ?: 0L }
                        ?.let { "$root/$it" }
                }
                spot.format.startsWith(RETROARCH) -> spot.pathFor("state.auto")
                else -> null
            } ?: continue
            if (!env.exists(path)) continue
            return ResumePoint(path, env.modified(path), spot.format)
        }
        return null
    }

    private const val DUCKSTATION = "duckstation.state"
    private const val PCSX2 = "pcsx2.state"
    private const val PCSX2_RESUME = ".resume.p2s"
    private const val RETROARCH = "retroarch.state"
}
