package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.romm.RommHome
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RomM on this computer from the store: looked at freely, started only when asked, and used once it answers. */
class RommHomeStoreTest {
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    /** Docker here, recording what it is asked to do. */
    private class Host(private val engine: RommHome.Engine? = RommHome.Engine.DOCKER) : RommHomeHost {
        val asked = CopyOnWriteArrayList<String>()
        var spec: RommHome.Spec? = null
        var up = false
        override suspend fun engine() = engine.also { asked += "engine" }
        override suspend fun current() = spec.also { asked += "current" }
        override suspend fun start(spec: RommHome.Spec): Result<Unit> {
            asked += "start"
            this.spec = spec
            up = true
            return Result.success(Unit)
        }
        override suspend fun stop(): Result<Unit> {
            asked += "stop"
            up = false
            return Result.success(Unit)
        }
        override suspend fun running() = up.also { asked += "running" }
    }

    @Test
    fun lookingNeverStartsAnythingAndStartingMakesItRommsHomeAddress(): Unit = runBlocking {
        val host = Host()
        val services = FakeServices(FuseData(DesktopDatabase.open(null)), cache).apply {
            home = host
            web = { req ->
                if (req.url.host == "localhost" && req.url.port == 8080 && host.up) respond("""{"SYSTEM":{"VERSION":"5.4.0"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else null
            }
        }
        val store = createFuseStore(services, scope)
        // Looked at (Settings, RomM's set-up): Docker is here, nothing set up, nothing run.
        val before = store.romm.homeServer()!!
        assertEquals("Docker", before.engine)
        assertNull(before.spec)
        assertTrue("start" !in host.asked && "stop" !in host.asked, "${host.asked}")

        val address = store.romm.startHome("/home/mo/Games", 8080, thisComputerOnly = true).getOrThrow()
        assertEquals("http://localhost:8080", address)
        assertEquals(RommHome.Spec("/home/mo/Games", services.dataDir.trimEnd('/', '\\') + "/romm-home", 8080, true), host.spec)
        assertEquals("http://localhost:8080", store.prefs.value.romm.localAddress)
        assertTrue(store.prefs.value.romm.enabled)
        assertEquals(true, store.romm.homeServer()!!.running)

        store.romm.stopHome().getOrThrow()
        assertEquals(false, store.romm.homeServer()!!.running)
    }

    @Test
    fun withoutDockerOrPodmanItIsntOffered(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(null)), cache).apply { home = Host(engine = null) }
        assertNull(createFuseStore(services, scope).romm.homeServer())
        assertNull(createFuseStore(FakeServices(FuseData(DesktopDatabase.open(null)), cache), scope).romm.homeServer())
    }
}
