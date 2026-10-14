package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.model.HomeStyle
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.sections
import io.github.matiyaaa.fuse.ui.shell.settings.ChoiceAid
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/** What Fii's Home is known by in [UiPrefs.unlocked]. */
internal const val FII_UNLOCK = "fii"

/** How tall Fii's bar is, above the hints. */
internal val FII_BAR = 92.dp

/** Fii's bar on a small screen (a handheld's lower screen with the menus on it, a phone). */
internal val FII_BAR_COMPACT = 66.dp

/**
 * Fii's board as Home shows it: how tall its bar is, and which of the bar's buttons the controller
 * is on (0 Sections, 1 Quick menu; null on the board). Down from the board's last row reaches the
 * bar, as the arranging bar is reached.
 */
@androidx.compose.runtime.Stable
internal class FiiBoard(val bar: Dp, val look: io.github.matiyaaa.fuse.model.BoardLook = io.github.matiyaaa.fuse.model.BoardLook.FII) {
    var focus by androidx.compose.runtime.mutableStateOf<Int?>(null)
}

/** What the bar's button [at] does: Fuse's sections, or the quick menu. */
internal fun AppState.fiiAction(at: Int) {
    if (at == 0) openSections() else quickMenuOpen = true
}

internal fun fiiLabel(at: Int) = if (at == 0) "Sections" else "Quick menu"

/** Where the band's flat top runs, as a share of the bar's height (the humps rise above it). */
private const val BAND_TOP = 0.3f

/** What Duo's and Trio's Homes are known by in [UiPrefs.unlocked]. */
internal const val DUO_UNLOCK = "duo"
internal const val TRIO_UNLOCK = "trio"

/** What a themed Home style is known by in [UiPrefs.unlocked]; null for the plain ones. */
internal fun HomeStyle.unlockKey(): String? = when (this) {
    HomeStyle.FII -> FII_UNLOCK
    HomeStyle.DUO -> DUO_UNLOCK
    HomeStyle.TRIO -> TRIO_UNLOCK
    else -> null
}

/** Every Home a theme can unlock. */
internal val HOME_UNLOCKS: Set<String> = setOf(FII_UNLOCK, DUO_UNLOCK, TRIO_UNLOCK)

/** The Home style the theme [id] comes with (Fii's, Duo's or Trio's), or null. */
internal fun themeHome(id: String?): HomeStyle? = when (id) {
    in ThemePresets.fiiFamily -> HomeStyle.FII
    in ThemePresets.duoFamily -> HomeStyle.DUO
    in ThemePresets.trioFamily -> HomeStyle.TRIO
    else -> null
}

/** The Home styles this person can pick: Fused and Network, and each themed one once unlocked (or in use). */
internal fun UiPrefs.homeStyles(): List<HomeStyle> =
    listOf(HomeStyle.FUSED, HomeStyle.NETWORK) + HomeStyle.entries.filter { it.themed && (it.unlockKey() in unlocked || home.style == it) }

/** True while a Home a theme unlocked hasn't been looked at yet. */
internal fun UiPrefs.newHomeUnlocked(): Boolean = unlockedNew.any { it in HOME_UNLOCKS }

internal fun HomeStyle.label(): String = when (this) {
    HomeStyle.FUSED -> "Fused"
    HomeStyle.NETWORK -> "Network"
    HomeStyle.FII -> "Fii"
    HomeStyle.DUO -> "Duo"
    HomeStyle.TRIO -> "Trio"
}

/** What a themed style's board looks like, in a few words. */
private fun HomeStyle.dress(): String = when (this) {
    HomeStyle.FII -> "glossy channels, with the clock along a bar at the bottom"
    HomeStyle.DUO -> "flat cards on graph paper, with the clock in a panel along the bottom"
    HomeStyle.TRIO -> "glossy icons over a dotted light, with a toolbar along the bottom"
    else -> ""
}

/** What each style is, and for the one being switched to, what changes ([theme] is the theme in use). */
internal fun HomeStyle.detail(theme: ThemeSpec): String = when (this) {
    HomeStyle.FUSED -> if (themeHome(theme.id) != null) "Your board in Fuse's own look, in ${theme.name}'s colours" else "A board of tiles you arrange yourself"
    HomeStyle.NETWORK -> "A continuous dashboard of shelves under a big title"
    else -> if (themeHome(theme.id) == this) "Your board as ${dress()}" else "Your board as ${dress()}, in ${theme.name}'s colours"
}

/**
 * Switches Home to [next]: a themed style keeps the plain one it came from, to go back to. The Homes
 * a theme unlocked count as looked at once any style is picked, so they are no longer marked new.
 */
internal fun AppState.applyHomeStyle(next: HomeStyle) {
    val before = store.prefs.value.home.style
    store.updatePrefs { it.copy(home = it.home.withStyle(next), unlockedNew = it.unlockedNew - HOME_UNLOCKS) }
    if (next != before) toasts.show("Home is now ${next.label()}")
}

/**
 * Asks which Home style to use: each one says what it is (and what changes), with a picture beside
 * it, and a newly unlocked one is marked until looked at. Choosing is the answer, so a switch never
 * happens without being asked; the one in use is checked and changes nothing.
 */
fun AppState.chooseHomeStyle() {
    val prefs = store.prefs.value
    val current = prefs.home.style
    val styles = prefs.homeStyles()
    choice = ChoiceSpec(
        title = "Home style",
        message = "Your board's tiles stay as they are whichever you pick.",
        icon = FuseIcons.Dashboard,
        options = styles.map { s ->
            MenuAction(
                "homestyle.${s.name.lowercase()}", s.label(), null,
                detail = s.detail(prefs.theme),
                trailing = if (s.unlockKey() in prefs.unlockedNew && current != s) Trailing.Badge("Unlocked") else Trailing.Check(s == current),
                onSelect = {
                    choice = null
                    applyHomeStyle(s)
                },
            )
        },
        aids = styles.map { ChoiceAid.HomeStyle(it) },
    )
}

/**
 * Uses the theme [spec], from the gallery. A theme with a Home of its own (Fii, Duo, Trio, light or
 * night) unlocks it the first time and asks whether to use it; leaving those themes while on their
 * Home asks whether to go back to the Home from before. Moving between a theme's light and night
 * asks nothing.
 */
fun AppState.useTheme(spec: ThemeSpec) {
    val before = store.prefs.value
    val had = themeHome(before.themeId)
    val comes = themeHome(spec.id)
    val key = comes?.unlockKey()
    val unlocking = key != null && key !in before.unlocked
    store.updatePrefs {
        val next = it.withTheme(spec)
        if (unlocking) next.copy(unlocked = next.unlocked + key!!, unlockedNew = next.unlockedNew + key) else next
    }
    val style = before.home.style
    when {
        comes != null && comes != had && style != comes -> choice = ChoiceSpec(
            title = "${spec.name.substringBefore(' ')} has its own Home",
            message = "Your board as ${comes.dress()}. " +
                "Your tiles stay where they are, and you can switch back any time in Settings, Home.",
            icon = FuseIcons.Dashboard,
            options = listOf(
                MenuAction("themehome.use", "Use ${comes.label()}'s Home", FuseIcons.Check, detail = "Home changes now", onSelect = {
                    choice = null
                    applyHomeStyle(comes)
                }),
                MenuAction("themehome.keep", "Keep my Home", FuseIcons.Home, detail = "Home stays ${style.label()}, in ${spec.name}'s colours", onSelect = {
                    choice = null
                    toasts.show("${comes.label()}'s Home is in Settings, Home, Home style")
                }),
            ),
            aids = listOf(ChoiceAid.HomeStyle(comes), ChoiceAid.HomeStyle(style)),
        )
        had != null && comes != had && style == had -> {
            val back = before.home.beforeFii?.takeIf { !it.themed } ?: HomeStyle.FUSED
            choice = ChoiceSpec(
                title = "Go back to the Home you had?",
                message = "${had.label()}'s Home works with any theme, in its colours. It stays in Settings, Home, Home style either way.",
                icon = FuseIcons.Dashboard,
                options = listOf(
                    MenuAction("themehome.back", "Go back to ${back.label()}", FuseIcons.Undo, detail = back.detail(spec), onSelect = {
                        choice = null
                        applyHomeStyle(back)
                    }),
                    MenuAction("themehome.stay", "Keep ${had.label()}'s Home", FuseIcons.Check, detail = "In ${spec.name}'s colours", onSelect = { choice = null }),
                ),
                aids = listOf(ChoiceAid.HomeStyle(back), ChoiceAid.HomeStyle(had)),
            )
        }
    }
}

/**
 * Fii's bar along the bottom of Home: a band of brushed white (silver at night) that swells up
 * around two round buttons at its ends, with the time large in the middle and the date under it.
 * The left button opens Fuse's sections, the right one the quick menu. A controller reaches both
 * the usual way (the bumpers or the top line for sections, the quick menu's own button), so the
 * bar adds nothing to learn; touch, the mouse and a click use the buttons.
 */
@Composable
internal fun FiiBar(app: AppState, board: FiiBoard, modifier: Modifier = Modifier) {
    when (board.look) {
        io.github.matiyaaa.fuse.model.BoardLook.DUO -> return DuoBar(app, board, modifier)
        io.github.matiyaaa.fuse.model.BoardLook.TRIO -> return TrioBar(app, board, modifier)
        else -> Unit
    }
    val prefs by app.store.prefs.collectAsState()
    val c = Fuse.colors
    val night = c.isDark
    val time = rememberClockText(prefs.clock24h)
    val date = remember(time) { formatDate() }
    val band = if (night) Color(0xFF1E252E) else Color(0xFFF7F9FB)
    val bandLow = if (night) Color(0xFF161B22) else Color(0xFFE3E8EE)
    val edge = c.accent.copy(alpha = if (night) 0.55f else 0.5f)
    val shade = Color.Black.copy(alpha = if (night) 0.4f else 0.1f)
    val compact = board.bar < FII_BAR
    BoxWithConstraints(modifier.fillMaxWidth().height(board.bar)) {
        val wide = maxWidth > 420.dp
        val button = (maxHeight * 0.78f).coerceAtMost(76.dp)
        val inset = if (compact) 12.dp else 18.dp
        Box(
            Modifier.matchParentSize().drawBehind {
                val w = size.width
                val h = size.height
                val r = button.toPx() / 2 + 10.dp.toPx()
                val cx = inset.toPx() + button.toPx() / 2
                val flat = h * BAND_TOP
                // The band's top: flat across the middle, swelling up in a smooth hump around each button.
                val path = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, flat - r * 0.55f)
                    cubicTo(0f, -r * 0.1f, cx + r * 1.1f, -r * 0.05f, cx + r * 1.55f, flat)
                    lineTo(w - cx - r * 1.55f, flat)
                    cubicTo(w - cx - r * 1.1f, -r * 0.05f, w, -r * 0.1f, w, flat - r * 0.55f)
                    lineTo(w, h)
                    close()
                }
                // A soft shadow just above the band, then the band and its accent line.
                drawPath(path, shade, alpha = 0.6f, style = Stroke(6.dp.toPx()))
                drawPath(path, Brush.verticalGradient(listOf(band, bandLow)))
                drawPath(path, edge, style = Stroke(1.5.dp.toPx()))
            },
        )
        Column(
            // The time and date sit inside the band, under its flat top.
            Modifier.align(Alignment.TopCenter).padding(top = board.bar * BAND_TOP + 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val size = if (compact) 22.sp else 30.sp
            FText(time, Fuse.type.display.copy(fontWeight = FontWeight.Light, fontSize = size, lineHeight = size * 1.15f), color = c.textMuted, maxLines = 1, align = TextAlign.Center)
            if (!compact || wide) FText(date, Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
        }
        FiiRoundButton(
            FuseIcons.Grid, fiiLabel(0), button, focused = board.focus == 0,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = inset),
        ) { board.focus = null; app.fiiAction(0) }
        FiiRoundButton(
            FuseIcons.Sliders, fiiLabel(1), button, focused = board.focus == 1,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = inset),
        ) { board.focus = null; app.fiiAction(1) }
    }
}

/** Fuse's sections as a list to go to, from Fii's left button. */
private fun AppState.openSections() {
    val prefs = store.prefs.value
    choice = ChoiceSpec(
        title = "Go to",
        icon = FuseIcons.Grid,
        options = prefs.destinations.map { d ->
            MenuAction("go.${d.name.lowercase()}", sections.label(d), sections.icon(d), onSelect = {
                choice = null
                go(Route.Root(d))
            })
        },
    )
}

/** One of Fii's round buttons: glossy white (or silver), a ring of the accent when pointed at or pressed. */
@Composable
private fun FiiRoundButton(icon: ImageVector, label: String, size: Dp, focused: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val night = c.isDark
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit by fuselineFloat(if (hovered || pressed || focused) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "fiiButton")
    val face = if (night) listOf(Color(0xFF39434F), Color(0xFF232A33)) else listOf(Color.White, Color(0xFFDCE2E8))
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + 0.04f * lit - if (pressed) 0.05f else 0f
                scaleX = s
                scaleY = s
                shadowElevation = 6.dp.toPx()
                shape = CircleShape
                clip = true
            }
            .background(Brush.verticalGradient(face), CircleShape)
            .border(2.dp, if (night) Color(0xFF8D98A6) else Color(0xFFC5CDD6), CircleShape)
            .drawBehind {
                // The gloss across the top half, and the accent ring when pointed at.
                drawOval(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = if (night) 0.18f else 0.75f), Color.Transparent), endY = this.size.height * 0.5f),
                    topLeft = Offset(this.size.width * 0.12f, this.size.height * 0.04f),
                    size = androidx.compose.ui.geometry.Size(this.size.width * 0.76f, this.size.height * 0.46f),
                )
                if (lit > 0.01f) drawCircle(c.accent, radius = this.size.minDimension / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx()), alpha = lit)
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = size * 0.4f, tint = if (night) Color(0xFFDCE4EC) else Color(0xFF5B6876))
    }
}


/**
 * Duo's bar: a flat strip of brushed grey with a bevelled top edge, a small lit panel in the middle
 * with the time and the date, and a squared button at each end (Fuse's sections, the quick menu), as
 * on a dual-screen handheld's menu.
 */
@Composable
private fun DuoBar(app: AppState, board: FiiBoard, modifier: Modifier) {
    val prefs by app.store.prefs.collectAsState()
    val c = Fuse.colors
    val night = c.isDark
    val time = rememberClockText(prefs.clock24h)
    val date = remember(time) { formatDate() }
    val compact = board.bar < FII_BAR
    val strip = if (night) listOf(Color(0xFF2E333B), Color(0xFF22262C)) else listOf(Color(0xFFE9ECF0), Color(0xFFD3D8DF))
    val edgeDark = if (night) Color(0xFF0E1013) else Color(0xFF9AA3AE)
    val edgeLight = if (night) Color(0xFF454C56) else Color.White
    val screen = if (night) Color(0xFF181B20) else Color(0xFFF8F9FB)
    val screenEdge = if (night) Color(0xFF4A525D) else Color(0xFFAFB7C1)
    BoxWithConstraints(modifier.fillMaxWidth().height(board.bar)) {
        val wide = maxWidth > 420.dp
        val button = (maxHeight * 0.62f).coerceAtMost(60.dp)
        val inset = if (compact) 10.dp else 16.dp
        Box(
            Modifier.matchParentSize().drawBehind {
                val top = size.height * 0.12f
                drawRect(Brush.verticalGradient(strip, startY = top), topLeft = Offset(0f, top))
                drawLine(edgeDark, Offset(0f, top), Offset(size.width, top), strokeWidth = 2.dp.toPx())
                drawLine(edgeLight, Offset(0f, top + 2.dp.toPx()), Offset(size.width, top + 2.dp.toPx()), strokeWidth = 1.dp.toPx())
            },
        )
        Box(
            Modifier.align(Alignment.Center).padding(top = board.bar * 0.12f)
                .background(screen, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .border(1.5.dp, screenEdge, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 4.dp else 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val size = if (compact) 20.sp else 26.sp
                FText(time, Fuse.type.numeric.copy(fontSize = size, lineHeight = size * 1.15f), color = c.text, maxLines = 1, align = TextAlign.Center)
                if (!compact || wide) FText(date, Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
            }
        }
        BarButton(
            FuseIcons.Grid, fiiLabel(0), button, board.focus == 0, androidx.compose.foundation.shape.RoundedCornerShape(button * 0.16f), gloss = false,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = inset, top = board.bar * 0.12f),
        ) { board.focus = null; app.fiiAction(0) }
        BarButton(
            FuseIcons.Sliders, fiiLabel(1), button, board.focus == 1, androidx.compose.foundation.shape.RoundedCornerShape(button * 0.16f), gloss = false,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = inset, top = board.bar * 0.12f),
        ) { board.focus = null; app.fiiAction(1) }
    }
}

/**
 * Trio's bar: a rounded white toolbar floating a little above the bottom edge, with the time and
 * date in the middle and a rounded, glossy icon button at each end, as on a 3D handheld's home.
 */
@Composable
private fun TrioBar(app: AppState, board: FiiBoard, modifier: Modifier) {
    val prefs by app.store.prefs.collectAsState()
    val c = Fuse.colors
    val night = c.isDark
    val time = rememberClockText(prefs.clock24h)
    val date = remember(time) { formatDate() }
    val compact = board.bar < FII_BAR
    val dock = if (night) listOf(Color(0xFF2C3138), Color(0xFF1F2328)) else listOf(Color.White, Color(0xFFEDF0F3))
    val dockEdge = if (night) Color(0xFF4A525C) else Color(0xFFD0D6DD)
    BoxWithConstraints(modifier.fillMaxWidth().height(board.bar)) {
        val wide = maxWidth > 420.dp
        val side = if (compact) 8.dp else 14.dp
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (compact) 18.dp else 24.dp)
        val button = (maxHeight * 0.6f).coerceAtMost(58.dp)
        Box(
            Modifier.matchParentSize().padding(start = side, end = side, top = board.bar * 0.1f, bottom = 4.dp)
                .graphicsLayer {
                    shadowElevation = 4.dp.toPx()
                    this.shape = shape
                    clip = true
                }
                .background(Brush.verticalGradient(dock), shape)
                .border(1.dp, dockEdge, shape),
        )
        Column(
            Modifier.align(Alignment.Center).padding(top = board.bar * 0.06f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val size = if (compact) 20.sp else 26.sp
            FText(time, Fuse.type.display.copy(fontWeight = FontWeight.Medium, fontSize = size, lineHeight = size * 1.15f), color = c.text, maxLines = 1, align = TextAlign.Center)
            if (!compact || wide) FText(date, Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
        }
        BarButton(
            FuseIcons.Grid, fiiLabel(0), button, board.focus == 0, androidx.compose.foundation.shape.RoundedCornerShape(button * 0.3f), gloss = true,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = side + 10.dp, top = board.bar * 0.06f),
        ) { board.focus = null; app.fiiAction(0) }
        BarButton(
            FuseIcons.Sliders, fiiLabel(1), button, board.focus == 1, androidx.compose.foundation.shape.RoundedCornerShape(button * 0.3f), gloss = true,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = side + 10.dp, top = board.bar * 0.06f),
        ) { board.focus = null; app.fiiAction(1) }
    }
}

/** A button on Duo's or Trio's bar, in [shape]: lit with the accent when pointed at, focused or pressed. */
@Composable
private fun BarButton(icon: ImageVector, label: String, size: Dp, focused: Boolean, shape: androidx.compose.ui.graphics.Shape, gloss: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val night = c.isDark
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit by fuselineFloat(if (hovered || pressed || focused) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "barButton")
    val face = when {
        gloss && night -> listOf(Color(0xFF3A414A), Color(0xFF262B32))
        gloss -> listOf(Color.White, Color(0xFFE1E6EB))
        night -> listOf(Color(0xFF3B424C), Color(0xFF2B3038))
        else -> listOf(Color(0xFFF7F8FA), Color(0xFFDDE2E8))
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + 0.04f * lit - if (pressed) 0.05f else 0f
                scaleX = s
                scaleY = s
                shadowElevation = (if (gloss) 5 else 2).dp.toPx()
                this.shape = shape
                clip = true
            }
            .background(Brush.verticalGradient(face), shape)
            .border(if (gloss) 1.5.dp else 2.dp, if (night) Color(0xFF6E7884) else Color(0xFFADB6C1), shape)
            .drawBehind {
                if (gloss) {
                    drawRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (night) 0.14f else 0.7f), Color.Transparent), endY = this.size.height * 0.5f),
                        size = androidx.compose.ui.geometry.Size(this.size.width, this.size.height * 0.5f),
                    )
                }
                if (lit > 0.01f) {
                    val inset = 2.dp.toPx()
                    val outline = shape.createOutline(
                        androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2), layoutDirection, this,
                    )
                    translate(inset, inset) { drawOutline(outline, c.accent, alpha = lit, style = Stroke(3.dp.toPx())) }
                }
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = size * 0.42f, tint = if (night) Color(0xFFDCE4EC) else Color(0xFF4F5B68))
    }
}
