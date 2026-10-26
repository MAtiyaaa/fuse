package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.GameId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

/** How a game ran on this device, as the person found it (Fuse 0.4.5, compatibility reports). */
@Serializable
enum class CompatVerdict(val label: String) {
    PLAYS_WELL("Plays well"),
    WITH_PROBLEMS("Plays, with problems"),
    DOES_NOT_WORK("Doesn't work"),
}

/** What went wrong in a game that plays with problems. */
@Serializable
enum class CompatProblem(val label: String) {
    GRAPHICS("Graphics"),
    SOUND("Sound"),
    CONTROLS("Controls"),
    SPEED("Speed"),
    SAVES("Saves"),
    CRASHES("Crashes"),
}

/** The person's own note of how a game ran here, with what it ran on when they said so. */
@Serializable
data class CompatNote(
    val verdict: CompatVerdict,
    val problems: Set<CompatProblem> = emptySet(),
    /** The name of the emulator its last session here used, when Fuse knows. */
    val emulator: String? = null,
    val fuseVersion: String = "",
    /** The kind of device ("Android", "Linux"), never its name. */
    val device: String = "",
    val at: Long = 0,
)

/**
 * Where a game stands on this device. Only the person can make a game playable: Fuse never decides
 * it from a game having started, so a game that ran with nobody saying how is only [LAUNCHES].
 */
enum class CompatStatus(val label: String) {
    VERIFIED("Verified playable"),
    WITH_ISSUES("Playable with issues"),
    LAUNCHES("Launches here, not confirmed"),
    UNSUPPORTED("Doesn't work here"),
    UNTESTED("Untested"),
}

/** A game's standing here: the person's note if any, and what Fuse saw of it running here. */
data class Compat(
    val status: CompatStatus,
    val note: CompatNote? = null,
    /** Sessions started from Fuse on this device. */
    val sessionsHere: Int = 0,
    val playedHereSeconds: Long = 0,
) {
    companion object {
        fun of(note: CompatNote?, sessionsHere: Int, playedHereSeconds: Long): Compat = Compat(
            status = when (note?.verdict) {
                CompatVerdict.PLAYS_WELL -> CompatStatus.VERIFIED
                CompatVerdict.WITH_PROBLEMS -> CompatStatus.WITH_ISSUES
                CompatVerdict.DOES_NOT_WORK -> CompatStatus.UNSUPPORTED
                null -> if (sessionsHere > 0) CompatStatus.LAUNCHES else CompatStatus.UNTESTED
            },
            note = note,
            sessionsHere = sessionsHere,
            playedHereSeconds = playedHereSeconds,
        )
    }
}

/**
 * Compatibility reports: how each game runs on this device, kept on this device. Nothing is sent
 * anywhere; the report is the person's to copy or save.
 */
interface CompatOps {
    fun of(game: GameId): Flow<Compat>

    /** Says how [game] runs here; null forgets what was said. */
    suspend fun set(game: GameId, verdict: CompatVerdict?, problems: Set<CompatProblem> = emptySet())

    /** Every game with a note or a session here, as text, without folder or user names. */
    suspend fun report(): String

    object None : CompatOps {
        override fun of(game: GameId): Flow<Compat> = flowOf(Compat(CompatStatus.UNTESTED))
        override suspend fun set(game: GameId, verdict: CompatVerdict?, problems: Set<CompatProblem>) = Unit
        override suspend fun report(): String = ""
    }
}
