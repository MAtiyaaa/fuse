package io.github.matiyaaa.fuse.ui.shell.addons

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.store.Extra
import io.github.matiyaaa.fuse.ui.shell.store.ExtraKind
import kotlinx.coroutines.launch

/** The Store's emulators that have extras Fuse can give them, by their Store key. */
internal val EXTRAS_EMULATORS = setOf("cemu", "rpcs3", "xenia", "eden", "citron", "duckstation", "pcsx2", "retroarch")

/**
 * What [emulator] can be given beyond its games, in one sheet: each extra with where it stands, run
 * when chosen, and put back where Fuse can undo it.
 */
internal fun AppState.extrasSheet(emulator: String, name: String) {
    scope.launch {
        val offered = store.extras.offered(emulator)
        if (offered.isEmpty()) {
            toasts.show("$name has nothing more Fuse can add here yet. Start it once so it makes its folders.")
            return@launch
        }
        choice = ChoiceSpec(
            title = "$name Extras",
            message = "Each is done as $name itself does it, and Fuse keeps what it replaces so you can put it back.",
            icon = FuseIcons.Sparkles,
            options = offered.flatMap { e -> rows(emulator, name, e) },
        )
    }
}

private fun AppState.rows(emulator: String, name: String, e: Extra): List<MenuAction> = listOfNotNull(
    MenuAction(
        "extra.${e.kind.name.lowercase()}", e.title, icon(e.kind),
        detail = e.detail,
        trailing = when {
            e.on != null -> Trailing.Switch(e.on)
            e.state != null -> Trailing.Value(e.state)
            else -> Trailing.None
        },
        onSelect = {
            choice = null
            if (e.needsFile) pickThen(emulator, name, e) else run(emulator, name, e, null)
        },
    ),
    if (e.undoable) MenuAction(
        "extra.${e.kind.name.lowercase()}.undo", "Put Back Before ${e.title}", FuseIcons.Undo,
        detail = "What $name had before Fuse's last ${e.title.lowercase()}",
        onSelect = {
            choice = null
            scope.launch {
                store.extras.undo(emulator, e.kind)
                    .onSuccess { toasts.show(it, ToastKind.SUCCESS, icon = FuseIcons.Undo) }
                    .onFailure { toasts.show(it.message ?: "It couldn't be put back", ToastKind.WARNING) }
            }
        },
    ) else null,
)

/** The firmware, chosen as a file or a folder, then put in place. */
private fun AppState.pickThen(emulator: String, name: String, e: Extra) {
    choice = ChoiceSpec(
        title = e.title,
        message = "Choose the firmware you dumped from your own console: a zip or 7z of it, or the folder holding its .nca files.",
        icon = icon(e.kind),
        options = listOf(
            MenuAction("pick.file", "Choose a Zip or 7z", FuseIcons.FileArchive, onSelect = {
                choice = null
                scope.launch { platform.storage.pickFile("Choose the firmware", "Firmware", listOf("zip", "7z", "rar"))?.let { run(emulator, name, e, it) } }
            }),
            MenuAction("pick.folder", "Choose a Folder", FuseIcons.FolderOpen, onSelect = {
                choice = null
                scope.launch { platform.storage.pickFolder("Choose the firmware folder")?.let { run(emulator, name, e, it) } }
            }),
        ),
        onBack = { extrasSheet(emulator, name) },
    )
}

private fun AppState.run(emulator: String, name: String, e: Extra, file: String?) {
    scope.launch {
        toasts.show("${e.title} for $name", ToastKind.INFO, icon = icon(e.kind))
        store.extras.run(emulator, e.kind, file)
            .onSuccess { toasts.show(it, ToastKind.SUCCESS, icon = FuseIcons.CircleCheck, durationMs = 8_000L) }
            .onFailure { toasts.show(it.message ?: "${e.title} didn't work", ToastKind.WARNING, durationMs = 8_000L) }
    }
}

private fun icon(kind: ExtraKind) = when (kind) {
    ExtraKind.CEMU_GRAPHIC_PACKS -> FuseIcons.Sparkles
    ExtraKind.CEMU_PRETENDO -> FuseIcons.Globe
    ExtraKind.RPCS3_PATCHES, ExtraKind.XENIA_PATCHES -> FuseIcons.Wand
    ExtraKind.SWITCH_FIRMWARE -> FuseIcons.HardDriveDownload
    ExtraKind.PLACE_FIRMWARE -> FuseIcons.PackagePlus
}
