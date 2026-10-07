package io.github.matiyaaa.fuse.ui.shell.systems

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import io.github.matiyaaa.fuse.integrations.systemart.SystemIcons
import io.github.matiyaaa.fuse.model.SystemTileLook
import io.github.matiyaaa.fuse.model.TilePattern
import io.github.matiyaaa.fuse.model.TileTone
import io.github.matiyaaa.fuse.ui.designsystem.components.IconBadge
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlin.math.PI
import kotlin.math.sin

/** The picture a system's small tile shows when the person hasn't chosen one: its usual icon, if the set has one. */
internal fun defaultTileIcon(card: PlatformCard): String? = SystemIcons.forPlatform(card.platform.id.value).firstOrNull()

/** The colour behind a system's small tile in [tone], from its own colour [accent]. */
internal fun tileBackground(tone: TileTone, accent: Color): Color = when (tone) {
    TileTone.LIGHT -> lerp(Color(0xFFF1F1EE), accent, 0.06f)
    TileTone.TINT -> lerp(Color.White, accent, 0.22f)
    TileTone.ACCENT -> accent
    TileTone.DARK -> lerp(Color(0xFF15171C), accent, 0.18f)
}

/**
 * A system half a card wide, the size of a game's box art: a picture of the system itself (its
 * console or handheld, from Fuse's icon set) on a light patterned background, the way a shelf of
 * systems looks; its logo where the set has no picture of it, or the person chose the logo; its
 * short name where it has neither. The person can change the picture, the pattern and the colour,
 * and put them back ([systemTileMenu]).
 */
@Composable
internal fun SystemTileFace(card: PlatformCard, look: SystemTileLook?) {
    val accent = card.platform.accent.toColor()
    val tone = look?.tone ?: TileTone.LIGHT
    val pattern = look?.pattern ?: TilePattern.DOTS
    val bg = tileBackground(tone, accent)
    val dark = bg.luminance() < 0.4f
    val ink = if (dark) Color.White else lerp(Color(0xFF1C1F26), accent, 0.35f)
    val icon = when (val chosen = look?.icon) {
        null -> defaultTileIcon(card)
        "" -> null
        else -> chosen
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val short = minOf(maxWidth, maxHeight)
        TilePatternLayer(pattern, ink.copy(alpha = if (dark) 0.10f else 0.08f), short.value)
        val name: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val style = Fuse.type.title
                BasicText(
                    card.platform.shortName,
                    Modifier.padding(horizontal = short * 0.1f),
                    style = style.copy(color = ink, textAlign = TextAlign.Center),
                    maxLines = 2,
                    autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.4f, maxFontSize = style.fontSize * 1.6f),
                )
            }
        }
        val logo: @Composable () -> Unit = {
            val art = card.art.logo
            if (art != null) {
                Artwork(art, Modifier.fillMaxSize().padding(short * 0.16f), contentScale = ContentScale.Fit, tint = ink, fallback = name)
            } else {
                name()
            }
        }
        if (icon != null) {
            // The set's pictures sit in a square with room around them: drawn a little past the tile's short side.
            Artwork(
                SystemIcons.url(icon),
                Modifier.align(Alignment.Center).size(short * 1.04f),
                contentScale = ContentScale.Fit,
                fadeIn = true,
                fallback = logo,
            )
        } else {
            logo()
        }
        if (!card.emulatorInstalled) {
            IconBadge(FuseIcons.Warning, Modifier.align(Alignment.TopEnd).padding(short * 0.06f), tint = Fuse.colors.warning, background = Fuse.colors.artScrim, size = Size.badge)
        }
    }
}

/** [pattern] drawn in [ink] over a tile whose short side is [scale] dp, so it keeps its size on any tile. */
@Composable
private fun TilePatternLayer(pattern: TilePattern, ink: Color, scale: Float) {
    if (pattern == TilePattern.PLAIN) return
    Canvas(Modifier.fillMaxSize()) {
        val step = (scale * density / 7f).coerceAtLeast(6f * density)
        when (pattern) {
            TilePattern.DOTS -> dots(ink, step)
            TilePattern.GRID -> grid(ink, step)
            TilePattern.DIAGONAL -> stripes(ink, step)
            TilePattern.WAVES -> waves(ink, step)
            TilePattern.PLAIN -> Unit
        }
    }
}

private fun DrawScope.dots(ink: Color, step: Float) {
    val r = step * 0.11f
    var row = 0
    var y = step / 2
    while (y < size.height + step) {
        // Every other row half a step along, as a tiled pattern does.
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width + step) {
            drawCircle(ink, r, Offset(x, y))
            x += step
        }
        y += step * 0.87f
        row++
    }
}

private fun DrawScope.grid(ink: Color, step: Float) {
    val w = (step * 0.05f).coerceAtLeast(1f)
    var x = 0f
    while (x <= size.width) {
        drawLine(ink, Offset(x, 0f), Offset(x, size.height), w)
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(ink, Offset(0f, y), Offset(size.width, y), w)
        y += step
    }
}

private fun DrawScope.stripes(ink: Color, step: Float) {
    val w = step * 0.22f
    var x = -size.height
    while (x < size.width) {
        drawLine(ink, Offset(x, size.height), Offset(x + size.height, 0f), w)
        x += step
    }
}

private fun DrawScope.waves(ink: Color, step: Float) {
    val stroke = Stroke((step * 0.08f).coerceAtLeast(1f))
    var y = step / 2
    while (y < size.height + step) {
        val path = Path()
        var x = 0f
        path.moveTo(0f, y)
        while (x <= size.width) {
            path.lineTo(x, y + sin(x / step * 2 * PI).toFloat() * step * 0.18f)
            x += step / 8
        }
        drawPath(path, ink, style = stroke)
        y += step * 0.75f
    }
}

/**
 * How [card]'s small tile looks, to change: its picture (any of the set's pictures of that system,
 * or its logo), its pattern and its colour, and Reset to put Fuse's own back.
 */
internal fun AppState.systemTileMenu(card: PlatformCard) {
    val id = card.platform.id.value
    val look = store.prefs.value.systemTiles[id] ?: SystemTileLook()
    fun set(change: (SystemTileLook) -> SystemTileLook) {
        store.updatePrefs { p ->
            val next = change(p.systemTiles[id] ?: SystemTileLook())
            p.copy(systemTiles = if (next == SystemTileLook()) p.systemTiles - id else p.systemTiles + (id to next))
        }
    }
    val icons = SystemIcons.forPlatform(id)
    val base = icons.firstOrNull()
    val pictureLabel = when (val i = look.icon) {
        null -> if (base != null) "Standard" else "Logo"
        "" -> "Logo"
        else -> base?.let { SystemIcons.label(i, it) } ?: "Logo"
    }
    contextMenu = null
    choice = ChoiceSpec(
        title = "${card.platform.shortName} Small Tile",
        icon = FuseIcons.Palette,
        message = "How ${card.platform.name} looks when it is half a card wide",
        options = listOfNotNull(
            MenuAction("picture", "Picture", FuseIcons.Image, trailing = Trailing.Value(pictureLabel), onSelect = { tilePicturePicker(card, icons, look) { v -> set { it.copy(icon = v) } } }),
            MenuAction("pattern", "Pattern", FuseIcons.Grid, trailing = Trailing.Value((look.pattern ?: TilePattern.DOTS).label), onSelect = {
                choice = ChoiceSpec(
                    title = "Pattern",
                    icon = FuseIcons.Grid,
                    options = TilePattern.entries.map { pt ->
                        MenuAction(pt.name, pt.label, null, trailing = Trailing.Check(pt == (look.pattern ?: TilePattern.DOTS)), onSelect = {
                            set { it.copy(pattern = pt.takeIf { p -> p != TilePattern.DOTS }) }
                            systemTileMenu(card)
                        })
                    },
                )
            }),
            MenuAction("tone", "Colour", FuseIcons.Palette, trailing = Trailing.Value((look.tone ?: TileTone.LIGHT).label), onSelect = {
                choice = ChoiceSpec(
                    title = "Colour",
                    icon = FuseIcons.Palette,
                    options = TileTone.entries.map { t ->
                        MenuAction(t.name, t.label, null, trailing = Trailing.Check(t == (look.tone ?: TileTone.LIGHT)), onSelect = {
                            set { it.copy(tone = t.takeIf { x -> x != TileTone.LIGHT }) }
                            systemTileMenu(card)
                        })
                    },
                )
            }),
            MenuAction("reset", "Reset Small Tile", FuseIcons.RotateCcw, detail = "Fuse's own picture, pattern and colour", enabled = look != SystemTileLook(), onSelect = {
                set { SystemTileLook() }
                choice = null
                toasts.show("${card.platform.shortName}'s small tile is back as it came")
            }),
        ),
    )
}

/** Every picture of [card]'s system the set has (models and colours), and its logo instead. */
private fun AppState.tilePicturePicker(card: PlatformCard, icons: List<String>, look: SystemTileLook, onPick: (String?) -> Unit) {
    val base = icons.firstOrNull()
    val current = look.icon ?: base ?: ""
    choice = ChoiceSpec(
        title = "Picture",
        icon = FuseIcons.Image,
        message = if (icons.isEmpty()) "Fuse's picture set has no picture of ${card.platform.name}, so its logo stands in" else SystemIcons.ATTRIBUTION,
        options = icons.map { name ->
            MenuAction(
                "icon.$name", SystemIcons.label(name, base ?: name),
                trailing = if (name == current) Trailing.Value("In use") else Trailing.None,
                art = MenuArt(SystemIcons.url(name), square = true, fallbackTitle = card.platform.shortName, accent = card.platform.accent, wide = false),
                onSelect = {
                    // The usual picture is Fuse's own choice, so it follows the set if that changes.
                    onPick(name.takeIf { it != base })
                    systemTileMenu(card)
                },
            )
        } + MenuAction("icon.logo", "Logo", FuseIcons.Type, detail = "${card.platform.shortName}'s logo instead of a picture", trailing = if (current == "") Trailing.Value("In use") else Trailing.None, onSelect = {
            onPick("")
            systemTileMenu(card)
        }),
    )
}
