package io.github.matiyaaa.fuse.ui.shell.music

/** One song that ships with Fuse. [id] names its file in `files/music` and is what settings store. */
data class MusicTrack(val id: String, val title: String, val album: String)

/**
 * The music Fuse ships: two albums by boipurple, "jam channel" and "creature interchange".
 * puddleworld plays under the menus by default and alright apothecary during first-time setup; any
 * of them, the user's own song, or all of them shuffled can be picked in Settings, Sound.
 */
object BundledMusic {
    const val ARTIST = "boipurple"
    const val ALBUM = "jam channel"
    const val ALBUM_TWO = "creature interchange"

    /** The albums in the order Settings lists them. */
    val albums: List<String> = listOf(ALBUM, ALBUM_TWO)

    /** The menu song until the user picks another. */
    const val MENU_DEFAULT = "puddleworld"

    /** What plays while setting Fuse up. */
    const val ONBOARDING = "alright-apothecary"

    /** Stands for the user's own song in [io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs.track]. */
    const val OWN_SONG = "file"

    /** In each album's order. */
    val tracks: List<MusicTrack> = listOf(
        MusicTrack("soiree", "soiree", ALBUM),
        MusicTrack("dewwy", "dewwy", ALBUM),
        MusicTrack("puddleworld", "puddleworld", ALBUM),
        MusicTrack("alright-apothecary", "alright apothecary", ALBUM),
        MusicTrack("chachuu", "chachuu", ALBUM),
        MusicTrack("beamrider", "beamrider", ALBUM),
        MusicTrack("mirth", "mirth", ALBUM),
        MusicTrack("comeaux", "comeaux", ALBUM),
        MusicTrack("wub-time", "wub time", ALBUM),
        MusicTrack("sighonara", "sighonara", ALBUM),
        MusicTrack("hellacious-hilltops", "hellacious hilltops", ALBUM_TWO),
        MusicTrack("kickback-kroose", "kickback kroose", ALBUM_TWO),
        MusicTrack("compassionate-cafe", "compassionate cafe", ALBUM_TWO),
        MusicTrack("mediocre-mall", "mediocre mall", ALBUM_TWO),
        MusicTrack("distant-dunes", "distant dunes", ALBUM_TWO),
        MusicTrack("plucky-park", "plucky park", ALBUM_TWO),
        MusicTrack("sarcastic-shop", "sarcastic shop", ALBUM_TWO),
        MusicTrack("sassy-shells", "sassy shells", ALBUM_TWO),
        MusicTrack("verisimilitudinous-valley", "verisimilitudinous valley", ALBUM_TWO),
        MusicTrack("nocturnal-knoll", "nocturnal knoll", ALBUM_TWO),
        MusicTrack("enamoring-eventide", "enamoring eventide", ALBUM_TWO),
        MusicTrack("fickle-fountain", "fickle fountain", ALBUM_TWO),
        MusicTrack("bedtime-ballad", "bedtime ballad", ALBUM_TWO),
        MusicTrack("reflective-river", "reflective river", ALBUM_TWO),
    )

    fun byId(id: String?): MusicTrack? = tracks.firstOrNull { it.id == id }

    /** The resource path of a bundled track (Compose resources in ui:designsystem). */
    fun resource(id: String): String = "files/music/$id.mp3"

    /** Where the unpacked copy lives in Fuse's cache; the version changes if the files ever do. */
    fun cachePath(id: String): String = "music/bundled-v1/$id.mp3"

    /** The credit shown in Settings, Sound and in the licences. */
    const val CREDIT = "Music by $ARTIST, from the albums $ALBUM and $ALBUM_TWO"
}
