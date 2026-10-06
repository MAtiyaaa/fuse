package io.github.matiyaaa.fuse.ui.shell.sync

import io.github.matiyaaa.fuse.sync.MergeChoice
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.ProfileMerge
import kotlin.test.Test
import kotlin.test.assertEquals

/** The choices Left and Right go through for a profile joining a host, and where each starts. */
class MergeOptionsTest {
    private fun p(id: String, name: String) = ProfileInfo(id, name, "cat", protected = false, createdAt = 0)
    private val merge = ProfileMerge(
        "Gaming PC",
        here = listOf(p("mo-here", "mo"), p("kid", "Kid")),
        host = listOf(p("sam", "Sam"), p("mo", "Mo")),
        suggested = mapOf("mo-here" to "mo"),
    )

    @Test
    fun theSamePersonByNameComesFirstThenEveryoneElse() {
        val mo = merge.here[0]
        assertEquals(MergeChoice.Same("mo"), MergeOptions.first(mo, merge))
        assertEquals(listOf(MergeChoice.Same("mo"), MergeChoice.Same("sam"), MergeChoice.Add, MergeChoice.LeaveOut), MergeOptions.of(mo, merge))
    }

    @Test
    fun someoneWithNoMatchStartsAsSomeoneNewAndTheChoicesGoRound() {
        val kid = merge.here[1]
        assertEquals(MergeChoice.Add, MergeOptions.first(kid, merge))
        assertEquals(MergeChoice.LeaveOut, MergeOptions.step(kid, merge, MergeChoice.Add, -1))
        assertEquals(MergeChoice.Same("sam"), MergeOptions.step(kid, merge, MergeChoice.Add, 1))
        assertEquals(MergeChoice.Add, MergeOptions.step(kid, merge, MergeChoice.LeaveOut, 1))
    }
}
