package io.github.matiyaaa.fuse.sync.romm

/**
 * How a game's save here and RomM's newest stand, from three things: the save here ([Here]), RomM's
 * newest ([Remote]), and the last version both agreed on ([Agreed], kept by this device). A
 * conflict is only ever when both changed since they agreed (or never agreed and differ): a save
 * that didn't change on one side is the other's to take, whichever is newer by the clock.
 */
object RommLedger {
    /** The save here: its hash (as RomM hashes it), and whether it holds nothing yet (blank save memory). */
    data class Here(val hash: String, val blank: Boolean = false)

    /** What this device and RomM last agreed on: the save's hash and RomM's id for it. */
    data class Agreed(val hash: String, val saveId: Long)

    /** RomM's newest save: its id and hash (null when RomM didn't say). */
    data class Remote(val id: Long, val hash: String?)

    enum class Before {
        /** The same save on both: nothing moves (and it is agreed). */
        IN_STEP,

        /** RomM's comes down: nothing here, a blank one, or this one unchanged since they agreed. */
        BRING_DOWN,

        /** This one goes up: RomM's unchanged since they agreed, this one changed. */
        SEND_UP,

        /** Both changed since they agreed, or they never agreed and differ: the person chooses. */
        ASK,

        /** RomM has no save of it: nothing to do before it starts. */
        NOTHING,
    }

    /** Before the game starts. */
    fun before(here: Here?, agreed: Agreed?, remote: Remote?): Before = when {
        remote == null -> Before.NOTHING
        here != null && here.hash == remote.hash -> Before.IN_STEP
        here == null || here.blank || agreed?.hash == here.hash -> Before.BRING_DOWN
        agreed != null && unchangedOnRomm(agreed, remote) -> Before.SEND_UP
        else -> Before.ASK
    }

    enum class After {
        /** Nothing to send: no save, a blank one, or the one already agreed. */
        NOTHING,

        /** RomM already has this very save: only agreed. */
        AGREE,

        /** It goes up. */
        SEND,

        /** RomM's changed meanwhile too: nothing goes over it, and the next start asks. */
        LEAVE_FOR_NEXT_START,
    }

    /** After the game closed (RomM asked for its newest only when something might go). */
    fun after(here: Here?, agreed: Agreed?, remote: Remote?): After = when {
        here == null || here.blank || agreed?.hash == here.hash -> After.NOTHING
        remote == null -> After.SEND
        remote.hash == here.hash -> After.AGREE
        agreed != null && unchangedOnRomm(agreed, remote) -> After.SEND
        else -> After.LEAVE_FOR_NEXT_START
    }

    /** RomM's newest is the one this device last agreed on. */
    private fun unchangedOnRomm(agreed: Agreed, remote: Remote) = agreed.saveId == remote.id || agreed.hash == remote.hash

    /** For the Sync tab: how the two stand, read only. */
    fun standing(here: Here?, agreed: Agreed?, remote: Remote?): RommSaveStanding {
        val h = here?.takeIf { !it.blank }
        return when {
            remote == null -> if (h != null) RommSaveStanding.NEWER_HERE else RommSaveStanding.UNKNOWN
            h == null -> RommSaveStanding.NOT_HERE
            h.hash == remote.hash -> RommSaveStanding.IN_STEP
            agreed == null -> RommSaveStanding.BOTH_CHANGED
            agreed.hash == h.hash -> RommSaveStanding.NEWER_ON_ROMM
            unchangedOnRomm(agreed, remote) -> RommSaveStanding.NEWER_HERE
            else -> RommSaveStanding.BOTH_CHANGED
        }
    }
}
