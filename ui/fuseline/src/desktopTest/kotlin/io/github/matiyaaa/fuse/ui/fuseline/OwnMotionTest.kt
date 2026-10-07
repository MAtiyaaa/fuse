package io.github.matiyaaa.fuse.ui.fuseline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every animation in Fuse runs on Fuseline: no app code reaches for Compose's animation APIs. The
 * one exception is [FuselineBridge], which hands lazy lists a spec made of Fuseline's numbers.
 */
class OwnMotionTest {
    @Test
    fun onlyTheBridgeTouchesComposeAnimation() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val sources = listOf("ui", "app").flatMap { dir ->
            File(root, dir).walkTopDown()
                // Paths with forward slashes on every system (Windows writes them with backslashes).
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.invariantSeparatorsPath && "/src/" in it.invariantSeparatorsPath }
                // Tests may compare Fuseline against Compose; shipped code may not use it.
                .filter { f -> TEST_DIRS.none { it in f.invariantSeparatorsPath } }
                .toList()
        }
        assertTrue(sources.size > 100, "found the sources (${sources.size})")
        val offenders = sources.filter { it.name != "FuselineBridge.kt" && it.readText().contains("androidx.compose.animation") }
        if (offenders.isNotEmpty()) fail("Use Fuseline instead of Compose animation in: " + offenders.joinToString { it.relativeTo(root).path })
    }

    private companion object {
        val TEST_DIRS = listOf("/src/desktopTest/", "/src/commonTest/", "/src/test/", "/src/androidTest/", "/src/androidUnitTest/")
    }
}
