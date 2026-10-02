package io.github.matiyaaa.fuse.ui.shell.music

/** One song that ships with Fuse. [id] names its file in `files/music` and is what settings store. */
data class MusicTrack(val id: String, val title: String)

/**
 * The music Fuse ships: the album "jam channel" by boipurple. puddleworld plays under the menus by
 * default and alright apothecary during first-time setup; any of them, or the user's own song, can
 * be picked in Settings, Screen and sound.
 */
object BundledMusic {
    const val ARTIST = "boipurple"
    const val ALBUM = "jam channel"

    /** The menu song until the user picks another. */
    const val MENU_DEFAULT = "puddleworld"

    /** What plays while setting Fuse up. */
    const val ONBOARDING = "alright-apothecary"

    /** Stands for the user's own song in [io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs.track]. */
    const val OWN_SONG = "file"

    /** In the album's order. */
    val tracks: List<MusicTrack> = listOf(
        MusicTrack("soiree", "soiree"),
        MusicTrack("dewwy", "dewwy"),
        MusicTrack("puddleworld", "puddleworld"),
        MusicTrack("alright-apothecary", "alright apothecary"),
        MusicTrack("chachuu", "chachuu"),
        MusicTrack("beamrider", "beamrider"),
        MusicTrack("mirth", "mirth"),
        MusicTrack("comeaux", "comeaux"),
        MusicTrack("wub-time", "wub time"),
        MusicTrack("sighonara", "sighonara"),
    )

    fun byId(id: String?): MusicTrack? = tracks.firstOrNull { it.id == id }

    /** The resource path of a bundled track (Compose resources in ui:designsystem). */
    fun resource(id: String): String = "files/music/$id.mp3"

    /** Where the unpacked copy lives in Fuse's cache; the version changes if the files ever do. */
    fun cachePath(id: String): String = "music/bundled-v1/$id.mp3"

    /** The credit shown in Settings, Screen and sound and in the licences. */
    const val CREDIT = "Music by $ARTIST, from the album $ALBUM"
}
