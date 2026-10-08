package io.github.matiyaaa.fuse.library.steam

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem

/** Public local Steam identity, without saved credentials or an assertion that its session is authenticated. */
data class SteamAccount(
    val steamId: String,
    val personaName: String,
    val mostRecent: Boolean,
    val lastSeenSeconds: Long?,
    val installation: String,
)

/**
 * Reads only loginusers.vdf's identity fields. MostRecent identifies a remembered account, not a
 * currently authenticated Steam session or a licence. Never reads config credentials/ConnectCache.
 * The remembered-account format is taken from local Steam loginusers.vdf files.
 */
class SteamAccounts(private val fs: FuseFileSystem) {
    suspend fun read(installations: List<String>): List<SteamAccount> = installations.distinct().flatMap { root ->
        fs.readText(FsPath.join(root, "config", "loginusers.vdf"), 256 * 1024)?.let { parse(it, root) }.orEmpty()
    }.distinctBy { it.steamId to it.installation }

    companion object {
        fun parse(text: String, installation: String): List<SteamAccount> {
            val users = Vdf.parse(text)["users"] as? Map<*, *> ?: return emptyList()
            return users.mapNotNull { (id, value) ->
                val steamId = id as? String ?: return@mapNotNull null
                if (steamId.toULongOrNull() == null || steamId.length != 17) return@mapNotNull null
                val fields = value as? Map<*, *> ?: return@mapNotNull null
                SteamAccount(
                    steamId, (fields["personaname"] as? String).orEmpty().ifBlank { "Steam user" },
                    fields["mostrecent"] == "1", (fields["timestamp"] as? String)?.toLongOrNull(), installation,
                )
            }
        }
    }
}
