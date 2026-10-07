package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.PreviewAction
import io.github.matiyaaa.fuse.ui.shell.app.TextPreviewSpec
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Developer options, Library list: every game Fuse found, as text to copy or save (for a bug
 * report about what was or wasn't recognised). Unlike the diagnostics report it names folders and
 * files, since that is the point; it is shown before anything is copied or saved.
 */
fun AppState.showLibraryList() {
    scope.launch {
        toasts.show("Listing the library")
        val text = libraryList(store, store.updates.currentVersion)
        textPreview = TextPreviewSpec(
            title = "Library list",
            message = "Every game Fuse found, with its folders and files. It names your folders and files: check it before you share it.",
            text = text,
            icon = FuseIcons.ListChecks,
            actions = listOf(
                PreviewAction("Copy", FuseIcons.Copy) {
                    scope.launch {
                        val ok = platform.writeClipboardText(text)
                        toasts.show(if (ok) "Copied the list" else "Copying isn't possible here", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
                    }
                },
                PreviewAction("Save", FuseIcons.Save, primary = true) {
                    scope.launch {
                        val where = platform.saveFile("fuse-library-${store.updates.currentVersion}.txt", "text/plain", text.encodeToByteArray())
                        if (where != null) {
                            textPreview = null
                            toasts.show("Saved $where", ToastKind.SUCCESS)
                        }
                    }
                },
            ),
        )
    }
}

/** The library as text: its folders, each system's folders, then every game with what Fuse found with it. */
internal suspend fun libraryList(store: FuseStore, version: String): String = buildString {
    appendLine("Fuse $version library list")
    appendLine()
    appendLine("Library folders")
    val sources = store.sources.sources.value
    if (sources.isEmpty()) appendLine("  (none)")
    for (s in sources) appendLine("  ${s.path}  [${s.kind}${if (s.enabled) "" else ", off"}]")
    appendLine()

    val systems = store.library.platforms.value
    appendLine("Systems found")
    if (systems.isEmpty()) appendLine("  (none)")
    for (c in systems) {
        appendLine("  ${c.platform.name} (${c.platform.id.value}): ${c.gameCount} games")
        c.romFolders.forEach { appendLine("    folder: $it") }
    }
    appendLine()

    // Games on this device only (RomM's and other devices' are listed elsewhere).
    val cards = store.library.games(GameQuery(includeHidden = true)).first().filter { it.id.value > 0 }
    appendLine("Games (${cards.size})")
    for ((platform, group) in cards.groupBy { it.platformId.value }.toSortedMap()) {
        appendLine()
        appendLine("== $platform (${group.size}) ==")
        for (card in group.sortedBy { it.title.lowercase() }) {
            val game = store.library.game(card.id).first()?.game
            if (game == null) {
                appendLine("- ${card.title}")
                continue
            }
            val loc = game.location
            appendLine("- ${game.displayTitle}")
            appendLine("    ${loc.kind} ${loc.interpretation}  ${sizeText(loc.sizeBytes)}${game.tags.serial?.let { "  id $it" }.orEmpty()}${if (game.hidden) "  hidden" else ""}")
            appendLine("    path: ${loc.path}")
            if (loc.launchPath != loc.path) appendLine("    starts: ${loc.launchPath}")
            for (c in game.content) appendLine("    + ${c.kind} ${c.name}  ${sizeText(c.sizeBytes)}  ${c.path}")
            for (d in game.discs) appendLine("    disc ${d.number}: ${d.path}")
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
    bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
    else -> "$bytes B"
}
