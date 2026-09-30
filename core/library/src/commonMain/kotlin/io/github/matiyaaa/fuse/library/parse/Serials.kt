package io.github.matiyaaa.fuse.library.parse

/**
 * Recognises console title ids (serials) in file and folder names and normalises them:
 * - PS1/PS2 disc serials keep Redump's dash form: `SLUS_012.34`, `SLUS01234` -> `SLUS-01234`.
 * - PSP, PS3, PS Vita, PS4 and PS5 ids are written without a dash, as the emulators use them:
 *   `BLUS-30001` -> `BLUS30001`, `PCSB00245`, `CUSA12345`, `NPUB30001`, `ULUS10041`.
 * - Switch title ids are 16 hex digits starting with "01", upper-cased: `0100ABCD12345000`.
 *   They are only accepted inside brackets, because bare hex runs are too ambiguous.
 */
object Serials {
    private const val SEP = "[-_ ]?"

    // PS1 / PS2 retail and demo prefixes, then "012.34" or "01234".
    private val ps12 = Regex("^(S[CL][UEPAKCJ][SMDAJ]|PAPX|PCPX|PBPX|SIPS|SCAJ|SLAJ|ESPM|CPCS)$SEP(\\d{3})\\.?(\\d{2})$", RegexOption.IGNORE_CASE)

    // PSP UMD, PS3 disc, PSN (PSP/PS3), Vita, PS4, PS5.
    private val modern = Regex(
        "^(U[CL][UEJAKS][SMBT]|B[CL][UEJAKT][SMDBT]|NP[A-Z]{2}|PCS[A-I]|V[CL][UEJAK][SM]|CUSA|PLAS|PCAS|PCJS|PLJS|PLJM|PCKS|PLKS|PPSA|ELJM)$SEP(\\d{5})$",
        RegexOption.IGNORE_CASE,
    )

    private val switchId = Regex("^01[0-9A-Fa-f]{14}$")

    /** Normalised serial for a whole token (a tag's content or a word), or null when it isn't one. */
    fun normalize(token: String, allowSwitchId: Boolean = true): String? {
        val t = token.trim()
        ps12.matchEntire(t)?.let { m ->
            return "${m.groupValues[1].uppercase()}-${m.groupValues[2]}${m.groupValues[3]}"
        }
        modern.matchEntire(t)?.let { m -> return m.groupValues[1].uppercase() + m.groupValues[2] }
        if (allowSwitchId && switchId.matches(t)) return t.uppercase()
        return null
    }

    // Candidate tokens in free text: letters, then optional separator, then digits with an optional dot.
    private val candidate = Regex("(?<![A-Za-z0-9])([A-Za-z]{4}[-_ ]?\\d{3}\\.?\\d{2})(?![A-Za-z0-9])")

    /** Finds a PlayStation-family serial anywhere in [text]. Returns the normalised serial and its range. */
    fun find(text: String): Pair<String, IntRange>? {
        for (m in candidate.findAll(text)) {
            val normalized = normalize(m.groupValues[1], allowSwitchId = false) ?: continue
            return normalized to m.range
        }
        return null
    }

    /** True for a Switch title id (16 hex digits starting with 01). */
    fun isSwitchTitleId(value: String): Boolean = switchId.matches(value.trim())

    /**
     * The base game's title id for a Switch update or DLC title id. Updates are `base + 0x800`;
     * DLC ids are `(base + 0x1000) + n`. Returns [titleId] itself for a base id, or null when it
     * is not a Switch title id.
     */
    fun switchBaseTitleId(titleId: String): String? {
        if (!isSwitchTitleId(titleId)) return null
        val value = titleId.trim().toLong(16)
        val low = value and 0xFFF
        val base = when {
            low == 0L -> value
            low == 0x800L -> value - 0x800
            else -> (value and 0xFFF.inv().toLong()) - 0x1000
        }
        return base.toString(16).uppercase().padStart(16, '0')
    }

    /** What a Switch title id stands for, from its low bits. */
    fun switchTitleKind(titleId: String): SwitchTitleKind? {
        if (!isSwitchTitleId(titleId)) return null
        val low = titleId.trim().toLong(16) and 0xFFF
        return when (low) {
            0L -> SwitchTitleKind.BASE
            0x800L -> SwitchTitleKind.UPDATE
            else -> SwitchTitleKind.DLC
        }
    }
}

/** Kind of Switch title a title id encodes. */
enum class SwitchTitleKind { BASE, UPDATE, DLC }
