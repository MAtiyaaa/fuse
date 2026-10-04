package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.integrations.KeyCheck
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconButton
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberPageState
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlin.math.ceil

/** One settings section: an id used in routes, a label and its rows. */
class SettingsSection(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val summary: String,
    val rows: @Composable (AppState) -> List<MenuAction>,
    /** False on devices the section means nothing on (Cartridge on Windows and macOS). */
    val available: (AppState) -> Boolean = { true },
    /** The heading the section is listed under, so twelve sections read as five groups. */
    val group: String? = null,
    /** What the section's row in the list shows on its right: how many findings, an update. Nothing when all is well. */
    val status: @Composable (AppState) -> Trailing = { Trailing.None },
)

/** Where Settings is: the section, the row, and whether the rows have focus. */
private class SettingsPlace(section: Int, rows: Boolean) {
    val sectionSel = LinearSelection(section)
    val rowSel = LinearSelection()
    var inRows by mutableStateOf(rows)

    /** The search row above the sections has focus (only while not [inRows]). */
    var onSearch by mutableStateOf(false)

    /** A row search found, landed on once its section's rows are there. */
    var pendingRow by mutableStateOf<String?>(null)

    /** The row asked for has been chosen; coming back keeps wherever the user went since. */
    var landed = false
}

/** The most places a settings search lists. */
private const val SEARCH_LIMIT = 12

private const val PERSONAL = "Personalize"
private const val GAMES = "Games"
private const val DEVICE = "This device"
private const val CONNECTIONS = "Connections"
private const val GENERAL = "General"

val settingsSections: List<SettingsSection> = listOf(
    SettingsSection("appearance", "Appearance", FuseIcons.Palette, "Theme, game art, glass and CRT", ::appearanceRows, group = PERSONAL),
    SettingsSection("accessibility", "Accessibility", FuseIcons.Accessibility, "Text size, screen edges, motion, focus", ::accessibilityRows, group = PERSONAL),
    SettingsSection("home", "Home", FuseIcons.Home, "Style, rows or widgets, top bar", ::homeRows, group = PERSONAL),
    SettingsSection("library", "Library", FuseIcons.Library, "Folders, scanning, browsing, names", ::libraryRows, group = GAMES),
    SettingsSection("systems", "Systems and emulators", FuseIcons.Chip, "Each system, and the emulators found", ::systemsRows, group = GAMES),
    SettingsSection("media", "Art and details", FuseIcons.Images, "Filling art, previews, sources and keys", ::mediaRows, group = GAMES, status = ::mediaStatus),
    SettingsSection("inputs", "Controls", FuseIcons.Gamepad, "Buttons, mapping, repeat and sticks", ::inputRows, group = DEVICE),
    SettingsSection("displays", "Display", FuseIcons.Monitor, "Screens, rotation, performance", ::displayAndPerformanceRows, group = DEVICE),
    SettingsSection("sound", "Sound", FuseIcons.Volume, "Menu music and interface sounds", ::soundRows, group = DEVICE),
    SettingsSection(
        "store", "Store", FuseIcons.Store, "Catalogue, added apps, update checks", ::storeRows,
        available = { it.store.appStore.supported }, group = CONNECTIONS,
    ),
    SettingsSection("accounts", "Accounts", FuseIcons.CircleUser, "RetroAchievements, Cartridge, Phone Link", ::accountsRows, group = CONNECTIONS),
    SettingsSection("addons", "Addons", FuseIcons.Blocks, "Jellyfin: your films, shows and music", ::addonsRows, group = CONNECTIONS, status = ::addonsStatus),
    SettingsSection("health", "System health", FuseIcons.HeartPulse, "What needs attention, and a bug report", ::healthRows, group = GENERAL, status = ::healthStatus),
    SettingsSection("storage", "Storage and backups", FuseIcons.HardDrive, "File access, drives, backup and restore", ::storageAndBackupRows, group = GENERAL),
    SettingsSection("about", "About", FuseIcons.Info, "Updates, privacy, licences, setup", ::aboutRows, group = GENERAL, status = ::aboutStatus),
)

/**
 * Sections 0.2.0 folded into others, by their old ids, so a link to one (a problem's "Open
 * settings", an older route) still lands in the right place.
 */
val settingsAliases: Map<String, String> = mapOf(
    "emulators" to "systems",
    "performance" to "displays",
    "achievements" to "accounts",
    "cartridge" to "accounts",
    "phonelink" to "accounts",
    "backup" to "storage",
    "updates" to "about",
    "privacy" to "about",
    "network" to "about",
)

/** The section [id] names now, following [settingsAliases]. */
fun settingsSectionId(id: String?): String? = id?.let { settingsAliases[it] ?: it }

/** Opens Settings on [section] (an old id works too), with [group] unfolded so [row] can be landed on. */
fun AppState.openSettings(section: String, row: String? = null, group: String? = null) {
    if (group != null) openGroups[group] = true
    go(Route.Settings(settingsSectionId(section), row))
}

/** How many findings need attention or fixing; notes alone show nothing. */
@Composable
private fun healthStatus(app: AppState): Trailing {
    val n = rememberHealthIssues(app).count { it.problem.severity >= Severity.ATTENTION }
    return if (n > 0) Trailing.Badge(n.toString()) else Trailing.None
}

/** An update waiting to be downloaded or installed. */
@Composable
private fun aboutStatus(app: AppState): Trailing {
    val available by app.store.updates.available.collectAsState()
    return if (available != null) Trailing.Badge("Update") else Trailing.None
}

/** A source whose key was turned down. */
@Composable
private fun mediaStatus(app: AppState): Trailing {
    val checks by app.store.media.keyChecks.collectAsState()
    val rejected = checks.values.count { it is KeyCheck.Rejected }
    return if (rejected > 0) Trailing.Badge(rejected.toString()) else Trailing.None
}

/**
 * Settings as two panes: the twelve sections on the room at the left, listed under five headings, and the
 * chosen section's settings on a panel at the right. Both lists glide their highlight from row to
 * row, and the section keeps a quiet marker while its rows have focus. Everything applies
 * immediately; there is no Save button. Settings that can differ per system or per game say where
 * their current value comes from.
 *
 * On a narrow screen (a phone held upright) the panes take turns: the sections, then the chosen
 * section's rows with a way back. On a short one (a 6 inch handheld) the heading shrinks to one line
 * so more rows fit.
 */
@Composable
fun SettingsScreen(app: AppState, initialSection: String?, initialRow: String? = null) {
    val sections = remember { settingsSections.filter { it.available(app) } }
    // Back from a screen Settings opened (a file picker, Storage) returns to the same row.
    val place = rememberPageState(app.navigator, "settings.${initialSection.orEmpty()}.${initialRow.orEmpty()}") {
        val id = settingsSectionId(initialSection)
        SettingsPlace(sections.indexOfFirst { it.id == id }.coerceAtLeast(0), initialSection != null)
    }
    val sectionSel = place.sectionSel
    val rowSel = place.rowSel
    var inRows by place::inRows
    val section = sections[sectionSel.index]
    val rows = section.rows(app)
    rowSel.clamp(rows.size)
    if (!place.landed && initialRow != null) {
        val at = rows.indexOfFirst { it.label == initialRow }
        if (at >= 0) rowSel.index = at
        place.landed = true
    }
    place.pendingRow?.let { wanted ->
        val at = rows.indexOfFirst { it.label == wanted }
        if (at >= 0) rowSel.index = at
        place.pendingRow = null
    }
    var onSearch by place::onSearch

    /** Goes to what search found, in place: its section, its group unfolded, its row chosen. */
    fun land(topic: SettingTopic) {
        topic.group?.let { app.openGroups[it] = true }
        val at = sections.indexOfFirst { it.id == topic.section }
        if (at < 0) return
        onSearch = false
        sectionSel.index = at
        rowSel.index = 0
        place.pendingRow = topic.row
        inRows = topic.row != null
        app.focusZone = FocusZone.CONTENT
    }

    fun search() {
        app.textInput = TextInputSpec(
            title = "Search settings",
            initial = "",
            placeholder = "Text size, controller, Wi-Fi",
            capitalize = false,
            doneLabel = "Search",
        ) { query ->
            val hits = SettingsIndex.search(query, sections, limit = SEARCH_LIMIT, cartridge = app.platform.features.cartridge, secondScreen = app.platform.features.secondScreen)
            when {
                query.isBlank() -> Unit
                hits.isEmpty() -> app.toasts.show("Nothing in Settings matches \"${query.trim()}\"", icon = FuseIcons.Search)
                hits.size == 1 -> land(hits.single().topic)
                else -> app.choice = ChoiceSpec(
                    title = "Settings for \"${query.trim()}\"",
                    message = "${hits.size} places in Settings",
                    icon = FuseIcons.Search,
                    options = hits.mapIndexed { i, h ->
                        MenuAction("hit.$i", h.title, h.section.icon, detail = h.path, onSelect = {
                            app.choice = null
                            land(h.topic)
                        })
                    },
                )
            }
        }
    }

    LaunchedEffect(inRows, section.id, onSearch) {
        app.hero = null
        app.hints = when {
            inRows -> listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Sections"))
            onSearch -> listOf(Hint(HintButton.CONFIRM, "Search"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back"))
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (inRows) {
            when (e.action) {
                NavAction.LEFT, NavAction.BACK -> { inRows = false; NavResult.MOVED }
                else -> handleMenuAction(e, rows, rowSel)
            }
        } else if (onSearch) {
            when (e.action) {
                NavAction.DOWN, NavAction.PAGE_DOWN -> { onSearch = false; NavResult.MOVED }
                NavAction.SELECT, NavAction.RIGHT -> { search(); NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        } else if (e.action == NavAction.UP && sectionSel.index == 0) {
            // Above the first section is the search row.
            onSearch = true
            NavResult.MOVED
        } else {
            when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                    val r = sectionSel.move(e.action, sections.size, vertical = true)
                    if (r == NavResult.MOVED) rowSel.index = 0
                    if (r == NavResult.IGNORED && e.action == NavAction.DOWN) NavResult.BLOCKED else r
                }
                NavAction.RIGHT, NavAction.SELECT -> { inRows = true; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
    }

    val focused = app.focusZone == FocusZone.CONTENT
    val panelRows = rows.map { r -> r.copy(onSelect = { rowSel.index = rows.indexOf(r); inRows = true; r.onSelect() }) }
    // Appearance leads with the theme in use; selecting the card opens every theme, as the Theme row does.
    val openThemes: (() -> Unit)? = if (section.id == "appearance") ({ app.go(Route.Themes) }) else null

    val statuses = sections.map { it.status(app) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW_BELOW
        val short = maxHeight < SHORT_BELOW
        // On a narrow screen the sections have the whole width, so each says what is in it.
        val sectionRows = sections.mapIndexed { i, s ->
            MenuAction(s.id, s.label, s.icon, detail = s.summary.takeIf { narrow }, trailing = statuses[i], section = s.group, onSelect = {
                sectionSel.index = sections.indexOf(s)
                rowSel.index = 0
                inRows = true
                app.focusZone = FocusZone.CONTENT
            })
        }
        if (narrow) {
            Column(Modifier.fillMaxSize().padding(horizontal = Space.gutterCompact)) {
                Spacer(Modifier.height(Size.hudHeight + Space.m))
                if (!inRows) {
                    FText("Settings", Fuse.type.display, maxLines = 1, modifier = Modifier.reveal(0))
                    Spacer(Modifier.height(Space.l))
                    MenuList(
                        sectionRows, sectionSel,
                        showSelection = focused && !onSearch,
                        modifier = Modifier.weight(1f).padding(bottom = Size.hintHeight).reveal(1),
                        header = { SearchRow(focused && onSearch, compact = true) { onSearch = true; search() } },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            FuseIcons.ChevronLeft, selected = false, onClick = { inRows = false },
                            size = Size.touch - Space.xs, contentDescription = "Sections",
                        )
                        Spacer(Modifier.width(Space.m))
                        SectionHeading(section, Modifier.weight(1f), compact = true)
                    }
                    Spacer(Modifier.height(Space.m))
                    SectionPanel(panelRows, rowSel, focused && inRows, openThemes, short = true, Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s).reveal(1))
                }
            }
        } else {
            val sidebar = ((maxWidth - Space.gutter * 2) * SIDEBAR_SHARE).coerceIn(SIDEBAR_MIN, SIDEBAR_MAX)
            Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
                Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
                // One heading line across both panes: Settings, then where in it you are. Their
                // baselines meet, and both panes start on the same line below.
                Row(Modifier.fillMaxWidth().reveal(0)) {
                    FText(
                        "Settings", if (short) Fuse.type.title else Fuse.type.display, maxLines = 1,
                        modifier = Modifier.width(sidebar).alignBy(FirstBaseline),
                    )
                    Spacer(Modifier.width(Space.xl))
                    SectionHeading(section, Modifier.weight(1f).alignBy(FirstBaseline), compact = short)
                }
                Spacer(Modifier.height(if (short) Space.m else Space.l))
                Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s)) {
                    MenuList(
                        sectionRows, sectionSel,
                        showSelection = focused && !onSearch,
                        dimSelection = inRows,
                        modifier = Modifier.width(sidebar).fillMaxHeight().reveal(1),
                        header = { SearchRow(focused && onSearch && !inRows, compact = short) { inRows = false; onSearch = true; search() } },
                    )
                    Spacer(Modifier.width(Space.xl))
                    SectionPanel(panelRows, rowSel, focused && inRows, openThemes, short, Modifier.weight(1f).fillMaxHeight().reveal(2))
                }
            }
        }
    }
}

/**
 * The row above the sections that searches every setting: a well like the search field's, with its
 * glass, that lifts into the focus colour when the controller is on it. Choosing it opens the
 * keyboard; what is typed lands on the setting (or lists them when several match).
 */
@Composable
private fun SearchRow(selected: Boolean, compact: Boolean, onOpen: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val edge by fuselineColor(
        when {
            selected -> c.focus
            hovered -> c.text.copy(alpha = 0.22f)
            else -> c.hairline
        },
        motion.tween(Durations.FAST),
        label = "settingsSearchEdge",
    )
    val fill by fuselineColor(
        if (selected) c.surfaceRaised else c.text.copy(alpha = if (c.isDark) 0.06f else 0.05f),
        motion.tween(Durations.FAST),
        label = "settingsSearchFill",
    )
    val tint by fuselineColor(if (selected) c.text else c.textMuted, motion.tween(Durations.FAST), label = "settingsSearchTint")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Space.s)
            .height(if (compact) Size.touch - Space.xs else Size.touch)
            .clip(shape)
            .background(fill)
            .border(if (selected) Size.focusStroke else Size.stroke, edge, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(FuseIcons.Search, size = Size.iconS, tint = tint)
        Spacer(Modifier.width(Space.m))
        FText("Search settings", Fuse.type.body, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

/** The chosen section's name with its summary: under it, or after it on one line when space is short. */
@Composable
private fun SectionHeading(section: SettingsSection, modifier: Modifier, compact: Boolean) {
    if (compact) {
        Row(modifier, verticalAlignment = Alignment.Bottom) {
            FText(section.label, Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(Space.m))
            FText(section.summary, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText(section.label, Fuse.type.title, maxLines = 1)
            FText(section.summary, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
        }
    }
}

/** The rows of a section on their panel; Appearance leads with the theme in use. */
@Composable
private fun SectionPanel(
    rows: List<MenuAction>,
    selection: LinearSelection,
    focused: Boolean,
    openThemes: (() -> Unit)?,
    short: Boolean,
    modifier: Modifier,
) {
    val cardHeight = if (openThemes != null) themeCardHeight(short) + Space.s else 0.dp
    Panel(modifier) {
        MenuList(
            rows, selection,
            showSelection = focused,
            header = openThemes?.let { open -> { ThemeCard(short, open, Modifier.padding(bottom = Space.s)) } },
            modifier = Modifier.padding(Space.s),
                            fadeEdges = true,
        )
    }
}

/**
 * The heading of a page Settings opens (Controls, Storage, Phone Link, Licences): its name in the
 * display face and a muted line under it, the same as Settings' own, so moving between them reads
 * as one place. [short] screens get a smaller name. [status] sits after the line (a progress bar, a
 * state), or under it when [stacked] (narrow screens, where the line needs the whole width).
 */
@Composable
internal fun SettingsPageHeading(
    title: String,
    subtitle: String,
    short: Boolean,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    status: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        FText(title, if (short) Fuse.type.title else Fuse.type.display, maxLines = 1)
        val line = @Composable { m: Modifier ->
            FText(subtitle, if (short) Fuse.type.caption else Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 2, modifier = m)
        }
        if (stacked || status == null) {
            line(Modifier)
            if (status != null) {
                Spacer(Modifier.height(Space.xs))
                status()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                line(Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(Space.l))
                status()
            }
        }
    }
}

// ----------------------------------------------------------------------------------- theme card

/**
 * The theme in use, as a small live picture over its name and tagline: it is drawn from the
 * interface's own colours and shapes, so it changes the moment the theme does. It sits right above
 * the Theme row, which opens every theme; a tap on it does the same.
 */
@Composable
private fun ThemeCard(short: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val spec = Fuse.look.spec
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val pictureHeight = themeCardHeight(short) - Space.m * 2
    Row(
        modifier
            .fillMaxWidth()
            .height(themeCardHeight(short))
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(c.text.copy(alpha = if (c.isDark) 0.05f else 0.04f))
            .lightEdge(shape, if (c.isDark) 0.1f else 0.5f)
            .fuseClickable(shape = shape, scale = false, role = Role.Button, onClickLabel = "Themes", onClick = onOpen)
            // The picture starts where the rows' icons do.
            .padding(start = Space.m + ROW_BAR.dp, top = Space.m, bottom = Space.m, end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeSwatch(Modifier.height(pictureHeight).width(pictureHeight * SWATCH_ASPECT))
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText(spec.name, Fuse.type.titleSmall, maxLines = 1)
            FText(
                listOfNotNull(spec.tagline.takeIf { it.isNotBlank() }, spec.author?.let { "by $it" }).joinToString("  ·  "),
                Fuse.type.caption, color = c.textMuted, maxLines = if (short) 1 else 2,
            )
        }
        if (!short) {
            Spacer(Modifier.width(Space.l))
            Palette()
        }
    }
}

private fun themeCardHeight(short: Boolean): Dp = if (short) Size.thumbL + Space.m * 2 - Space.s else Size.thumbL + Space.xl + Space.m

/**
 * The theme's colours as a row of small chips in its tile corners: the room, its panels, the muted
 * and main text, and the accent. Each has a hairline so the room's chip still shows on the card.
 */
@Composable
private fun Palette() {
    val c = Fuse.colors
    val chips = listOf(c.ink, c.surfaceRaised, c.textMuted, c.text, c.accent)
    val edge = c.text.copy(alpha = if (c.isDark) 0.2f else 0.18f)
    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f)
    val d = Size.iconS
    val gap = Space.xs
    Spacer(
        Modifier.size(width = d * chips.size + gap * (chips.size - 1), height = d).drawWithCache {
            val side = size.height
            val step = side + gap.toPx()
            val outline = Path().apply { addOutline(shape.createOutline(androidx.compose.ui.geometry.Size(side, side), layoutDirection, this@drawWithCache)) }
            val hair = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
            onDrawBehind {
                chips.forEachIndexed { i, color ->
                    translate(left = i * step) {
                        drawPath(outline, color)
                        drawPath(outline, edge, style = hair)
                    }
                }
            }
        },
    )
}

/**
 * A tiny picture of Fuse in the current theme: the room with the game's light rising from the
 * bottom left, the top line, a title, and a row of tiles with the second one chosen over its spark
 * bar, in the theme's tile corners. Everything is built once per size and colour set.
 */
@Composable
internal fun ThemeSwatch(modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val corner = Fuse.geometry.tileCornerFraction
    val shape = SquircleShape.fraction((corner * 0.9f).coerceAtLeast(0.08f))
    Spacer(
        modifier
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val glow = Brush.radialGradient(
                    listOf(c.accent.copy(alpha = if (c.isDark) 0.45f else 0.3f), Color.Transparent),
                    center = Offset(w * 0.12f, h * 1.1f),
                    radius = w * 0.9f,
                )
                val tile = h * 0.3f
                val chosen = tile * 1.16f
                val gap = tile * 0.24f
                val baseline = h * 0.86f
                val tiles = (0 until 4).map { i ->
                    val s = if (i == 1) chosen else tile
                    val x = w * 0.09f + i * (tile + gap) + if (i > 1) chosen - tile else 0f
                    val path = Path().apply {
                        addOutline(SquircleShape.fraction(corner.coerceAtLeast(0.08f)).createOutline(androidx.compose.ui.geometry.Size(s, s), layoutDirection, this@drawWithCache))
                        translate(Offset(x, baseline - s - if (i == 1) h * 0.04f else 0f))
                    }
                    path
                }
                val bar = h * 0.035f
                val barX = w * 0.09f + tile + gap + chosen * 0.3f
                val tileFill = lerp(c.surfaceRaised, c.text, 0.06f)
                val chosenFill = lerp(c.surfaceRaised, c.accent, 0.38f)
                val pill = CornerRadius(h)
                onDrawBehind {
                    drawRect(c.ink)
                    drawRect(glow)
                    // The top line: the mark, three section tabs (the first chosen) and the clock.
                    val y = h * 0.12f
                    val m = h * 0.075f
                    drawRoundRect(c.text.copy(alpha = 0.9f), Offset(w * 0.09f, y), androidx.compose.ui.geometry.Size(m, m), CornerRadius(m * 0.3f))
                    for (i in 0 until 3) {
                        drawRoundRect(
                            c.text.copy(alpha = if (i == 0) 0.85f else 0.3f),
                            Offset(w * 0.09f + m * 2f + i * w * 0.11f, y + m * 0.3f),
                            androidx.compose.ui.geometry.Size(w * 0.08f, m * 0.4f),
                            pill,
                        )
                    }
                    drawRoundRect(c.text.copy(alpha = 0.5f), Offset(w * 0.8f, y + m * 0.3f), androidx.compose.ui.geometry.Size(w * 0.11f, m * 0.4f), pill)
                    // The selected game's title and a quiet meta line.
                    drawRoundRect(c.text.copy(alpha = 0.9f), Offset(w * 0.09f, h * 0.33f), androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.07f), pill)
                    drawRoundRect(c.textMuted.copy(alpha = 0.6f), Offset(w * 0.09f, h * 0.45f), androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.04f), pill)
                    tiles.forEachIndexed { i, p -> drawPath(p, if (i == 1) chosenFill else tileFill) }
                    drawRoundRect(c.accent, Offset(barX, baseline + h * 0.02f), androidx.compose.ui.geometry.Size(chosen * 0.4f, bar), CornerRadius(bar / 2))
                }
            }
            .lightEdge(shape, if (c.isDark) 0.22f else 0.6f),
    )
}

/** Below this width the panes take turns; below this height the heading shrinks to one line. */
private val NARROW_BELOW = 640.dp
private val SHORT_BELOW = 560.dp

/** The section list takes this share of the width, within these bounds. */
private const val SIDEBAR_SHARE = 0.27f
private val SIDEBAR_MIN = 256.dp
private val SIDEBAR_MAX = 320.dp

/** Picture shape of the theme card: the screen's own proportion. */
private const val SWATCH_ASPECT = 16f / 10f

/** The width of a menu row's accent bar, which the theme card lines up with. */
private const val ROW_BAR = 3f
