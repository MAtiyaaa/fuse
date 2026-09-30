package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.FuseMark
import io.github.matiyaaa.fuse.ui.shell.app.Route
import kotlinx.coroutines.launch

/**
 * Home before any games are found. It says what to do next in one sentence and offers the two ways
 * forward, instead of an empty dashboard. While a scan runs it shows progress here.
 */
@Composable
fun HomeEmpty(app: AppState) {
    val scan by app.store.sources.scan.collectAsState()
    val sources by app.store.sources.sources.collectAsState()
    val scanning = scan.phase == ScanPhase.DISCOVERING || scan.phase == ScanPhase.SCANNING || scan.phase == ScanPhase.SAVING
    val sel = remember { LinearSelection() }
    val c = Fuse.colors

    fun addFolder() {
        app.scope.launch {
            val path = app.platform.storage.pickFolder("Choose your games folder") ?: return@launch
            app.store.sources.add(path)
            app.store.sources.rescan()
        }
    }

    val actions = listOf(
        Triple("Add a games folder", FuseIcons.FolderSearch) { addFolder() },
        Triple("Run setup", FuseIcons.Sparkles) { app.go(Route.Onboarding) },
    )

    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Choose"))
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, actions.size)
            NavAction.SELECT -> { actions[sel.index].third(); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            FuseMark(Modifier.size(40.dp))
            FText(
                if (scanning) "Looking for your games" else if (sources.isEmpty()) "Let's find your games" else "No games found yet",
                Fuse.type.hero,
            )
            FText(
                when {
                    scanning -> scan.currentPath ?: "Scanning"
                    sources.isEmpty() -> "Point Fuse at the folder that holds your ROMs (for example ROMs, or a RomM library). Fuse only reads it: nothing is moved or renamed."
                    else -> "Fuse looked in ${sources.size} ${if (sources.size == 1) "folder" else "folders"} and found nothing it recognises. Check that each system has its own folder, like ROMs/snes or roms/ps2."
                },
                Fuse.type.body,
                color = c.textMuted,
                maxLines = 4,
            )
            if (scanning) {
                ProgressBar(null, Modifier.widthIn(max = 420.dp).padding(top = Space.s))
                FText("${scan.gamesFound} games so far", Fuse.type.caption, color = c.textMuted)
            } else {
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    actions.forEachIndexed { i, (label, icon, run) ->
                        FuseButton(
                            label = label,
                            icon = icon,
                            selected = i == sel.index && app.focusZone == FocusZone.CONTENT,
                            kind = if (i == 0) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                            onClick = { sel.index = i; run() },
                        )
                    }
                }
            }
        }
    }
}
