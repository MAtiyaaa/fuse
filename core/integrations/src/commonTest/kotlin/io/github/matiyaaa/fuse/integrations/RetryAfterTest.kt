package io.github.matiyaaa.fuse.integrations

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryAfterTest {
    @Test fun secondsAndHttpDatesAreHonoured() {
        assertEquals(120L, retryAfterSeconds(headersOf(HttpHeaders.RetryAfter, "120"), 0))
        assertEquals(1L, retryAfterSeconds(headersOf(HttpHeaders.RetryAfter, "Thu, 01 Jan 1970 00:00:01 GMT"), 1))
        assertEquals(0L, retryAfterSeconds(headersOf(HttpHeaders.RetryAfter, "Thu, 01 Jan 1970 00:00:01 GMT"), 2000))
        assertNull(retryAfterSeconds(headersOf(HttpHeaders.RetryAfter, "unknown"), 0))
    }
}
