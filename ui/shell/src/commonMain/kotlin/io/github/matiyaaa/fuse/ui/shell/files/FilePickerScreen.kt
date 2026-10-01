package io.github.matiyaaa.fuse.ui.shell.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FilePurpose
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.addGameFile
import io.github.matiyaaa.fuse.ui.shell.app.installApkGame
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.BrowseEntry
import io.github.matiyaaa.fuse.ui.shell.store.BrowseListing

/** Where the picker was last, kept for the next time it opens. */
private class PickerPlace {
    var path by mutableStateOf<String?>(null)
    val selection = LinearSelection()
}

/**
 * Fuse's own file picker, made for a controller: storage places, then folders and files, folders
 * first. B goes up a folder. Picking an APK installs it as a game; picking a game file asks for its
 * system, then adds it to the library.
 */
@Composable
fun FilePickerScreen(app: AppState, purpose: FilePurpose) {
    val place = rememberRouteState(app.navigator, "pickfile.$purpose") { PickerPlace() }
    val sel = place.selection
    var listing by androidx.compose.runtime.remember { mutableStateOf<BrowseListing?>(null) }
    // The folder just left, so going up lands on it.
    var cameFrom by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

    LaunchedEffect(place.path) {
        val loaded = app.store.library.browse(place.path)
        // A folder that went away since last time: start from the storage places.
        listing = if (loaded.error != null && place.path != null && cameFrom == null && listing == null) app.store.library.browse(null).also { place.path = null } else loaded
        val back = cameFrom
        sel.index = back?.let { b -> listing?.entries?.indexOfFirst { it.path == b }?.takeIf { it >= 0 }?.plus(if (listing?.path != null) 1 else 0) } ?: 0
        cameFrom = null
    }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Up a folder"))
    }

    val l = listing
    fun open(entry: BrowseEntry) {
        when {
            entry.isDirectory -> { place.path = entry.path; sel.index = 0 }
            purpose == FilePurpose.APK -> app.installApkGame(entry.path)
            else -> app.addGameFile(entry.path)
        }
    }
    fun up() {
        cameFrom = l?.path
        place.path = l?.parent
    }
    val shown = l?.entries.orEmpty().filter { e -> e.isDirectory || purpose == FilePurpose.GAME || FsPath.extension(e.name) == "apk" }
    val rows = buildList {
        if (l?.path != null) add(MenuAction("up", "Up a folder", FuseIcons.ArrowLeft, detail = l.parent?.let(FsPath::name)?.ifEmpty { "/" } ?: "Storage", onSelect = ::up))
        shown.forEach { e ->
            add(
                when {
                    l?.path == null -> MenuAction("r.${e.path}", e.name, FuseIcons.HardDrive, detail = e.path, trailing = Trailing.Chevron, onSelect = { open(e) })
                    e.isDirectory -> MenuAction("d.${e.path}", e.name, FuseIcons.Folder, trailing = Trailing.Chevron, onSelect = { open(e) })
                    else -> MenuAction(
                        "f.${e.path}", e.name, if (purpose == FilePurpose.APK) FuseIcons.Package else FuseIcons.File,
                        detail = fileDetail(e, purpose),
                        trailing = Trailing.Value(bytesText(e.sizeBytes)),
                        onSelect = { open(e) },
                    )
                },
            )
        }
    }
    sel.clamp(rows.size)

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.BACK -> if (l?.path != null) { up(); NavResult.CONSUMED } else NavResult.IGNORED
            else -> handleMenuAction(e, rows, sel)
        }
    }

    val selected = rows.getOrNull(sel.index)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 820.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            FText(if (purpose == FilePurpose.APK) "Choose an APK" else "Choose a game file", Fuse.type.title, maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            FText(where(l), Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
            Spacer(Modifier.height(Space.l))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                Box(Modifier.weight(if (wide) 0.62f else 1f).fillMaxHeight()) {
                    when {
                        l == null -> Spinner(Modifier.align(Alignment.Center))
                        else -> MenuList(
                            rows, sel,
                            modifier = Modifier.fillMaxWidth().padding(bottom = Size.hintHeight),
                            showSelection = app.focusZone == FocusZone.CONTENT,
                            header = if (shown.isEmpty() || l.error != null) {
                                { Empty(l, purpose) }
                            } else {
                                null
                            },
                        )
                    }
                }
                if (wide) Guide(purpose, selected, Modifier.weight(0.38f))
            }
        }
    }
}

/** The folder shown, as a short path, or the storage places. */
private fun where(l: BrowseListing?): String = when {
    l == null -> "Looking at your storage"
    l.path == null -> "Pick where to look"
    else -> l.path
}

/** For a game file: the systems that take it; for an APK, nothing extra. */
private fun fileDetail(e: BrowseEntry, purpose: FilePurpose): String? {
    if (purpose == FilePurpose.APK) return "Android app"
    val systems = PlatformCatalog.forExtension(FsPath.extension(e.name))
    return when {
        systems.isEmpty() -> null
        systems.size <= 2 -> systems.joinToString(" or ") { it.shortName }
        else -> "${systems.first().shortName} and ${systems.size - 1} more"
    }
}

@Composable
private fun Empty(l: BrowseListing, purpose: FilePurpose) {
    FText(
        when {
            l.error != null -> l.error
            l.path == null -> "Fuse can't see any storage. Allow All files access in Settings, Library."
            purpose == FilePurpose.APK -> "No APK files or folders here."
            else -> "This folder is empty."
        },
        Fuse.type.body,
        color = Fuse.colors.textMuted,
        modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
    )
}

/** What happens with the file, and what the selected row is. */
@Composable
private fun Guide(purpose: FilePurpose, selected: MenuAction?, modifier: Modifier) {
    val c = Fuse.colors
    Panel(modifier.padding(bottom = Size.hintHeight + Space.s)) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Box(
                Modifier.size(56.dp).background(c.accent.copy(alpha = 0.14f), RoundedCornerShape(Fuse.geometry.control)),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(if (purpose == FilePurpose.APK) FuseIcons.Package else FuseIcons.Gamepad, size = 28.dp, tint = c.accent)
            }
            FText(if (purpose == FilePurpose.APK) "Install a game from its APK" else "Add a game from anywhere", Fuse.type.titleSmall)
            FText(
                if (purpose == FilePurpose.APK) {
                    "Android asks you to confirm the install. Then the game joins the Android system with box art, details and play time. The APK stays where it is."
                } else {
                    "Pick the file, then the system it's for. It joins your library with art and details, and stays where it is on your storage."
                },
                Fuse.type.body,
                color = c.textMuted,
            )
            if (selected != null && selected.id.startsWith("f.")) {
                Spacer(Modifier.height(Space.s))
                FText(selected.label, Fuse.type.label, maxLines = 2)
                selected.detail?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 1) }
            }
        }
    }
}
