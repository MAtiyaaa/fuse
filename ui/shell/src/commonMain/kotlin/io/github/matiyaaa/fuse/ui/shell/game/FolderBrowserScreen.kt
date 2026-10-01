package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonRow
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.play
import kotlinx.coroutines.launch

/**
 * For games whose folder behaviour is "Open as a folder": pick which file inside to launch. The
 * game's name leads, the folder it is reading sits under it, and the files are a list with an icon
 * for what each one is (a disc image, an archive, a playlist). While Fuse looks inside the folder
 * the list keeps its shape as placeholders.
 */
@Composable
fun FolderBrowserScreen(app: AppState, id: GameId) {
    val detailFlow = remember(id) { app.store.library.game(id) }
    val detail by detailFlow.collectAsState(initial = null)
    var files by remember { mutableStateOf<List<String>?>(null) }
    val sel = remember { LinearSelection() }
    val reveal = rememberReveal(id)
    LaunchedEffect(id) { files = app.store.library.launchCandidates(id) }
    LaunchedEffect(Unit) { app.hints = listOf(Hint(HintButton.CONFIRM, "Play"), Hint(HintButton.BACK, "Back")) }
    val folder = detail?.game?.location?.path.orEmpty()
    val actions = files.orEmpty().map { path ->
        val inside = path.removePrefix(folder).trimStart('/', '\\')
        val cut = inside.lastIndexOfAny(charArrayOf('/', '\\'))
        MenuAction(
            path,
            if (cut < 0) inside else inside.substring(cut + 1),
            fileIcon(inside),
            // Files in subfolders say where they are, so two "Disc 1" files can be told apart.
            detail = if (cut < 0) null else inside.substring(0, cut),
            onSelect = {
                val card = detail?.toCard()
                if (card != null) app.play(card, discPath = path) else app.scope.launch { app.store.library.launch(id, discPath = path) }
            },
        )
    }
    sel.clamp(actions.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, actions, sel) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.xl))
            Column(Modifier.reveal(reveal, 0), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                val title = detail?.game?.displayTitle
                val style = if (compact) Fuse.type.title else Fuse.type.display
                if (title != null) {
                    FText(title, style, maxLines = 1)
                } else {
                    val h = with(LocalDensity.current) { style.lineHeight.toDp() }
                    Skeleton(Modifier.fillMaxWidth(0.4f).height(h), shape = RoundedCornerShape(Radius.s))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.FolderOpen, size = Size.iconS, tint = Fuse.colors.textMuted)
                    Spacer(Modifier.width(Space.s))
                    FText("Choose what to launch", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                    if (folder.isNotEmpty()) {
                        FText("  ·  ", Fuse.type.body, color = Fuse.colors.textFaint, maxLines = 1)
                        FText(shortFolder(folder), Fuse.type.body, color = Fuse.colors.textFaint, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            val panel = Modifier.widthIn(max = Size.touch * 18).weight(1f).padding(bottom = Size.hintHeight + Space.s).reveal(reveal, 1)
            when {
                files == null -> Panel(panel) {
                    Column(Modifier.padding(Space.s)) { repeat(5) { SkeletonRow(detail = it % 2 == 1) } }
                }
                actions.isEmpty() -> Box(panel.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        FuseIcons.FolderSearch,
                        "Nothing here can be launched",
                        message = "None of the files in this folder is one ${detail?.platform?.name ?: "this system"} games use. " +
                            "Folder Behaviour in the game's options reads the folder another way.",
                        compact = compact,
                    )
                }
                else -> Panel(panel) {
                    MenuList(actions, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                }
            }
        }
    }
}

/** An icon for what a file is, from its extension. */
private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "iso", "chd", "cue", "bin", "gdi", "cdi", "rvz", "gcz", "wbfs", "cso", "pbp" -> FuseIcons.Disc
    "zip", "7z", "rar" -> FuseIcons.FileArchive
    "m3u" -> FuseIcons.FileText
    "nsp", "xci", "3ds", "cia" -> FuseIcons.Package
    else -> FuseIcons.File
}
