package io.github.matiyaaa.fuse.ui.shell.sync

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.sync.EmulatorSaves
import io.github.matiyaaa.fuse.sync.SaveAdapters
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
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
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.settings.infoRow
import kotlinx.coroutines.launch

/** The page's own state while it is open: the emulators, once looked at, and a count to look again. */
internal class SaveFoldersState {
    val sel = LinearSelection()
    var list by mutableStateOf<List<EmulatorSaves>?>(null)
    var round by mutableIntStateOf(0)
}

/**
 * Settings, Save folders (from Fuse Sync and from Syncthing): each emulator the library plays in,
 * and where its saves are on this device. Those Fuse can't find come first, each with what to do;
 * any of them can be pointed at a folder the person chose (an emulator that saves where you tell
 * it, a memory stick on a card, an Android folder moved out of private storage).
 */
@Composable
internal fun SaveFoldersScreen(app: AppState) {
    val page = rememberRouteState(app.navigator, "save.folders") { SaveFoldersState() }
    val installed by app.store.emulators.installed.collectAsState()
    val reveal = rememberReveal()
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }
    LaunchedEffect(page.round) {
        page.list = runCatching { app.store.sync.saveFolders() }.getOrDefault(emptyList())
    }
    val list = page.list
    val rows = saveFolderRows(app, list, installed, page)
    page.sel.keepOn(rows.map { it.id })
    page.sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, page.sel)
    }
    val missing = list?.count { it.state == EmulatorSaves.State.NOT_FOUND } ?: 0
    val (ok, title, detail) = when {
        list == null -> Triple(null, "Looking", "Finding where each emulator keeps its saves")
        list.isEmpty() -> Triple(null, "Nothing yet", "Emulators show here once your library plays in them")
        missing > 0 -> Triple(false, count(missing, "needs a folder", "need a folder"), "of ${count(list.size, "emulator")} in your library")
        else -> Triple(true, "All found", "${count(list.count { it.state == EmulatorSaves.State.FOUND }, "emulator")} with saves Fuse can reach")
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        val wide = maxWidth >= 1040.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(Modifier.reveal(reveal, 0), verticalAlignment = Alignment.CenterVertically) {
                SaveFoldersMark(if (compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    FText("Save Folders", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(ok)
                        Spacer(Modifier.width(Space.s))
                        FText(title, Fuse.type.bodyStrong, maxLines = 1)
                        FText("  ·  $detail", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1, fit = true)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.widthIn(max = Size.touch * 18).weight(1f, fill = !wide).fillMaxHeight().reveal(reveal, 1)) {
                    MenuList(rows, page.sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                }
                if (wide) SaveFoldersSide(Modifier.width(320.dp).reveal(reveal, 2))
            }
        }
    }
}

private fun saveFolderRows(app: AppState, list: List<EmulatorSaves>?, installed: List<InstalledEmulator>, page: SaveFoldersState): List<MenuAction> = buildList {
    add(MenuAction("import", "Import Saves", FuseIcons.FileUp, detail = "A save file, folder or ZIP from another device. Preview matches before replacing anything", onSelect = { openSaveImporter(app) }))
    if (list == null) {
        add(infoRow("looking", "Looking at your emulators", icon = FuseIcons.Search))
        return@buildList
    }
    if (list.isEmpty()) {
        add(infoRow("none", "No emulators in use yet", detail = "Add games to your library, and the emulators that play them show here with their save folders", icon = FuseIcons.FolderOpen))
        return@buildList
    }
    for (e in list) {
        val name = (installed.firstOrNull { it.id.value == e.emulatorId } ?: installed.firstOrNull { SaveAdapters.baseId(it.id.value) == e.emulator })?.name
            ?: e.emulator.replaceFirstChar { it.uppercase() }
        val systems = e.systems.mapNotNull { PlatformCatalog.byId(PlatformId(it))?.shortName }.distinct().take(3).joinToString(", ")
        val about = when (e.state) {
            EmulatorSaves.State.FOUND -> e.chosen ?: e.where
            else -> e.note
        }
        val section = when (e.state) {
            EmulatorSaves.State.NOT_FOUND -> "Fuse can't reach these"
            EmulatorSaves.State.FOUND -> "Found"
            EmulatorSaves.State.UNSUPPORTED -> "Not supported yet"
        }
        add(MenuAction(
            "emu.${e.emulator}", name,
            when {
                e.state == EmulatorSaves.State.UNSUPPORTED -> FuseIcons.CircleSlash
                e.state == EmulatorSaves.State.FOUND -> FuseIcons.FolderOpen
                e.note?.contains("private Android folder") == true -> FuseIcons.ShieldAlert
                else -> FuseIcons.FolderSearch
            },
            detail = listOf(systems, about.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  "),
            trailing = when (e.state) {
                EmulatorSaves.State.FOUND -> Trailing.Value(if (e.chosen != null) "Your folder" else "Found")
                EmulatorSaves.State.NOT_FOUND -> if (e.canChoose) Trailing.Value("Choose") else Trailing.Value("Can't reach")
                EmulatorSaves.State.UNSUPPORTED -> Trailing.Value("Not yet")
            },
            unavailableReason = if (e.state == EmulatorSaves.State.UNSUPPORTED) e.note else null,
            section = section,
            onSelect = { chooseFolder(app, e, name, page) },
        ))
    }
}

/** What can be done about one emulator's folder: choose one, or go back to where Fuse finds it. */
private fun chooseFolder(app: AppState, e: EmulatorSaves, name: String, page: SaveFoldersState) {
    if (!e.canChoose) {
        app.toasts.show("$name says where its saves are itself: its Saves folder, in its own Settings, Directory")
        return
    }
    fun pick() {
        app.choice = null
        app.scope.launch {
            val path = app.platform.storage.pickFolder("Where $name keeps its saves") ?: return@launch
            app.store.sync.setSaveFolder(e.emulator, path)
            app.toasts.show("$name's saves are kept in step from there", ToastKind.SUCCESS, icon = FuseIcons.FolderSync)
            page.round++
        }
    }
    val message = when {
        e.chosen != null -> "Fuse keeps $name's saves in step from ${e.chosen}."
        e.state == EmulatorSaves.State.FOUND -> "Fuse found its saves in ${e.where}. Choose another folder if $name keeps them somewhere else."
        else -> e.note ?: "Choose the folder $name keeps its saves in."
    }
    app.choice = ChoiceSpec(
        title = name,
        icon = FuseIcons.FolderOpen,
        message = message,
        options = listOfNotNull(
            MenuAction("pick", if (e.chosen != null) "Choose Another Folder" else "Choose Its Folder", FuseIcons.FolderPlus, detail = "The folder that has its saves (or its data folder, with the saves inside)", onSelect = { pick() }),
            if (e.chosen != null) MenuAction("auto", "Let Fuse Find It", FuseIcons.FolderSearch, detail = "Back to where $name keeps its saves by default", onSelect = {
                app.choice = null
                app.scope.launch {
                    app.store.sync.setSaveFolder(e.emulator, null)
                    page.round++
                }
            }) else null,
        ),
    )
}

/** On wide screens: how Fuse finds saves, and what helps where it can't. */
@Composable
private fun SaveFoldersSide(modifier: Modifier) {
    val c = Fuse.colors
    Panel(modifier) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            FText("How Fuse finds saves", Fuse.type.bodyStrong, maxLines = 1)
            for ((icon, line) in listOf(
                FuseIcons.FolderSearch to "By where each emulator keeps them, and by each game's own id for 3DS, Switch, Wii U, PS3 and PSP",
                FuseIcons.FolderPlus to "Where an emulator saves to a folder you picked in it, pick the same folder here",
                FuseIcons.ShieldAlert to "Android keeps some apps' own folders private. Moving the emulator's data to your storage fixes that",
                FuseIcons.Users to "Fuse Sync keeps each person's saves apart. Syncthing keeps one save per game for everyone",
            )) {
                Row(verticalAlignment = Alignment.Top) {
                    FuseIcon(icon, size = Size.iconS, tint = c.accent)
                    Spacer(Modifier.width(Space.s))
                    FText(line, Fuse.type.caption, color = c.textMuted, maxLines = 4)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            FText(
                "Folders chosen here are used by Fuse Sync and Syncthing alike, and only on this device.",
                Fuse.type.caption, color = c.textFaint, maxLines = 3,
            )
        }
    }
}

/** The page's mark: an open folder in a well of the accent. */
@Composable
private fun SaveFoldersMark(size: Dp, modifier: Modifier = Modifier) {
    val accent = Fuse.colors.accent
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.3f), accent.copy(alpha = 0.1f)))),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(FuseIcons.FolderOpen, size = size * 0.48f, tint = accent)
    }
}
