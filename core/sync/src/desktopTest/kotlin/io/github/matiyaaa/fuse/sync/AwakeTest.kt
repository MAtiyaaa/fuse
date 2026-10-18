package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwakeTest {
    @Test
    fun aWaitOnTheHostEndsWhenTheDeviceSleeps() = runBlocking {
        val awake = MutableStateFlow(true)
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val result = async {
            awake.untilAsleep {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        started.await()
        awake.value = false
        assertNull(withTimeout(5_000) { result.await() })
        assertTrue(cancelled, "the wait itself was stopped, so its answer can't wake the device")
    }

    @Test
    fun awakeTheAnswerComesThrough() = runBlocking {
        val awake = MutableStateFlow(true)
        assertEquals(42, awake.untilAsleep { delay(10); 42 })
        // Asleep already: nothing is asked at all.
        awake.value = false
        var asked = false
        assertNull(awake.untilAsleep { asked = true; 1 })
        assertFalse(asked)
    }

    @Test
    fun aFailureIsNotTakenForSleep() = runBlocking {
        val awake = MutableStateFlow(true)
        assertFailsWith<IllegalStateException> { awake.untilAsleep<Int> { error("the host didn't answer") } }
        Unit
    }

    @Test
    fun roundsWaitForTheDeviceToWake() = runBlocking {
        val awake = MutableStateFlow(false)
        val woke = async { awake.awaitAwake(); true }
        delay(50)
        assertFalse(woke.isCompleted, "asleep, the round waits")
        awake.value = true
        assertTrue(withTimeout(5_000) { woke.await() })
        // Awake already: straight through.
        withTimeout(1_000) { Awake.always.awaitAwake() }
    }
}
