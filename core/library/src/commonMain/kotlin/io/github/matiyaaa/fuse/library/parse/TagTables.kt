package io.github.matiyaaa.fuse.library.parse

/**
 * Names Fuse puts in [io.github.matiyaaa.fuse.model.FilenameTags.flags]. Display code can compare
 * against these instead of repeating string literals.
 */
object NameFlags {
    const val VERIFIED = "Verified"
    const val PENDING = "Pending"
    const val ALTERNATE = "Alternate"
    const val BAD_DUMP = "Bad Dump"
    const val FIXED = "Fixed"
    const val HACK = "Hack"
    const val MODIFIED = "Modified"
    const val OVERDUMP = "Overdump"
    const val UNDERDUMP = "Underdump"
    const val PIRATE = "Pirate"
    const val TRAINED = "Trained"
    const val CRACKED = "Cracked"
    const val VIRUS = "Virus"
    const val BAD_CHECKSUM = "Bad Checksum"
    const val TRANSLATION = "Translation"
    const val BETA = "Beta"
    const val ALPHA = "Alpha"
    const val PROTO = "Proto"
    const val DEMO = "Demo"
    const val SAMPLE = "Sample"
    const val PREVIEW = "Preview"
    const val KIOSK = "Kiosk"
    const val PROMO = "Promo"
    const val DEBUG = "Debug"
    const val UNLICENSED = "Unl"
    const val PUBLIC_DOMAIN = "Public Domain"
    const val AFTERMARKET = "Aftermarket"
    const val HOMEBREW = "Homebrew"
    const val UPDATE = "Update"
    const val DLC = "DLC"
    const val BASE = "Base"

    /** Development and release-variant flags. The display cleaner keeps these visible. */
    val releaseVariants: Set<String> = setOf(BETA, ALPHA, PROTO, DEMO, SAMPLE, PREVIEW, KIOSK, PROMO, DEBUG)
}

/** Region and language code tables (RomM's `parse_tags` tables plus No-Intro/GoodTools/TOSEC names). */
internal object TagTables {
    /** Region codes, matched case-sensitively (upper case), RomM's table. */
    val regionCodes: Map<String, String> = mapOf(
        "A" to "Australia", "AS" to "Asia", "B" to "Brazil", "C" to "Canada", "CH" to "China",
        "E" to "Europe", "F" to "France", "FN" to "Finland", "G" to "Germany", "GR" to "Greece",
        "H" to "Netherlands", "HK" to "Hong Kong", "I" to "Italy", "J" to "Japan", "K" to "Korea",
        "NL" to "Netherlands", "NO" to "Norway", "R" to "Russia", "S" to "Spain", "SW" to "Sweden",
        "T" to "Taiwan", "U" to "USA", "UK" to "UK", "W" to "World",
    )

    /** GoodTools packs several one-letter codes into one tag, for example "(JU)" or "(JUE)". */
    val comboRegionLetters: Set<Char> = setOf('J', 'U', 'E', 'W', 'K', 'B', 'A', 'F', 'G', 'S', 'I')

    /** Region names, matched case-insensitively, to the canonical name Fuse stores. */
    val regionNames: Map<String, String> = buildMap {
        fun add(canonical: String, vararg names: String) {
            put(canonical.lowercase(), canonical)
            names.forEach { put(it, canonical) }
        }
        add("USA", "us", "u.s.a.", "united states", "north america", "ntsc-u")
        add("Europe", "eu", "eur", "pal-e")
        add("Japan", "jp", "jpn", "ntsc-j")
        add("World")
        add("Asia")
        add("Australia")
        add("Brazil")
        add("Canada")
        add("China")
        add("France")
        add("Germany")
        add("Italy")
        add("Korea", "south korea")
        add("Netherlands", "holland")
        add("Spain")
        add("Sweden")
        add("Taiwan")
        add("UK", "united kingdom", "england")
        add("Russia")
        add("Scandinavia")
        add("Hong Kong")
        add("Greece")
        add("Finland")
        add("Norway")
        add("Denmark")
        add("Portugal")
        add("Poland")
        add("Mexico")
        add("Latin America")
        add("India")
        add("Belgium")
        add("Switzerland")
        add("Austria")
        add("New Zealand")
        add("Ireland")
        add("Argentina")
        add("South Africa")
        add("Croatia")
        add("Czech")
        add("Hungary")
        add("Israel")
        add("Turkey")
        add("Unknown", "unk")
    }

    /** Two-letter language codes (No-Intro style "En", "Fr"), matched case-insensitively. */
    val languageCodes: Map<String, String> = mapOf(
        "ar" to "Arabic", "da" to "Danish", "de" to "German", "el" to "Greek", "en" to "English",
        "es" to "Spanish", "fi" to "Finnish", "fr" to "French", "it" to "Italian", "ja" to "Japanese",
        "ko" to "Korean", "nl" to "Dutch", "no" to "Norwegian", "pl" to "Polish", "pt" to "Portuguese",
        "ru" to "Russian", "sr" to "Serbian", "sv" to "Swedish", "zh" to "Chinese", "ca" to "Catalan",
        "cs" to "Czech", "hu" to "Hungarian", "tr" to "Turkish", "he" to "Hebrew", "hr" to "Croatian",
        "ro" to "Romanian", "th" to "Thai", "vi" to "Vietnamese",
    )

    /** Full language names, matched case-insensitively. */
    val languageNames: Map<String, String> = languageCodes.values.associateBy { it.lowercase() }

    /** Three-letter codes used by fan translation tags such as "[T+Fre]". */
    val translationCodes: Map<String, String> = mapOf(
        "eng" to "English", "fre" to "French", "fra" to "French", "ger" to "German", "deu" to "German",
        "spa" to "Spanish", "esp" to "Spanish", "ita" to "Italian", "por" to "Portuguese",
        "bra" to "Portuguese", "rus" to "Russian", "pol" to "Polish", "dut" to "Dutch", "swe" to "Swedish",
        "nor" to "Norwegian", "dan" to "Danish", "fin" to "Finnish", "chi" to "Chinese", "kor" to "Korean",
        "jap" to "Japanese", "jpn" to "Japanese", "gre" to "Greek", "hun" to "Hungarian", "cze" to "Czech",
        "tur" to "Turkish", "ara" to "Arabic", "heb" to "Hebrew", "cat" to "Catalan", "ser" to "Serbian",
        "cro" to "Croatian", "rom" to "Romanian",
    )

    /** GoodTools / TOSEC one or two letter dump flags inside square brackets (case-sensitive). */
    val dumpFlags: Map<String, String> = mapOf(
        "a" to NameFlags.ALTERNATE, "b" to NameFlags.BAD_DUMP, "f" to NameFlags.FIXED, "h" to NameFlags.HACK,
        "m" to NameFlags.MODIFIED, "o" to NameFlags.OVERDUMP, "p" to NameFlags.PIRATE, "t" to NameFlags.TRAINED,
        "u" to NameFlags.UNDERDUMP, "v" to NameFlags.VIRUS, "x" to NameFlags.BAD_CHECKSUM,
        "cr" to NameFlags.CRACKED, "tr" to NameFlags.TRANSLATION,
    )

    /** Status words (whole tag, case-insensitive, optional trailing number such as "Beta 2"). */
    val statusWords: Map<String, String> = mapOf(
        "beta" to NameFlags.BETA, "alpha" to NameFlags.ALPHA, "proto" to NameFlags.PROTO,
        "prototype" to NameFlags.PROTO, "demo" to NameFlags.DEMO, "sample" to NameFlags.SAMPLE,
        "preview" to NameFlags.PREVIEW, "kiosk" to NameFlags.KIOSK, "promo" to NameFlags.PROMO,
        "debug" to NameFlags.DEBUG, "unl" to NameFlags.UNLICENSED, "unlicensed" to NameFlags.UNLICENSED,
        "pd" to NameFlags.PUBLIC_DOMAIN, "public domain" to NameFlags.PUBLIC_DOMAIN,
        "pirate" to NameFlags.PIRATE, "aftermarket" to NameFlags.AFTERMARKET, "homebrew" to NameFlags.HOMEBREW,
        "hack" to NameFlags.HACK, "translated" to NameFlags.TRANSLATION, "translation" to NameFlags.TRANSLATION,
        "alt" to NameFlags.ALTERNATE,
    )

    /** Switch-style content markers: "[UPD]", "(Update)", "[DLC]", "[BASE]". */
    val contentMarkers: Map<String, String> = mapOf(
        "upd" to NameFlags.UPDATE, "update" to NameFlags.UPDATE,
        "dlc" to NameFlags.DLC, "addon" to NameFlags.DLC, "add-on" to NameFlags.DLC,
        "base" to NameFlags.BASE, "base game" to NameFlags.BASE,
    )

    /** Video standards: recognised (and hidden by the cleaner) but not stored. */
    val videoStandards: Set<String> = setOf("ntsc", "pal", "secam", "pal 60hz", "50hz", "60hz", "pal60")

    /** RomM provider id tags such as "(igdb-1234)". */
    val providerIdPrefixes: Set<String> = setOf(
        "igdb", "ra", "ssfr", "steam", "moby", "sgdb", "tgdb", "hltb", "launchbox", "hasheous", "flashpoint",
    )
}
