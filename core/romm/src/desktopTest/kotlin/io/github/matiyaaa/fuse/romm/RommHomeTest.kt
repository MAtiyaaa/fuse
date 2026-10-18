package io.github.matiyaaa.fuse.romm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RomM on this computer: the Compose file Fuse writes, and the commands it would run (none are run here). */
class RommHomeTest {
    private val spec = RommHome.Spec(library = "/home/mo/Games", dataDir = "/home/mo/.local/share/Fuse/romm-home")

    @Test
    fun theLibraryIsReadOnlyAndRommsOwnFilesAreInFusesFolder() {
        val text = RommHome.compose(spec)
        val library = text.lines().indexOfFirst { it.trim() == "source: \"/home/mo/Games\"" }
        assertTrue(library > 0, text)
        assertEquals("target: /romm/library", text.lines()[library + 1].trim())
        assertEquals("read_only: true", text.lines()[library + 2].trim())
        assertTrue("source: \"/home/mo/.local/share/Fuse/romm-home/assets\"" in text)
        assertTrue("source: \"/home/mo/.local/share/Fuse/romm-home/config\"" in text)
        // RomM's release, pinned; the project Fuse finds its containers by.
        assertTrue("image: ${RommHome.ROMM_IMAGE}" in text && "name: fuse-romm" in text)
    }

    @Test
    fun noSecretIsInTheFileOnlyWhereComposeReadsThemFromTheEnvironment() {
        val secrets = RommHome.newSecrets({ n -> ByteArray(n) { it.toByte() } })
        assertEquals(RommHome.SECRET_VARS.toSet(), secrets.keys)
        assertTrue(secrets.values.all { it.length == 64 && it.all { c -> c in "0123456789abcdef" } })
        val text = RommHome.compose(spec)
        assertTrue(secrets.values.none { it in text })
        assertTrue("DB_PASSWD=\${FUSE_ROMM_DB_PASSWORD}" in text)
        assertTrue("MARIADB_PASSWORD=\${FUSE_ROMM_DB_PASSWORD}" in text)
        assertTrue("MARIADB_ROOT_PASSWORD=\${FUSE_ROMM_DB_ROOT_PASSWORD}" in text)
        assertTrue("ROMM_AUTH_SECRET_KEY=\${FUSE_ROMM_AUTH_SECRET_KEY}" in text)
        // No metadata service is set up for it: nothing reaches out until the person sets RomM up.
        assertFalse("SCREENSCRAPER" in text || "HASHEOUS" in text || "STEAMGRIDDB" in text)
    }

    @Test
    fun thePortIsTheHomeNetworksOrThisComputersAlone() {
        assertTrue("- \"8080:8080\"" in RommHome.compose(spec))
        assertTrue("- \"127.0.0.1:9000:8080\"" in RommHome.compose(spec.copy(port = 9000, thisComputerOnly = true)))
        assertEquals("http://localhost:9000", RommHome.address(spec.copy(port = 9000)))
        assertFailsWith<IllegalArgumentException> { RommHome.compose(spec.copy(port = 70_000)) }
    }

    @Test
    fun windowsPathsAndOddNamesStayOnePathEach() {
        val text = RommHome.compose(spec.copy(library = "D:\\My \"Games\"\\", dataDir = "C:\\Users\\Mo\\AppData\\Roaming\\Fuse\\romm-home"))
        assertTrue("source: \"D:/My \\\"Games\\\"\"" in text, text)
        assertTrue("source: \"C:/Users/Mo/AppData/Roaming/Fuse/romm-home/assets\"" in text)
    }

    @Test
    fun eachEngineHasItsOwnCommands() {
        assertEquals(listOf("docker", "compose", "-p", "fuse-romm", "-f", "/x/compose.yml", "up", "-d"), RommHome.up(RommHome.Engine.DOCKER, "/x/compose.yml"))
        assertEquals(listOf("podman", "compose", "-p", "fuse-romm", "-f", "/x/compose.yml", "stop"), RommHome.stop(RommHome.Engine.PODMAN, "/x/compose.yml"))
        assertEquals(listOf("podman-compose", "-p", "fuse-romm", "-f", "/x/compose.yml", "ps", "-q"), RommHome.running(RommHome.Engine.PODMAN_COMPOSE, "/x/compose.yml"))
        assertEquals(listOf("--version"), RommHome.Engine.PODMAN_COMPOSE.version)
    }
}
