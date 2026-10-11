package io.github.matiyaaa.fuse.launch.patches

/** A cheat in a PPSSPP cheat file: its `_C` line (from 0) and whether that line turns it on. */
data class CwCheat(val line: Int, val name: String, val on: Boolean)

/**
 * PPSSPP's CWCheat files as it reads and writes them (Core/CwCheat.cpp, CheatFileParser, and
 * UI/CwCheatScreen.cpp, RebuildCheatFile): a game's cheats are in `PSP/Cheats/GAMEID.ini` on its
 * memory stick; `_S ULUS-10041` starts a game's block, `_C0 name` is a cheat that is off and `_C1 name`
 * (any digit from 1) one that is on, `_L` lines are its codes, and `//` and `#` start comments. In a
 * file with several games only the block for this game counts; a file with one `_S` line counts
 * whatever id it names, as PPSSPP allows. A cheat is listed once a code line follows it, as PPSSPP
 * lists it. Turning a cheat on or off rewrites only its `_C` line.
 */
object CwCheats {
    fun parse(text: String, gameId: String): List<CwCheat> {
        val want = gameId.replace("-", "")
        val lines = lines(text)
        val out = ArrayList<CwCheat>()
        var games = 0
        var enabled = true
        var risky = false
        var pending: CwCheat? = null
        for ((i, raw) in lines.withIndex()) {
            val line = (if (i == 0) raw.removePrefix("﻿") else raw).trim()
            if (line.length < 5 || line[0] != '_') continue
            when (line[1]) {
                'S' -> {
                    pending = null
                    games++
                    val matches = want.isEmpty() || line.substring(2).trim().replace("-", "") == want
                    when {
                        matches -> {
                            // The right block after all: the one-game leniency gives way to it.
                            if (risky) { out.clear(); risky = false }
                            enabled = true
                        }
                        games == 1 -> { risky = true; enabled = true }
                        else -> {
                            if (risky) { out.clear(); risky = false }
                            enabled = false
                        }
                    }
                }
                'C' -> {
                    pending = null
                    val digit = line[2]
                    if (digit !in '0'..'9' || !enabled) continue
                    pending = CwCheat(i, line.substring(4), digit != '0')
                }
                'L' -> {
                    pending?.let { out += it }
                    pending = null
                }
            }
        }
        return out
    }

    /** [text] with the cheat at [cheat]'s line turned [on]; null when that line no longer holds it. */
    fun set(text: String, cheat: CwCheat, on: Boolean): String? {
        val crlf = text.contains("\r\n")
        val lines = lines(text).toMutableList()
        val current = lines.getOrNull(cheat.line) ?: return null
        val bom = cheat.line == 0 && current.startsWith("﻿")
        val body = current.removePrefix("﻿").trim()
        if (!body.startsWith("_C") || !body.contains(cheat.name)) return null
        lines[cheat.line] = (if (bom) "﻿" else "") + (if (on) "_C1 " else "_C0 ") + cheat.name
        val sep = if (crlf) "\r\n" else "\n"
        return lines.joinToString(sep) + if (text.endsWith("\n")) sep else ""
    }

    private fun lines(text: String): List<String> {
        val all = text.replace("\r\n", "\n").split('\n')
        return if (text.endsWith("\n")) all.dropLast(1) else all
    }
}

/**
 * Where PPSSPP keeps a game's cheats and settings on its memory stick ([memstick] holds `PSP`): the
 * cheat files in `PSP/Cheats`, its settings in `PSP/SYSTEM/ppsspp.ini`, and a game's own settings,
 * when it has them, in `PSP/SYSTEM/GAMEID_ppsspp.ini` (Core/Util/PathUtil.cpp, GetGameConfigFilePath).
 */
data class PpssppHome(val memstick: String) {
    fun cheatFile(gameId: String): String = "$memstick/PSP/Cheats/$gameId.ini"

    val settingsFile: String get() = "$memstick/PSP/SYSTEM/ppsspp.ini"

    fun gameSettingsFile(gameId: String): String = "$memstick/PSP/SYSTEM/${gameId}_ppsspp.ini"

    companion object {
        /** `[General] EnableCheats`, a setting a game's own settings file can hold too. */
        const val SECTION = "General"
        const val CHEATS_SWITCH = "EnableCheats"

        /** A PSP game id as PPSSPP files cheats under it: four letters and five digits, `ULUS10041`. */
        fun gameId(serial: String?): String? = serial?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { ID.matches(it) }

        private val ID = Regex("[A-Z]{4}[0-9]{5}")
    }
}
