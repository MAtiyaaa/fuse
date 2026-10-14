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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** Where the band's flat top runs, as a share of the bar's height (the humps rise above it). */
private const val BAND_TOP = 0.3f

/** The Home styles this person can pick: Fused and Network, and Fii's once unlocked (or in use). */
internal fun UiPrefs.homeStyles(): List<HomeStyle> =
    listOf(HomeStyle.FUSED, HomeStyle.NETWORK) + if (FII_UNLOCK in unlocked || home.style == HomeStyle.FII) listOf(HomeStyle.FII) else emptyList()

internal fun HomeStyle.label(): String = when (this) {
    HomeStyle.FUSED -> "Fused"
    HomeStyle.NETWORK -> "Network"
    HomeStyle.FII -> "Fii"
}

/** What each style is, and for the one being switched to, what changes ([theme] is the theme in use). */
internal fun HomeStyle.detail(theme: ThemeSpec): String = when (this) {
    HomeStyle.FUSED -> if (theme.id in ThemePresets.fiiFamily) "Your board in Fuse's own look, in Fii's colours" else "A board of tiles you arrange yourself"
    HomeStyle.NETWORK -> "A continuous dashboard of shelves under a big title"
    HomeStyle.FII -> if (theme.id in ThemePresets.fiiFamily) "Your board as glossy channels, with the clock along a bar at the bottom"
    else "Your board as glossy channels with the clock at the bottom, in ${theme.name}'s colours"
}

/**
 * Switches Home to [next]: Fii's keeps the style it came from, to go back to. Fii's Home counts as
 * looked at once any style is picked, so it is no longer marked new.
 */
internal fun AppState.applyHomeStyle(next: HomeStyle) {
    val before = store.prefs.value.home.style
    store.updatePrefs { it.copy(home = it.home.withStyle(next), unlockedNew = it.unlockedNew - FII_UNLOCK) }
    if (next != before) toasts.show("Home is now ${next.label()}")
}

/**
 * Asks which Home style to use: each one says what it is (and what changes), with a picture beside
 * it, and Fii's is marked new until looked at. Choosing is the answer, so a switch never happens
 * without being asked; the one in use is checked and changes nothing.
 */
fun AppState.chooseHomeStyle() {
    val prefs = store.prefs.value
    val current = prefs.home.style
    val styles = prefs.homeStyles()
    val fresh = FII_UNLOCK in prefs.unlockedNew
    choice = ChoiceSpec(
        title = "Home style",
        message = "Your board's tiles stay as they are whichever you pick.",
        icon = FuseIcons.Dashboard,
        options = styles.map { s ->
            MenuAction(
                "homestyle.${s.name.lowercase()}", s.label(), null,
                detail = s.detail(prefs.theme),
                trailing = if (s == HomeStyle.FII && fresh && current != s) Trailing.Badge("Unlocked") else Trailing.Check(s == current),
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
 * Uses the theme [spec], from the gallery. Fii (or Fii Night) unlocks Fii's Home the first time and
 * asks whether to use it; leaving the Fii themes while on Fii's Home asks whether to go back to the
 * Home from before. Moving between Fii and Fii Night asks nothing.
 */
fun AppState.useTheme(spec: ThemeSpec) {
    val before = store.prefs.value
    val wasFii = before.themeId in ThemePresets.fiiFamily
    val isFii = spec.id in ThemePresets.fiiFamily
    val unlocking = isFii && FII_UNLOCK !in before.unlocked
    store.updatePrefs {
        val next = it.withTheme(spec)
        if (unlocking) next.copy(unlocked = next.unlocked + FII_UNLOCK, unlockedNew = next.unlockedNew + FII_UNLOCK) else next
    }
    val style = before.home.style
    when {
        isFii && !wasFii && style != HomeStyle.FII -> choice = ChoiceSpec(
            title = "Fii has its own Home",
            message = "Glossy channels on your board, with the clock and date along a bar at the bottom. " +
                "Your tiles stay where they are, and you can switch back any time in Settings, Home.",
            icon = FuseIcons.Dashboard,
            options = listOf(
                MenuAction("fii.use", "Use Fii's Home", FuseIcons.Check, detail = "Home changes now", onSelect = {
                    choice = null
                    applyHomeStyle(HomeStyle.FII)
                }),
                MenuAction("fii.keep", "Keep my Home", FuseIcons.Home, detail = "Home stays ${style.label()}, in Fii's colours", onSelect = {
                    choice = null
                    toasts.show("Fii's Home is in Settings, Home, Home style")
                }),
            ),
            aids = listOf(ChoiceAid.HomeStyle(HomeStyle.FII), ChoiceAid.HomeStyle(style)),
        )
        wasFii && !isFii && style == HomeStyle.FII -> {
            val back = before.home.beforeFii?.takeIf { it != HomeStyle.FII } ?: HomeStyle.FUSED
            choice = ChoiceSpec(
                title = "Go back to the Home you had?",
                message = "Fii's Home works with any theme, in its colours. It stays in Settings, Home, Home style either way.",
                icon = FuseIcons.Dashboard,
                options = listOf(
                    MenuAction("fii.back", "Go back to ${back.label()}", FuseIcons.Undo, detail = back.detail(spec), onSelect = {
                        choice = null
                        applyHomeStyle(back)
                    }),
                    MenuAction("fii.stay", "Keep Fii's Home", FuseIcons.Check, detail = "In ${spec.name}'s colours", onSelect = { choice = null }),
                ),
                aids = listOf(ChoiceAid.HomeStyle(back), ChoiceAid.HomeStyle(HomeStyle.FII)),
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
internal fun FiiBar(app: AppState, modifier: Modifier = Modifier) {
    val prefs by app.store.prefs.collectAsState()
    val c = Fuse.colors
    val night = c.isDark
    val time = rememberClockText(prefs.clock24h)
    val date = remember(time) { formatDate() }
    val band = if (night) Color(0xFF1E252E) else Color(0xFFF7F9FB)
    val bandLow = if (night) Color(0xFF161B22) else Color(0xFFE3E8EE)
    val edge = c.accent.copy(alpha = if (night) 0.55f else 0.5f)
    val shade = Color.Black.copy(alpha = if (night) 0.4f else 0.1f)
    BoxWithConstraints(modifier.fillMaxWidth().height(FII_BAR)) {
        val button = (maxHeight * 0.78f).coerceAtMost(76.dp)
        val inset = 18.dp
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
            Modifier.align(Alignment.TopCenter).padding(top = FII_BAR * BAND_TOP + 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FText(time, Fuse.type.display.copy(fontWeight = FontWeight.Light, fontSize = 30.sp, lineHeight = 34.sp), color = c.textMuted, maxLines = 1, align = TextAlign.Center)
            FText(date, Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
        }
        FiiRoundButton(
            FuseIcons.Grid, "Sections", button,
            Modifier.align(Alignment.CenterStart).padding(start = inset),
        ) { app.openSections() }
        FiiRoundButton(
            FuseIcons.Sliders, "Quick menu", button,
            Modifier.align(Alignment.CenterEnd).padding(end = inset),
        ) { app.quickMenuOpen = true }
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
private fun FiiRoundButton(icon: ImageVector, label: String, size: Dp, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val night = c.isDark
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit by fuselineFloat(if (hovered || pressed) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "fiiButton")
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

/** The room [FiiBar] takes over the board's foot, for the board to keep clear. */
internal val FiiBarRoom: Dp get() = FII_BAR + Size.hintHeight
