package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.ui.shell.platform.SteamClientLink
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveChange
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveQueue
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveResult
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Steam's client through its local debug port: the Chrome DevTools Protocol its embedded browser
 * (CEF) serves on port 8080 when a file named `.cef-enable-remote-debugging` is in Steam's folder.
 * As Decky Loader does (backend/decky_loader/injector.py): `GET /json` lists the pages, the shared
 * one is where `SteamClient` and Steam's stores live, and `Runtime.evaluate` runs script in it.
 * Only a page served from this computer is ever reached.
 */
internal class SteamCef(
    private val base: String = "http://127.0.0.1:$PORT",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) {
    /** One page Steam's client has open: its title, address and where its debugger listens. */
    data class Page(val title: String, val url: String, val socket: String)

    /** The pages Steam's client has open; empty when nothing (or something other than Steam) answers. */
    suspend fun pages(): List<Page> = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI("$base/json")).timeout(Duration.ofSeconds(2)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching emptyList()
            Json.parseToJsonElement(response.body()).jsonArray.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val socket = o["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Page(o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(), socket)
            }.filter { loopback(it.socket) }
        }.getOrDefault(emptyList())
    }

    /** Steam's shared page, as Decky Loader picks it; null when Steam isn't listening. */
    suspend fun shared(): Page? = pick(pages())

    /**
     * Runs [expression] in [page], waiting for the promise it makes: what it gives back, as JSON, or
     * a failure with the words of the error it threw.
     */
    suspend fun evaluate(page: Page, expression: String, timeoutMs: Long = EVALUATE_MS): Result<JsonElement> = runCatching {
        val answer = CompletableDeferred<JsonObject>()
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()

            override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) {
                    val message = runCatching { Json.parseToJsonElement(text.toString()).jsonObject }.getOrNull()
                    text.setLength(0)
                    if (message?.get("id")?.jsonPrimitive?.longOrNull == ID) answer.complete(message)
                }
                socket.request(1)
                return null
            }

            override fun onError(socket: WebSocket, error: Throwable) {
                answer.completeExceptionally(error)
            }

            override fun onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                answer.completeExceptionally(IllegalStateException("Steam closed the connection"))
                return null
            }
        }
        val socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(URI(page.socket), listener).await()
        try {
            val call = buildJsonObject {
                put("id", ID)
                put("method", "Runtime.evaluate")
                putJsonObject("params") {
                    put("expression", expression)
                    put("userGesture", true)
                    put("awaitPromise", true)
                    put("returnByValue", true)
                }
            }
            socket.sendText(call.toString(), true).await()
            outcome(withTimeout(timeoutMs) { answer.await() }).getOrThrow()
        } finally {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").await() }
            socket.abort()
        }
    }

    companion object {
        /** The port Steam's client opens. */
        const val PORT = 8080

        /** The file in Steam's folder that makes it open the port when it starts (as Decky Loader's installer writes it). */
        const val FLAG = ".cef-enable-remote-debugging"

        /** What Fuse writes in [FLAG] so it knows the file as its own; Steam only looks for the file. */
        const val MARK = "Written by Fuse (Change Steam While It Runs). Steam only checks that this file is here.\n"

        /** The shared page's titles, as Decky Loader knows them across Steam's versions. */
        val SHARED = listOf("SharedJSContext", "Steam Shared Context presented by Valve™", "Steam", "SP")

        const val EVALUATE_MS = 20_000L
        private const val ID = 1L

        /** The shared page among [pages]: a known title served from Steam's own pages, else the oldest title. */
        fun pick(pages: List<Page>): Page? =
            pages.firstOrNull { p -> p.title in SHARED && ("https://steamloopback.host/routes/" in p.url || "https://steamloopback.host/index.html" in p.url) }
                ?: pages.firstOrNull { it.title == SHARED.first() }

        /** A debugger address on this computer only. */
        fun loopback(address: String): Boolean = runCatching {
            val uri = URI(address)
            uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1")
        }.getOrDefault(false)

        /** What a `Runtime.evaluate` answer says: the value given back, or the error thrown, in its own words. */
        fun outcome(answer: JsonObject): Result<JsonElement> {
            answer["error"]?.let { e -> return Result.failure(IllegalStateException(e.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Steam refused the call")) }
            val result = answer["result"] as? JsonObject ?: return Result.failure(IllegalStateException("Steam gave no answer"))
            (result["exceptionDetails"] as? JsonObject)?.let { d ->
                val described = (d["exception"] as? JsonObject)?.get("description")?.jsonPrimitive?.contentOrNull
                    ?: (d["exception"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
                    ?: d["text"]?.jsonPrimitive?.contentOrNull
                val words = described?.lineSequence()?.firstOrNull()?.removePrefix("Uncaught ")?.removePrefix("Error: ")?.trim()
                return Result.failure(IllegalStateException(words?.takeIf { it.isNotEmpty() } ?: "Steam's client threw an error"))
            }
            return Result.success((result["result"] as? JsonObject)?.get("value") ?: JsonNull)
        }
    }
}

/**
 * The script for each change, run in Steam's shared page: the calls the Fuse Decky plugin makes
 * (tools/decky/fuse/src/liveChanges.ts, from `@decky/ui`'s App.d.ts and stores.d.ts), each guarded so
 * that when Steam changes its insides the change fails, is reported, and Fuse goes the usual way.
 */
internal object SteamCefScripts {
    /** Steam's ELibraryAssetType for each picture kind: Capsule 0, Hero 1, Logo 2, Header 3, Icon 4. */
    val ASSET = mapOf("capsule" to 0, "hero" to 1, "logo" to 2, "header" to 3, "icon" to 4)

    /** ELaunchSource._2ftLibraryDetails: as if Play were pressed on the entry's own page. */
    const val FROM_DETAILS = 100

    private fun js(s: String) = JsonPrimitive(s).toString()

    private const val APPS = "const apps = (typeof SteamClient !== \"undefined\" && SteamClient) ? SteamClient.Apps : undefined;\n" +
        "if (!apps) throw new Error(\"Steam's client isn't reachable here\");\n"

    /** The script making [c]; it gives back the entry's id in Steam. */
    fun make(c: SteamLiveChange): String = "(async () => {\n" + APPS + when (c.op) {
        SteamLiveChange.Op.ADD -> """
            const appId = await apps.AddShortcut(${js(c.name)}, ${js(c.exe)}, ${js(c.startDir)}, ${js(c.launchOptions)});
            if (typeof appId !== "number" || appId <= 0) throw new Error("Steam didn't make the entry");
            apps.SetShortcutName(appId, ${js(c.name)});
            apps.SetShortcutLaunchOptions(appId, ${js(c.launchOptions)});
            return appId;
        """.trimIndent()
        SteamLiveChange.Op.UPDATE -> c.appId?.let { id ->
            """
            apps.SetShortcutExe($id, ${js(c.exe)});
            apps.SetShortcutStartDir($id, ${js(c.startDir)});
            apps.SetShortcutLaunchOptions($id, ${js(c.launchOptions)});
            return $id;
            """.trimIndent()
        } ?: "throw new Error(\"No entry to change\");"
        SteamLiveChange.Op.REMOVE -> c.appId?.let { id -> "apps.RemoveShortcut($id);\nreturn $id;" } ?: "throw new Error(\"No entry to take out\");"
        SteamLiveChange.Op.RUN -> c.appId?.let { id ->
            """
            const overview = (typeof appStore !== "undefined" && appStore.GetAppOverviewByAppID) ? appStore.GetAppOverviewByAppID($id) : undefined;
            const gameId = overview ? overview.gameid : undefined;
            if (typeof gameId !== "string" || !gameId) throw new Error("Steam doesn't have this entry yet");
            apps.RunGame(gameId, "", -1, $FROM_DETAILS);
            return $id;
            """.trimIndent()
        } ?: "throw new Error(\"No entry to start\");"
    } + "\n})()"

    /** The script giving entry [appId] a picture of [kind] ([data] in base64, [format] "png" or "jpg"). */
    fun art(appId: Long, kind: String, data: String, format: String): String? {
        val type = ASSET[kind] ?: return null
        if (format != "png" && format != "jpg") return null
        return "(async () => {\n$APPS await apps.SetCustomArtworkForApp($appId, ${js(data)}, ${js(format)}, $type);\nreturn true;\n})()"
    }

    /** The script placing entry [appId]'s logo, unless the person placed it themselves. */
    fun logo(appId: Long, logo: io.github.matiyaaa.fuse.ui.shell.platform.SteamLogo): String {
        val position = buildJsonObject {
            put("nVersion", 1)
            putJsonObject("logoPosition") {
                put("pinnedPosition", logo.pinned)
                put("nWidthPct", logo.widthPct)
                put("nHeightPct", logo.heightPct)
            }
        }.toString()
        return """
            (async () => {
            $APPS
            const overview = (typeof appStore !== "undefined" && appStore.GetAppOverviewByAppID) ? appStore.GetAppOverviewByAppID($appId) : undefined;
            if (overview && typeof appDetailsStore !== "undefined" && appDetailsStore.GetCustomLogoPosition && appDetailsStore.GetCustomLogoPosition(overview)) return false;
            await apps.SetCustomLogoPositionForApp($appId, ${js(position)});
            return true;
            })()
        """.trimIndent()
    }
}

/**
 * Fuse's changes to Steam's library made through Steam's own running client, with no plugin: the
 * waiting changes in [queue], each made with [SteamCefScripts] in Steam's shared page and reported,
 * every couple of seconds while Steam listens. The Fuse Decky plugin can be there too; each change
 * is handed out once ([SteamLiveQueue.take]), so the two never both make it.
 *
 * The port is the person's choice ([allow]), kept in [choice]: Fuse writes [SteamCef.FLAG] in each
 * Steam folder here that hasn't got it, and Steam reads it when it starts. Decky Loader's installer
 * puts the same file there and Decky needs it, so turning this off takes away only a flag Fuse wrote
 * ([SteamCef.MARK]), and none while Decky Loader is installed.
 */
internal class DesktopSteamClient(
    /** Steam's folders on this computer (where its library and the flag file live). */
    private val roots: () -> List<File>,
    private val queue: SteamLiveQueue,
    private val cef: SteamCef = SteamCef(),
    /** Where the person's choice is kept, in Fuse's own data; null keeps it for this run only. */
    private val choice: File? = null,
    /** Decky Loader is installed here, and needs Steam's port whoever wrote the flag. */
    private val deckyLoader: () -> Boolean = { deckyLoaderInstalled(File(System.getProperty("user.home"))) },
) : SteamClientLink {
    private fun flags(): List<File> = roots().map { File(it, SteamCef.FLAG) }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }

    @Volatile private var chosen: Boolean? = null

    /**
     * The person's choice. Before it was kept (0.4.3.1 and 0.4.3.2), turning this on only wrote the
     * flag, so a flag that is there stands for "on" until the person chooses again.
     */
    private fun choiceNow(): Boolean =
        chosen ?: choice?.takeIf { it.isFile }?.let { runCatching { it.readText().trim() == ON }.getOrNull() } ?: flags().any { it.isFile }

    private fun remember(on: Boolean) {
        chosen = on
        choice?.let { f -> f.parentFile?.mkdirs(); f.writeText(if (on) ON else OFF) }
    }

    /** Fuse wrote [flag]: it starts with Fuse's mark (a flag Decky Loader's installer makes is empty). */
    private fun fuses(flag: File): Boolean = runCatching {
        flag.isFile && flag.length() <= SteamCef.MARK.length * 2 && flag.readText().startsWith(SteamCef.MARK)
    }.getOrDefault(false)

    override suspend fun allowed(): Boolean = withContext(Dispatchers.IO) { choiceNow() }

    override suspend fun allow(on: Boolean): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val flags = flags()
            if (flags.isEmpty()) error("Fuse didn't find Steam's folder on this computer.")
            val decky = !on && deckyLoader()
            val kept = mutableListOf<File>()
            if (on) {
                for (f in flags) if (!f.exists()) f.writeText(SteamCef.MARK)
            } else {
                for (f in flags.filter { it.exists() }) {
                    if (decky || !fuses(f)) kept += f
                    else if (!f.delete()) error("Fuse couldn't take ${f.path} away.")
                }
            }
            remember(on)
            when {
                !on && decky -> "Off. Fuse no longer changes Steam through its port. Steam keeps it open for Decky Loader, which uses it."
                !on && kept.isNotEmpty() -> "Off. Fuse no longer changes Steam through its port. Steam keeps it open, since Fuse didn't mark the file that opens it " +
                    "(Decky Loader's installer, another program or an older Fuse put it there). To close it, remove ${kept.joinToString(" and ") { it.path }}."
                !on -> "Off. Steam closes its port the next time it starts; until then, changes wait for Steam to close."
                listening() -> "On. Steam's library changes right away while Steam runs."
                else -> "On. Steam opens its port the next time it starts; until then, changes wait for Steam to close."
            }
        }.onFailure { Log.warn("Steam client link: changing the flag", it) }
    }

    override suspend fun listening(): Boolean = cef.shared() != null

    @Volatile private var worker: Job? = null

    /** Starts making changes through Steam's client whenever it listens; once, for the life of [scope]. */
    fun start(scope: CoroutineScope) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive) {
                val page = if (allowed()) cef.shared() else null
                if (page != null) {
                    round(page)
                    delay(ROUND_MS)
                } else {
                    delay(IDLE_MS)
                }
            }
        }
    }

    /** One round: the waiting changes nobody has taken, each made and reported in turn. */
    suspend fun round(page: SteamCef.Page) {
        for (c in queue.take()) {
            val result = make(page, c)
            queue.done(c.id, result)
            if (!result.ok) Log.warn("Steam client link: ${c.op} for game ${c.gameId} wasn't made: ${result.message}")
        }
    }

    /** Makes [c] in Steam, then gives it its pictures and logo place; a picture Steam refuses doesn't undo the entry. */
    suspend fun make(page: SteamCef.Page, c: SteamLiveChange): SteamLiveResult {
        val made = cef.evaluate(page, SteamCefScripts.make(c))
        val value = made.getOrElse { return SteamLiveResult(false, message = it.message) }
        val appId = (value as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: c.appId
        if (appId != null && (c.op == SteamLiveChange.Op.ADD || c.op == SteamLiveChange.Op.UPDATE)) {
            for ((kind, path) in c.art) {
                val file = File(path).takeIf { it.isFile && it.length() in 1..MAX_ART_BYTES } ?: continue
                val format = when (file.extension.lowercase()) { "png" -> "png"; "jpg", "jpeg" -> "jpg"; else -> continue }
                val data = withContext(Dispatchers.IO) { java.util.Base64.getEncoder().encodeToString(file.readBytes()) }
                val script = SteamCefScripts.art(appId, kind, data, format) ?: continue
                cef.evaluate(page, script).onFailure { Log.warn("Steam client link: the $kind picture wasn't taken: ${it.message}") }
            }
            val logo = c.logo
            if (c.op == SteamLiveChange.Op.ADD && logo != null && "logo" in c.art) cef.evaluate(page, SteamCefScripts.logo(appId, logo))
        }
        return SteamLiveResult(true, appId = appId)
    }

    companion object {
        const val ROUND_MS = 2_000L
        const val IDLE_MS = 6_000L

        /** The person's choice, as [choice] keeps it. */
        private const val ON = "on"
        private const val OFF = "off"

        /** The largest picture handed to Steam's client, as through the Decky bridge. */
        const val MAX_ART_BYTES = 12L * 1024 * 1024
    }
}
