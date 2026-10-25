package io.github.matiyaaa.fuse.data.settings

import kotlinx.serialization.Serializable

/**
 * What an app outside Fuse that talks to it may ask Fuse to do. These are the only things any can be
 * given: household passwords and keys, saves and the save vault, emulators' own files and files on
 * the device are not among them, so no extension can be given them, whatever it asks.
 */
@Serializable
enum class ExtensionScope(val title: String, val detail: String) {
    STATUS("See what Fuse shows", "Fuse One's state, downloads, and who is playing"),
    LIBRARY("See your library", "Which games are in Fuse, with their names and art"),
    PLAY("Open games in Fuse", "Fuse opens the game's page; starting it stays yours"),
    CHANGE_LIBRARY("Change your library", "Add and remove games, fix their names and art"),
    EMULATORS("Install and remove emulators", "Through Fuse's Store, as you would there"),
    CONTROL("Type into Fuse", "From its keyboard, into the text field open on the device"),
    CAPTURES("See and save captures", "Screenshots and recordings Fuse took"),
}

/**
 * An app that talks to Fuse: the [scopes] it can ask for at all, and those it has until the person
 * says otherwise ([defaults]).
 */
data class Extension(
    val id: String,
    val name: String,
    /** How Settings speaks of it where it is set up ("the plugin", "a phone"). */
    val subject: String,
    val description: String,
    val scopes: List<ExtensionScope>,
    val defaults: Set<ExtensionScope>,
)

/**
 * What each extension may do on this device, where the person changed it from its defaults: the
 * whole set it may do, per extension id. The device's, not a person's (an extension is installed on
 * the device).
 */
@Serializable
data class ExtensionSettings(val granted: Map<String, Set<ExtensionScope>> = emptyMap())

/** The extensions Fuse knows, and whether one may do something. */
object Extensions {
    /** Fuse's own Decky plugin in Steam: Fuse in its quick access menu. Emulators only when the person allows it. */
    val DECKY = Extension(
        "decky", "Fuse Decky plugin", "the plugin", "Fuse in Steam's quick access menu, through the Decky bridge",
        scopes = listOf(ExtensionScope.STATUS, ExtensionScope.LIBRARY, ExtensionScope.PLAY, ExtensionScope.CHANGE_LIBRARY, ExtensionScope.EMULATORS),
        defaults = setOf(ExtensionScope.STATUS, ExtensionScope.LIBRARY, ExtensionScope.PLAY, ExtensionScope.CHANGE_LIBRARY),
    )

    /** A phone signed in to Phone Link with the account set on this device. */
    val PHONE_LINK = Extension(
        "phone-link", "Phone Link", "a phone", "A phone signed in with this device's Phone Link account",
        scopes = listOf(ExtensionScope.STATUS, ExtensionScope.LIBRARY, ExtensionScope.PLAY, ExtensionScope.CHANGE_LIBRARY, ExtensionScope.CONTROL, ExtensionScope.CAPTURES),
        defaults = setOf(ExtensionScope.STATUS, ExtensionScope.LIBRARY, ExtensionScope.PLAY, ExtensionScope.CHANGE_LIBRARY, ExtensionScope.CONTROL, ExtensionScope.CAPTURES),
    )

    val all: List<Extension> = listOf(DECKY, PHONE_LINK)

    /** What no extension is ever given, in words, for where permissions are shown. */
    const val NEVER = "No app is ever given household passwords and keys, saves, emulators' files or your files: there is no way for one to ask"

    /** What [extension] may do now. */
    fun granted(settings: ExtensionSettings, extension: Extension): Set<ExtensionScope> =
        (settings.granted[extension.id] ?: extension.defaults).intersect(extension.scopes.toSet())

    fun allows(settings: ExtensionSettings, extension: Extension, scope: ExtensionScope): Boolean =
        scope in granted(settings, extension)

    /** [settings] with [scope] given to (or taken from) [extension]. */
    fun set(settings: ExtensionSettings, extension: Extension, scope: ExtensionScope, allowed: Boolean): ExtensionSettings {
        if (scope !in extension.scopes) return settings
        val now = granted(settings, extension).let { if (allowed) it + scope else it - scope }
        val granted = if (now == extension.defaults) settings.granted - extension.id else settings.granted + (extension.id to now)
        return settings.copy(granted = granted)
    }

    /** The words an extension is refused with, for it to show. */
    fun refusal(extension: Extension, scope: ExtensionScope): String =
        "Fuse doesn't let ${extension.name} ${scope.title.replaceFirstChar { it.lowercase() }}. You can allow it in Fuse: Settings, ${if (extension == DECKY) "Library, Decky" else "Accounts, Phone Link"}."
}
