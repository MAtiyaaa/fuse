package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.sync.romm.RommLedger
import io.github.matiyaaa.fuse.sync.romm.RommLedger.After
import io.github.matiyaaa.fuse.sync.romm.RommLedger.Agreed
import io.github.matiyaaa.fuse.sync.romm.RommLedger.Before
import io.github.matiyaaa.fuse.sync.romm.RommLedger.Here
import io.github.matiyaaa.fuse.sync.romm.RommLedger.Remote
import io.github.matiyaaa.fuse.sync.romm.RommSaveStanding
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every case of a save here, RomM's newest and what they last agreed on: a conflict only when both
 * changed since they agreed (or never agreed and differ); otherwise the side that didn't change
 * takes the other's.
 */
class RommLedgerTest {
    private val a = "aaaa"
    private val b = "bbbb"
    private val c = "cccc"

    private data class Case(val name: String, val here: Here?, val agreed: Agreed?, val remote: Remote?, val before: Before, val after: After, val standing: RommSaveStanding)

    private val cases = listOf(
        Case("nothing anywhere", null, null, null, Before.NOTHING, After.NOTHING, RommSaveStanding.UNKNOWN),
        Case("only here, never sent", Here(a), null, null, Before.NOTHING, After.SEND, RommSaveStanding.NEWER_HERE),
        Case("only here, blank", Here(a, blank = true), null, null, Before.NOTHING, After.NOTHING, RommSaveStanding.UNKNOWN),
        Case("only on RomM", null, null, Remote(1, a), Before.BRING_DOWN, After.NOTHING, RommSaveStanding.NOT_HERE),
        Case("blank here, RomM has one", Here(b, blank = true), null, Remote(1, a), Before.BRING_DOWN, After.NOTHING, RommSaveStanding.NOT_HERE),
        Case("the same, never agreed", Here(a), null, Remote(1, a), Before.IN_STEP, After.AGREE, RommSaveStanding.IN_STEP),
        Case("the same, agreed", Here(a), Agreed(a, 1), Remote(1, a), Before.IN_STEP, After.NOTHING, RommSaveStanding.IN_STEP),
        Case("changed here only", Here(b), Agreed(a, 1), Remote(1, a), Before.SEND_UP, After.SEND, RommSaveStanding.NEWER_HERE),
        Case("changed on RomM only", Here(a), Agreed(a, 1), Remote(2, b), Before.BRING_DOWN, After.NOTHING, RommSaveStanding.NEWER_ON_ROMM),
        Case("changed on both", Here(b), Agreed(a, 1), Remote(2, c), Before.ASK, After.LEAVE_FOR_NEXT_START, RommSaveStanding.BOTH_CHANGED),
        Case("never agreed and different", Here(b), null, Remote(1, a), Before.ASK, After.LEAVE_FOR_NEXT_START, RommSaveStanding.BOTH_CHANGED),
        // RomM sent the agreed save again under a new id (a re-upload of the same bytes): unchanged there.
        Case("changed here, RomM re-uploaded the agreed save", Here(b), Agreed(a, 1), Remote(2, a), Before.SEND_UP, After.SEND, RommSaveStanding.NEWER_HERE),
        // RomM didn't say its hash: known by its id only.
        Case("changed here, RomM's hash unknown but its id agreed", Here(b), Agreed(a, 1), Remote(1, null), Before.SEND_UP, After.SEND, RommSaveStanding.NEWER_HERE),
        Case("unchanged here, RomM's hash unknown and newer", Here(a), Agreed(a, 1), Remote(2, null), Before.BRING_DOWN, After.NOTHING, RommSaveStanding.NEWER_ON_ROMM),
        Case("both changed onto the same save", Here(b), Agreed(a, 1), Remote(2, b), Before.IN_STEP, After.AGREE, RommSaveStanding.IN_STEP),
    )

    @Test
    fun everyCaseBeforeTheGameStarts() {
        for (k in cases) assertEquals(k.before, RommLedger.before(k.here, k.agreed, k.remote), k.name)
    }

    @Test
    fun everyCaseAfterItCloses() {
        for (k in cases) assertEquals(k.after, RommLedger.after(k.here, k.agreed, k.remote), k.name)
    }

    @Test
    fun everyCaseAsTheSyncTabSaysIt() {
        for (k in cases) assertEquals(k.standing, RommLedger.standing(k.here, k.agreed, k.remote), k.name)
    }
}
