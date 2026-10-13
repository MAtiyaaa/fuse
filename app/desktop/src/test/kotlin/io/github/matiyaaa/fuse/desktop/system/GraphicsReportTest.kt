package io.github.matiyaaa.fuse.desktop.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Fuse says about how it draws, and why not on the graphics card. */
class GraphicsReportTest {
    @Test
    fun skiasOwnWarningsAreKeptAsTheReason() {
        // In the app this runs before Skia first logs; in a test run another test may have drawn first.
        if (GraphicsReport.install()) {
            // The way Skiko itself logs a failed graphics API, through the logger just installed.
            val logger = Class.forName("org.jetbrains.skiko.Logger")
            val impl = logger.getMethod("getLoggerImpl").invoke(logger.getField("INSTANCE").get(null)) as org.jetbrains.skiko.SkikoLoggerInterface
            impl.warn(RuntimeException("Cannot create Linux GL context"), "Failed to create redrawer")
        } else {
            GraphicsReport.remember("Failed to create redrawer", RuntimeException("Cannot create Linux GL context"))
        }
        assertEquals("Failed to create redrawer: Cannot create Linux GL context", GraphicsReport.skiaFailures.last())
        GraphicsReport.remember("Failed to create redrawer", RuntimeException("Cannot create Linux GL context"))
        assertEquals(1, GraphicsReport.skiaFailures.count { it.startsWith("Failed to create redrawer") }, "the same message twice is kept once")
    }

    @Test
    fun anEnvironmentThatForcesSoftwareIsNamed() {
        val env = mapOf("LIBGL_ALWAYS_SOFTWARE" to "1", "GALLIUM_DRIVER" to "llvmpipe", "LD_LIBRARY_PATH" to "/home/deck/.steam/ubuntu12_64:/usr/lib")
        val causes = GraphicsReport.environmentCauses(env::get)
        assertEquals(3, causes.size, "$causes")
        assertTrue(causes[0].startsWith("LIBGL_ALWAYS_SOFTWARE"))
        assertTrue(causes[2].startsWith("Steam's runtime libraries are ahead"))
        assertTrue(GraphicsReport.environmentCauses(mapOf("LIBGL_ALWAYS_SOFTWARE" to "0")::get).isEmpty())
        val setAside = GraphicsReport.environmentCauses(mapOf("FUSE_STEAM_LD_LIBRARY_PATH" to "/x/steam-runtime/lib")::get)
        assertTrue(setAside.single().contains("set aside"))
    }

    @Test
    fun steamRuntimePartsAreToldFromTheSystems() {
        assertEquals(
            listOf("/home/deck/.local/share/Steam/ubuntu12_32/steam-runtime/lib/x86_64-linux-gnu", "/home/deck/.steam/ubuntu12_64"),
            GraphicsReport.steamRuntimeLibraries("/home/deck/.local/share/Steam/ubuntu12_32/steam-runtime/lib/x86_64-linux-gnu:/usr/lib64:/home/deck/.steam/ubuntu12_64"),
        )
        assertTrue(GraphicsReport.steamRuntimeLibraries(null).isEmpty())
    }

    @Test
    fun theSystemsRendererAndSkiasDriverAreRead() {
        val glx = "name of display: :0\nOpenGL vendor string: AMD\nOpenGL renderer string: AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 18.1.8, DRM 3.57)\n"
        assertEquals("AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 18.1.8, DRM 3.57)", GraphicsReport.parseGlxRenderer(glx))
        assertNull(GraphicsReport.parseGlxRenderer("Error: unable to open display"))
        assertEquals("AMD Radeon RX 7800 XT", GraphicsReport.driver("GraphicsApi: OPENGL\nVendor: AMD\nModel: AMD Radeon RX 7800 XT"))
        assertEquals("Mesa llvmpipe (LLVM 18.1.8, 256 bits)", GraphicsReport.driver("Vendor: Mesa\nModel: llvmpipe (LLVM 18.1.8, 256 bits)"))
        assertNull(GraphicsReport.driver(null))
    }
}
