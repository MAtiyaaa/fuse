package io.github.matiyaaa.fuse.ui.shell.audit

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.FuseHttpConfig
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.shell.platform.fuseImageLoader
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Fuse against a person's own servers, to see what their RomM and Jellyfin give it: the lists, the
 * art as it arrives and how long the server takes. Never part of the audit's usual runs: each
 * scenario needs its server in the environment (ROMM_HOST, ROMM_USERNAME, ROMM_PASSWORD;
 * JELLYFIN_HOST, JELLYFIN_USERNAME, JELLYFIN_PASSWORD) and is left out without it.
 *
 *     ./gradlew :ui:shell:desktopAudit -Pfuse.audit.dir=/tmp/live --tests '*UiAudit.liveM'
 */
@OptIn(DelicateCoilApi::class)
internal fun AuditDriver.liveScreens() {
    scenario("live", "romm") {
        val romm = live.romm
        live.updatePrefs { it.copy(romm = it.romm.copy(enabled = true)) }
        runBlocking {
            romm.setAddresses("", env("ROMM_HOST"), RouteMode.REMOTE)
            romm.usePassword(env("ROMM_USERNAME"), env("ROMM_PASSWORD")).getOrThrow()
        }
        romm.refresh(full = true)
        val started = System.currentTimeMillis()
        pumpUntil("RomM's games", 300_000) { romm.state.value.games > 0 && romm.state.value.syncing == null }
        println("Live: RomM's ${romm.state.value.games} games in ${System.currentTimeMillis() - started} ms")
        show(live)
        tab(Destination.LIBRARY)
        waitFor("This device", 30_000)
        shoot("Library, All, as it opens", 600)
        shoot("Library, All, five seconds on", 5_000)
        // Down to the shelf of what is on RomM, and its See all.
        repeat(4) { tap(PadButton.DPAD_DOWN) }
        shoot("further down", 3_000)
        tap(PadButton.DPAD_LEFT)
        shoot("a shelf's See all", 2_000)
        // The shelf of what is on RomM: its See all opens every game on the server.
        tap(PadButton.A)
        waitFor("games on RomM", 30_000)
        shoot("every game on RomM", 6_000)
        repeat(3) { tap(PadButton.DPAD_DOWN) }
        shoot("the games under D", 4_000)
        repeat(3) { tap(PadButton.DPAD_DOWN) }
        shoot("further down every game", 4_000)
    }

    scenario("live", "jellyfin") {
        val service = live.jellyfin ?: throw NotCovered("This store has no Jellyfin")
        live.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = true, mode = "REMOTE", remoteAddress = env("JELLYFIN_HOST"))) }
        settle(800)
        runBlocking {
            var tries = 0
            while (service.signIn(env("JELLYFIN_USERNAME"), env("JELLYFIN_PASSWORD")).isFailure && ++tries < 10) kotlinx.coroutines.delay(500)
        }
        pumpUntil("the server to answer", 30_000) { service.state.value.base != null }
        show(live)
        tab(Destination.CARTRIDGE)
        shoot("Jellyfin as it opens", 300)
        shoot("Jellyfin, two seconds on", 2_000)
        shoot("Jellyfin, six seconds on", 4_000)
        // The libraries lead the home where nothing is part way: the first is opened.
        waitFor("Libraries", 30_000)
        tap(PadButton.A)
        shoot("a library as it opens", 600)
        shoot("a library, three seconds on", 3_000)
        shoot("a library, eight seconds on", 5_000)
        tap(PadButton.DPAD_DOWN, 3)
        shoot("further down the library", 4_000)
    }
}

/** A variable the live scenarios need, or the scenario is left out. */
private fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: throw NotCovered("$name isn't set")

/** Each test window's live store, by its cache folder: a window's folders go when it closes. */
private val liveStores = HashMap<String, FuseStore>()

/**
 * The sample library with a real connection out, for [liveScreens]: its own folders, and the app's
 * own image loader over that connection, so pictures come as they would in the app.
 */
@OptIn(DelicateCoilApi::class)
private val AuditDriver.live: FuseStore
    get() = liveStores[cache.path] ?: run {
        val http = FuseHttp.client(
            OkHttp.create { config { dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }) } },
            FuseHttpConfig(appVersion = "audit"),
        )
        val dir = File(cache, "live").apply { mkdirs() }
        SingletonImageLoader.setUnsafe(fuseImageLoader(PlatformContext.INSTANCE, File(dir, "images").path, http, lowMemory = false))
        val kept = File(dir, "kept")
        val store = runBlocking {
            AuditLibrary.store(File(dir, "games"), File(dir, "cache"), controls, scope) { base ->
                object : FuseServices by base {
                    override val http = http

                    // Pictures fetched once and kept, as the app keeps them (RomM's art goes through here).
                    override suspend fun cacheFile(relativePath: String, content: suspend () -> ByteArray): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val file = File(kept, relativePath.trimStart('/'))
                        if (file.isFile && file.length() > 0) return@withContext file.path
                        val bytes = try {
                            content()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            println("Live: no picture for $relativePath: $e")
                            return@withContext null
                        }
                        file.parentFile.mkdirs()
                        file.writeBytes(bytes)
                        file.path
                    }
                }
            }
        }.first
        liveStores[cache.path] = store
        store
    }
