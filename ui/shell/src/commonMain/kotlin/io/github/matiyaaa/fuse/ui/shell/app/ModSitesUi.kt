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

private const val MAX_GAMES = 12
private const val MAX_TEXT = 420
private const val MAX_LICENCE = 160
