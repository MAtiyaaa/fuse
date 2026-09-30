package io.github.matiyaaa.fuse.integrations

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/** A Fuse HTTP client on a MockEngine that records every request. Only the transport is fake. */
class TestHttp(private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()

    val client: HttpClient = FuseHttp.client(
        MockEngine { request ->
            requests += request
            handler(request)
        },
        FuseHttpConfig(appVersion = "1.2.3"),
    )

    val last: HttpRequestData get() = requests.last()
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>): HttpResponseData =
    respond(
        content = body,
        status = status,
        headers = headersOf(*(listOf(HttpHeaders.ContentType to listOf("application/json")) + extra.map { it.first to listOf(it.second) }).toTypedArray()),
    )

fun MockRequestHandleScope.text(body: String, status: HttpStatusCode): HttpResponseData =
    respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "text/plain"))

/** Body text of a request sent with a String body. */
val HttpRequestData.bodyText: String get() = (body as? TextContent)?.text.orEmpty()

fun HttpRequestData.param(name: String): String? = url.parameters[name]
