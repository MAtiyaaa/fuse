package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonRow
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
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
import io.github.matiyaaa.fuse.ui.shell.app.icon
import io.github.matiyaaa.fuse.ui.shell.app.showProblem
import io.github.matiyaaa.fuse.ui.shell.app.screenName
import io.github.matiyaaa.fuse.ui.shell.systems.SystemMark
import io.github.matiyaaa.fuse.ui.shell.systems.gamesText
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

/**
 * One system's settings: which emulator its games start in, how its folders are read, how its
 * games look, and its files. The page leads with the system itself (its mark, name, game count and
 * emulator) so it is clear what is being changed; rows come in small named groups, and every
 * inheritable row says whether it is set here or follows the global setting.
 */
@Composable
fun PlatformSettingsScreen(app: AppState, platformId: PlatformId) {
    val platforms by app.store.library.platforms.collectAsState()
    val card = platforms.firstOrNull { it.platform.id == platformId }
    val sel = remember { LinearSelection() }
    val reveal = rememberReveal(platformId)
    val p = card?.platform
    val on = listOf(true to "On", false to "Off")
    val health = rememberHealthIssues(app).filter { it.platform == platformId }
    val usage by app.store.storage.usage.collectAsState()
    val rows = if (card == null || p == null) emptyList() else buildList {
        // What System health found about this system leads the page: it is what needs doing.
        health.take(5).forEach { issue ->
            add(MenuAction(
                "health.${issue.id}", issue.problem.title, issue.problem.kind.icon(),
                detail = issue.problem.message, trailing = Trailing.Chevron, section = "Needs attention",
                onSelect = { app.showProblem(issue.problem) },
            ))
        }
        if (health.size > 5) add(MenuAction("health.more", "${health.size - 5} more in System health", FuseIcons.HeartPulse, trailing = Trailing.Chevron, section = "Needs attention", onSelect = { app.go(Route.Settings("health")) }))
        val playing = "Playing"
        add(MenuAction("emulator", "Emulator", FuseIcons.Chip, trailing = Trailing.Value(card.emulatorName ?: "None installed"), detail = "${card.installedEmulators} installed for ${p.shortName}", section = playing, onSelect = { app.platformEmulatorPicker(card) }))
        add(app.scopedRow(ScopedSettings.FolderMode, platformId, "Folder behaviour", FuseIcons.FolderOpen, listOf(
            FolderPolicy.AUTO to "Automatic", FolderPolicy.FOLDER_AS_GAME to "Folder is the game",
            FolderPolicy.FOLDER_BROWSER to "Open as a folder", FolderPolicy.FILE to "Files only",
        ), detail = "How folders inside ${p.shortName}'s folder are read. Nothing on disk changes").copy(section = playing))
        if (app.hasTwoScreens && !io.github.matiyaaa.fuse.launch.DualScreenPlatforms.usesSecondScreen(platformId)) {
            add(app.scopedRow(ScopedSettings.LaunchScreen, platformId, "Games open on", FuseIcons.DualScreen, LaunchDisplay.entries.map { it to screenName(it) }, detail = "Where this system's games start. A game can have its own in its options").copy(section = playing))
        }
        add(app.scopedRow(ScopedSettings.GenerateM3u, platformId, "Disc playlists", FuseIcons.Disc, on, detail = "Multi-disc games get a playlist in Fuse's storage (never in your folder)").copy(section = playing))
        val look = "How its games look"
        add(app.scopedRow(ScopedSettings.Layout, platformId, "View", FuseIcons.Grid, LibraryLayout.entries.map { it to when (it) {
            LibraryLayout.ICON -> "Grid"; LibraryLayout.CAPSULE -> "Capsules"; LibraryLayout.COVER_GRID -> "Cover grid"; LibraryLayout.COMPACT_LIST -> "List"
        } }).copy(section = look))
        add(app.scopedRow(ScopedSettings.ShowHero, platformId, "Background art", FuseIcons.Image, on).copy(section = look))
        add(app.scopedRow(ScopedSettings.ShowLogo, platformId, "Title logos", FuseIcons.Type, on).copy(section = look))
        add(app.scopedRow(ScopedSettings.VideoPreview, platformId, "Video previews", FuseIcons.Film, on).copy(section = look))
        add(app.scopedRow(ScopedSettings.Border, platformId, "Dynamic border", FuseIcons.Square, listOf(
            BorderStyle(mode = BorderMode.OFF) to "Off",
            BorderStyle(mode = BorderMode.PLATFORM_DEFAULT) to "System frame",
            BorderStyle(mode = BorderMode.PLATFORM_DEFAULT, logoOverlay = true) to "System frame with logo",
        ), detail = "A frame in ${p.shortName}'s colour around its games' art").copy(section = look))
        val details = "Art and details"
        add(app.scopedRow(ScopedSettings.ScrapeEnabled, platformId, "Find art and details", FuseIcons.Wand, on).copy(section = details))
        add(app.scopedRow(ScopedSettings.Matching, platformId, "Matching", FuseIcons.Target, listOf(MatchStrictness.EXACT to "Exact", MatchStrictness.NORMAL to "Normal", MatchStrictness.AGGRESSIVE to "Aggressive")).copy(section = details))
        val files = "Files"
        val bios = card.bios
        if (bios.state != BiosState.NOT_REQUIRED) {
            val checkedAs = bios.checked ?: bios.state
            add(MenuAction(
                "bios", "BIOS and firmware", if (bios.state == BiosState.MISSING || bios.state == BiosState.PARTIAL) FuseIcons.Warning else FuseIcons.Key,
                detail = buildString {
                    if (bios.confirmed) append("You marked it as set up. Fuse's check said: ${biosWord(checkedAs).lowercase()}\n")
                    if (bios.found.isNotEmpty()) append("Found: ${bios.found.joinToString(", ")}\n")
                    if (bios.missing.isNotEmpty()) append("Missing: ${bios.missing.joinToString(", ")}\n")
                    if (!bios.confirmed) bios.note?.let { append(it) }
                    p.bios?.hint?.let { if (bios.state != BiosState.READY) append("\n$it") }
                }.trim().ifBlank { null },
                trailing = Trailing.Value(if (bios.confirmed) "Set up by you" else biosWord(bios.state)),
                section = files,
                onSelect = { app.biosChoice(p.id, p.name, bios) },
            ))
        }
        // A system can span drives; where its games are, once Storage has measured them.
        val drives = usage?.volumes.orEmpty()
        val byDrive = usage?.games.orEmpty().filter { it.card.platformId == platformId }.groupBy { it.volumeId }
        if (drives.size > 1 && byDrive.isNotEmpty()) {
            add(infoRow(
                "drives", "Where its games are",
                detail = drives.filter { byDrive[it.id] != null }.joinToString("  ·  ") { v -> "${v.label}: ${byDrive[v.id]?.size ?: 0}" + if (!v.online) " (not connected)" else "" },
                icon = FuseIcons.HardDrive,
            ).copy(section = files))
        }
        if (card.romFolders.isEmpty()) add(infoRow("rom.none", "ROM folder", value = "None found", icon = FuseIcons.Folder).copy(section = files))
        for (folder in card.romFolders) add(infoRow("rom.$folder", "ROM folder", detail = folder, icon = FuseIcons.Folder).copy(section = files))
        val tools = "Tools"
        add(MenuAction("media", "System media", FuseIcons.Image, detail = "Icon, background and logo", trailing = Trailing.Chevron, section = tools, onSelect = { app.go(Route.Media(MediaOwner.OfPlatform(platformId), p.name)) }))
        add(MenuAction("fill", "Fill missing game art", FuseIcons.Wand, detail = "Only games without art; your own art is never replaced", section = tools, onSelect = {
            app.store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, platform = platformId)
            app.toasts.show("Finding missing art for ${p.shortName}")
        }))
        add(MenuAction("rescan", "Rescan ${p.shortName}", FuseIcons.Refresh, detail = "Looks through its folders again for new and moved games", section = tools, onSelect = { app.store.sources.rescan(ScanScope.PLATFORM, platformId); app.toasts.show("Rescanning") }))
    }
    sel.keepOn(rows.map { it.id })
    sel.clamp(rows.size)

    LaunchedEffect(Unit) {
        app.hero = card?.let { HeroSource(it.platform.id, it.art.hero, it.platform.accent.toColor()) }
        app.hints = listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Back"))
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, sel)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(Modifier.reveal(reveal, 0), verticalAlignment = Alignment.CenterVertically) {
                if (card != null) {
                    SystemMark(card, if (compact) Size.thumb else Size.thumbL)
                    Spacer(Modifier.width(Space.l))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    FText(p?.name ?: platformId.value, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                    val facts = listOfNotNull("System settings", card?.let { gamesText(it.gameCount) }, card?.emulatorName?.takeIf { card.emulatorInstalled })
                    FText(facts.joinToString("  ·  "), Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            val panel = Modifier.widthIn(max = Size.touch * 18).weight(1f).padding(bottom = Size.hintHeight + Space.s).reveal(reveal, 1)
            if (card == null) {
                // The system's details are on their way: rows in the list's shape.
                Panel(panel) { Column(Modifier.padding(Space.s)) { repeat(6) { SkeletonRow(detail = it % 3 != 2) } } }
            } else {
                Panel(panel) {
                    MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                }
            }
        }
    }
}

/** A firmware state in a word or two. */
internal fun biosWord(state: BiosState): String = when (state) {
    BiosState.READY -> "Ready"
    BiosState.PARTIAL -> "Partly found"
    BiosState.MISSING -> "Missing"
    BiosState.UNKNOWN -> "Can't check"
    BiosState.NOT_REQUIRED -> "Not needed"
}

/**
 * What can be done about a system's firmware: look again, or tell Fuse it is set up when the check
 * can't see it (an emulator's own folder, a file named its own way), and take that back later.
 */
internal fun AppState.biosChoice(platform: io.github.matiyaaa.fuse.model.PlatformId, name: String, bios: io.github.matiyaaa.fuse.model.BiosStatus) {
    fun mark(on: Boolean) {
        choice = null
        store.updatePrefs { prefs ->
            prefs.copy(biosConfirmed = if (on) (prefs.biosConfirmed + platform.value).distinct() else prefs.biosConfirmed - platform.value)
        }
        toasts.show(if (on) "$name firmware marked as set up" else "Fuse goes by its own check for $name again")
    }
    val options = buildList {
        add(MenuAction("again", "Check again", FuseIcons.Refresh, detail = "Looks through the firmware folders once more", onSelect = {
            choice = null
            store.sources.refreshBios()
            toasts.show("Checking $name firmware")
        }))
        when {
            bios.confirmed -> add(MenuAction("undo", "Go by Fuse's check", FuseIcons.RotateCcw, detail = "Shows what the check finds again, warnings included", onSelect = { mark(false) }))
            bios.state in io.github.matiyaaa.fuse.model.BiosStatus.OVERRIDABLE -> add(MenuAction(
                "mark", "It's set up", FuseIcons.CircleCheck,
                detail = "For firmware in a place Fuse can't look or named its own way. Its warnings go away",
                onSelect = { mark(true) },
            ))
        }
    }
    choice = ChoiceSpec(
        title = "$name firmware",
        message = if (bios.confirmed) "You marked it as set up. Fuse's check said: ${biosWord(bios.checked ?: bios.state).lowercase()}." else "Fuse's check: ${biosWord(bios.state).lowercase()}.",
        options = options,
        icon = FuseIcons.Key,
    )
}
