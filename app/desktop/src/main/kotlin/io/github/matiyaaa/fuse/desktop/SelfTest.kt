package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.input.GamepadSink
import io.github.matiyaaa.fuse.desktop.input.SdlGamepads
import io.github.matiyaaa.fuse.desktop.services.DesktopFuseServices
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.model.PadButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * `--self-test`: proves a packaged Fuse works on this system without a window. In a throwaway
 * folder it opens the database and every service, looks for emulators, stores and reads back a
 * credential, and on Windows and macOS starts SDL for controllers. Prints what it did; the exit code
 * is 0 when everything worked. CI runs it on each package.
 */
internal object SelfTest {
    fun run(): Int {
        val os = DesktopOs.current
        val root = Files.createTempDirectory("fuse-self-test").toFile()
        val base = root.fusePath
        val dirs = FuseDirs(root.fusePath, "$base/cache", "$base/data", "$base/config", "$base/config", "$base/data")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var failures = 0
        fun check(name: String, block: () -> String) {
            try {
                println("ok    $name: ${block()}")
            } catch (e: Throwable) {
                failures++
                println("FAIL  $name: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        println("Fuse ${BuildInfo.VERSION} self-test on $os (${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")})")
        var services: DesktopFuseServices? = null
        check("services") {
            services = DesktopFuseServices.create(dirs, scope)
            "host ${services!!.host}, apps ${if (services!!.apps == null) "off" else "on"}"
        }
        services?.let { s ->
            check("emulators") { runBlocking { s.emulators.detect() }.joinToString { it.id.value }.ifEmpty { "none installed" } }
            check("credentials") {
                runBlocking {
                    s.secrets.put("selftest.key", "value with spaces and 'quotes'")
                    val back = s.secrets.get("selftest.key")
                    s.secrets.remove("selftest.key")
                    check(back == "value with spaces and 'quotes'") { "read back $back" }
                }
                "stored and read back"
            }
            check("cache") { s.writeCacheFile("self-test/a.txt", "x") ?: error("not written") }
        }
        if (os != DesktopOs.LINUX) {
            check("controllers") {
                val pads = SdlGamepads(NoSink)
                pads.start()
                val until = System.currentTimeMillis() + 5_000
                while (!pads.started && pads.failure == null && System.currentTimeMillis() < until) Thread.sleep(50)
                pads.close()
                when {
                    pads.started -> "SDL started"
                    else -> error(pads.failure ?: "SDL did not start in 5 s")
                }
            }
        }
        services?.close()
        scope.cancel()
        root.deleteRecursively()
        println(if (failures == 0) "self-test passed" else "self-test failed ($failures)")
        return if (failures == 0) 0 else 1
    }

    private object NoSink : GamepadSink {
        override fun press(button: PadButton) = Unit
        override fun release(button: PadButton) = Unit
        override fun stick(x: Float, y: Float) = Unit
        override fun trigger(button: PadButton, value: Float) = Unit
    }
}
