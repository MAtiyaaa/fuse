package io.github.matiyaaa.fuse.integrations

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Cross-checks the pure-Kotlin MD5 against the JDK for many lengths around block boundaries. */
class Md5JvmCrossCheckTest {
    @Test
    fun matchesJdkMd5() {
        val random = Random(42)
        val lengths = (0..200) + listOf(511, 512, 513, 1023, 1024, 4095, 65_537, 1_000_003)
        for (length in lengths) {
            val data = random.nextBytes(length)
            val expected = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
            assertEquals(expected, Md5.hex(data), "length $length")
        }
    }
}
