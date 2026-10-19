package io.github.matiyaaa.fuse.launch.achievements

import io.github.matiyaaa.fuse.launch.online.OnlineValue
import kotlin.io.encoding.Base64

/**
 * A RetroAchievements sign-in as each emulator keeps its own, from the emulator's own source: the
 * user name and the login token RetroAchievements gave for the password (never the password), in the
 * emulator's own file, section and key, so the emulator signs in by itself the next time it starts.
 *
 * - RetroArch v1.22.2 (`configuration.c`): `cheevos_enable`, `cheevos_username` and `cheevos_token`
 *   in `retroarch.cfg`; with a token it signs in by it before any password (`cheevos/cheevos.c`,
 *   `rc_client_begin_login_with_token`).
 * - PCSX2 v2.8.2 (`pcsx2/Achievements.cpp`): `[Achievements]` `Enabled`, `Username`, `Token` and
 *   `LoginTimestamp`; a token found in the settings file is moved by PCSX2 into its own secrets file
 *   when it starts (`Achievements::Initialize`).
 * - DuckStation v0.1-11894 (`src/core/achievements.cpp`): `[Cheevos]` `Enabled`, `Username`,
 *   `Token` and `LoginTimestamp`, the token encrypted as [duckStationToken] does it.
 * - PPSSPP v1.20.4 (`Core/Config.cpp`, `Core/RetroAchievements.cpp`, `UI/NativeApp.cpp`):
 *   `[Achievements]` `AchievementsEnable` and `AchievementsUserName` in `ppsspp.ini`, and the token
 *   alone in `PSP/SYSTEM/ppsspp_retroachievements.dat` ([PPSSPP_TOKEN_FILE]).
 * - Dolphin 2609a (`Source/Core/Core/Config/AchievementSettings.cpp`): `[Achievements]` `Enabled`,
 *   `Username` and `ApiToken` in `RetroAchievements.ini` beside `Dolphin.ini` ([DOLPHIN_FILE]).
 */
object RaSignIn {
    /** The emulator families whose sign-in Fuse writes. */
    val families: Set<String> = setOf("retroarch", "retroarch-steam", "pcsx2", "duckstation", "ppsspp", "dolphin")

    /** Dolphin's file for RetroAchievements (`System::Achievements`), beside Dolphin.ini. */
    const val DOLPHIN_FILE = "RetroAchievements"

    /** PPSSPP's file for its token, in `PSP/SYSTEM` (`GetSecretPath`, "ppsspp_" + "retroachievements" + ".dat"). */
    const val PPSSPP_TOKEN_FILE = "ppsspp_retroachievements.dat"

    /** The key in each family's files that holds the token itself, which is never kept by Fuse in its records. */
    fun tokenKey(family: String): String? = when (family) {
        "retroarch", "retroarch-steam" -> "cheevos_token"
        "pcsx2", "duckstation" -> "Token"
        "dolphin" -> "ApiToken"
        else -> null
    }

    /**
     * What [family] keeps for [user] signed in with [token] (as its own file has it: [OnlineValue]'s
     * file is null for its main settings file), [nowSeconds] being now; DuckStation's token is the
     * encrypted [duckStationToken]. Empty when Fuse doesn't write this family's sign-in.
     */
    fun values(family: String, user: String, token: String, nowSeconds: Long, duckStationToken: String? = null): List<OnlineValue> = when (family) {
        "retroarch", "retroarch-steam" -> listOf(
            OnlineValue(null, "", "cheevos_enable", "true"),
            OnlineValue(null, "", "cheevos_username", user),
            OnlineValue(null, "", "cheevos_token", token),
        )
        "pcsx2" -> listOf(
            OnlineValue(null, "Achievements", "Enabled", "true"),
            OnlineValue(null, "Achievements", "Username", user),
            OnlineValue(null, "Achievements", "Token", token),
            OnlineValue(null, "Achievements", "LoginTimestamp", nowSeconds.toString()),
        )
        "duckstation" -> if (duckStationToken == null) emptyList() else listOf(
            OnlineValue(null, "Cheevos", "Enabled", "true"),
            OnlineValue(null, "Cheevos", "Username", user),
            OnlineValue(null, "Cheevos", "Token", duckStationToken),
            OnlineValue(null, "Cheevos", "LoginTimestamp", nowSeconds.toString()),
        )
        "ppsspp" -> listOf(
            OnlineValue(null, "Achievements", "AchievementsEnable", "True"),
            OnlineValue(null, "Achievements", "AchievementsUserName", user),
        )
        "dolphin" -> listOf(
            OnlineValue(DOLPHIN_FILE, "Achievements", "Enabled", "True"),
            OnlineValue(DOLPHIN_FILE, "Achievements", "Username", user),
            OnlineValue(DOLPHIN_FILE, "Achievements", "ApiToken", token),
        )
        else -> emptyList()
    }

    /** Who [family]'s files say is signed in, from what [read] finds for each value of [values]; null when no one is. */
    fun signedInAs(family: String, read: (OnlineValue) -> String?): String? {
        val user = values(family, "", "", 0, "").firstOrNull { it.key.contains("user", ignoreCase = true) }?.let(read)?.trim()
        return user?.takeIf { it.isNotEmpty() }
    }

    /**
     * The token as DuckStation keeps it (`EncryptLoginToken`): AES-128 in CBC mode over the token
     * padded with zeros to whole blocks, with the key and the IV from [duckStationKey], in Base64.
     */
    fun duckStationToken(token: String, user: String, machineKey: String?): String {
        val (key, iv) = duckStationKey(user, machineKey)
        val bytes = token.encodeToByteArray()
        val padded = bytes.copyOf((bytes.size + 15) / 16 * 16)
        return Base64.Default.encode(RaCrypto.aes128CbcEncrypt(key, iv, padded))
    }

    /** [encrypted] as DuckStation reads it back (`DecryptLoginToken`); null when it doesn't decrypt to text. */
    fun duckStationTokenOf(encrypted: String, user: String, machineKey: String?): String? {
        val data = runCatching { Base64.Default.decode(encrypted.trim()) }.getOrNull() ?: return null
        if (data.isEmpty() || data.size % 16 != 0) return null
        val (key, iv) = duckStationKey(user, machineKey)
        val plain = runCatching { RaCrypto.aes128CbcDecrypt(key, iv, data) }.getOrNull() ?: return null
        return plain.decodeToString().trimEnd('\u0000').takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x20..0x7e } }
    }

    /**
     * DuckStation's key and IV (`GetLoginEncryptionKey`): SHA-256 of [machineKey] (when it isn't
     * portable, and not on Android) then the user name, hashed 100 more times; the first 16 bytes are
     * the key and the last 16 the IV. [machineKey] is exactly the text DuckStation reads for it (see
     * [MACHINE_KEY]); null when DuckStation runs portable.
     */
    fun duckStationKey(user: String, machineKey: String?): Pair<ByteArray, ByteArray> {
        var key = RaCrypto.sha256((machineKey?.encodeToByteArray() ?: ByteArray(0)) + user.encodeToByteArray())
        repeat(100) { key = RaCrypto.sha256(key) }
        return key.copyOfRange(0, 16) to key.copyOfRange(16, 32)
    }

    /**
     * Where DuckStation's machine key comes from (`GetLoginEncryptionMachineKey`), for the host to
     * read: on Linux the whole of `/etc/machine-id` (its line break too); on Windows the registry's
     * `HKLM\SOFTWARE\Microsoft\Cryptography` `MachineGuid` with its closing NUL (the length
     * `RegGetValueA` gives counts it); on macOS the host's UUID (`gethostuuid`) as 32 lowercase hex
     * digits. A wrong one costs nothing: DuckStation asks to sign in again.
     */
    const val MACHINE_KEY = "DuckStation's machine key"

    /** Hex of SHA-256 over [text], for recording what Fuse wrote without keeping the token itself. */
    fun fingerprint(text: String): String = RaCrypto.sha256(text.encodeToByteArray()).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** A RetroAchievements user name as RetroAchievements accepts one: letters and digits, 2 to 20. */
    fun validUser(user: String): Boolean = user.length in 2..20 && user.all { it.isLetterOrDigit() && it.code < 128 }

    /** A login token as RetroAchievements gives one: letters and digits only (what every file above can hold as written). */
    fun validToken(token: String): Boolean = token.length in 8..128 && token.all { it.isLetterOrDigit() && it.code < 128 }
}
