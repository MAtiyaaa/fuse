package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.ktor.client.engine.mock.respond
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Each Fuse profile signs in to RetroAchievements once, with its own account; the device's is shared by the rest. */
class RetroAchievementsProfilesTest {
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-ra-profiles").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    @Test
    fun eachProfileKeepsItsOwnAccountAndLettingOneGoLeavesTheOthers(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "ra.db").absolutePath)), cache)
        // RetroAchievements answering for the two made-up accounts (API_GetUserProfile).
        services.web = { request ->
            val user = request.url.parameters["u"]
            if (request.url.host != "retroachievements.org" || user !in setOf("Ana", "Mo")) null
            else respond("""{"User":"$user","ULID":"01H$user","TotalPoints":120}""", io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        }
        val store = createFuseStore(services, scope)
        suspend fun playing(profile: String) {
            // As choosing who plays does: Fuse's own settings say whose turn it is.
            services.data.settings.update { it.copy(sync = it.sync.copy(activeProfile = profile)) }
            withTimeout(10_000) { while (store.prefs.value.sync.activeProfile != profile) delay(20) }
        }
        val secrets = services.secrets

        playing("ana")
        assertTrue(store.achievements.connect("Ana", "key-a").isSuccess)
        assertEquals("Ana", secrets.get("${SecretKeys.RA_USERNAME}@ana"))
        assertEquals("Ana", secrets.get(SecretKeys.RA_USERNAME), "the first account here is the device's too")

        playing("mo")
        assertTrue(store.achievements.connect("Mo", "key-m").isSuccess)
        assertEquals("Mo", secrets.get("${SecretKeys.RA_USERNAME}@mo"))
        assertEquals("key-m", secrets.get("${SecretKeys.RA_API_KEY}@mo"))
        assertEquals("Ana", secrets.get(SecretKeys.RA_USERNAME), "the device's stays the first one")
        assertEquals("Ana", secrets.get("${SecretKeys.RA_USERNAME}@ana"), "Ana's profile keeps its own")

        // Mo lets theirs go: Mo's profile has none from now on, and Ana's keeps its own.
        store.achievements.disconnect()
        assertTrue(secrets.get("${SecretKeys.RA_API_KEY}@mo") == null)
        assertEquals("key-a", secrets.get("${SecretKeys.RA_API_KEY}@ana"))
        assertEquals("Ana", secrets.get(SecretKeys.RA_USERNAME))
    }
}
