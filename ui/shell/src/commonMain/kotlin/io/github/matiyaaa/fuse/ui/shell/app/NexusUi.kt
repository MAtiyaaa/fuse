package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.integrations.nexus.NexusFile
import io.github.matiyaaa.fuse.integrations.nexus.NexusMods
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.achievements.grouped
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementPreview
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.NexusModList
import io.github.matiyaaa.fuse.ui.shell.store.NexusPage
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.launch

/** The row in a game's Patches and Cheats sheet that opens its mods on Nexus Mods. */
internal fun AppState.nexusRow(card: GameCard): MenuAction = MenuAction(
    "mods.nexus", "Find Mods on Nexus Mods", FuseIcons.Globe,
    detail = "With your own Nexus Mods key. Each is shown before anything changes",
    trailing = Trailing.Chevron,
    section = "Add Your Own",
    onSelect = { nexusMods(card) },
)

/** The game's mods on Nexus Mods: its trending, latest added and latest updated, and one by its link. */
internal fun AppState.nexusMods(card: GameCard) {
    scope.launch {
        when (val list = store.library.nexusMods(card.id)) {
            is NexusModList.Unavailable -> when {
                list.needsGame -> nexusGamePicker(card)
                else -> showProblem(Problem(list.title, list.reason, if (list.needsKey) ProblemKind.ACCOUNT else ProblemKind.NETWORK, Severity.INFO, reassurance = null))
            }
            is NexusModList.Ready -> {
                val seen = HashSet<Long>()
                val rows = list.lists.flatMap { (which, mods) ->
                    mods.filter { seen.add(it.id) }.map { m ->
                        MenuAction(
                            "mod.${m.id}", m.name ?: "Mod ${m.id}", FuseIcons.Puzzle,
                            detail = listOfNotNull(
                                versionText(m.version),
                                m.author?.let { "by $it" },
                                when (m.endorsements) { 0L -> null; 1L -> "1 endorsement"; else -> "${grouped(m.endorsements)} endorsements" },
                            ).joinToString("  ·  "),
                            trailing = Trailing.Chevron,
                            section = which.title,
                            onSelect = { nexusPage(card, list.game.domain, m.id) },
                        )
                    }
                }
                val byLink = MenuAction(
                    "link", "A Mod by Its Link", FuseIcons.Link, detail = "Its page's address on nexusmods.com", section = "",
                    onSelect = {
                        textInput = TextInputSpec("A mod on Nexus Mods", "", placeholder = "nexusmods.com/${list.game.domain}/mods/…", capitalize = false, doneLabel = "Open") { url ->
                            val found = NexusMods.modOf(url)
                            if (found == null) toasts.show("That isn't a mod's page on Nexus Mods.") else nexusPage(card, found.first, found.second)
                        }
                    },
                )
                val other = MenuAction(
                    "other", "Not ${list.game.name}?", FuseIcons.Search, detail = "Choose this game on Nexus Mods again", section = "",
                    onSelect = { nexusGamePicker(card) },
                )
                choice = ChoiceSpec(
                    icon = FuseIcons.Globe,
                    title = "${list.game.name} on Nexus Mods",
                    message = if (rows.isEmpty()) "Nexus Mods lists nothing new for this game right now. Open one by its link instead." else
                        "Fuse adds mods and texture packs for this game's emulator from current .zip files, each shown before anything changes." +
                            if (list.premium) "" else " Your account downloads through the website, so each file takes its Mod Manager Download link.",
                    options = rows + byLink + other,
                )
            }
        }
    }
}

/** Which game on Nexus Mods this is: from Nexus Mods' own list, the same name first. */
private fun AppState.nexusGamePicker(card: GameCard, query: String? = null) {
    scope.launch {
        val games = store.library.nexusGames(card.id, query)
        if (games.problem != null) {
            showProblem(Problem("Couldn't look on Nexus Mods", games.problem, ProblemKind.NETWORK, Severity.INFO, reassurance = null))
            return@launch
        }
        val rows = games.found.take(MAX_GAMES).map { g ->
            MenuAction(
                "game.${g.id}", g.name, FuseIcons.Gamepad,
                detail = "${g.mods} mods".takeIf { g.mods > 0 },
                trailing = Trailing.Check(games.linked?.id == g.id),
                onSelect = {
                    scope.launch {
                        store.library.linkNexusGame(card.id, g)
                        nexusMods(card)
                    }
                },
            )
        }
        val search = MenuAction(
            "search", "Search Another Name", FuseIcons.Search, section = "",
            onSelect = {
                textInput = TextInputSpec("Search Nexus Mods", games.query, doneLabel = "Search", message = "The game's name as Nexus Mods has it") { q -> nexusGamePicker(card, q) }
            },
        )
        choice = ChoiceSpec(
            icon = FuseIcons.Globe,
            title = "Which is ${card.title}?",
            message = if (rows.isEmpty()) "Nexus Mods has no game called \"${games.query}\". Search another name." else
                "Mods are listed for the game you choose, and Fuse remembers it. Mods made for another version of a game may not work.",
            options = rows + search,
        )
    }
}

/** A mod on Nexus Mods: its files with what Fuse can do with each, and its page. */
internal fun AppState.nexusPage(card: GameCard, game: String, modId: Long) {
    scope.launch {
        when (val p = store.library.nexusPage(card.id, game, modId)) {
            is NexusPage.Unavailable -> showProblem(Problem(p.title, p.reason, ProblemKind.NETWORK, Severity.INFO, reassurance = null))
            is NexusPage.Ready -> {
                val mod = p.mod
                val files = p.files.map { f ->
                    // Files of one mod often share a name: each says its version, even when it can't be fetched.
                    val why = when {
                        p.kind == null -> "Fuse doesn't add mods for this game's emulator; see its page"
                        else -> f.notFetchable?.let { "Not fetched: $it" }
                    }?.let { listOfNotNull(versionText(f.version), it).joinToString("  ·  ") }
                    MenuAction(
                        "file.${f.id}", f.name, FuseIcons.FileArchive,
                        detail = listOfNotNull(bytesText(f.sizeKb * 1024), versionText(f.version), categoryText(f.category)).joinToString("  ·  "),
                        trailing = if (why == null) Trailing.Chevron else Trailing.None,
                        unavailableReason = why,
                        section = "Files",
                        onSelect = { if (p.premium) fetchNexusFile(card, game, modId, f, nxm = null) else nexusFreeFile(card, game, modId, f, p.kind) },
                    )
                }
                val open = MenuAction("open", "Open Its Page", FuseIcons.External, detail = mod.url.removePrefix("https://"), section = "", onSelect = { platform.openUrl(mod.url) })
                choice = ChoiceSpec(
                    icon = FuseIcons.Puzzle,
                    title = mod.name ?: "Mod $modId",
                    message = listOfNotNull(mod.author?.let { "Made by $it." }, mod.summary?.take(MAX_TEXT)).joinToString(" ").ifEmpty { null },
                    options = files + open,
                )
            }
        }
    }
}

/**
 * A file for an account that downloads through the website, as Nexus Mods asks: its Mod Manager
 * Download link pasted, or the file downloaded on the website and then added from where it landed.
 */
private fun AppState.nexusFreeFile(card: GameCard, game: String, modId: Long, file: NexusFile, kind: EnhancementKind?) {
    val page = "https://www.nexusmods.com/$game/mods/$modId?tab=files&file_id=${file.id}"
    choice = ChoiceSpec(
        icon = FuseIcons.Download,
        title = file.name,
        message = "Nexus Mods gives download links straight away to Premium accounts. With yours, the file comes from its page on the website.",
        options = listOfNotNull(
            MenuAction("paste", "Paste Its Mod Manager Download Link", FuseIcons.Link, detail = "From the file's Mod Manager Download button on its page", onSelect = {
                choice = null
                textInput = TextInputSpec("Mod Manager Download link", "", placeholder = "nxm://$game/mods/$modId/files/${file.id}?…", capitalize = false, doneLabel = "Fetch") { link ->
                    if (NexusMods.nxmOf(link) == null) toasts.show("That isn't a Mod Manager Download link.") else fetchNexusFile(card, game, modId, file, link)
                }
            }),
            MenuAction("open", "Open the File's Page", FuseIcons.External, detail = "Download it there, then add it below", onSelect = { platform.openUrl(page) }),
            kind?.let { k ->
                MenuAction("own", "Add the File You Downloaded", FuseIcons.FileArchive, detail = "The .zip, from where your browser saved it", onSelect = { pickAndPlan(card, k, "the game's emulator", folder = false) })
            },
        ),
    )
}

/** Fetches a file with its progress, then shows exactly what adding it would do. */
private fun AppState.fetchNexusFile(card: GameCard, game: String, modId: Long, file: NexusFile, nxm: String?) {
    fun progress(fraction: Float?) {
        choice = ChoiceSpec(
            icon = FuseIcons.Download,
            title = "Fetching ${file.name}",
            message = "Into Fuse's own folder first. Nothing in the emulator's folders changes yet.",
            options = listOf(
                MenuAction("fetching", file.fileName, FuseIcons.FileArchive, detail = bytesText(file.sizeKb * 1024), trailing = Trailing.Progress(fraction, fraction?.let { "${(it * 100).toInt()}%" } ?: "")),
            ),
        )
    }
    progress(null)
    scope.launch {
        var shown = -1
        val preview = store.library.fetchNexusFile(card.id, game, modId, file.id, nxm) { f ->
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

/** A version as mods show theirs: "v2.1". */
private fun versionText(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("v", ignoreCase = true)) it else "v$it" }

/** What Nexus Mods files a file under, in words: MAIN is the main file, OLD_VERSION an old version. */
private fun categoryText(category: String?): String? {
    val c = category?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (c.uppercase()) {
        "MAIN" -> "Main file"
        "OLD_VERSION" -> "Old version"
        else -> c.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

private const val MAX_GAMES = 12
private const val MAX_TEXT = 420
