package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorOption
import kotlinx.coroutines.launch

/**
 * Whether this device has [d] at all: Apps needs an app list (Android), Cartridge a platform it runs
 * on (Android and Linux). What isn't offered is left out of the tabs, Settings and Home, not shown
 * empty.
 */
internal fun AppState.offers(d: Destination): Boolean = when (d) {
    Destination.APPS -> store.apps.supported
    Destination.CARTRIDGE -> platform.features.cartridge
    else -> true
}

/** Whether a Home widget of [kind] can show anything on this device. */
internal fun AppState.offers(kind: WidgetKind): Boolean = when (kind) {
    WidgetKind.PINNED_APPS -> store.apps.supported
    WidgetKind.CARTRIDGE_DOWNLOADS -> platform.features.cartridge
    else -> true
}

/** Opens Fuse's picker to show where an emulator is. */
internal fun AppState.startLocate(request: LocateRequest) {
    choice = null
    contextMenu = null
    go(Route.PickFile(FilePurpose.EMULATOR, request))
}

/**
 * Uses the program at [path] for the emulator in [request], then the system or game it was being
 * chosen for uses it too.
 */
internal fun AppState.locateEmulator(request: LocateRequest, path: String) {
    scope.launch {
        if (store.emulators.locate(request.emulator, path)) {
            request.platform?.let { store.emulators.setPlatformEmulator(it, request.emulator) }
            request.game?.let { store.library.setEmulator(it, request.emulator) }
            navigator.pop()
            toasts.show("${request.name} is ready. Fuse starts games with it from there", ToastKind.SUCCESS)
        } else {
            toasts.show("Fuse can't start ${FsPath.name(path.trimEnd('/'))}. Pick ${request.name}'s program", ToastKind.ERROR)
        }
    }
}

/** "Locate an emulator": every emulator Fuse knows here, where it was found, and a way to point at it. */
internal fun AppState.locatePicker() {
    val located = store.emulators.located.value
    choice = ChoiceSpec(
        title = "Locate an emulator",
        message = "Fuse looks in the usual places. If it missed one, pick it here and show Fuse its program.",
        options = store.emulators.known().map { o ->
            MenuAction(
                "loc.${o.id.value}", o.name, if (o.installed) FuseIcons.CircleCheck else FuseIcons.Search,
                detail = when {
                    o.id in located -> "Located: ${located.getValue(o.id)}"
                    o.installed -> "Found: ${o.note}"
                    else -> "Not found"
                },
                onSelect = { if (o.id in located) locatedOptions(o) else startLocate(LocateRequest(o.id, o.name)) },
            )
        },
    )
}

private fun AppState.locatedOptions(o: EmulatorOption) {
    choice = ChoiceSpec(
        title = o.name,
        message = store.emulators.located.value[o.id],
        options = listOf(
            MenuAction("again", "Choose another program", FuseIcons.FolderOpen, onSelect = { startLocate(LocateRequest(o.id, o.name)) }),
            MenuAction("forget", "Forget this location", FuseIcons.Trash, detail = "Fuse looks for it in the usual places again", onSelect = {
                choice = null
                scope.launch {
                    store.emulators.forget(o.id)
                    toasts.show("Fuse forgot where ${o.name} was")
                }
            }),
        ),
    )
}

/** "Emulator folders": extra folders Fuse searches, with a way to add one or stop searching one. */
internal fun AppState.emulatorFoldersPicker() {
    val folders = store.emulators.searchFolders.value
    choice = ChoiceSpec(
        title = "Emulator folders",
        message = "Besides the usual places, Fuse searches these folders and the folders inside them.",
        options = listOf(
            MenuAction("add", "Add a folder", FuseIcons.FolderPlus, onSelect = {
                choice = null
                scope.launch {
                    val path = platform.storage.pickFolder("Choose a folder with emulators") ?: return@launch
                    store.emulators.setSearchFolders((folders + path).distinct())
                    toasts.show("Searching ${FsPath.name(path)} for emulators")
                }
            }),
        ) + folders.map { f ->
            MenuAction("f.$f", FsPath.name(f).ifEmpty { f }, FuseIcons.Folder, detail = "$f\nSelect to stop searching here", onSelect = {
                choice = null
                scope.launch { store.emulators.setSearchFolders(folders - f) }
            })
        },
    )
}
