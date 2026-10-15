package io.github.matiyaaa.fuse.ui.shell.music

/**
 * One of Fuse's songs. [id] names its file and is what settings store. An [inside] song ships in
 * `files/music`; the others are fetched from [BundledMusic.remoteUrls] the first time they are
 * wanted and kept, checked against their [bytes] and [sha256].
 */
data class MusicTrack(
    val id: String,
    val title: String,
    val album: String,
    val inside: Boolean = false,
    val bytes: Long = 0,
    val sha256: String = "",
)

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
        MusicTrack("soiree", "soiree", ALBUM, bytes = 2711822, sha256 = "91ab91694c9e0ea53ced1d68ec618105ebbd6f0238cd4a97b8d09765eb4d5a23"),
        MusicTrack("dewwy", "dewwy", ALBUM, bytes = 2273018, sha256 = "2e283f68b1ecdeec3362a0bb81f74fbde7b2592e42c479e4b1cec5ce22d52ada"),
        MusicTrack("puddleworld", "puddleworld", ALBUM, inside = true, bytes = 2534195, sha256 = "21c9bda975ffc86b896505a7f1e9bd2fee23bba5a303dea4654e1b436ab0766a"),
        MusicTrack("alright-apothecary", "alright apothecary", ALBUM, inside = true, bytes = 1416645, sha256 = "c953e585d9fd2e897242aaa2b2c5a49ee5a2d9b7d42d3e0f5756f41aa4f163ee"),
        MusicTrack("chachuu", "chachuu", ALBUM, bytes = 2002411, sha256 = "a759879c721eb18331aaf56d758f1e71196fd6cdd4aeaf5bb6f2fa32947231a0"),
        MusicTrack("beamrider", "beamrider", ALBUM, bytes = 1903920, sha256 = "b033d8f783024a1e11f050be27e21a9d84d88c0587b30595472e62b7a75920bb"),
        MusicTrack("mirth", "mirth", ALBUM, bytes = 2069188, sha256 = "c2e7aff883efcfd9b804151a304d4db33c8a64a422a79bdd5e3315186b5b965f"),
        MusicTrack("comeaux", "comeaux", ALBUM, bytes = 2000933, sha256 = "1076780ed34ad055dea5319e616f81c9289e603a4f62f5119e130583d7cd2243"),
        MusicTrack("wub-time", "wub time", ALBUM, bytes = 2102479, sha256 = "dde8e9af2ed092f44f143d4fa371274c16e12269252a26183ebc13a50d6101a3"),
        MusicTrack("sighonara", "sighonara", ALBUM, bytes = 2412743, sha256 = "088f4fa7a72783db6363201e2db9e9cabc74f63dc1c2cd3a37635a200a724ec5"),
        MusicTrack("hellacious-hilltops", "hellacious hilltops", ALBUM_TWO, bytes = 1474657, sha256 = "efbdd7a5f60f4a0a9a58a1ccb4f58bc9b1f3b46864bd3d5e1bd6e154ad9ce3ce"),
        MusicTrack("kickback-kroose", "kickback kroose", ALBUM_TWO, bytes = 1492383, sha256 = "2576ccf3145884210f6c88ce31fcfa307bd2f7e523025571d4bd8a4142021a2e"),
        MusicTrack("compassionate-cafe", "compassionate cafe", ALBUM_TWO, bytes = 1389965, sha256 = "6af1ae454433d847a57f02c3c47a0c72be416da03c3f8321f6cbf71d86cc6144"),
        MusicTrack("mediocre-mall", "mediocre mall", ALBUM_TWO, bytes = 1608704, sha256 = "c5521ea9fa195b6c1e42a51ca28b7e1bfa1291e08635dc5e970df363d733f792"),
        MusicTrack("distant-dunes", "distant dunes", ALBUM_TWO, bytes = 1802852, sha256 = "c6500c4fba2b44abe2848abe1068fb59d17f3c18112c8022e2cc704ed27b6c77"),
        MusicTrack("plucky-park", "plucky park", ALBUM_TWO, bytes = 1675572, sha256 = "99214fb98bf24d4f5371697218e2d03d8b3815280197fff1e27df34a11a31b7e"),
        MusicTrack("sarcastic-shop", "sarcastic shop", ALBUM_TWO, bytes = 1825997, sha256 = "670a5c375ad1b277b1f46d4f53afeb593e1d27e652c4e556dca1f365d6dbdee5"),
        MusicTrack("sassy-shells", "sassy shells", ALBUM_TWO, bytes = 1644377, sha256 = "a6448b52cd6405d022d0e77398c6f51b0690d663b043e5e61bb6f8deefe360c1"),
        MusicTrack("verisimilitudinous-valley", "verisimilitudinous valley", ALBUM_TWO, bytes = 2123595, sha256 = "631e2f7dd8cd02c3d0a131141ca22b0d26724519832d2371aceb449cc5407082"),
        MusicTrack("nocturnal-knoll", "nocturnal knoll", ALBUM_TWO, bytes = 1663400, sha256 = "b6e7a1c5f6f00fb24224c8dbfc5d3cda41a3266a488d39826a8ba2c740e04260"),
        MusicTrack("enamoring-eventide", "enamoring eventide", ALBUM_TWO, bytes = 1649313, sha256 = "9c9eea98f7db3a57ac8a5e95c220f8c4a9e1348b415defa92d8230d265f63c45"),
        MusicTrack("fickle-fountain", "fickle fountain", ALBUM_TWO, bytes = 2209144, sha256 = "8ab06980167934328aa95565666f65200255868d0b35e563718b610159194f96"),
        MusicTrack("bedtime-ballad", "bedtime ballad", ALBUM_TWO, bytes = 1621434, sha256 = "a5a5070e97e5efa536a3bf0595365ff2b63fe3dc32a827e296d85e47cf9a3666"),
        MusicTrack("reflective-river", "reflective river", ALBUM_TWO, bytes = 1666571, sha256 = "f67601125fbdf41e08f79136482ffb1ad607ce9481541695df035e21177c9890"),
    )

    fun byId(id: String?): MusicTrack? = tracks.firstOrNull { it.id == id }

    /** The resource path of a song that ships inside Fuse (Compose resources in ui:designsystem). */
    fun resource(id: String): String = "files/music/$id.webm"

    /**
     * Where a song is kept in Fuse's cache, unpacked or fetched: Opus in WebM since 0.4.2.15. The
     * version changes if the files ever do.
     */
    fun cachePath(id: String): String = "music/v2/$id.webm"

    /** Where the 0.4.2.14 and earlier copies were unpacked (MP3), cleared once. */
    const val OLD_CACHE = "music/bundled-v1"

    /**
     * Where a song that doesn't ship inside Fuse is fetched from: the repository's `music` branch,
     * then the same files through jsDelivr's mirror of it.
     */
    fun remoteUrls(id: String): List<String> = listOf(
        "https://raw.githubusercontent.com/MAtiyaaa/fuse/music/v2/$id.webm",
        "https://cdn.jsdelivr.net/gh/MAtiyaaa/fuse@music/v2/$id.webm",
    )

    /** The songs fetched rather than shipped, and how much they take together. */
    val remote: List<MusicTrack> get() = tracks.filter { !it.inside }
    val remoteBytes: Long get() = remote.sumOf { it.bytes }

    /** The credit shown in Settings, Sound and in the licences. */
    const val CREDIT = "Music by $ARTIST, from the albums $ALBUM and $ALBUM_TWO"
}
