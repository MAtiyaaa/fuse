package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.launch.AdapterRegistry
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.settings.NativeSettings
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.sync.RetroArchCoreSaves
import io.github.matiyaaa.fuse.sync.SaveAdapters

/**
 * What Fuse does with each emulator it knows, read from the code that does it rather than written
 * by hand, so it can't claim more than Fuse does: whether its saves sync (a save adapter, else the
 * reason), whether Fuse changes its settings and cheats, and how its firmware is checked. One row
 * per emulator in every catalog (Android, Linux, Windows, macOS) and one per RetroArch core.
 * [VERSION] goes up whenever what a row can say changes; docs/emulator-contracts.md is this table
 * written out, and a test keeps the two the same.
 *
 * "Supported" means Fuse has code for it, checked against the emulator's own source and tested
 * with fixtures.
 */
object EmulatorContracts {
    const val VERSION = 1

    enum class Level { SUPPORTED, NOT_SUPPORTED, NOT_APPLICABLE }

    data class Cell(val level: Level, val detail: String) {
        fun text(): String = when (level) {
            Level.SUPPORTED -> "Yes${if (detail.isEmpty()) "" else ": $detail"}"
            Level.NOT_SUPPORTED -> "No: $detail"
            Level.NOT_APPLICABLE -> "Not needed"
        }
    }

    data class Row(val id: String, val name: String, val host: Host, val platforms: List<String>, val saves: Cell, val settings: Cell, val cheats: Cell, val firmware: Cell)

    data class CoreRow(val core: String, val platforms: List<String>, val saves: Cell)

    /** Built-in openers (shortcuts, Steam, scripts): no emulator, so nothing to contract. */
    private val openers = setOf("steam", "steam-url", "desktop", "shortcut", "open", "script", "anyps5")

    /** Families whose cheats Fuse changes on a computer (NativePatches). */
    private val cheatFamilies = setOf("retroarch", "pcsx2", "duckstation", "ppsspp")

    /** Families whose firmware installed inside them Fuse checks on a computer (InEmulatorFirmware, DesktopEmulators). */
    private val insideFirmware = setOf("eden", "citron", "ryujinx", "rpcs3", "vita3k")

    fun family(id: String): String = SaveAdapters.baseId(id)

    val rows: List<Row> by lazy {
        AdapterRegistry.Default.adapters
            .filter { family(it.id.value) !in openers }
            .map { a ->
                val fam = family(a.id.value)
                val adapter = SaveAdapters.forEmulator(a.id.value)
                val saves = if (adapter != null) Cell(Level.SUPPORTED, "") else Cell(Level.NOT_SUPPORTED, SaveAdapters.whyNot(a.id.value))
                val computer = a.host != Host.ANDROID
                val settings = when {
                    computer && fam in NativeSettings.families -> Cell(Level.SUPPORTED, "for every game and for one game")
                    computer -> Cell(Level.NOT_SUPPORTED, "changed in the emulator")
                    else -> Cell(Level.NOT_SUPPORTED, "changed in the emulator on Android")
                }
                val cheats = when {
                    computer && fam in cheatFamilies -> Cell(Level.SUPPORTED, "")
                    computer -> Cell(Level.NOT_SUPPORTED, "changed in the emulator")
                    else -> Cell(Level.NOT_SUPPORTED, "changed in the emulator on Android")
                }
                // Optional firmware kept inside the emulator (3DS keys for encrypted dumps) is not needed to play.
                val needs = a.platforms.mapNotNull { p -> PlatformCatalog.byId(p.value)?.bios?.let { p.value to it } }
                    .filterNot { it.second.optional && it.second.installedInEmulator }
                val firmware = when {
                    needs.isEmpty() -> Cell(Level.NOT_APPLICABLE, "")
                    needs.all { it.second.optional } -> Cell(Level.SUPPORTED, "optional; BIOS folders, by name and checksum")
                    needs.any { it.second.installedInEmulator && !it.second.optional } ->
                        if (computer && fam in insideFirmware) Cell(Level.SUPPORTED, "installed inside it: checked to be there")
                        else Cell(Level.NOT_SUPPORTED, "installed inside it, where Fuse can't look")
                    else -> Cell(Level.SUPPORTED, "BIOS folders, by name and checksum")
                }
                Row(a.id.value, a.name, a.host, a.platforms.map { it.value }.sorted(), saves, settings, cheats, firmware)
            }
            .sortedWith(compareBy({ it.host.ordinal }, { it.id }))
    }

    val cores: List<CoreRow> by lazy {
        val byCore = LinkedHashMap<String, MutableSet<String>>()
        for (host in listOf(Host.ANDROID, Host.LINUX, Host.WINDOWS, Host.MACOS)) {
            for (p in PlatformCatalog.all.map { it.id }) for (core in RetroArchCores.coresFor(host, p)) byCore.getOrPut(core) { sortedSetOf() } += p.value
        }
        byCore.toSortedMap().map { (core, platforms) ->
            val saves = when (val layout = RetroArchCoreSaves.layout(core)) {
                RetroArchCoreSaves.Layout.SRAM -> Cell(Level.SUPPORTED, "save RAM RetroArch writes (.srm, .rtc)")
                RetroArchCoreSaves.Layout.FLYCAST -> Cell(Level.SUPPORTED, "its own VMU files")
                RetroArchCoreSaves.Layout.DOSBOX_PURE -> Cell(Level.SUPPORTED, "its own save archives")
                RetroArchCoreSaves.Layout.PPSSPP -> Cell(Level.SUPPORTED, "its own memory stick")
                is RetroArchCoreSaves.Layout.Unsupported -> Cell(Level.NOT_SUPPORTED, layout.why)
            }
            CoreRow(core, platforms.toList(), saves)
        }
    }

    /** The table as Markdown, for docs/emulator-contracts.md. */
    fun markdown(): String = buildString {
        appendLine("# Emulator contracts")
        appendLine()
        appendLine("Version $VERSION. Generated from Fuse's code by `EmulatorContractsTest`; do not edit by hand.")
        appendLine("\"Yes\" means Fuse has code for it, checked against the emulator's own source and tested with fixtures.")
        for (host in listOf(Host.ANDROID, Host.LINUX, Host.WINDOWS, Host.MACOS)) {
            appendLine()
            appendLine("## ${host.name.lowercase().replaceFirstChar { it.uppercase() }}")
            appendLine()
            appendLine("| Emulator | Id | Systems | Saves | Settings | Cheats | Firmware |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            for (r in rows.filter { it.host == host }) {
                appendLine("| ${r.name} | `${r.id}` | ${r.platforms.joinToString(", ")} | ${r.saves.text()} | ${r.settings.text()} | ${r.cheats.text()} | ${r.firmware.text()} |")
            }
        }
        appendLine()
        appendLine("## RetroArch cores")
        appendLine()
        appendLine("| Core | Systems | Saves |")
        appendLine("| --- | --- | --- |")
        for (c in cores) appendLine("| `${c.core}` | ${c.platforms.joinToString(", ")} | ${c.saves.text()} |")
    }.trimEnd() + "\n"
}
