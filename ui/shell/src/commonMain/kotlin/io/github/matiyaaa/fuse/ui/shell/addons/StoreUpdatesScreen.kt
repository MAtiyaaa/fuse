package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.Managed
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseCheck
import io.github.matiyaaa.fuse.ui.shell.store.Standing
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import io.github.matiyaaa.fuse.ui.shell.store.StoreState

/**
 * Every installed app's updates in one place: what each has and what is new, its size, the channel
 * it follows, and whether Update All takes it. Apps installed some other way say who updates them
 * (Flatpak, the system), and Fuse never puts a second copy beside them. While a game is played,
 * updates wait for it to end.
 */
@Composable
fun StoreUpdatesScreen(app: AppState) {
    val ops = app.store.appStore
    val state by ops.state.collectAsState()
    val sel = remember { LinearSelection(0) }
    PageEffect(Unit) { ops.checkInstalled(force = false) }
    PageEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }

    val apps = installedApps(state)
    val updatable = state.updatable
    val size = updatable.sumOf { (state.releases[it.key] as? ReleaseCheck.Ready)?.release?.file?.sizeBytes ?: 0L }
    val checkedAt = state.releases.values.filterIsInstance<ReleaseCheck.Ready>().maxOfOrNull { it.checkedAt }
    val rows = buildList {
        add(MenuAction(
            "updates.all", if (updatable.isEmpty()) "Everything Is Up to Date" else "Update All (${updatable.size})", FuseIcons.CircleArrowDown,
            detail = when {
                state.holding -> "Waiting for your game to end. Nothing is replaced under a game"
                updatable.isEmpty() && state.updates.isNotEmpty() -> "The updates there are, you left out of Update All"
                updatable.isEmpty() -> "Fuse looks again by itself, and when you check"
                else -> listOfNotNull(updatable.joinToString(", ") { it.name }.take(80), size.takeIf { it > 0 }?.let(::bytesText)).joinToString("  ·  ")
            },
            enabled = updatable.isNotEmpty() && !state.holding,
            onSelect = { ops.updateAll() },
        ))
        add(MenuAction(
            "updates.check", "Check Again", FuseIcons.Refresh,
            detail = checkedAt?.let { "Looked ${agoText(it)}" } ?: "Looks at each app's own releases",
            onSelect = { ops.checkInstalled(force = true) },
        ))
        for (a in apps) add(appRow(app, state, a))
    }
    sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, sel)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            FText("Updates", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
            FText(
                when {
                    apps.isEmpty() -> "Nothing from the Store is installed yet."
                    state.updates.isEmpty() -> "${apps.size} installed, all up to date as far as Fuse knows."
                    else -> "${state.updates.size} of ${apps.size} installed ${if (state.updates.size == 1) "has an update" else "have updates"}. Choose one to leave it out of Update All, or to follow its pre-releases."
                },
                Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 2,
            )
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Panel(Modifier.widthIn(max = Size.touch * 20).fillMaxWidth().weight(1f).padding(bottom = Size.hintHeight + Space.s)) {
                MenuList(rows, sel, modifier = Modifier.padding(Space.s).fillMaxHeight(), showSelection = app.focusZone == FocusZone.CONTENT)
            }
        }
    }
}

/** Installed apps, those with an update first, then by name. */
internal fun installedApps(state: StoreState): List<StoreApp> =
    state.catalogue?.apps.orEmpty().filter { it.key in state.installed }
        .sortedWith(compareBy<StoreApp>({ a -> if (state.updates.any { it.key == a.key }) 0 else 1 }, { it.name.lowercase() }))

/** What an app's line on the Updates page says about it, from its install and its newest release. */
internal fun updateLine(state: StoreState, a: StoreApp): String {
    val installed = state.installed[a.key]
    val release = (state.releases[a.key] as? ReleaseCheck.Ready)?.release
    val have = installed?.versionName?.removePrefix("v")
    val channel = if (a.key in state.prerelease) "  ·  Pre-releases" else ""
    state.jobs[a.key]?.let { job -> return jobLine(job, a.name, state.desktop).removeSuffix(".") }
    return when (state.managedBy(a.key)) {
        Managed.FLATPAK -> "Updated with Flatpak, from its own remote" + (have?.let { "  ·  $it" } ?: "")
        Managed.SYSTEM -> "Updated by your system's packages" + (have?.let { "  ·  $it" } ?: "")
        Managed.ELSEWHERE -> "Installed outside Fuse, so Fuse leaves it as it is" + (have?.let { "  ·  $it" } ?: "")
        Managed.FUSE -> when (state.standing(a.key)) {
            Standing.UPDATE -> listOfNotNull(
                listOfNotNull(have, release?.version?.removePrefix("v")).joinToString(" to "),
                release?.file?.sizeBytes?.let(::bytesText),
            ).joinToString("  ·  ") + channel
            Standing.CURRENT -> "Up to date" + (have?.let { ", $it" } ?: "") + channel
            else -> when (state.releases[a.key]) {
                is ReleaseCheck.Failed -> "Couldn't look: " + (state.releases[a.key] as ReleaseCheck.Failed).message
                ReleaseCheck.Checking -> "Looking"
                else -> (have?.let { "Version $it" } ?: "Installed") + channel
            }
        }
    }
}

private fun appRow(app: AppState, state: StoreState, a: StoreApp): MenuAction {
    val ops = app.store.appStore
    val update = state.updates.any { it.key == a.key }
    val excluded = a.key in state.excluded
    return MenuAction(
        "app.${a.key}", a.name,
        when {
            update -> FuseIcons.CircleArrowDown
            state.managedBy(a.key) != Managed.FUSE -> FuseIcons.Package
            else -> FuseIcons.PackageCheck
        },
        detail = updateLine(state, a),
        trailing = when {
            excluded && update -> Trailing.Value("Left out")
            update -> Trailing.Value("Update")
            else -> Trailing.Chevron
        },
        onSelect = {
            val prerelease = a.key in state.prerelease
            app.choice = ChoiceSpec(
                title = a.name,
                message = updateLine(state, a),
                icon = FuseIcons.Package,
                options = listOfNotNull(
                    MenuAction("u.now", "Update Now", FuseIcons.CircleArrowDown, onSelect = { app.choice = null; ops.install(a.key) })
                        .takeIf { update && state.jobs[a.key]?.active != true },
                    MenuAction("u.flatpak", "Update with Flatpak", FuseIcons.CircleArrowDown, detail = "Flatpak fetches it from its own remote; Fuse doesn't put a copy beside it", onSelect = { app.choice = null; ops.updateOther(a.key) })
                        .takeIf { state.managedBy(a.key) == Managed.FLATPAK && state.jobs[a.key]?.active != true },
                    MenuAction(
                        "u.exclude", if (excluded) "Put Back in Update All" else "Leave Out of Update All", if (excluded) FuseIcons.ListPlus else FuseIcons.CircleSlash,
                        detail = if (excluded) null else "It still updates from here or its page", onSelect = { app.choice = null; ops.setExcluded(a.key, !excluded) },
                    ).takeIf { state.managedBy(a.key) == Managed.FUSE },
                    MenuAction(
                        "u.channel", if (prerelease) "Follow Stable Releases" else "Follow Pre-releases", FuseIcons.GitCompare,
                        detail = if (prerelease) "Back to the releases its developers mark as stable" else "Nightly and beta builds, as its developers publish them. Newer, and less tested",
                        onSelect = { app.choice = null; ops.setPrerelease(a.key, !prerelease) },
                    ).takeIf { a.key in state.channels && state.managedBy(a.key) == Managed.FUSE },
                    MenuAction("u.page", "Details and What's New", FuseIcons.Info, onSelect = { app.choice = null; app.go(Route.StoreApp(a.key)) }),
                ),
            )
        },
    )
}
