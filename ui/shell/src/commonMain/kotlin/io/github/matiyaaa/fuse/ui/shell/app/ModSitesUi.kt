package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.integrations.gamebanana.GbFile
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementPreview
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.ModSiteMods
import io.github.matiyaaa.fuse.ui.shell.store.ModSitePage
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.launch

/** The row in a game's Patches and Cheats sheet that opens its mods on GameBanana. */
internal fun AppState.modSiteRow(card: GameCard): MenuAction = MenuAction(
    "mods.gamebanana", "Find Mods on GameBanana", FuseIcons.Globe,
    detail = "Textures, cheats and patches made for this game, checked before anything changes",
    trailing = Trailing.Chevron,
    section = "Add Your Own",
    onSelect = { modSiteMods(card) },
)

/** The game's mods on GameBanana, after asking which game there it is when that isn't known yet. */
internal fun AppState.modSiteMods(card: GameCard, page: Int = 1) {
    scope.launch {
        when (val mods = store.library.modSiteMods(card.id, page)) {
            is ModSiteMods.Unavailable -> if (mods.needsGame) modSiteGamePicker(card) else showProblem(Problem(mods.title, mods.reason, ProblemKind.NETWORK, Severity.INFO, reassurance = null))
            is ModSiteMods.Ready -> {
                val rows = mods.mods.map { m ->
                    val mod = m.mod
                    val about = listOfNotNull(
                        mod.category,
                        mod.version?.let { if (it.startsWith("v", ignoreCase = true)) it else "v$it" },
                        mod.submitter?.let { "by $it" },
                        "${mod.likes} ${if (mod.likes == 1L) "like" else "likes"}".takeIf { mod.likes > 0 },
                    ).joinToString("  ·  ")
                    MenuAction(
                        "mod.${mod.id}", mod.name,
                        when (m.kind) {
                            EnhancementKind.TEXTURES -> FuseIcons.Images
                            EnhancementKind.MODS -> FuseIcons.Puzzle
                            EnhancementKind.CHEATS -> FuseIcons.Wand
                            EnhancementKind.PATCHES -> FuseIcons.Bandage
                            null -> FuseIcons.Package
                        },
                        detail = about + if (mod.obsolete) "  ·  marked out of date" else "",
                        trailing = Trailing.Chevron,
                        section = if (m.kind != null) "Fuse Can Add These" else "Follow Their Own Instructions",
                        onSelect = { modSitePage(card, mod.id) },
                    )
                }.sortedBy { if (it.section == "Fuse Can Add These") 0 else 1 }
                val more = if (!mods.more) emptyList() else listOf(
                    MenuAction("more", "More Mods", FuseIcons.ChevronsDown, detail = "${mods.total} in all", section = "", onSelect = { modSiteMods(card, page + 1) }),
                )
                val other = MenuAction(
                    "other", "Not ${mods.game.name}?", FuseIcons.Search, detail = "Choose this game on GameBanana again",
                    section = "", onSelect = { modSiteGamePicker(card) },
                )
                choice = ChoiceSpec(
                    icon = FuseIcons.Globe,
                    title = "${mods.game.name} on GameBanana",
                    message = if (mods.mods.isEmpty()) {
                        "GameBanana has no mods for this game yet."
                    } else {
                        "Most liked first. Fuse adds textures, cheats and patches for this game's emulator, from files GameBanana's checks passed. Each is shown before anything changes."
                    },
                    options = rows + more + other,
                )
            }
        }
    }
}

/** Which game on GameBanana this is: a search for its name, the same name first, or another search. */
private fun AppState.modSiteGamePicker(card: GameCard, query: String? = null) {
    scope.launch {
        val games = store.library.modSiteGames(card.id, query)
        if (games.problem != null) {
            showProblem(Problem("Couldn't search GameBanana", games.problem, ProblemKind.NETWORK, Severity.INFO, reassurance = null))
            return@launch
        }
        val rows = games.found.take(MAX_GAMES).map { g ->
            MenuAction(
                "game.${g.id}", g.name, FuseIcons.Gamepad,
                trailing = Trailing.Check(games.linked?.id == g.id),
                onSelect = {
                    scope.launch {
                        store.library.linkModSiteGame(card.id, g)
                        modSiteMods(card)
                    }
                },
            )
        }
        val search = MenuAction(
            "search", "Search Another Name", FuseIcons.Search, section = "",
            onSelect = {
                textInput = TextInputSpec("Search GameBanana", games.query, doneLabel = "Search", message = "The game's name as GameBanana has it") { q ->
                    modSiteGamePicker(card, q)
                }
            },
        )
        choice = ChoiceSpec(
            icon = FuseIcons.Globe,
            title = "Which is ${card.title}?",
            message = if (rows.isEmpty()) {
                "GameBanana has no game called \"${games.query}\". Search another name."
            } else {
                "Mods are listed for the game you choose, and Fuse remembers it. Pick the same edition and system as yours: mods made for another version may not work."
            },
            options = rows + search,
        )
    }
}

/** A mod's page: what it is, and each of its files with what GameBanana's checks said, or why Fuse leaves it be. */
internal fun AppState.modSitePage(card: GameCard, modId: Long) {
    scope.launch {
        when (val p = store.library.modSitePage(card.id, modId)) {
            is ModSitePage.Unavailable -> showProblem(Problem(p.title, p.reason, ProblemKind.NETWORK, Severity.INFO, reassurance = null))
            is ModSitePage.Ready -> {
                val mod = p.page.mod
                val files = p.page.files.map { f ->
                    val why = when {
                        p.kind == null -> "Fuse doesn't add ${mod.category ?: "this kind of mod"}; see its page"
                        else -> f.notFetchable?.let { "Not fetched: $it" }
                    }
                    MenuAction(
                        "file.${f.id}", f.name, FuseIcons.FileArchive,
                        detail = listOfNotNull(bytesText(f.size), f.version, "passed GameBanana's checks".takeIf { f.fetchable }).joinToString("  ·  "),
                        trailing = if (why == null) Trailing.Chevron else Trailing.None,
                        unavailableReason = why,
                        section = "Files",
                        onSelect = { fetchModFile(card, mod.id, f) },
                    )
                }
                val open = MenuAction(
                    "open", "Open Its Page", FuseIcons.External, detail = mod.url.removePrefix("https://"), section = "",
                    onSelect = { platform.openUrl(mod.url) },
                )
                choice = ChoiceSpec(
                    icon = FuseIcons.Package,
                    title = mod.name,
                    message = listOfNotNull(
                        p.page.text.take(MAX_TEXT).let { if (p.page.text.length > MAX_TEXT) it.trimEnd() + "…" else it }.ifBlank { null },
                        p.page.license?.let { "Licence: ${it.take(MAX_LICENCE)}" },
                    ).joinToString("\n\n").ifEmpty { null },
                    options = files + open,
                )
            }
        }
    }
}

/** Fetches a mod's file with its progress, then shows exactly what adding it would do. */
private fun AppState.fetchModFile(card: GameCard, modId: Long, file: GbFile) {
    fun progress(fraction: Float?) {
        choice = ChoiceSpec(
            icon = FuseIcons.Download,
            title = "Fetching ${file.name}",
            message = "Into Fuse's own folder first, and checked against GameBanana's size and checksum. Nothing in the emulator's folders changes yet.",
            options = listOf(
                MenuAction("fetching", file.name, FuseIcons.FileArchive, detail = bytesText(file.size), trailing = Trailing.Progress(fraction, fraction?.let { "${(it * 100).toInt()}%" } ?: "")),
            ),
        )
    }
    progress(null)
    scope.launch {
        var shown = -1
        val preview = store.library.fetchModSiteFile(card.id, modId, file.id) { f ->
            val step = (f * 50).toInt()
            if (step != shown) {
                shown = step
                progress(f)
            }
        }
        choice = null
        when (preview) {
            is EnhancementPreview.Unavailable -> showProblem(Problem(preview.title, preview.reason, ProblemKind.EMULATOR, Severity.INFO))
            is EnhancementPreview.Ready -> confirmEnhancement(card, preview)
        }
    }
}

/** The row that opens what EmuCoreX's catalogues have for a PS2 game. */
internal fun AppState.emuCoreXRow(card: GameCard): MenuAction = MenuAction(
    "mods.emucorex", "Find Packs and Cheats on EmuCoreX", FuseIcons.Globe,
    detail = "Texture packs for this disc and PCSX2 cheats and patches for its CRC, from EmuCoreX's public catalogue",
    trailing = Trailing.Chevron,
    section = "Add Your Own",
    onSelect = { emuCoreXList(card) },
)

/** What EmuCoreX has for the game: texture packs first, then patches and cheats, with who made each. */
private fun AppState.emuCoreXList(card: GameCard) {
    scope.launch {
        val found = store.library.emuCoreX(card.id)
        found.problem?.let {
            showProblem(Problem("Couldn't look in EmuCoreX's catalogue", it, ProblemKind.NETWORK, Severity.INFO, reassurance = null))
            return@launch
        }
        val packs = found.packs.map { (p, kind) ->
            MenuAction(
                "pack.${p.id}", p.name, FuseIcons.Images,
                detail = listOfNotNull(bytesText(p.sizeBytes), p.authors.takeIf { it.isNotEmpty() }?.let { "by ${it.joinToString(", ")}" }, p.version).joinToString("  ·  "),
                trailing = Trailing.Chevron,
                unavailableReason = if (kind == null) "This game's emulator doesn't take texture packs here" else null,
                section = "Texture Packs",
                onSelect = { emuCoreXEntry(card, p.id) },
            )
        }
        val pnach = found.pnach.map { (c, kind) ->
            MenuAction(
                "pnach.${c.id}", c.description?.takeIf { it.length <= MAX_ROW } ?: c.title,
                if (c.isPatch) FuseIcons.Bandage else FuseIcons.Wand,
                detail = listOfNotNull(c.sourceName, c.authors.takeIf { it.isNotEmpty() }?.let { "by ${it.joinToString(", ")}" }).joinToString("  ·  "),
                trailing = Trailing.Chevron,
                unavailableReason = if (kind == null) "This game's emulator doesn't take these here" else null,
                section = if (c.isPatch) "Patches" else "Cheats",
                onSelect = { emuCoreXEntry(card, c.id) },
            )
        }.sortedBy { if (it.section == "Patches") 0 else 1 }
        val home = MenuAction(
            "open", "About EmuCoreX's Catalogue", FuseIcons.External, detail = "github.com/sashkinbro/EmuCoreX", section = "",
            onSelect = { platform.openUrl(io.github.matiyaaa.fuse.integrations.emucorex.EmuCoreX.HOME) },
        )
        val which = listOfNotNull(found.serial, found.crc?.let { "CRC $it" }).joinToString(", ")
        choice = ChoiceSpec(
            icon = FuseIcons.Globe,
            title = "${card.title} on EmuCoreX",
            message = if (packs.isEmpty() && pnach.isEmpty()) {
                "EmuCoreX's catalogue has nothing for this disc ($which) yet."
            } else {
                "Matched by this disc ($which), never by name. Gathered by EmuCoreX from their makers, who keep the credit. Each is shown before anything changes."
            },
            options = packs + pnach + home,
        )
    }
}

/** One of EmuCoreX's packs or pnach files: who made it, its licence, and Add or its own page. */
private fun AppState.emuCoreXEntry(card: GameCard, entryId: String) {
    scope.launch {
        val found = store.library.emuCoreX(card.id)
        val pack = found.packs.firstOrNull { it.first.id == entryId }
        val pnach = found.pnach.firstOrNull { it.first.id == entryId }
        val title = pack?.first?.name ?: pnach?.first?.let { it.description?.takeIf { d -> d.length <= MAX_ROW } ?: it.title } ?: return@launch
        val why = pack?.let { (p, kind) -> if (kind == null) "This game's emulator doesn't take texture packs here" else p.notFetchable }
            ?: pnach?.let { (c, kind) -> if (kind == null) "This game's emulator doesn't take these here" else c.notFetchable }
        val reason = why?.replaceFirstChar { it.uppercase() }
        val source = pack?.first?.sourceUrl ?: pnach?.first?.sourceUrl
        // Who made it and its licence first, so they are never what a long description pushes out.
        val about = pack?.first?.let { p ->
            listOfNotNull(
                listOfNotNull(
                    p.authors.takeIf { it.isNotEmpty() }?.let { "Made by ${it.joinToString(", ")}." },
                    p.license?.let { "Licence: ${it.take(MAX_LICENCE).trimEnd('.')}." },
                ).joinToString(" ").ifEmpty { null },
                p.description,
                p.credits,
            )
        } ?: pnach!!.first.let { c ->
            listOfNotNull(
                listOfNotNull(
                    c.authors.takeIf { it.isNotEmpty() }?.let { "Made by ${it.joinToString(", ")}" + (c.sourceName?.let { s -> ", from $s." } ?: ".") },
                    c.license?.let { "Licence: ${it.take(MAX_LICENCE).trimEnd('.')}." },
                ).joinToString(" ").ifEmpty { null },
                c.description?.takeIf { it.length > MAX_ROW },
            )
        }
        val add = MenuAction(
            "add", if (pack != null) "Add This Pack" else "Add This File", FuseIcons.Download,
            detail = pack?.first?.let { p -> listOfNotNull(bytesText(p.sizeBytes), "${p.fileCount} pictures".takeIf { p.fileCount > 0 }, "checked against its SHA-256").joinToString("  ·  ") }
                ?: "From GitHub, then shown before anything changes",
            unavailableReason = reason,
            onSelect = { fetchEmuCoreX(card, entryId, title, pack?.first?.sizeBytes) },
        )
        val open = source?.let {
            MenuAction("open", "Open Its Page", FuseIcons.External, detail = it.removePrefix("https://").take(MAX_ROW), section = "", onSelect = { platform.openUrl(it) })
        }
        val back = MenuAction("back", "Back to the List", FuseIcons.ArrowLeft, section = "", onSelect = { emuCoreXList(card) })
        choice = ChoiceSpec(
            icon = if (pack != null) FuseIcons.Images else FuseIcons.Wand,
            title = title,
            message = about.joinToString("\n\n").take(MAX_TEXT * 2).ifEmpty { null },
            options = listOfNotNull(add, open, back),
        )
    }
}

/** Fetches an EmuCoreX entry with its progress, then shows exactly what adding it would do. */
private fun AppState.fetchEmuCoreX(card: GameCard, entryId: String, name: String, size: Long?) {
    fun progress(fraction: Float?) {
        choice = ChoiceSpec(
            icon = FuseIcons.Download,
            title = "Fetching $name",
            message = "Into Fuse's own folder first, and checked against EmuCoreX's catalogue. Nothing in the emulator's folders changes yet.",
            options = listOf(
                MenuAction("fetching", name, FuseIcons.FileArchive, detail = size?.let(::bytesText), trailing = Trailing.Progress(fraction, fraction?.let { "${(it * 100).toInt()}%" } ?: "")),
            ),
        )
    }
    progress(null)
    scope.launch {
        var shown = -1
        val preview = store.library.fetchEmuCoreX(card.id, entryId) { f ->
            val step = (f * 50).toInt()
            if (step != shown) {
                shown = step
                progress(f)
            }
        }
        choice = null
        when (preview) {
            is EnhancementPreview.Unavailable -> showProblem(Problem(preview.title, preview.reason, ProblemKind.EMULATOR, Severity.INFO))
            is EnhancementPreview.Ready -> confirmEnhancement(card, preview)
        }
    }
}

private const val MAX_GAMES = 12
private const val MAX_ROW = 80
private const val MAX_TEXT = 420
private const val MAX_LICENCE = 160
