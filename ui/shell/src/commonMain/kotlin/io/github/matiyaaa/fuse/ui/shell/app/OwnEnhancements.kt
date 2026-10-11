package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementPreview
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementRemoval
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementView
import io.github.matiyaaa.fuse.ui.shell.store.Enhancements
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.launch

/**
 * The game's own additions in its Patches and Cheats sheet: what a person added from files of their
 * own (each opening what taking it out does), and a way to add each kind its emulator takes.
 */
internal fun AppState.ownEnhancementRows(card: GameCard, own: Enhancements, emulator: String): List<MenuAction> {
    val added = own.added.map { e ->
        MenuAction(
            "own.${e.id}", e.name, kindIcon(e.kind),
            detail = describe(e),
            trailing = Trailing.Chevron,
            section = "Your Own",
            onSelect = { enhancementSheet(card, e) },
        )
    }
    val add = own.kinds.map { kind ->
        when (kind) {
            EnhancementKind.CHEATS -> MenuAction(
                "add.cheats", "Add Cheats From a File", FuseIcons.FileUp,
                detail = "A cheat file of your own for $emulator, or a .zip holding one",
                section = "Add Your Own",
                onSelect = { addEnhancement(card, kind, emulator) },
            )
            EnhancementKind.PATCHES -> MenuAction(
                "add.patches", "Add Patches From a File", FuseIcons.FileUp,
                detail = "A patch file of your own for $emulator, or a .zip holding one",
                section = "Add Your Own",
                onSelect = { addEnhancement(card, kind, emulator) },
            )
            EnhancementKind.TEXTURES -> MenuAction(
                "add.textures", "Add a Texture Pack", FuseIcons.Images,
                detail = "A pack's folder or .zip. You see exactly what goes where first",
                section = "Add Your Own",
                onSelect = { addEnhancement(card, kind, emulator) },
            )
        }
    }
    // Mods made for the game, from GameBanana, where its emulator takes any.
    val site = if (own.kinds.isEmpty()) emptyList() else listOf(modSiteRow(card))
    return added + add + site
}

private fun kindIcon(kind: EnhancementKind) = when (kind) {
    EnhancementKind.CHEATS -> FuseIcons.Wand
    EnhancementKind.PATCHES -> FuseIcons.Bandage
    EnhancementKind.TEXTURES -> FuseIcons.Images
}

private fun count(n: Int, one: String, many: String = "${one}s") = if (n == 1) "1 $one" else "$n $many"

private fun describe(e: EnhancementView): String = (e.origin?.let { o -> "From $o" + (e.version?.let { v -> "  ·  ${if (v.startsWith("v", ignoreCase = true)) v else "v$v"}" } ?: "") + "  ·  " } ?: "") + when (e.kind) {
    EnhancementKind.TEXTURES -> "Texture pack  ·  ${count(e.files, "picture")}"
    EnhancementKind.CHEATS -> "Cheats  ·  in ${e.where.substringAfterLast('/')}"
    EnhancementKind.PATCHES -> "Patches  ·  in ${e.where.substringAfterLast('/')}"
} + if (e.settingsChanged) "  ·  settings turned on for it" else ""

/** The file types each emulator's cheats and patches come in, and archives holding them. */
private fun extensions(emulator: String): List<String> = when (emulator) {
    "PCSX2" -> listOf("pnach", "txt", "zip")
    "DuckStation" -> listOf("cht", "txt", "zip")
    "PPSSPP" -> listOf("ini", "txt", "db", "zip")
    else -> listOf("cht", "zip")
}

/** Chooses what to add, then shows exactly what adding it would do before anything changes. */
private fun AppState.addEnhancement(card: GameCard, kind: EnhancementKind, emulator: String) {
    if (kind == EnhancementKind.TEXTURES) {
        choice = ChoiceSpec(
            icon = FuseIcons.Images,
            title = "Add a texture pack",
            message = "Choose the pack's own folder, or the .zip it came in. Nothing is copied until you have seen what goes where.",
            options = listOf(
                MenuAction("folder", "Choose a Folder", FuseIcons.FolderOpen, onSelect = { pickAndPlan(card, kind, emulator, folder = true) }),
                MenuAction("zip", "Choose a .zip Archive", FuseIcons.FileArchive, onSelect = { pickAndPlan(card, kind, emulator, folder = false) }),
            ),
        )
        return
    }
    pickAndPlan(card, kind, emulator, folder = false)
}

private fun AppState.pickAndPlan(card: GameCard, kind: EnhancementKind, emulator: String, folder: Boolean) {
    choice = null
    scope.launch {
        val word = when (kind) {
            EnhancementKind.CHEATS -> "cheats"
            EnhancementKind.PATCHES -> "patches"
            EnhancementKind.TEXTURES -> "texture pack"
        }
        val source = when {
            folder -> platform.storage.pickFolder("Choose the $word folder for ${card.title}")
            kind == EnhancementKind.TEXTURES -> platform.storage.pickFile("Choose the $word for ${card.title}", "Zip archives", listOf("zip"))
            else -> platform.storage.pickFile("Choose $word for ${card.title}", "$emulator $word", extensions(emulator))
        } ?: return@launch
        when (val preview = store.library.planEnhancement(card.id, kind, source)) {
            is EnhancementPreview.Unavailable -> showProblem(Problem(preview.title, preview.reason, ProblemKind.EMULATOR, Severity.INFO))
            is EnhancementPreview.Ready -> confirmEnhancement(card, preview)
        }
    }
}

/** At most this many paths are named in a preview; the rest are counted. */
private const val PREVIEW_PATHS = 4

private fun list(paths: List<String>): String =
    paths.take(PREVIEW_PATHS).joinToString(", ") + if (paths.size > PREVIEW_PATHS) " and ${paths.size - PREVIEW_PATHS} more" else ""

/** The preview: the files that go in, those they replace, settings they turn on and what was left out, then Add. */
internal fun AppState.confirmEnhancement(card: GameCard, p: EnhancementPreview.Ready) {
    val goesIn = when {
        p.appendsTo != null -> "${count(p.codes, "code")} from ${p.name} are added to the end of ${p.appendsTo}."
        p.kind == EnhancementKind.TEXTURES -> "${count(p.adds.size + p.replaces.size, "picture")} (${bytesText(p.bytes)}) go in ${p.target}."
        else -> "${count(p.codes, "code")} from ${p.name} go in ${list(p.adds + p.replaces)}."
    }
    val paragraphs = listOfNotNull(
        goesIn,
        p.replaces.takeIf { it.isNotEmpty() }?.let { "Replaced, and kept to put back: ${list(it)}." },
        p.settings.takeIf { it.isNotEmpty() }?.let { it.joinToString(". ") + "." },
        p.rejected.takeIf { it.isNotEmpty() }?.let { "Left out: ${list(it)}." },
        p.notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        "Fuse makes a restoration point first, so taking it out again puts back everything as it was.",
    )
    confirm = ConfirmSpec(
        title = "Add ${p.name} to ${card.title}?",
        message = paragraphs.joinToString("\n\n"),
        confirmLabel = "Add",
    ) {
        scope.launch {
            val why = store.library.addEnhancement(p.token)
            if (why == null) {
                toasts.show("Added for ${p.emulator}", ToastKind.SUCCESS)
                gamePatchPicker(card)
            } else {
                showProblem(Problem("Couldn't add ${p.name}", why, ProblemKind.EMULATOR, Severity.ATTENTION))
            }
        }
    }
}

/** One thing a person added: taking it out, and for a mod from GameBanana, its files there (a newer version among them). */
private fun AppState.enhancementSheet(card: GameCard, e: EnhancementView) {
    val modId = e.originUrl?.takeIf { e.origin == "GameBanana" }?.substringAfterLast('/')?.toLongOrNull()
    choice = ChoiceSpec(
        icon = kindIcon(e.kind),
        title = e.name,
        message = describe(e) + ". Added for ${e.emulator}.",
        options = listOfNotNull(
            modId?.let {
                MenuAction(
                    "files", "Its Files on GameBanana", FuseIcons.Globe,
                    detail = "A newer version is added beside this one; then take this one out",
                    trailing = Trailing.Chevron,
                    onSelect = { modSitePage(card, it) },
                )
            },
            MenuAction(
                "out", "Take Out", FuseIcons.Undo,
                detail = "Puts back every file and setting as it was before",
                destructive = true,
                onSelect = { takeOutEnhancement(card, e) },
            ),
        ),
    )
}

/** Taking out something a person added: what it puts back, and, when it was changed since, whether to anyway. */
private fun AppState.takeOutEnhancement(card: GameCard, e: EnhancementView) {
    choice = null
    val what = when (e.kind) {
        EnhancementKind.TEXTURES -> "The pack's ${count(e.files, "picture")} leave ${e.where}, and any it replaced come back."
        else -> "Its codes come out of ${e.where.substringAfterLast('/')}; everything else in it stays."
    }
    confirm = ConfirmSpec(
        title = "Take out ${e.name}?",
        message = listOfNotNull(
            what,
            "What it turned on in ${e.emulator}'s settings for this game goes back to how it was, unless you changed it there since.".takeIf { e.settingsChanged },
        ).joinToString("\n\n"),
        confirmLabel = "Take Out",
        destructive = true,
    ) { scope.launch { removeEnhancement(card, e, force = false) } }
}

private suspend fun AppState.removeEnhancement(card: GameCard, e: EnhancementView, force: Boolean) {
    when (val r = store.library.removeEnhancement(card.id, e.id, force)) {
        EnhancementRemoval.Removed -> {
            toasts.show("Took out ${e.name}", ToastKind.SUCCESS)
            gamePatchPicker(card)
        }
        is EnhancementRemoval.ChangedSince -> confirm = ConfirmSpec(
            title = "Changed in ${e.emulator} since",
            message = r.message,
            confirmLabel = "Put the Earlier File Back",
            destructive = true,
        ) { scope.launch { removeEnhancement(card, e, force = true) } }
        is EnhancementRemoval.Failed -> showProblem(Problem("Couldn't take out ${e.name}", r.message, ProblemKind.EMULATOR, Severity.ATTENTION, reassurance = null))
    }
}
