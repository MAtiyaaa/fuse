package io.github.matiyaaa.fuse.launch.settings

/**
 * Where Cemu keeps a Wii U game's profile (src/Cafe/GameProfile/GameProfile.cpp, v2.6): its own in
 * `gameProfiles/<title id>.ini` in Cemu's config folder (`ActiveSettings::GetConfigPath`), the
 * title id as 16 lower-case hex digits; while that isn't there, the profile Cemu ships for the game,
 * `gameProfiles/default/<title id>.ini` in its data folder, which it reads instead (one or the
 * other, never both). `GameProfile::Save` writes `key = value` under `[General]`, `[CPU]`,
 * `[Graphics]` and `[Controller]`, after the game's name as `# name`.
 */
object CemuProfile {
    fun gameFile(config: String, titleId: String): String = "$config/gameProfiles/${titleId.lowercase()}.ini"

    fun shippedFile(data: String, titleId: String): String = "$data/gameProfiles/default/${titleId.lowercase()}.ini"

    /** The first line Cemu writes in a game's profile: its name. */
    fun header(name: String): String = "# ${name.replace('\n', ' ').trim()}\n\n"
}

/** Cemu's two folders: its data ([data], where it keeps the profiles it ships) and its config ([config], where settings.xml and a game's own profile are). */
data class CemuHome(val data: String, val config: String)
