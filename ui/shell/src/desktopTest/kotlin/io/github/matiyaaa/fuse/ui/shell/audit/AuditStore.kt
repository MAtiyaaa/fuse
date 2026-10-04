package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.ui.shell.store.ArchiveInfo
import io.github.matiyaaa.fuse.ui.shell.store.DownloadSink
import io.github.matiyaaa.fuse.ui.shell.store.InstalledPackage
import io.github.matiyaaa.fuse.ui.shell.store.PackageBridge
import io.github.matiyaaa.fuse.ui.shell.store.PackageOutcome
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * The Store as the audit's Android device sees it: the real Obtainium Emulation Pack (the test
 * fixture from core:integrations), GitHub answering each app's releases in GitHub's own format, and
 * a device with a few of the pack's apps installed (two behind their newest release). Downloads
 * start and then hold, so a download in progress can be shown.
 */
internal object AuditStore {
    private val pack: String by lazy {
        File("../../core/integrations/src/desktopTest/resources/obtainium/pack-standard.json").readText()
    }

    /** The newest release each sample repository answers with; others answer v1.0.0. */
    private val versions = mapOf(
        "flycast" to "v2.5", "aps3e" to "v1.48", "moonlight-android" to "v12.1.0", "melonDS-android" to "1.10.0",
        "azahar" to "2123.1", "Vita3K-builds" to "v0.2.0-3920", "pegasus-frontend" to "v0.13.1",
    )

    /** The pack's apps the device has, with the versions Android reports. */
    val installed = listOf(
        InstalledPackage("com.flycast.emulator", "2.4", 24, "Flycast"),
        InstalledPackage("aenu.aps3e", "1.48", 148, "aPS3e"),
        InstalledPackage("com.limelight", "12.1.0", 1210, "Moonlight"),
        InstalledPackage("me.magnum.melonds", "1.9.0", 190, "melonDS"),
    )

    @OptIn(DelicateCoroutinesApi::class)
    fun answer(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData? = with(scope) {
        val url = request.url.toString()
        when {
            url == "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/latest" ->
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/tag/v7.18.0"))
            url.startsWith("https://github.com/RJNY/Obtainium-Emulation-Pack/releases/download/v7.18.0/") ->
                respond(pack, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            request.url.host == "api.github.com" && request.url.encodedPath.endsWith("/releases/latest") ->
                respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            request.url.host == "api.github.com" && request.url.encodedPath.endsWith("/releases") -> {
                val segments = request.url.encodedPath.split('/')
                val owner = segments[2]
                val repo = segments[3]
                val tag = versions[repo] ?: "v1.0.0"
                respond(
                    """[{"tag_name":"$tag","name":"$repo $tag","published_at":"2026-09-24T12:00:00Z","html_url":"https://github.com/$owner/$repo/releases/tag/$tag",
                        "draft":false,"prerelease":false,
                        "body":"## What's new\n- Faster shader compilation on Adreno and Mali GPUs\n- Controller hot-plugging no longer drops the first input\n- Fixed audio crackle after resuming from sleep\n\n## Fixes\n- Save states load correctly after an update\n- [Full changelog](https://github.com/$owner/$repo/compare)",
                        "assets":[{"name":"$repo-$tag-arm64-v8a.apk","browser_download_url":"https://github.com/$owner/$repo/releases/download/$tag/$repo-arm64-v8a.apk","size":$SIZE},
                                  {"name":"$repo-$tag-x86_64.apk","browser_download_url":"https://github.com/$owner/$repo/releases/download/$tag/$repo-x86_64.apk","size":$SIZE},
                                  {"name":"$repo-$tag-windows-x64.zip","browser_download_url":"https://github.com/$owner/$repo/releases/download/$tag/$repo-windows-x64.zip","size":$SIZE},
                                  {"name":"$repo-$tag-x86_64.AppImage","browser_download_url":"https://github.com/$owner/$repo/releases/download/$tag/$repo-x86_64.AppImage","size":$SIZE}]}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            request.url.host == "github.com" && "/releases/download/" in url -> {
                // Starts, reaches 42 percent and holds.
                val body = ByteChannel(autoFlush = true)
                GlobalScope.launch {
                    body.writeFully(ByteArray((SIZE * 42 / 100).toInt()))
                    awaitCancellation()
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, SIZE.toString()))
            }
            else -> null
        }
    }

    private const val SIZE = 24L * 1024 * 1024
}

/** The audit device's packages: [AuditStore.installed], and an installer that is never reached. */
internal class AuditPackages(private val dir: File) : PackageBridge {
    override val abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    override val hasSecondScreen = false
    override val changes = MutableSharedFlow<String>()
    override suspend fun installed(packageName: String) = AuditStore.installed.firstOrNull { it.packageName == packageName }
    override fun canInstall() = true
    override fun requestInstallPermission() = Unit
    override fun freeBytes(): Long = 64L shl 30
    override suspend fun newDownload(fileName: String): DownloadSink {
        val file = File(dir, "store-$fileName").also { it.parentFile.mkdirs() }
        val out = file.outputStream()
        return object : DownloadSink {
            override val path: String = file.absolutePath
            override suspend fun write(bytes: ByteArray, count: Int) = out.write(bytes, 0, count)
            override suspend fun finish(): String { out.close(); return "" }
            override suspend fun discard() { runCatching { out.close() }; file.delete() }
        }
    }
    override suspend fun inspect(path: String): ArchiveInfo? = null
    override suspend fun install(path: String, onTurn: () -> Unit): PackageOutcome = PackageOutcome.Cancelled
    override suspend fun uninstall(packageName: String): PackageOutcome = PackageOutcome.Cancelled
    override fun launch(packageName: String) = true
    override fun iconModel(packageName: String): Any? = null
}
