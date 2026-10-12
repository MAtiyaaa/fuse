package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.settings.ExtensionScope
import io.github.matiyaaa.fuse.data.settings.Extensions
import io.github.matiyaaa.fuse.ui.shell.app.ExternalRequest
import io.github.matiyaaa.fuse.ui.shell.app.ExternalRequests
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Fuse Decky Bridge: how the Fuse Decky plugin, running inside Steam, talks to Fuse on the same
 * device. Fuse owns everything (the library, profiles, Fuse One, saves and transfers); the plugin
 * only asks, and shows answers in Steam. Nothing here can touch saves, change emulator files or
 * hand out household secrets or Steam credentials: the bridge offers a short list of read-only
 * views and a few actions the person could do in Fuse themselves, each only as far as the person
 * lets the plugin ([Extensions.DECKY]: emulators not until they allow it).
 *
 * It listens on the loopback address only, on a port the system picks, and every request must carry
 * a random token. Both are written to [discovery] (readable by this user only) for the plugin to
 * find; the file goes when the bridge stops, so a plugin never talks to a Fuse that isn't there.
 * Either side can restart on its own: the plugin reads the file again and carries on.
 */
class DeckyBridge(
    private val store: FuseStore,
    private val version: String,
    private val discovery: File,
    random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val token: String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private var server: EmbeddedServer<*, *>? = null

    /** The port the bridge listens on, once started. */
    @Volatile var port: Int = 0
        private set

    /** Starts listening and writes the discovery file. Returns the port; 0 when it couldn't start. */
    fun start(): Int {
        if (server != null) return port
        val s = embeddedServer(CIO, port = 0, host = LOOPBACK) {
            intercept(ApplicationCallPipeline.Plugins) {
                if (!authorised(call)) {
                    call.respondText(error("Not allowed"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    finish()
                }
            }
            routing {
                get("/v1/hello") { call.json(hello()) }
                // Each of the rest only as far as the person lets the plugin (Settings, Library, Decky).
                get("/v1/status") { call.allowedTo(ExtensionScope.STATUS) { call.json(status()) } }
                get("/v1/steam/{appId}") { call.allowedTo(ExtensionScope.LIBRARY) { call.json(steamGame(call.appId() ?: return@allowedTo call.bad())) } }
                post("/v1/steam/{appId}/add") { call.allowedTo(ExtensionScope.CHANGE_LIBRARY) { call.json(addSteam(listOf(call.appId() ?: return@allowedTo call.bad()))) } }
                post("/v1/steam/{appId}/remove") { call.allowedTo(ExtensionScope.CHANGE_LIBRARY) { call.json(removeSteam(call.appId() ?: return@allowedTo call.bad())) } }
                post("/v1/steam/{appId}/open") { call.allowedTo(ExtensionScope.PLAY) { call.json(openSteam(call.appId() ?: return@allowedTo call.bad())) } }
                post("/v1/steam/import") {
                    call.allowedTo(ExtensionScope.CHANGE_LIBRARY) {
                        val ids = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject["appIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull } }.getOrNull()
                        if (ids.isNullOrEmpty()) call.bad() else call.json(addSteam(ids.take(MAX_IMPORT)))
                    }
                }
                get("/v1/emulators") { call.allowedTo(ExtensionScope.EMULATORS) { call.json(emulators()) } }
                post("/v1/emulators/update-all") { call.allowedTo(ExtensionScope.EMULATORS) { call.json(storeAction { updateAll() }) } }
                post("/v1/emulators/install-all") { call.allowedTo(ExtensionScope.EMULATORS) { call.json(storeAction { installAll() }) } }
                post("/v1/emulators/uninstall-all") { call.allowedTo(ExtensionScope.EMULATORS) { call.json(storeAction { uninstallAll() }) } }
            }
        }
        return try {
            s.start(wait = false)
            val bound = runBlocking { s.engine.resolvedConnectors().first().port }
            server = s
            port = bound
            writeDiscovery(bound)
            bound
        } catch (e: Exception) {
            runCatching { s.stop(100, 500) }
            0
        }
    }

    override fun close() {
        server?.let { runCatching { it.stop(100, 500) } }
        server = null
        port = 0
        runCatching { discovery.delete() }
    }

    private fun authorised(call: ApplicationCall): Boolean {
        val given = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private fun hello(): JsonObject = buildJsonObject {
        put("app", "Fuse")
        put("version", version)
        put("protocol", PROTOCOL)
        // Who is playing is part of what Fuse shows: only with that allowed.
        put("profile", if (allows(ExtensionScope.STATUS)) store.sync.service?.activeProfile?.value?.name else null)
        // What the plugin may do here, so it offers only that.
        putJsonArray("allowed") { for (s in Extensions.granted(store.prefs.value.extensions, Extensions.DECKY)) add(JsonPrimitive(s.name.lowercase())) }
    }

    private fun allows(scope: ExtensionScope) = Extensions.allows(store.prefs.value.extensions, Extensions.DECKY, scope)

    /** Runs [block] when the person lets the plugin do [scope]; otherwise says where they can allow it. */
    private suspend fun ApplicationCall.allowedTo(scope: ExtensionScope, block: suspend () -> Unit) {
        if (!allows(scope)) {
            respondText(
                buildJsonObject { put("error", "not-allowed"); put("message", Extensions.refusal(Extensions.DECKY, scope)); put("scope", scope.name.lowercase()) }.toString(),
                ContentType.Application.Json, HttpStatusCode.Forbidden,
            )
            return
        }
        block()
    }

    /** Fuse One at a glance, and what is moving: all things Fuse already shows the person. */
    private fun status(): JsonObject = buildJsonObject {
        put("protocol", PROTOCOL)
        val svc = store.sync.service
        putJsonObject("fuseOne") {
            put("available", svc != null)
            if (svc != null) {
                val words = io.github.matiyaaa.fuse.ui.shell.sync.syncWords(svc.status.value)
                put("state", words.title)
                put("detail", words.detail)
                put("ok", words.ok)
                put("profile", svc.activeProfile.value?.name)
                putJsonArray("devices") {
                    for (d in svc.devices.value.filterNot { it.revoked }) add(buildJsonObject {
                        put("name", d.name)
                        put("platform", d.platform)
                        put("lastSeen", d.lastSeen)
                    })
                }
            }
        }
        val t = store.transfers.summary.value
        putJsonObject("transfers") {
            put("downloading", t.activeDownloads)
            put("uploading", t.activeUploads)
            put("queued", t.queued + t.waiting)
            put("failed", t.failed)
            t.progress?.let { put("progress", it) }
        }
    }

    private suspend fun steamGame(appId: Long): JsonObject {
        val id = store.library.steamGame(appId)
        return buildJsonObject {
            put("appId", appId)
            put("inFuse", id != null)
            id?.let { put("gameId", it.value) }
        }
    }

    /** Brings Steam's games [appIds] into the library (installed ones; Steam keeps installing the rest). */
    private suspend fun addSteam(appIds: List<Long>): JsonObject {
        val wanted = appIds.toSet()
        val found = runCatching { store.sources.findSteamGames() }.getOrDefault(emptyList()).filter { it.appId in wanted }
        val added = if (found.isEmpty()) 0 else store.sources.addSteamGames(found)
        return buildJsonObject {
            put("added", added)
            put("missing", wanted.size - found.size)
            put("message", when {
                found.isEmpty() -> "Only installed Steam games can be added to Fuse."
                added == 1 -> "${found.first().name} is in Fuse."
                else -> "$added games are in Fuse."
            })
        }
    }

    /** Takes Steam's game out of Fuse's library (hidden, like Remove from the library); Steam keeps it. */
    private suspend fun removeSteam(appId: Long): JsonObject {
        val id = store.library.steamGame(appId)
        if (id != null) store.library.setHidden(id, true)
        return buildJsonObject {
            put("removed", id != null)
            put("message", if (id != null) "Removed from Fuse. It stays in Steam." else "It isn't in Fuse.")
        }
    }

    private suspend fun openSteam(appId: Long): JsonObject {
        val id = store.library.steamGame(appId)
        val posted = id != null && ExternalRequests.post(ExternalRequest.OpenGame(id))
        return buildJsonObject { put("opened", posted) }
    }

    private fun emulators(): JsonObject {
        val ops = store.appStore
        val state = ops.state.value
        return buildJsonObject {
            put("supported", ops.supported && state.desktop)
            put("installed", state.installed.size)
            putJsonArray("updates") { for (a in state.updates) add(buildJsonObject { put("key", a.key); put("name", a.name) }) }
            put("busy", state.jobs.size)
        }
    }

    private fun storeAction(run: io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps.() -> Unit): JsonObject {
        val ops = store.appStore
        if (!ops.supported || !ops.state.value.desktop) return buildJsonObject { put("started", false); put("message", "Fuse's Store isn't available here.") }
        ops.run()
        return buildJsonObject { put("started", true); put("message", "Started in Fuse. Progress shows in Fuse's Store and Downloads.") }
    }

    /** Port, token and protocol for the plugin, written atomically and readable by this user alone. */
    private fun writeDiscovery(port: Int) {
        discovery.parentFile?.mkdirs()
        val body = buildJsonObject {
            put("port", port)
            put("token", token)
            put("protocol", PROTOCOL)
            put("version", version)
            put("pid", ProcessHandle.current().pid())
        }.toString()
        val tmp = File(discovery.parentFile, ".${discovery.name}.tmp")
        tmp.writeText(body)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), discovery.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun ApplicationCall.appId(): Long? = parameters["appId"]?.toLongOrNull()?.takeIf { it > 0 }

    private suspend fun ApplicationCall.json(body: JsonObject) = respondText(body.toString(), ContentType.Application.Json)

    private suspend fun ApplicationCall.bad() = respondText(error("Bad request"), ContentType.Application.Json, HttpStatusCode.BadRequest)

    private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

    companion object {
        /** The bridge's protocol: a plugin built for another refuses, rather than guessing. */
        const val PROTOCOL = 1
        const val LOOPBACK = "127.0.0.1"

        /** The most Steam games one import asks for. */
        const val MAX_IMPORT = 500

        /** Where the plugin looks: `$XDG_DATA_HOME/fuse` or `~/.local/share/fuse`, never Fuse's own data folder. */
        fun discoveryFile(home: String = System.getProperty("user.home"), xdgData: String? = System.getenv("XDG_DATA_HOME")): File =
            File(xdgData?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "fuse/decky-bridge.json")
    }
}
