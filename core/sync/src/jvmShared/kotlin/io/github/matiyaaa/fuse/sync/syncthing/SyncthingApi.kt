package io.github.matiyaaa.fuse.sync.syncthing

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Why a call to Syncthing failed. */
class SyncthingException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { UNREACHABLE, KEY, REFUSED }
}

/**
 * Syncthing's REST API, only what Fuse uses: who this device is, the devices and folders in its
 * configuration, devices and folders waiting to be accepted, a folder's state, and asking it to
 * look a folder over. Every call carries the API key in its header; nothing here logs.
 */
internal class SyncthingApi(private val http: HttpClient, val base: String, private val apiKey: String?) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun healthy(): Boolean = runCatching {
        http.get("$base/rest/noauth/health").status.isSuccess()
    }.getOrDefault(false)

    suspend fun status(): JsonObject = obj("/rest/system/status")
    suspend fun version(): JsonObject = obj("/rest/system/version")
    suspend fun connections(): JsonObject = obj("/rest/system/connections")
    suspend fun devices(): List<JsonObject> = arr("/rest/config/devices")
    suspend fun folders(): List<JsonObject> = arr("/rest/config/folders")
    suspend fun folderDefaults(): JsonObject = runCatching { obj("/rest/config/defaults/folder") }.getOrDefault(JsonObject(emptyMap()))
    suspend fun pendingDevices(): JsonObject = runCatching { obj("/rest/cluster/pending/devices") }.getOrDefault(JsonObject(emptyMap()))
    suspend fun pendingFolders(): JsonObject = runCatching { obj("/rest/cluster/pending/folders") }.getOrDefault(JsonObject(emptyMap()))
    suspend fun folderStatus(id: String): JsonObject = obj("/rest/db/status") { parameter("folder", id) }

    suspend fun putDevice(device: JsonObject, id: String) = send { http.put("$base/rest/config/devices/$id") { auth(); json(device) } }
    suspend fun deleteDevice(id: String) = send { http.delete("$base/rest/config/devices/$id") { auth() } }
    suspend fun putFolder(folder: JsonObject, id: String) = send { http.put("$base/rest/config/folders/$id") { auth(); json(folder) } }
    suspend fun deleteFolder(id: String) = send { http.delete("$base/rest/config/folders/$id") { auth() } }

    /** Asks Syncthing to look [folder] over now ([sub] only, when given). */
    suspend fun scan(folder: String, sub: String? = null) = send {
        http.post("$base/rest/db/scan") {
            auth()
            parameter("folder", folder)
            sub?.let { parameter("sub", it) }
        }
    }

    private fun HttpRequestBuilder.auth() {
        apiKey?.let { header("X-API-Key", it) }
    }

    private fun HttpRequestBuilder.json(body: JsonElement) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    private suspend fun obj(path: String, extra: HttpRequestBuilder.() -> Unit = {}): JsonObject =
        json.parseToJsonElement(text(path, extra)).jsonObject

    private suspend fun arr(path: String): List<JsonObject> =
        (json.parseToJsonElement(text(path)) as? JsonArray)?.jsonArray?.map { it.jsonObject }.orEmpty()

    private suspend fun text(path: String, extra: HttpRequestBuilder.() -> Unit = {}): String {
        val r = send { http.get("$base$path") { auth(); extra() } }
        return r.bodyAsText()
    }

    private suspend fun send(call: suspend () -> HttpResponse): HttpResponse {
        val r = try {
            call()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw SyncthingException("Syncthing isn't answering at $base.", SyncthingException.Kind.UNREACHABLE)
        }
        when {
            r.status.value == 401 || r.status.value == 403 -> throw SyncthingException("Syncthing didn't take that API key.", SyncthingException.Kind.KEY)
            !r.status.isSuccess() -> throw SyncthingException("Syncthing said no (${r.status.value}).", SyncthingException.Kind.REFUSED)
        }
        return r
    }

    companion object {
        /**
         * The client for Syncthing on this device. Syncthing for Android serves its API over https
         * with a certificate it made itself, which nothing can vouch for; on this device's own
         * address (and only there) that certificate is accepted. Anything else is checked as usual.
         */
        fun client(address: String): HttpClient {
            val loopback = isLoopback(address)
            return HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 3_000
                    requestTimeoutMillis = 15_000
                }
                if (loopback) {
                    engine {
                        https {
                            trustManager = object : X509TrustManager {
                                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                            }
                        }
                    }
                }
            }
        }

        fun isLoopback(address: String): Boolean {
            val host = runCatching { Url(withScheme(address)).host }.getOrDefault("").lowercase()
            return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
        }

        /** "127.0.0.1:8384" becomes "http://127.0.0.1:8384"; an address with its scheme stays. */
        fun withScheme(address: String): String {
            val a = address.trim().trimEnd('/')
            return if (a.startsWith("http://") || a.startsWith("https://")) a else "http://$a"
        }
    }
}
