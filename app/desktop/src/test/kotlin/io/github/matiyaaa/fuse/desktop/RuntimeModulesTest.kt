package io.github.matiyaaa.fuse.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Java runtime packed with Fuse holds only the modules `nativeDistributions` names (and
 * Compose's own: java.desktop, java.logging, jdk.crypto.ec). Code that reaches a module left out
 * runs in tests and from Gradle but fails in the AppImage, Msi and Dmg with NoClassDefFoundError:
 * RetroArch's cores and add-ons were once downloaded with java.net.http, which wasn't packed.
 */
class RuntimeModulesTest {
    private val root = File("../..")

    /** A package Fuse's own code reaches, and the module it comes from. */
    private val modules = mapOf(
        "java.net.http" to "java.net.http",
        "java.sql" to "java.sql",
        "java.lang.management" to "java.management",
        "javax.management" to "java.management",
        "javax.naming" to "java.naming",
        "java.util.prefs" to "java.prefs",
        "javax.xml" to "java.xml",
        "org.w3c.dom" to "java.xml",
        "javax.script" to "java.scripting",
        "com.sun.net.httpserver" to "jdk.httpserver",
        "jdk.jfr" to "jdk.jfr",
    )

    /** Source sets that go into the desktop app: its own and every module's shared and desktop code. */
    private fun shipped(): Sequence<File> {
        val sets = setOf("main", "commonMain", "jvmMain", "desktopMain", "jvmShared")
        return listOf("app/desktop", "core", "ui").asSequence()
            .map { File(root, it) }
            .flatMap { it.walkTopDown().onEnter { d -> d.name != "build" && d.name != "node_modules" } }
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.invariantSeparatorsPath.split('/').any { it in sets } && "/src/" in f.invariantSeparatorsPath }
    }

    @Test
    fun everyModuleFusesOwnCodeReachesIsPackedWithIt() {
        val build = File(root, "app/desktop/build.gradle.kts").readText()
        val packed = Regex("""modules\(([^)]*)\)""").findAll(build)
            .flatMap { m -> Regex("\"([a-z.]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] } }
            .toSet() + setOf("java.desktop", "java.logging", "jdk.crypto.ec")
        val missing = sortedMapOf<String, String>()
        for (f in shipped()) {
            val text = f.readText()
            for ((pkg, module) in modules) {
                if (module in packed || module in missing) continue
                if (Regex("""(^|[^\w.])${Regex.escape(pkg)}\.[A-Za-z]""").containsMatchIn(text)) missing[module] = f.relativeTo(root).invariantSeparatorsPath
            }
        }
        assertTrue(missing.isEmpty(), "modules reached but not packed (module to the first file reaching it): $missing")
    }
}
