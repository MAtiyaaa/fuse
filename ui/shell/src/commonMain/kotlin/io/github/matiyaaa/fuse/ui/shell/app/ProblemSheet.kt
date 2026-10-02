package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemAction
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A [Problem] on screen, with what it is about: the game it happened to ([card]) and how to try
 * again ([retry]), so its actions can act.
 */
data class ProblemSpec(
    val problem: Problem,
    val card: GameCard? = null,
    val retry: (() -> Unit)? = null,
)

/** Shows [problem] in the problem sheet. */
fun AppState.showProblem(problem: Problem, card: GameCard? = null, retry: (() -> Unit)? = null) {
    platform.sounds.play(if (problem.severity == Severity.INFO) SoundCue.OPEN else SoundCue.ERROR)
    this.problem = ProblemSpec(problem, card, retry)
}

/** The icon a problem is drawn with, after what it is about. */
fun ProblemKind.icon(): ImageVector = when (this) {
    ProblemKind.DRIVE -> FuseIcons.HardDrive
    ProblemKind.EMULATOR -> FuseIcons.Chip
    ProblemKind.FILE -> FuseIcons.FileQuestion
    ProblemKind.ACCESS -> FuseIcons.LockKeyhole
    ProblemKind.FIRMWARE -> FuseIcons.Key
    ProblemKind.NETWORK -> FuseIcons.WifiOff
    ProblemKind.ACCOUNT -> FuseIcons.UserRound
    ProblemKind.DATA -> FuseIcons.Database
    ProblemKind.DISPLAY -> FuseIcons.Monitor
    ProblemKind.RECOVERY -> FuseIcons.LifeBuoy
    ProblemKind.GENERAL -> FuseIcons.Alert
}

/** The colour a severity is told in: calm for information, warm for attention, red only when broken. */
@Composable
fun Severity.tint(): Color = when (this) {
    Severity.HEALTHY -> Fuse.colors.success
    Severity.INFO -> Fuse.colors.textMuted
    Severity.ATTENTION -> Fuse.colors.warning
    Severity.BROKEN -> Fuse.colors.danger
}

/** Carries out [action] for the problem in [spec]. Closes the sheet first, so what opens next is in front. */
fun AppState.runProblemAction(action: ProblemAction, spec: ProblemSpec?) {
    problem = null
    when (action) {
        is ProblemAction.Retry -> spec?.retry?.invoke()
        is ProblemAction.PickEmulator -> {
            val card = spec?.card
            when {
                card != null -> emulatorPicker(card)
                action.game != null -> scope.launch {
                    val detail = store.library.game(action.game).first() ?: return@launch
                    store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery(platform = detail.game.platformId)).first()
                        .firstOrNull { it.id == action.game }?.let(::emulatorPicker)
                }
                action.platform != null -> go(Route.PlatformSettings(action.platform))
            }
        }
        is ProblemAction.OpenEmulator -> scope.launch { store.emulators.openEmulator(action.emulator) }
        is ProblemAction.OpenLink -> platform.openUrl(action.url)
        is ProblemAction.CheckDrives -> {
            store.sources.refreshDrives()
            toasts.show("Looking at your drives")
        }
        is ProblemAction.OpenStorage -> go(Route.Storage)
        is ProblemAction.OpenSettings -> go(Route.Settings(action.section))
        is ProblemAction.GrantAccess -> platform.storage.request()
        is ProblemAction.OpenSystem -> go(Route.PlatformSettings(action.platform))
        is ProblemAction.OpenGame -> go(Route.GameInfo(action.game))
        is ProblemAction.AdoptDrive -> scope.launch {
            val ok = store.sources.adoptDrive(action.source)
            toasts.show(if (ok) "Fuse will use this drive for the library" else "That folder can't be read on this drive", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
        }
        is ProblemAction.RemoveSource -> {
            val source = store.sources.sources.value.firstOrNull { it.id == action.source } ?: return
            confirm = ConfirmSpec(
                title = "Remove ${source.label}?",
                message = "Fuse stops looking in this folder. Its games stay in Fuse, marked missing, so adding it again brings them back with everything you changed. No files are deleted.",
                confirmLabel = "Remove",
                destructive = true,
                onConfirm = { scope.launch { store.sources.remove(source) } },
            )
        }
        is ProblemAction.Rescan -> {
            store.sources.rescan(ScanScope.QUICK)
            toasts.show("Scanning your library")
        }
        is ProblemAction.ShowMissing -> {
            navigator.remembered("lib.all") { io.github.matiyaaa.fuse.ui.shell.library.LibraryViewState() }.apply {
                segment = io.github.matiyaaa.fuse.ui.shell.library.LibrarySegment.MISSING
                system = null
            }
            navigator.replace(Route.Root(io.github.matiyaaa.fuse.model.Destination.LIBRARY))
        }
        is ProblemAction.OpenHealth -> go(Route.Settings("health"))
        is ProblemAction.LeaveSafeMode -> {
            safeMode = null
            store.resumeAutomaticWork()
            toasts.show("Fuse is running normally again", ToastKind.SUCCESS)
        }
        is ProblemAction.ResetAppearance -> {
            store.updatePrefs {
                it.withTheme(io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets.Fuse)
                    .copy(motion = null, glass = io.github.matiyaaa.fuse.model.GlassSettings(), crt = io.github.matiyaaa.fuse.model.CrtSettings(), videoPreview = true)
            }
            toasts.show("Appearance is back to Fuse's own", ToastKind.SUCCESS)
        }
    }
}

/** The safe mode sheet: why Fuse is in it, that nothing was lost, and the ways out. */
fun AppState.showSafeMode() {
    val mode = safeMode ?: return
    val repeated = mode.reason == SafeMode.Reason.REPEATED_FAILURES
    showProblem(
        Problem(
            title = "Fuse started in safe mode",
            message = if (repeated) {
                "The last ${mode.failedStarts} starts didn't finish. Fuse is running with its own look, without effects, music or video, and nothing starts by itself, so you can change what went wrong."
            } else {
                "You asked Fuse to start safely. It is running with its own look, without effects, music or video, and nothing starts by itself."
            },
            kind = ProblemKind.RECOVERY,
            severity = Severity.INFO,
            reassurance = "Your library, settings and themes are kept exactly as they were.",
            actions = listOf(ProblemAction.LeaveSafeMode(), ProblemAction.ResetAppearance(), ProblemAction.OpenSettings("about", "Settings")),
        ),
    )
}

/**
 * The problem sheet: an icon in a well tinted by how much it matters, the headline, the
 * explanation, a line saying nothing was harmed (when true), and what can be done, as a list the
 * D-pad walks. "Technical details" opens the system's own words in place, for a bug report, without
 * ever leading the sheet.
 */
@Composable
internal fun ProblemOverlay(app: AppState) {
    val spec = app.problem
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    var details by remember(spec) { mutableStateOf(false) }
    val sel = remember(spec) { LinearSelection() }
    val actions = (spec ?: shown)?.let { s -> problemActions(app, s, details) { details = !details } }.orEmpty()
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { app.problem = null; app.platform.sounds.play(SoundCue.CLOSE); NavResult.CONSUMED }
                else -> handleMenuAction(e, actions, sel)
            }
        }
    }
    LaunchedEffect(spec) { sel.index = 0 }
    Overlay(visible = spec != null, onDismiss = { app.problem = null }, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        val p = s.problem
        val c = Fuse.colors
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val compact = maxHeight < 560.dp
            Panel(
                Modifier
                    .widthIn(min = minOf(440.dp, room), max = minOf(580.dp, room))
                    .heightIn(max = minOf(620.dp, maxHeight - Space.l * 2)),
            ) {
                Column(Modifier.padding(if (compact) Space.l else Space.xl)) {
                    Row(verticalAlignment = Alignment.Top) {
                        val tint = p.severity.tint()
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            FuseIcon(p.kind.icon(), size = Size.iconL, tint = tint)
                        }
                        Spacer(Modifier.width(Space.l))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            FText(p.title, Fuse.type.title, maxLines = 3, modifier = Modifier.semantics { heading() })
                            FText(p.message, Fuse.type.body, color = c.textMuted, maxLines = if (compact) 5 else 8)
                        }
                    }
                    p.reassurance?.let { line ->
                        Spacer(Modifier.height(Space.m))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control)).background(c.success.copy(alpha = 0.08f))
                                .padding(horizontal = Space.m, vertical = Space.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FuseIcon(FuseIcons.ShieldCheck, size = Size.iconS, tint = c.success)
                            Spacer(Modifier.width(Space.s))
                            FText(line, Fuse.type.caption, color = c.text, maxLines = 3)
                        }
                    }
                    AnimatedVisibility(details && p.details != null) {
                        Column {
                            Spacer(Modifier.height(Space.m))
                            Box(
                                Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(Fuse.geometry.control))
                                    .background(c.text.copy(alpha = 0.05f)).padding(Space.m).verticalScroll(rememberScrollState()),
                            ) {
                                FText(p.details.orEmpty(), Fuse.type.caption, color = c.textMuted, maxLines = 40)
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    MenuList(actions, sel, modifier = Modifier.weight(1f, fill = false), fill = false)
                }
            }
        }
    }
}

/** The sheet's rows: the problem's own actions, technical details when there are any, then Close. */
private fun problemActions(app: AppState, spec: ProblemSpec, detailsOpen: Boolean, toggleDetails: () -> Unit): List<MenuAction> = buildList {
    spec.problem.actions.forEachIndexed { i, a ->
        add(MenuAction("a$i", a.label, a.icon(), onSelect = { app.runProblemAction(a, spec) }))
    }
    if (spec.problem.details != null) {
        add(
            MenuAction(
                "details", if (detailsOpen) "Hide technical details" else "Technical details", FuseIcons.FileText,
                trailing = if (detailsOpen) Trailing.None else Trailing.Chevron, section = "", onSelect = toggleDetails,
            ),
        )
    }
    add(MenuAction("close", "Close", FuseIcons.Close, section = if (spec.problem.details != null) null else "", onSelect = { app.problem = null }))
}

private fun ProblemAction.icon(): ImageVector = when (this) {
    is ProblemAction.Retry -> FuseIcons.RotateCcw
    is ProblemAction.PickEmulator -> FuseIcons.Chip
    is ProblemAction.OpenEmulator -> FuseIcons.External
    is ProblemAction.OpenLink -> FuseIcons.Download
    is ProblemAction.CheckDrives -> FuseIcons.Refresh
    is ProblemAction.OpenStorage -> FuseIcons.HardDrive
    is ProblemAction.OpenSettings -> FuseIcons.Settings
    is ProblemAction.GrantAccess -> FuseIcons.LockOpen
    is ProblemAction.OpenSystem -> FuseIcons.Layers
    is ProblemAction.OpenGame -> FuseIcons.Gamepad
    is ProblemAction.AdoptDrive -> FuseIcons.FolderSync
    is ProblemAction.RemoveSource -> FuseIcons.FolderX
    is ProblemAction.Rescan -> FuseIcons.ScanSearch
    is ProblemAction.LeaveSafeMode -> FuseIcons.LogOut
    is ProblemAction.ShowMissing -> FuseIcons.FileSearch
    is ProblemAction.OpenHealth -> FuseIcons.HeartPulse
    is ProblemAction.ResetAppearance -> FuseIcons.Paintbrush
}
