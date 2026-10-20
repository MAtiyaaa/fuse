package io.github.matiyaaa.fuse.launch.online

import io.github.matiyaaa.fuse.launch.settings.YamlText

/**
 * RPCN, RPCS3's own online service, as RPCS3 v0.0.43 keeps a person's account: `rpcn.yml` in its
 * config folder (`rpcs3/Emu/NP/rpcn_config.cpp`, `cfg_rpcn::get_path`: `fs::get_config_dir(true)`)
 * with `Version` 2, `Host`, `NPID` (the user name), `Password` and `Token` (`rpcn_config.h`). RPCS3
 * never keeps the password itself: its account dialog (`rpcs3qt/rpcn_settings_dialog.cpp`,
 * `derive_password`) stretches it with PBKDF2 over HMAC-SHA3-256, 200,000 rounds, the salt "No
 * matter where you go, everybody's connected." and 32 bytes, written as upper-case hexadecimal, and
 * signs in with that. Fuse does the same, so the password is used once and kept nowhere, by Fuse
 * or RPCS3. A user name is at most 16 letters, digits, `_` or `-`, and a token at most 16
 * characters (the dialog's own limits). Pure: text in, text out.
 */
object Rpcn {
    const val FILE = "rpcn.yml"
    const val HOST = "np.rpcs3.net"

    /** The account a file holds, when it has one RPCS3 would sign in with. */
    data class Account(val npid: String, val host: String, val hasToken: Boolean)

    private val USER = Regex("^[A-Za-z0-9_-]{1,16}$")
    private val TOKEN = Regex("^[A-Za-z0-9]{1,16}$")
    private const val SALT = "No matter where you go, everybody's connected."
    private const val ROUNDS = 200_000

    fun validUser(name: String): Boolean = USER.matches(name)
    fun validToken(token: String): Boolean = TOKEN.matches(token)

    /** The password as RPCS3 keeps it (see above). */
    fun derivePassword(password: String): String =
        Sha3.pbkdf2(password.encodeToByteArray(), SALT.encodeToByteArray(), ROUNDS, Sha3.SIZE).joinToString("") { b ->
            (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }

    /** The account in [text] (`rpcn.yml`), or null when it has no user name and password. */
    fun account(text: String?): Account? {
        val y = YamlText(text ?: return null)
        val npid = y.values("", "NPID").lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        y.values("", "Password").lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val host = y.values("", "Host").lastOrNull()?.takeIf { it.isNotBlank() } ?: HOST
        return Account(npid, host, y.values("", "Token").lastOrNull()?.isNotBlank() == true)
    }

    /**
     * [text] signed in as [npid] with the [derived] password and [token]: everything else in it
     * (the server, the list of servers, IPv6) stays as it was. A file not there yet is made as RPCS3
     * makes it, from its defaults.
     */
    fun signIn(text: String?, npid: String, derived: String, token: String): String {
        val y = YamlText(text?.takeIf { it.isNotBlank() } ?: DEFAULTS)
        y.set("", "Version", "2")
        y.set("", "NPID", npid)
        y.set("", "Password", derived)
        y.set("", "Token", token)
        return y.toString()
    }

    /** [text] with the account's [token] (the code RPCN emailed), everything else as it was. */
    fun withToken(text: String, token: String): String {
        val y = YamlText(text)
        y.set("", "Token", token)
        return y.toString()
    }

    /** [text] signed out: its user name stays, the password and token are taken out, as RPCS3's own empty values. */
    fun signOut(text: String): String {
        val y = YamlText(text)
        y.set("", "Password", "")
        y.set("", "Token", "")
        return y.toString()
    }

    // ----------------------------------------------------------------- making an account

    /**
     * RPCN's own port and protocol, as RPCS3's client speaks it (rpcs3/Emu/NP/rpcn_client.cpp):
     * TLS 1.2 to port 31313 (`rpcn_config.cpp`, the host's default port), where the server first
     * sends a ServerInfo packet with its protocol version, which must be RPCS3's (32). Each packet
     * is a 15-byte header (type, command, whole size, id, little-endian) and its data.
     */
    const val PORT = 31313
    const val PROTOCOL = 32
    const val HEADER = 15

    /** The picture RPCS3 gives a new account (`rpcn_settings_dialog.cpp`). */
    const val AVATAR = "https://rpcs3.net/cdn/netplay/DefaultAvatar.png"

    /** Packet types and the one command used here (`rpcn_types.h`). */
    const val REQUEST = 0
    const val REPLY = 1
    const val SERVER_INFO = 3
    const val CREATE = 2

    /** RPCS3's own check of an email address (`rpcn_settings_dialog.cpp`, its validator). */
    private val EMAIL = Regex("^[a-zA-Z0-9.!#$%&\u2019'*+/=?^_`{|}~-]+@[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*$")

    fun validEmail(email: String): Boolean = EMAIL.matches(email)

    /** A packet's header. */
    data class Header(val type: Int, val command: Int, val size: Long, val id: Long)

    fun header(bytes: ByteArray): Header {
        require(bytes.size >= HEADER)
        return Header(bytes[0].toInt() and 0xFF, le(bytes, 1, 2).toInt(), le(bytes, 3, 4), le(bytes, 7, 8))
    }

    /**
     * The request making an account (`rpcn_client::create_user`): the user name, the stretched
     * password, the name shown online (the user name, as RPCS3 sends it), the picture and the email,
     * each ending in a zero byte.
     */
    fun createRequest(id: Long, npid: String, derived: String, email: String): ByteArray {
        val data = listOf(npid, derived, npid, AVATAR, email).flatMap { it.encodeToByteArray().toList() + 0.toByte() }.toByteArray()
        val size = HEADER + data.size
        val out = ByteArray(size)
        out[0] = REQUEST.toByte()
        putLe(out, 1, CREATE.toLong(), 2)
        putLe(out, 3, size.toLong(), 4)
        putLe(out, 7, id, 8)
        data.copyInto(out, HEADER)
        return out
    }

    /** What RPCN's answer to making an account means, in words; null when the account was made (`ErrorType`). */
    fun creationProblem(code: Int): String? = when (code) {
        0 -> null
        3 -> "RPCN didn't take that user name, password or email."
        4 -> "RPCN was asked too soon. Wait a minute, then try again."
        10 -> "RPCN couldn't make the account."
        11 -> "There's already an RPCN account with that user name."
        12 -> "RPCN doesn't take email addresses from that provider."
        13 -> "There's already an RPCN account with that email address."
        24 -> "RPCN couldn't keep the account just now. Try again later."
        25 -> "RPCN couldn't send its email. Try again later."
        else -> "RPCN couldn't make the account (error $code)."
    }

    private fun le(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun putLe(b: ByteArray, at: Int, value: Long, n: Int) {
        for (i in 0 until n) b[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    /** `rpcn.yml` as RPCS3 writes it with nothing set (`rpcn_config.h`'s defaults, in its order). */
    private const val DEFAULTS = "Version: 2\nHost: np.rpcs3.net\nNPID: \"\"\nPassword: \"\"\nToken: \"\"\n" +
        "Hosts: \"Official RPCN Server|np.rpcs3.net\"\nExperimental IPv6 support: false\n"
}

/** RPCN reached over the network: an account made as RPCS3's own account dialog makes it. */
expect object RpcnNetwork {
    /**
     * Makes the RPCN account [npid] at [host] with the [derived] password ([Rpcn.derivePassword]) and
     * [email], to which RPCN sends the account's token. Null when it was made, else why not, in words.
     */
    suspend fun create(host: String, npid: String, derived: String, email: String): String?
}
