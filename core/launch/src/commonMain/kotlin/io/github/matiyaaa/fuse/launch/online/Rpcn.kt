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

    /** [text] signed out: its user name stays, the password and token are taken out, as RPCS3's own empty values. */
    fun signOut(text: String): String {
        val y = YamlText(text)
        y.set("", "Password", "")
        y.set("", "Token", "")
        return y.toString()
    }

    /** `rpcn.yml` as RPCS3 writes it with nothing set (`rpcn_config.h`'s defaults, in its order). */
    private const val DEFAULTS = "Version: 2\nHost: np.rpcs3.net\nNPID: \"\"\nPassword: \"\"\nToken: \"\"\n" +
        "Hosts: \"Official RPCN Server|np.rpcs3.net\"\nExperimental IPv6 support: false\n"
}
