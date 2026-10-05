package io.github.matiyaaa.fuse.sync.syncthing

import java.io.File

/** Syncthing's own settings on this computer: where its interface answers, and its API key. */
internal data class LocalSyncthing(val address: String, val apiKey: String?)

/**
 * Reads Syncthing's config.xml where Syncthing keeps it on each computer, so a computer that runs
 * Syncthing connects with nothing typed: the address its interface answers on and its API key.
 * Android keeps it inside the Syncthing app, where no other app may read it.
 */
internal object SyncthingConfig {
    /** Where config.xml may be, most likely first. */
    fun candidates(host: String, home: String, env: (String) -> String?): List<File> = when (host) {
        "WINDOWS" -> listOfNotNull(
            env("LOCALAPPDATA")?.let { File(it, "Syncthing/config.xml") },
            env("APPDATA")?.let { File(it, "Syncthing/config.xml") },
            File(home, "AppData/Local/Syncthing/config.xml"),
        )
        "MACOS" -> listOf(File(home, "Library/Application Support/Syncthing/config.xml"))
        "LINUX" -> listOfNotNull(
            // Since Syncthing 1.27 its state lives under XDG_STATE_HOME; older installs kept it in .config.
            (env("XDG_STATE_HOME") ?: "$home/.local/state").let { File(it, "syncthing/config.xml") },
            (env("XDG_CONFIG_HOME") ?: "$home/.config").let { File(it, "syncthing/config.xml") },
            File(home, ".var/app/me.kozec.syncthingtk/config/syncthing/config.xml"),
        )
        else -> emptyList()
    }

    fun read(host: String, home: String, env: (String) -> String? = System::getenv): LocalSyncthing? {
        val file = candidates(host, home, env).firstOrNull { it.isFile } ?: return null
        return parse(runCatching { file.readText() }.getOrNull() ?: return null)
    }

    /** The interface's address (with https when its tls is on) and its key, from config.xml's gui element. */
    fun parse(xml: String): LocalSyncthing? {
        val gui = Regex("""<gui\b([^>]*)>(.*?)</gui>""", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        val attrs = gui.groupValues[1]
        val body = gui.groupValues[2]
        val tls = Regex("""tls="(true|false)"""").find(attrs)?.groupValues?.get(1) == "true"
        var address = Regex("""<address>(.*?)</address>""").find(body)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "127.0.0.1:8384" }
        // Listening everywhere still answers on this device's own address.
        if (address.startsWith("0.0.0.0")) address = "127.0.0.1" + address.removePrefix("0.0.0.0")
        if (address.startsWith("[::]")) address = "127.0.0.1" + address.removePrefix("[::]")
        val key = Regex("""<apikey>(.*?)</apikey>""").find(body)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        return LocalSyncthing((if (tls) "https://" else "http://") + address, key)
    }
}
