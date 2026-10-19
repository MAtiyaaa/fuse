package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.retroachievements.RaConnect
import io.github.matiyaaa.fuse.launch.achievements.RaSignIn
import io.github.matiyaaa.fuse.launch.online.OnlineValue
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.settings.CfgText
import io.github.matiyaaa.fuse.launch.settings.SettingsEdit
import io.github.matiyaaa.fuse.launch.settings.SettingsText
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIn
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignInResult
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIns

/**
 * Signing the emulators here in to RetroAchievements once, for the profile in use ([RaSignIn]): the
 * password goes to RetroAchievements in one request for a login token ([RaConnect]) and is kept
 * nowhere; the token is kept in the secret store (the profile's own, and the device's when it is the
 * device's account) and written into each emulator's own files, where it would write it itself.
 *
 * What changes is recorded so Sign Emulators Out puts back exactly what was there: the plain values
 * as every other setting change is ([SettingsEdit], under [KIND]), and a token only as whether one
 * was there and a fingerprint of the one Fuse wrote; a token the emulator held before is kept in the
 * secret store, never in Fuse's records. Nothing changes while a game is being played.
 */
internal class RaEmulatorSignIn(
    private val ctx: StoreContext,
    private val credentials: DefaultCredentialOps,
    /** The RetroAchievements user name of the profile in use (or the device's), or null. */
    private val account: suspend () -> String?,
    /** The profile in use, or null without profiles. */
    private val profile: () -> String?,
) {
    private val fs get() = ctx.services.fs

    /** One emulator's files for its sign-in: the main settings file, Dolphin's own file for it, and PPSSPP's token file. */
    private class Place(val installed: InstalledEmulator, val family: String, val files: EmulatorFiles, val main: String, val extra: Map<String, String>, val tokenFile: String?, val cfg: Boolean, val machineKey: String?) {
        fun pathOf(file: String?): String = file?.let { extra[it] } ?: main
        fun text(content: String?): SettingsText = if (cfg) CfgText(content ?: "") else IniText(content ?: "")
        val paths: List<String> get() = (listOf(main) + extra.values).distinct()
    }

    private fun family(e: InstalledEmulator) = e.id.value.substringAfter('.')

    /** Where [e] keeps its sign-in, or why Fuse can't write it. */
    private suspend fun place(e: InstalledEmulator, files: EmulatorFiles): Pair<Place?, String?> {
        val family = family(e)
        val notSetUp = "Start ${e.name} once so it sets up its folders."
        return when (family) {
            "retroarch", "retroarch-steam" -> {
                val config = files.retroArch(e)?.config ?: return null to notSetUp
                Place(e, family, files, config, emptyMap(), null, cfg = true, machineKey = null) to null
            }
            "pcsx2" -> {
                val home = files.pcsx2(e) ?: return null to notSetUp
                Place(e, family, files, home.settingsFile, emptyMap(), null, cfg = false, machineKey = null) to null
            }
            "duckstation" -> {
                val home = files.duckstation(e) ?: return null to notSetUp
                // DuckStation's key is made from this computer's own id; "" when it runs portable.
                val machine = files.duckStationMachineKey(e) ?: return null to "Fuse can't read the id DuckStation encrypts its sign-in with on this computer. Sign in from DuckStation's own settings."
                Place(e, family, files, home.settingsFile, emptyMap(), null, cfg = false, machineKey = machine) to null
            }
            "ppsspp" -> {
                val home = files.ppsspp(e) ?: return null to notSetUp
                Place(e, family, files, home.settingsFile, emptyMap(), "${home.memstick}/PSP/SYSTEM/${RaSignIn.PPSSPP_TOKEN_FILE}", cfg = false, machineKey = null) to null
            }
            "dolphin" -> {
                val home = files.dolphin(e) ?: return null to notSetUp
                Place(e, family, files, home.file(null), mapOf(RaSignIn.DOLPHIN_FILE to home.file(RaSignIn.DOLPHIN_FILE)), null, cfg = false, machineKey = null) to null
            }
            else -> null to "Fuse doesn't sign ${e.name} in."
        }
    }

    private suspend fun read(p: Place, path: String): SettingsText = p.text(fs.readText(path, MAX_TEXT))

    /** Each emulator here that keeps a RetroAchievements sign-in, and who it is signed in as. */
    suspend fun list(): EmulatorSignIns {
        val files = ctx.services.emulatorFiles
            ?: return EmulatorSignIns.Unavailable("On this device, each emulator signs in to RetroAchievements from its own settings.")
        val user = account()
        val list = ctx.installed.value.filter { family(it) in RaSignIn.families }.map { e ->
            val (p, problem) = place(e, files)
            if (p == null) return@map EmulatorSignIn(e.name, family(e), null, byFuse = false, problem = problem)
            val texts = HashMap<String, SettingsText>()
            for (path in p.paths) texts[path] = read(p, path)
            val who = RaSignIn.signedInAs(p.family) { v -> texts[p.pathOf(v.file)]?.values(v.section, v.key)?.lastOrNull() }
            EmulatorSignIn(e.name, p.family, who, byFuse = p.paths.any { ctx.data.owned.get(KIND, it).isNotEmpty() })
        }
        return EmulatorSignIns.Ready(user, list)
    }

    /**
     * Signs the account of the profile in use in with [password] (used for this one request) and
     * every emulator here in with its token.
     */
    suspend fun signIn(password: String): EmulatorSignInResult {
        val user = account() ?: return EmulatorSignInResult(problem = "Connect your RetroAchievements account first.")
        if (password.isEmpty()) return EmulatorSignInResult(problem = "Type your RetroAchievements password.")
        if (ctx.services.emulatorFiles == null) return EmulatorSignInResult(problem = "On this device, each emulator signs in from its own settings.")
        if (ctx.playing.value != null) return EmulatorSignInResult(problem = "A game is being played. Sign the emulators in once it has closed.")
        val login = when (val r = RaConnect(ctx.services.http, "Fuse/${ctx.services.appVersion}").login(user, password)) {
            is ApiResult.Success -> r.value
            is ApiResult.AuthError -> return EmulatorSignInResult(problem = r.message)
            is ApiResult.Failure -> return EmulatorSignInResult(problem = "Couldn't reach RetroAchievements: ${r.message}")
        }
        val token = login.token.reveal()
        if (!RaSignIn.validToken(token)) return EmulatorSignInResult(problem = "RetroAchievements answered with a token Fuse can't write.")
        // Kept for this profile, so a switch back to it (or an emulator installed later) signs in again without the password.
        profile()?.let { credentials.put("${SecretKeys.RA_TOKEN}@$it", token) }
        if (profile() == null || credentials.get(SecretKeys.RA_USERNAME)?.equals(login.user, ignoreCase = true) == true) credentials.put(SecretKeys.RA_TOKEN, token)
        // The name as RetroAchievements spells it becomes the account's, so a switch back writes the same name.
        if (login.user != user && login.user.equals(user, ignoreCase = true)) {
            listOfNotNull(profile()?.let { "${SecretKeys.RA_USERNAME}@$it" }, SecretKeys.RA_USERNAME)
                .firstOrNull { credentials.get(it) == user }?.let { credentials.put(it, login.user) }
        }
        return write(login.user, token)
    }

    /** The token kept for the profile in use (its own, else the device's when the account is the device's). */
    private suspend fun keptToken(user: String): String? {
        profile()?.let { p -> credentials.get("${SecretKeys.RA_TOKEN}@$p")?.let { return it } }
        return credentials.get(SecretKeys.RA_TOKEN)?.takeIf { credentials.get(SecretKeys.RA_USERNAME)?.equals(user, ignoreCase = true) == true }
    }

    /**
     * After the profile in use changed: the emulators Fuse signed in before are signed in as this
     * profile's account (or signed out, when it has none), so each person's unlocks are their own.
     * Returns what to say, or null when nothing changed.
     */
    suspend fun follow(): String? {
        val files = ctx.services.emulatorFiles ?: return null
        if (ctx.playing.value != null) return null
        val signed = ctx.installed.value.filter { family(it) in RaSignIn.families }.mapNotNull { e ->
            place(e, files).first?.takeIf { p -> p.paths.any { ctx.data.owned.get(KIND, it).isNotEmpty() } }
        }
        if (signed.isEmpty()) return null
        val user = account()
        val token = user?.let { keptToken(it) }
        if (user == null || token == null) {
            signed.forEach { restore(it) }
            return "The emulators Fuse signed in to RetroAchievements are signed out for this profile"
        }
        val done = signed.filter { apply(it, user, token) == null }
        return if (done.isEmpty()) null else "${done.joinToString(", ") { it.installed.name }} signed in to RetroAchievements as $user"
    }

    private suspend fun write(user: String, token: String): EmulatorSignInResult {
        val files = ctx.services.emulatorFiles ?: return EmulatorSignInResult(problem = "On this device, each emulator signs in from its own settings.")
        val signedIn = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (e in ctx.installed.value.filter { family(it) in RaSignIn.families }) {
            val (p, problem) = place(e, files)
            if (p == null) {
                failed += e.name to (problem ?: "Fuse can't write its sign-in.")
                continue
            }
            val why = apply(p, user, token)
            if (why == null) signedIn += e.name else failed += e.name to why
        }
        return EmulatorSignInResult(signedIn = signedIn, failed = failed, problem = if (signedIn.isEmpty() && failed.isEmpty()) "No emulator here keeps a RetroAchievements sign-in Fuse can write." else null)
    }

    /** Writes [user] and [token] into [p]'s files; null when done, else why not. */
    private suspend fun apply(p: Place, user: String, token: String): String? {
        val duck = if (p.family == "duckstation") RaSignIn.duckStationToken(token, user, p.machineKey?.takeIf { it.isNotEmpty() }) else null
        val values = RaSignIn.values(p.family, user, token, ctx.now() / 1000, duck)
        if (values.isEmpty()) return "Fuse doesn't sign ${p.installed.name} in."
        val tokenKey = RaSignIn.tokenKey(p.family)
        for ((file, these) in values.groupBy { it.file }) {
            val path = p.pathOf(file)
            val before = fs.readText(path, MAX_TEXT)
            val text = p.text(before)
            var records = ctx.data.owned.get(KIND, path)
            var tokens = ctx.data.owned.get(TOKENS, path)
            for (v in these) {
                if (v.key == tokenKey) tokens = changeToken(path, text, tokens, v) else records = SettingsEdit.change(text, records, v.section, v.key, v.value)
            }
            if (!p.files.write(path, text.toString())) return "Fuse couldn't write ${p.installed.name}'s settings."
            ctx.data.owned.set(KIND, path, records)
            ctx.data.owned.set(TOKENS, path, tokens)
            if (before == null) ctx.data.owned.set(KIND, "$path#made", setOf("file"))
        }
        p.tokenFile?.let { f ->
            val before = fs.readText(f, MAX_TEXT)
            if (before != token) {
                val earlier = ctx.data.owned.get(TOKENS, f).firstOrNull()
                if (earlier == null) {
                    // What was there first is kept for putting back, in the secret store only.
                    before?.let { credentials.put(was(f, "", ""), it) }
                    ctx.data.owned.set(TOKENS, f, setOf(record("", "", before != null, RaSignIn.fingerprint(token))))
                } else {
                    ctx.data.owned.set(TOKENS, f, setOf(earlier.split(SEP).let { record("", "", it[2] == "1", RaSignIn.fingerprint(token)) }))
                }
                if (!p.files.writePrivate(f, token)) return "Fuse couldn't write ${p.installed.name}'s sign-in."
            }
        }
        return null
    }

    /** Sets the token value [v] in [text], keeping in [tokens] whether one was there and a fingerprint of this one (the one there before goes to the secret store). */
    private suspend fun changeToken(path: String, text: SettingsText, tokens: Set<String>, v: OnlineValue): Set<String> {
        val earlier = tokens.firstOrNull { it.startsWith("${v.section}$SEP${v.key}$SEP") }
        val existed = if (earlier != null) earlier.split(SEP)[2] == "1" else {
            val now = text.values(v.section, v.key).lastOrNull()
            now?.let { credentials.put(was(path, v.section, v.key), it) }
            now != null
        }
        text.set(v.section, v.key, v.value)
        return tokens - setOfNotNull(earlier) + record(v.section, v.key, existed, RaSignIn.fingerprint(v.value))
    }

    /** Signs out every emulator Fuse signed in: what was there before is put back; a value changed in the emulator since stays. */
    suspend fun signOut(): Boolean {
        val files = ctx.services.emulatorFiles ?: return false
        if (ctx.playing.value != null) return false
        var any = false
        for (e in ctx.installed.value.filter { family(it) in RaSignIn.families }) {
            val p = place(e, files).first ?: continue
            if (restore(p)) any = true
        }
        return any
    }

    private suspend fun restore(p: Place): Boolean {
        var any = false
        for (path in p.paths) {
            val records = ctx.data.owned.get(KIND, path)
            val tokens = ctx.data.owned.get(TOKENS, path)
            if (records.isEmpty() && tokens.isEmpty()) continue
            val before = fs.readText(path, MAX_TEXT) ?: run { forget(path); continue }
            val text = p.text(before)
            // The tokens first, so a section Fuse made is empty when the rest is put back.
            for (r in tokens) {
                val (section, key, existed, print) = r.split(SEP).takeIf { it.size == 4 } ?: continue
                val now = text.values(section, key).lastOrNull()
                // A token the emulator wrote itself since (signed in again there) stays.
                if (now == null || RaSignIn.fingerprint(now) != print) continue
                val earlier = if (existed == "1") credentials.get(was(path, section, key)) else null
                if (earlier != null) text.set(section, key, earlier) else text.removeKey(section, key)
            }
            SettingsEdit.restore(text, records)
            val made = ctx.data.owned.get(KIND, "$path#made").isNotEmpty()
            val done = if (made && !text.hasEntries()) p.files.remove(path) else p.files.write(path, text.toString())
            if (!done) return any
            forget(path)
            any = true
        }
        p.tokenFile?.let { f ->
            val r = ctx.data.owned.get(TOKENS, f).firstOrNull()?.split(SEP) ?: return@let
            val now = fs.readText(f, MAX_TEXT)
            if (now != null && RaSignIn.fingerprint(now) == r.getOrNull(3)) {
                val earlier = if (r.getOrNull(2) == "1") credentials.get(was(f, "", "")) else null
                if (earlier != null) p.files.writePrivate(f, earlier) else p.files.remove(f)
            }
            credentials.remove(was(f, "", ""))
            ctx.data.owned.set(TOKENS, f, emptySet())
            any = true
        }
        return any
    }

    private suspend fun forget(path: String) {
        for (r in ctx.data.owned.get(TOKENS, path)) r.split(SEP).takeIf { it.size == 4 }?.let { credentials.remove(was(path, it[0], it[1])) }
        ctx.data.owned.set(KIND, path, emptySet())
        ctx.data.owned.set(KIND, "$path#made", emptySet())
        ctx.data.owned.set(TOKENS, path, emptySet())
    }

    private fun record(section: String, key: String, existed: Boolean, print: String) = listOf(section, key, if (existed) "1" else "0", print).joinToString(SEP)

    /** Where the token a file held before Fuse signed it in is kept, in the secret store. */
    private fun was(path: String, section: String, key: String) = "ra.was:" + RaSignIn.fingerprint("$path|$section|$key").take(32)

    private companion object {
        const val MAX_TEXT = 1024 * 1024

        /** Fuse's records of the plain values signing in changed, by file. */
        const val KIND = "emulator.cheevos"

        /** Whether each file held a token before, and a fingerprint of the one Fuse wrote: never a token itself. */
        const val TOKENS = "emulator.cheevos.token"

        const val SEP = "\u001f"
    }
}
