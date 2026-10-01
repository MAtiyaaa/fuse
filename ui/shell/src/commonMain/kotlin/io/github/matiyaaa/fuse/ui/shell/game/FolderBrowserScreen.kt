package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.ui.shell.app.play

/** For games whose folder behaviour is "Open as a folder": pick which file inside to launch. */
@Composable
fun FolderBrowserScreen(app: AppState, id: GameId) {
    val detailFlow = remember(id) { app.store.library.game(id) }
    val detail by detailFlow.collectAsState(initial = null)
    var files by remember { mutableStateOf<List<String>?>(null) }
    val sel = remember { LinearSelection() }
    LaunchedEffect(id) { files = app.store.library.launchCandidates(id) }
    LaunchedEffect(Unit) { app.hints = listOf(Hint(HintButton.CONFIRM, "Play"), Hint(HintButton.BACK, "Back")) }
    val folder = detail?.game?.location?.path.orEmpty()
    val actions = files.orEmpty().map { path ->
        MenuAction(path, path.removePrefix(folder).trimStart('/'), FuseIcons.File, onSelect = {
            val card = detail?.toCard()
            if (card != null) app.play(card, discPath = path) else app.scope.launch { app.store.library.launch(id, discPath = path) }
        })
    }
    sel.clamp(actions.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, actions, sel) }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        Spacer(Modifier.height(Size.hudHeight + Space.l))
        FText(detail?.game?.displayTitle ?: "", Fuse.type.display, maxLines = 1)
        FText("Choose what to launch", Fuse.type.body, color = Fuse.colors.textMuted)
        Spacer(Modifier.height(Space.l))
        when {
            files == null -> Spinner()
            actions.isEmpty() -> FText("Nothing in this folder can be launched by this system's emulator.", Fuse.type.body, color = Fuse.colors.textMuted)
            else -> Panel(Modifier.widthIn(max = 880.dp).weight(1f).padding(bottom = Size.hintHeight + Space.s)) {
                MenuList(actions, sel, modifier = Modifier.padding(Space.s))
            }
        }
    }
}
