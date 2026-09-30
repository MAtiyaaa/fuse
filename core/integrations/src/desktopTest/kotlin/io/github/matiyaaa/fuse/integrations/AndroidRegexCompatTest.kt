package io.github.matiyaaa.fuse.integrations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android compiles regular expressions with ICU, not with Java's engine, and ICU rejects some
 * patterns Java accepts. The one that bit Fuse: a "]" right after "[" or "[^" ("[^]]*"), which
 * Java reads as a literal bracket. On Android the pattern fails when its class first loads, and
 * every later use throws NoClassDefFoundError (0.0.1 and 0.0.2 could not search for art because
 * of one in TitleNormalizer). Desktop tests can't see that, so shared code is checked here: write
 * "[^\\]]" instead.
 */
class AndroidRegexCompatTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** An unescaped "]" as the first member of a character class, inside a Kotlin string literal. */
    private val leadingBracket = Regex("""\[\^?\](?!\))""")

    @Test
    fun sharedRegexesAvoidJavaOnlyCharacterClasses() {
        val offenders = listOf("core", "ui").flatMap { dir ->
            File(root, dir).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "${File.separator}commonMain${File.separator}" in it.path }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { i, line ->
                        val regexLine = "Regex(" in line || "toRegex" in line
                        if (regexLine && leadingBracket.containsMatchIn(line)) "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
                    }
                }
                .toList()
        }
        assertTrue(offenders.isEmpty(), "Regexes Android's ICU engine rejects:\n" + offenders.joinToString("\n"))
    }
}
