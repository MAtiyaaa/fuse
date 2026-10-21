package io.github.matiyaaa.fuse.integrations.ports

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.model.AndroidPorts
import io.github.matiyaaa.fuse.model.Port
import io.github.matiyaaa.fuse.model.PortTokens
import io.github.matiyaaa.fuse.model.PortsIndex
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import kotlin.io.encoding.Base64

/**
 * An index read and checked: its ports, and the ones left out with why ([skipped], by id). A
 * [signed] one carries the [text] and [signature] it was believed by, to be kept and checked again.
 */
data class PortsList(
    val index: PortsIndex,
    val skipped: Map<String, String> = emptyMap(),
    val signed: Boolean = false,
    val text: String = "",
    val signature: String? = null,
)

/**
 * Reads Fuse Ports' index (`ports/index.json`, see INTEGRATIONS.md, Fuse Ports) and checks every
 * port in it before anything is shown: an id of letters, digits and dashes; each build an HTTPS
 * file with its size and SHA-256; every path inside Fuse's copy relative and without `..`; the
 * arguments' placeholders only ones Fuse fills. A port that fails is left out with the reason; an
 * index of another format is refused whole.
 */
object PortsIndexReader {
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val PLACEHOLDER = Regex("\\{[^}]*\\}")
    private val XXH3 = Regex("^[0-9a-f]{16}$")
    private val ENV = Regex("^[A-Z_][A-Z0-9_]{0,63}$")

    /** The largest build Fuse downloads (2 GiB). */
    const val MAX_BUILD = 2L shl 30

    fun read(text: String, signature: String? = null): Result<PortsList> {
        val index = runCatching { FuseHttp.json.decodeFromString(PortsIndex.serializer(), text) }.getOrElse {
            return Result.failure(IllegalArgumentException("The list of ports couldn't be read."))
        }
        if (index.format != PortsIndex.FORMAT) return Result.failure(IllegalArgumentException("The list of ports is in a newer format than this Fuse reads. Update Fuse."))
        val skipped = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        val ports = index.ports.filter { port ->
            val problem = problem(port) ?: if (!seen.add(port.id)) "it is listed twice" else null
            if (problem != null) skipped[port.id] = problem
            problem == null
        }
            // A picture that isn't over HTTPS is left out, not the port.
            .map { p -> AndroidPorts.picture(p.cover).let { c -> p.copy(cover = c, coverCredit = p.coverCredit.takeIf { c != null }) } }
        // Android ports Fuse checked: each is one of Fuse's own, whatever the entry says.
        val androidSeen = HashSet<String>()
        val android = index.android.mapNotNull { port ->
            val problem = androidPortProblem(port) ?: if (!androidSeen.add(port.id)) "it is listed twice" else null
            if (problem != null) skipped["android:${port.id}"] = problem
            val cover = AndroidPorts.picture(port.cover)
            port.takeIf { problem == null }?.copy(
                checked = true,
                listedVerified = false,
                cover = cover,
                coverCredit = port.coverCredit.takeIf { cover != null },
                screenshot = AndroidPorts.picture(port.screenshot),
            )
        }
        return Result.success(PortsList(index.copy(ports = ports, android = android), skipped, signed = signature != null, text = text, signature = signature))
    }

    /** Why [port] can't be offered, or null when it can. */
    fun problem(port: Port): String? {
        if (!ID.matches(port.id)) return "its id isn't letters, digits and dashes"
        if (port.name.isBlank() || port.game.isBlank() || port.version.isBlank()) return "it has no name, game or version"
        if (!port.homepage.startsWith("https://")) return "its page isn't an HTTPS address"
        if (port.builds.isEmpty()) return "it has no builds"
        for (b in port.builds) {
            if (b.os !in OSES || b.arch !in ARCHES) return "a build is for a system Fuse doesn't know (${b.os}, ${b.arch})"
            if (!b.url.startsWith("https://") || b.url.any { it.isWhitespace() }) return "a build isn't offered over HTTPS"
            if (!SHA256.matches(b.sha256)) return "a build has no SHA-256 to check it by"
            if (b.size <= 0 || b.size > MAX_BUILD) return "a build's size isn't one Fuse downloads"
            if (!safe(b.run)) return "a build's program isn't inside it"
        }
        val dataIds = port.data.map { it.id }
        if (dataIds.size != dataIds.toSet().size || dataIds.any { !ID.matches(it) }) return "its game files are named twice or oddly"
        for (d in port.data) {
            val r = d.rom
            if (r != null) {
                if (d.needs.isNotEmpty() || d.overlay != null) return "it needs a ROM and a folder at once"
                if (!XXH3.matches(r.xxh3) || !ID.matches(r.system)) return "the ROM it needs isn't said"
                if (!safe(r.place)) return "its ROM would go outside it"
                continue
            }
            if (d.needs.isEmpty() || d.needs.any { names -> names.isEmpty() || names.any { !safe(it) } }) return "what it needs from the game isn't said"
            d.overlay?.let { o ->
                if (o.into.isNotEmpty() && !safe(o.into)) return "its game files would go outside it"
                if (o.own.any { !safe(it.trimEnd('/')) }) return "what it writes would go outside it"
            }
        }
        if (port.data.count { it.overlay != null } > 1) return "it links more than one folder in"
        port.saves?.let { if (it.isNotEmpty() && !safe(it)) return "its saves would go outside it" }
        if (port.env.keys.any { !ENV.matches(it) }) return "it names a variable oddly"
        for (arg in port.args + port.env.values) {
            for (m in PLACEHOLDER.findAll(arg)) {
                val token = m.value
                val known = token == PortTokens.INSTALL || token == PortTokens.HOME ||
                    (token.startsWith(PortTokens.DATA_PREFIX) && token.removePrefix(PortTokens.DATA_PREFIX).removeSuffix("}") in dataIds)
                if (!known) return "its command names $token, which Fuse doesn't fill"
            }
        }
        port.check?.expect?.let { if (runCatching { Regex(it) }.isFailure) return "its check can't be read" }
        port.settings?.let { st ->
            val inside = listOf(PortTokens.HOME, PortTokens.INSTALL).firstOrNull { st.file.startsWith("$it/") }?.let { st.file.removePrefix("$it/") }
            if (inside == null || !safe(inside)) return "its settings file would be outside it"
            if (st.format !in SETTINGS_FORMATS) return "its settings are in a form Fuse doesn't write"
        }
        return null
    }

    /** A relative path with `/` that stays inside its folder. */
    internal fun safe(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && '\\' !in path && ':' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private val OSES = setOf("linux", "windows", "macos")
    private val SETTINGS_FORMATS = setOf("ini")
    private val ARCHES = setOf("x86_64", "arm64")
}

/**
 * Checks the index's Ed25519 signature (RFC 8032) with the public key Fuse carries, [PUBLIC_KEY]:
 * the project's signing key, whose private half is only the repository secret `PORTS_SIGNING_KEY`
 * the ports job signs with (`tools/ports/check.py publish`). A list it doesn't verify is never believed.
 */
object PortsSignature {
    /** Base64 of the raw 32-byte Ed25519 public key the published index is signed with. */
    val PUBLIC_KEY: String? = "8M/UoVY406Ritfva7PVr/xOn3xsTasxo0nQdb/QQM3Q="

    /** True when [signature] (Base64 of 64 bytes) is [publicKey]'s (Base64 of 32 bytes) over exactly [message]. */
    fun verify(message: ByteArray, signature: String, publicKey: String): Boolean {
        val sig = runCatching { Base64.Default.decode(signature.trim()) }.getOrNull() ?: return false
        val key = runCatching { Base64.Default.decode(publicKey.trim()) }.getOrNull() ?: return false
        if (sig.size != 64 || key.size != 32) return false
        return ed25519Verify(message, sig, key)
    }
}

/** Ed25519 verification by the platform (the JDK's own on a computer); false where it can't. */
internal expect fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean

/**
 * Fetches the published index and its signature (the `ports` release of Fuse's repository, made
 * by the ports job) and believes them only when the signature verifies with [publicKey] and the
 * index is newer than [newerThan]. Never called before the person turns Fuse Ports on.
 */
class PortsClient(
    http: HttpClient,
    private val indexUrl: String = INDEX_URL,
    private val publicKey: () -> String? = { PortsSignature.PUBLIC_KEY },
) {
    private val api = ProviderHttp(http, "Fuse Ports", limiter = null, secrets = { emptyList() })

    suspend fun fetch(newerThan: Long): ApiResult<PortsList?> {
        val key = publicKey() ?: return ApiResult.NotConfigured("This Fuse carries no key to check a newer list of ports with.")
        val text = when (val r = get(indexUrl)) {
            is ApiResult.Success -> r.value
            // None published yet (before the ports job first signs one): the list inside Fuse stands.
            is ApiResult.HttpError -> if (r.code == 404) return ApiResult.Success(null) else return r
            is ApiResult.Failure -> return r
        }
        val signature = when (val r = get("$indexUrl.sig")) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return r
        }
        if (!PortsSignature.verify(text.encodeToByteArray(), signature, key)) {
            return ApiResult.InvalidResponse("The list of ports didn't carry Fuse's signature, so it wasn't used.")
        }
        val list = PortsIndexReader.read(text, signature).getOrElse { return ApiResult.InvalidResponse(it.message ?: "The list of ports couldn't be read.") }
        return ApiResult.Success(list.takeIf { it.index.revision > newerThan })
    }

    private suspend fun get(url: String): ApiResult<String> {
        val raw = when (val r = api.execute { url(url) }) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return r
        }
        api.failureFor(raw)?.let { return it }
        // The address it came from in the end must still be HTTPS (GitHub hands release files over to its own file host).
        if (raw.url.isNotEmpty() && !raw.url.startsWith("https://")) return ApiResult.InvalidResponse("The list of ports came from an address that isn't HTTPS.")
        return ApiResult.Success(raw.body)
    }

    companion object {
        const val INDEX_URL = "https://github.com/matiyaaa/fuse/releases/download/ports/index.json"
    }
}
