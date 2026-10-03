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
import io.github.matiyaaa.fuse.ui.shell.settings.importThemeFile
import io.github.matiyaaa.fuse.ui.shell.app.FilePurpose
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.LocateRequest
import io.github.matiyaaa.fuse.ui.shell.app.pickLicence
import io.github.matiyaaa.fuse.ui.shell.app.addGameFile
import io.github.matiyaaa.fuse.ui.shell.app.installApkGame
import io.github.matiyaaa.fuse.ui.shell.app.locateEmulator
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
 * system, then adds it to the library; picking a program ([locate]) tells Fuse where that emulator
 * is (a macOS app is picked like a file).
 */
@Composable
fun FilePickerScreen(app: AppState, purpose: FilePurpose, locate: LocateRequest? = null, licence: io.github.matiyaaa.fuse.ui.shell.app.LicencePick? = null) {
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
        val now = listing
        // Back up: on the folder just left. Into a folder: on its first entry, B goes up.
        val up = if (now?.path != null) 1 else 0
        sel.index = back?.let { b -> now?.entries?.indexOfFirst { it.path == b }?.takeIf { it >= 0 }?.plus(up) }
            ?: if (up == 1 && now?.entries?.isNotEmpty() == true) 1 else 0
        cameFrom = null
    }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Up a folder"))
    }

    val l = listing
    fun open(entry: BrowseEntry) {
        when {
            purpose == FilePurpose.EMULATOR && isProgram(entry) -> locate?.let { app.locateEmulator(it, entry.path) }
            entry.isDirectory -> { place.path = entry.path; sel.index = 0 }
            purpose == FilePurpose.APK -> app.installApkGame(entry.path)
            purpose == FilePurpose.EMULATOR -> Unit
            purpose == FilePurpose.THEME -> app.importThemeFile(entry.path)
            purpose == FilePurpose.LICENCE -> licence?.let { app.pickLicence(it, entry.path) }
            else -> app.addGameFile(entry.path)
        }
    }
    fun up() {
        cameFrom = l?.path
        place.path = l?.parent
    }
    val shown = l?.entries.orEmpty().filter { e ->
        when (purpose) {
            FilePurpose.GAME -> true
            FilePurpose.APK -> e.isDirectory || FsPath.extension(e.name) == "apk"
            FilePurpose.EMULATOR -> e.isDirectory || isProgram(e)
            FilePurpose.THEME -> e.isDirectory || FsPath.extension(e.name) == "json"
            FilePurpose.LICENCE -> e.isDirectory || isLicenceFile(e.name, licence?.vita == true)
        }
    }
    val rows = buildList {
        if (l?.path != null) add(MenuAction("up", "Up a folder", FuseIcons.ArrowLeft, detail = l.parent?.let(FsPath::name)?.ifEmpty { "/" } ?: "Storage", onSelect = ::up))
        shown.forEach { e ->
            add(
                when {
                    l?.path == null -> MenuAction("r.${e.path}", e.name, FuseIcons.HardDrive, detail = e.path, trailing = Trailing.Chevron, onSelect = { open(e) })
                    purpose == FilePurpose.EMULATOR && isProgram(e) -> MenuAction(
                        "f.${e.path}", e.name, FuseIcons.Joystick, detail = if (e.isDirectory) "App" else "Program", onSelect = { open(e) },
                    )
                    e.isDirectory -> MenuAction("d.${e.path}", e.name, FuseIcons.Folder, trailing = Trailing.Chevron, onSelect = { open(e) })
                    else -> MenuAction(
                        "f.${e.path}", e.name, when (purpose) { FilePurpose.APK -> FuseIcons.Package; FilePurpose.THEME -> FuseIcons.Palette; FilePurpose.LICENCE -> FuseIcons.Key; else -> FuseIcons.File },
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
            FText(
                when (purpose) {
                    FilePurpose.APK -> "Choose an APK"
                    FilePurpose.THEME -> "Choose a theme file"
                    FilePurpose.EMULATOR -> "Where is ${locate?.name ?: "the emulator"}?"
                    FilePurpose.GAME -> "Choose a game file"
                    FilePurpose.LICENCE -> if (licence?.vita == true) "Choose the licence or zRIF" else "Choose the .rap licence"
                },
                Fuse.type.title, maxLines = 1,
            )
            Spacer(Modifier.height(Space.xs))
            Trail(l)
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
                if (wide) Guide(purpose, selected, Modifier.weight(0.38f), locate)
            }
        }
    }
}

/** Where the picker is: the storage place, then the last folders below it. */
@Composable
private fun Trail(l: BrowseListing?) {
    val c = Fuse.colors
    val parts = when {
        l == null -> listOf("Looking at your storage")
        l.path == null -> listOf("Pick where to look")
        l.trail.size > 3 -> listOf(l.trail.first(), "...") + l.trail.takeLast(2)
        else -> l.trail.ifEmpty { listOf(l.path) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, part ->
            if (i > 0) FuseIcon(FuseIcons.ChevronRight, size = 16.dp, tint = c.textFaint, modifier = Modifier.padding(horizontal = Space.xs))
            FText(part, Fuse.type.body, color = if (i == parts.lastIndex) c.text else c.textMuted, maxLines = 1)
        }
    }
}

/** Files that can hold a licence: a PS3 .rap, or for the Vita a .rif, work.bin or a text file with its zRIF. */
private fun isLicenceFile(name: String, vita: Boolean): Boolean {
    val ext = FsPath.extension(name)
    return if (vita) ext in setOf("rif", "zrif", "txt", "tsv", "bin") else ext == "rap"
}

/** A program Fuse can run: a Windows .exe, a macOS app (a folder), or a file without an extension. */
private fun isProgram(e: BrowseEntry): Boolean {
    val ext = FsPath.extension(e.name)
    return if (e.isDirectory) ext == "app" else ext == "exe" || ext.isEmpty()
}

/** For a game file: the systems that take it; for an APK, nothing extra. */
private fun fileDetail(e: BrowseEntry, purpose: FilePurpose): String? {
    if (purpose == FilePurpose.APK) return "Android app"
    if (purpose == FilePurpose.THEME) return "Theme file"
    if (purpose == FilePurpose.LICENCE) return "Licence"
    val systems = PlatformCatalog.forExtension(FsPath.extension(e.name))
    return when {
        systems.isEmpty() -> null
        systems.size == 1 -> systems.single().name
        systems.size == 2 -> systems.joinToString(" or ") { it.shortName }
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
            purpose == FilePurpose.EMULATOR -> "No programs or folders here."
            purpose == FilePurpose.THEME -> "No theme files (.json) or folders here."
            purpose == FilePurpose.LICENCE -> "No licence files or folders here."
            else -> "This folder is empty."
        },
        Fuse.type.body,
        color = Fuse.colors.textMuted,
        modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
    )
}

/** What happens with the file, and what the selected row is. */
@Composable
private fun Guide(purpose: FilePurpose, selected: MenuAction?, modifier: Modifier, locate: LocateRequest?) {
    val c = Fuse.colors
    Panel(modifier.padding(bottom = Size.hintHeight + Space.s)) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Box(
                Modifier.size(56.dp).background(c.accent.copy(alpha = 0.14f), RoundedCornerShape(Fuse.geometry.control)),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(
                    when (purpose) {
                        FilePurpose.APK -> FuseIcons.Package
                        FilePurpose.EMULATOR -> FuseIcons.Joystick
                        FilePurpose.GAME -> FuseIcons.Gamepad
                        FilePurpose.THEME -> FuseIcons.Palette
                        FilePurpose.LICENCE -> FuseIcons.Key
                    },
                    size = 28.dp, tint = c.accent,
                )
            }
            FText(
                when (purpose) {
                    FilePurpose.APK -> "Install a game from its APK"
                    FilePurpose.EMULATOR -> "Show Fuse where ${locate?.name ?: "it"} is"
                    FilePurpose.GAME -> "Add a game from anywhere"
                    FilePurpose.THEME -> "Add a theme from a file"
                    FilePurpose.LICENCE -> "The licence for this content"
                },
                Fuse.type.titleSmall,
            )
            FText(
                when (purpose) {
                    FilePurpose.APK ->
                        "Android asks you to confirm the install. Then the game joins the Android system with box art, details and play time. The APK stays where it is."
                    FilePurpose.EMULATOR ->
                        "Pick its program (a .exe on Windows, the app on a Mac). Fuse remembers it and starts games with it from there."
                    FilePurpose.GAME ->
                        "Pick the file, then the system it's for. It joins your library with art and details, and stays where it is on your storage."
                    FilePurpose.THEME ->
                        "Pick a theme's .json file. Fuse shows what it is before adding it, and keeps a copy, so the file can go afterwards."
                    FilePurpose.LICENCE ->
                        "Pick the licence that came with it. Fuse hands it to the emulator under the name it looks for, and keeps nothing; the file stays where it is."
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
