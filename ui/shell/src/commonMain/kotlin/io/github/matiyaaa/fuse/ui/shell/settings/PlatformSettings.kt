package io.github.matiyaaa.fuse.ui.shell.settings

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BorderMode
import io.github.matiyaaa.fuse.model.BorderStyle
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedKey
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SettingScope
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
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.hasTwoScreens
import io.github.matiyaaa.fuse.ui.shell.app.screenName
import io.github.matiyaaa.fuse.ui.shell.systems.platformEmulatorPicker
import kotlinx.coroutines.launch

/**
 * One inheritable setting for a system. The row says whether the value is set for this system or
 * comes from the global setting, and the choice list always offers "Use global" to go back.
 */
@Composable
fun <T> AppState.scopedRow(
    key: ScopedKey<T>,
    platform: PlatformId,
    label: String,
    icon: ImageVector,
    options: List<Pair<T, String>>,
    detail: String? = null,
): MenuAction {
    val flow = remember(key, platform) { store.settings.observe(key, platform, null) }
    val resolved by flow.collectAsState(initial = null)
    val r = resolved
    val name = { v: T -> options.firstOrNull { it.first == v }?.second ?: v.toString() }
    val trailing = when {
        r == null -> Trailing.None
        r.from == SettingScope.PLATFORM -> Trailing.Value(name(r.value))
        else -> Trailing.Inherited("${name(r.value)} · Global")
    }
    return MenuAction(key.id, label, icon, detail = detail, trailing = trailing, onSelect = {
        choice = ChoiceSpec(
            title = label,
            message = "For this system only. Games can still override it.",
            options = listOf(
                MenuAction("inherit", "Use global setting", FuseIcons.Layers, trailing = Trailing.Check(r?.from != SettingScope.PLATFORM), onSelect = {
                    scope.launch { store.settings.clear(key, ScopeRef.platform(platform)) }
                    choice = null
                }),
            ) + options.map { (v, n) ->
                MenuAction("${key.id}.$n", n, null, trailing = Trailing.Check(r?.from == SettingScope.PLATFORM && r.value == v), onSelect = {
                    scope.launch { store.settings.set(key, ScopeRef.platform(platform), v) }
                    choice = null
                })
            },
        )
    })
}

@Composable
fun PlatformSettingsScreen(app: AppState, platformId: PlatformId) {
    val platforms by app.store.library.platforms.collectAsState()
    val card = platforms.firstOrNull { it.platform.id == platformId }
    val sel = remember { LinearSelection() }
    val p = card?.platform
    val on = listOf(true to "On", false to "Off")
    val rows = if (card == null || p == null) emptyList() else buildList {
        add(MenuAction("emulator", "Emulator", FuseIcons.Chip, trailing = Trailing.Value(card.emulatorName ?: "None installed"), detail = "${card.installedEmulators} installed for ${p.shortName}", onSelect = { app.platformEmulatorPicker(card) }))
        add(app.scopedRow(ScopedSettings.FolderMode, platformId, "Folder behaviour", FuseIcons.FolderOpen, listOf(
            FolderPolicy.AUTO to "Automatic", FolderPolicy.FOLDER_AS_GAME to "Folder is the game",
            FolderPolicy.FOLDER_BROWSER to "Open as a folder", FolderPolicy.FILE to "Files only",
        ), detail = "How folders inside ${p.shortName}'s folder are read. Nothing on disk changes"))
        add(app.scopedRow(ScopedSettings.Layout, platformId, "View", FuseIcons.Grid, LibraryLayout.entries.map { it to when (it) {
            LibraryLayout.ICON -> "Grid"; LibraryLayout.CAPSULE -> "Capsules"; LibraryLayout.COVER_GRID -> "Cover grid"; LibraryLayout.COMPACT_LIST -> "List"
        } }))
        add(app.scopedRow(ScopedSettings.ShowHero, platformId, "Background art", FuseIcons.Image, on))
        add(app.scopedRow(ScopedSettings.ShowLogo, platformId, "Title logos", FuseIcons.Type, on))
        add(app.scopedRow(ScopedSettings.VideoPreview, platformId, "Video previews", FuseIcons.Film, on))
        add(app.scopedRow(ScopedSettings.Border, platformId, "Dynamic border", FuseIcons.Square, listOf(
            BorderStyle(mode = BorderMode.OFF) to "Off",
            BorderStyle(mode = BorderMode.PLATFORM_DEFAULT) to "System frame",
            BorderStyle(mode = BorderMode.PLATFORM_DEFAULT, logoOverlay = true) to "System frame with logo",
        ), detail = "A frame in ${p.shortName}'s colour around its games' art"))
        if (app.hasTwoScreens && !io.github.matiyaaa.fuse.launch.DualScreenPlatforms.usesSecondScreen(platformId)) {
            add(app.scopedRow(ScopedSettings.LaunchScreen, platformId, "Games open on", FuseIcons.DualScreen, LaunchDisplay.entries.map { it to screenName(it) }, detail = "Where this system's games start. A game can have its own in its options"))
        }
        add(app.scopedRow(ScopedSettings.GenerateM3u, platformId, "Disc playlists", FuseIcons.Disc, on, detail = "Multi-disc games get a playlist in Fuse's storage (never in your folder)"))
        add(app.scopedRow(ScopedSettings.ScrapeEnabled, platformId, "Find art and details", FuseIcons.Wand, on))
        add(app.scopedRow(ScopedSettings.Matching, platformId, "Matching", FuseIcons.Target, listOf(MatchStrictness.EXACT to "Exact", MatchStrictness.NORMAL to "Normal", MatchStrictness.AGGRESSIVE to "Aggressive")))
        val bios = card.bios
        add(infoRow(
            "bios", "BIOS and firmware",
            when (bios.state) {
                BiosState.READY -> "Ready"; BiosState.PARTIAL -> "Partly found"; BiosState.MISSING -> "Missing"
                BiosState.UNKNOWN -> "Can't check"; BiosState.NOT_REQUIRED -> "Not needed"
            },
            detail = buildString {
                if (bios.found.isNotEmpty()) append("Found: ${bios.found.joinToString(", ")}\n")
                if (bios.missing.isNotEmpty()) append("Missing: ${bios.missing.joinToString(", ")}\n")
                bios.note?.let { append(it) }
                p.bios?.hint?.let { if (bios.state != BiosState.READY) append("\n$it") }
            }.trim().ifBlank { null },
            icon = FuseIcons.Key,
        ))
        for (folder in card.romFolders) add(infoRow("rom.$folder", "ROM folder", detail = folder, icon = FuseIcons.Folder))
        add(MenuAction("media", "System media", FuseIcons.Image, detail = "Icon, background and logo", trailing = Trailing.Chevron, onSelect = { app.go(Route.Media(MediaOwner.OfPlatform(platformId), p.name)) }))
        add(MenuAction("fill", "Fill missing game art", FuseIcons.Wand, onSelect = {
            app.store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, platform = platformId)
            app.toasts.show("Finding missing art for ${p.shortName}")
        }))
        add(MenuAction("rescan", "Rescan ${p.shortName}", FuseIcons.Refresh, onSelect = { app.store.sources.rescan(ScanScope.PLATFORM, platformId); app.toasts.show("Rescanning") }))
    }
    sel.clamp(rows.size)

    LaunchedEffect(Unit) {
        app.hero = card?.let { HeroSource(it.platform.id, it.art.hero, it.platform.accent.toColor()) }
        app.hints = listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Back"))
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, sel)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        Spacer(Modifier.height(Size.hudHeight + Space.l))
        FText(p?.name ?: platformId.value, Fuse.type.display)
        FText("System settings", Fuse.type.body, color = Fuse.colors.textMuted)
        Spacer(Modifier.height(Space.l))
        Panel(Modifier.widthIn(max = 880.dp).weight(1f).padding(bottom = Size.hintHeight + Space.s)) {
            MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
        }
    }
}
