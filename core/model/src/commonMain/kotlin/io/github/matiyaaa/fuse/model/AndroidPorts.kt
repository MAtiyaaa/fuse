package io.github.matiyaaa.fuse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A port published as an Android app: an APK its own project puts in the releases of [repo], which
 * takes the game's files inside the app itself (a ROM or a game's folder picked on its first start).
 * Fuse installs it, attaches it to the person's copy of the game and starts it in the emulator's
 * place. Two kinds of entry exist: ones Fuse checked against the port's own source ([checked], from
 * the `android` list of the signed `ports/index.json`), and ones listed by Open Ports Games
 * (openportsgames.com, data under CC BY 4.0), shown as unchecked.
 */
@Serializable
data class AndroidPort(
    /** Lower case letters, digits and dashes: Open Ports Games' id, or Fuse's for a checked entry. */
    val id: String,
    /** The port's own name ("2 Ship 2 Harkinian"). */
    val name: String,
    /** The game it plays ("The Legend of Zelda: Majora's Mask"). */
    val game: String,
    /** Other names the game goes by, matched like [game] ("Pokémon Blue"). */
    val titles: List<String> = emptyList(),
    /** The library's systems the game comes from ("n64"); empty when none of Fuse's is. */
    val systems: List<String> = emptyList(),
    val kind: AndroidPortKind = AndroidPortKind.SOURCE_PORT,
    val status: AndroidPortStatus = AndroidPortStatus.BETA,
    /** The project's page; a GitHub repository is installed from its releases. */
    val repo: String,
    /** The licence as its project or listing names it (an SPDX id). */
    val licence: String? = null,
    val about: String? = null,
    /** What the app asks for on its first start, in words ("Your Majora's Mask ROM, the US one"). */
    val needs: String? = null,
    /** Its Android package, when Fuse read it from the port's source; else learned on first install. */
    @SerialName("package") val packageName: String? = null,
    /** Which APK of a release, when there are several: a regular expression over the file's name. */
    val asset: String? = null,
    /** ROMs it is made for, by the XXH3-64 of their big-endian form (16 hex digits, as [PortRom]). */
    val roms: List<String> = emptyList(),
    /** File types it opens from another app (its manifest's VIEW filter), for handing a ROM over. */
    val opens: List<String> = emptyList(),
    /** Where each fact comes from in the port's own code or documentation. */
    val source: String? = null,
    /** True for an entry of Fuse's own list, read from the port's source. */
    val checked: Boolean = false,
    /** True when Open Ports Games marks it verified. */
    val listedVerified: Boolean = false,
    /** Who published the original game, as its listing names them ("Nintendo"). */
    val publisher: String? = null,
    /** The year the original game came out. */
    val year: Int? = null,
    /** The original game's genre, as its listing sorts it ("action-adventure"). */
    val genre: String? = null,
    /** The original game's box art: an HTTPS picture its listing or Fuse's list points to. */
    val cover: String? = null,
    /** Where [cover] comes from, as its listing credits it ("Wikipedia"). */
    val coverCredit: String? = null,
    /** A picture of the port being played: an HTTPS picture its listing points to. */
    val screenshot: String? = null,
) {
    /** True when Fuse can install it: an HTTPS GitHub repository, whose releases hold the APK. */
    val installable: Boolean get() = AndroidPorts.githubRepo(repo) != null
}

/** How a port was made, as Open Ports Games sorts them. */
@Serializable
enum class AndroidPortKind {
    @SerialName("recompilation") RECOMPILATION,
    @SerialName("decompilation") DECOMPILATION,
    @SerialName("source-port") SOURCE_PORT,
    @SerialName("runtime-port") RUNTIME_PORT,
    @SerialName("reimplementation") REIMPLEMENTATION,
}

/** How far along a port is, by its listing. */
@Serializable
enum class AndroidPortStatus {
    @SerialName("stable") STABLE,
    @SerialName("beta") BETA,
    @SerialName("alpha") ALPHA,
}

object AndroidPorts {
    private val GITHUB = Regex("^https://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})/?$")

    /**
     * [url] when it is a picture Fuse may show: an HTTPS address of a reasonable length with no
     * spaces in it. Anything else (plain HTTP, a file, a data address) is no picture.
     */
    fun picture(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") && it.length <= 2048 && it.none(Char::isWhitespace) }

    /** The owner and name of a GitHub repository's page, or null when [url] isn't one. */
    fun githubRepo(url: String): Pair<String, String>? {
        val m = GITHUB.matchEntire(url.trim()) ?: return null
        val name = m.groupValues[2].removeSuffix(".git")
        if (name.isEmpty() || name == "." || name == "..") return null
        return m.groupValues[1] to name
    }
}
