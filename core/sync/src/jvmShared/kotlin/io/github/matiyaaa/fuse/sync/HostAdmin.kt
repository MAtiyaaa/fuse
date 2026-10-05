package io.github.matiyaaa.fuse.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Fuse managing a host that runs as its own background process on this computer (the service),
 * through the host's management calls: from this computer only, with the token in the host's folder.
 */
class HostAdmin(private val port: Int, private val token: String, private val http: HttpClient = SyncClient.defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val base get() = "http://127.0.0.1:$port${SyncApi.BASE}/admin"

    private suspend fun call(method: HttpMethod, path: String, body: String? = null): String {
        val resp = http.request {
            this.method = method
            url.takeFrom(base + path)
            header("X-Fuse-Admin", token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw SyncException(runCatching { json.decodeFromString(ApiError.serializer(), text).error }.getOrDefault("The host said no."), "admin", resp.status.value)
        return text
    }

    suspend fun pairingCode(): String = json.decodeFromString(String.serializer(), call(HttpMethod.Post, "/pairing"))
    suspend fun status(): HostStatus = json.decodeFromString(HostStatus.serializer(), call(HttpMethod.Get, "/status"))
    suspend fun rename(id: String, name: String) { call(HttpMethod.Patch, "/devices/$id", json.encodeToString(DeviceChange.serializer(), DeviceChange(name = name))) }
    suspend fun revoke(id: String) { call(HttpMethod.Delete, "/devices/$id") }

    companion object {
        /** The admin for a host already serving [port] from [hostDir], or null when nothing does. */
        fun of(hostDir: File, port: Int): HostAdmin? {
            val token = File(hostDir, "admin.token").takeIf { it.isFile }?.readText()?.trim() ?: return null
            return if (portFree(port)) null else HostAdmin(port, token)
        }

        /** True when nothing listens on [port] on this computer. */
        fun portFree(port: Int): Boolean = runCatching { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(port)) } }.isSuccess
    }
}
