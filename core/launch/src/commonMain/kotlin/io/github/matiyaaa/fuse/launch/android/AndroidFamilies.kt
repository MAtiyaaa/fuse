package io.github.matiyaaa.fuse.launch.android

/**
 * Recognises forks and renamed builds that are not in the catalog by words in their package name or
 * app label (the approach of Cartridge's `FAMILIES`, MIT). A family match reuses the family's intent,
 * is marked [io.github.matiyaaa.fuse.model.InstalledEmulator.isFamilyMatch], and always requires the
 * activity to be checked on the device.
 */
object AndroidFamilies {
    /**
     * One family. The rule matches when every word of any one group matches a word of the text. Order
     * matters: more specific rules come first (AzaharPlus before Azahar, Winlator Cmod before Winlator).
     */
    data class Rule(val emulatorId: String, val groups: List<List<String>>)

    private fun rule(id: String, vararg words: String) = Rule(id, words.map { listOf(it) })
    private fun all(id: String, vararg words: String) = Rule(id, listOf(words.toList()))

    val rules: List<Rule> = listOf(
        rule("retroarch", "retroarch"),
        rule("ppsspp", "ppsspp"),
        rule("dolphin-mmjr2", "mmjr"),
        rule("dolphin", "dolphin", "dolphinemu"),
        rule("azaharplus", "azaharplus"),
        rule("mandarine", "mandarine"),
        rule("azahar", "azahar", "citra", "borked3ds"),
        rule("watermelonds", "melondualds", "watermelonds"),
        rule("melonds", "melonds"),
        rule("duckstation", "duckstation"),
        rule("nethersx2", "aethersx2", "nethersx2"),
        rule("eden", "eden"),
        rule("citron", "citron"),
        rule("sudachi", "sudachi"),
        rule("yuzu", "yuzu", "suyu", "torzu"),
        rule("strato", "strato"),
        rule("skyline", "skyline"),
        rule("kenji-nx", "kenjinx"),
        rule("flycast", "flycast", "reicast"),
        rule("cemu", "cemu"),
        rule("aps3e", "aps3e"),
        rule("drastic", "drastic"),
        rule("redream", "redream"),
        rule("armsx3", "armsx3"),
        rule("armsx1", "armsx1"),
        rule("armsx2", "armsx2", "pcsx2"),
        rule("vita3k", "vita3k"),
        rule("rpcsx", "rpcsx"),
        rule("rpcs3-android", "rpcs3"),
        rule("shadps4", "shadps4", "shandroidps4"),
        rule("ax360e", "ax360e"),
        rule("skyemu", "skyemu"),
        rule("panda3ds", "panda3ds", "pandroid"),
        rule("lemuroid", "lemuroid"),
        all("winlator-frost", "winlator", "frost"),
        rule("bannerlator", "bannerlator"),
        rule("winnative", "winnative"),
        all("winlator-cmod", "winlator", "cmod"),
        rule("winlator", "winlator"),
        rule("gamenative", "gamenative"),
        all("gamehub-lite", "gamehub", "lite"),
        rule("gamehub", "gamehub"),
    )

    /** Apps that mention emulator names but are not emulators (frontends, launchers, keyboards). */
    private val NOT_EMULATOR = Regex(
        "browser|launcher|cartridge|daijisho|emulationstation|es-de|esde|pegasus|beacon|frontend|keyboard|wallpaper",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The catalog id of the family [pkg]/[label] belongs to, or null. A word matches a family word when
     * it is equal, or starts with it and is at most 6 characters longer ("azaharplus" is not "azahar"
     * because the AzaharPlus rule comes first).
     */
    fun familyOf(pkg: String, label: String = ""): String? {
        val text = "$pkg $label"
        if (NOT_EMULATOR.containsMatchIn(text)) return null
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        fun has(w: String) = words.any { it == w || (it.startsWith(w) && it.length <= w.length + 6) }
        return rules.firstOrNull { r -> r.groups.any { group -> group.all(::has) } }?.emulatorId
    }
}
