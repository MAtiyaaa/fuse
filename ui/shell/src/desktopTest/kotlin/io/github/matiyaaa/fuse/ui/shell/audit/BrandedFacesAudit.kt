package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformFamily
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PlatformKind
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.focus.packBoard
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.shell.home.BoardFace
import io.github.matiyaaa.fuse.ui.shell.home.title
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The widgets redesigned in 0.3.0 (Continue playing, Systems, Favourites, Collections, Cartridge)
 * at every size, drawn straight from sample feeds with real image files: a game with background
 * art, a cover and a logo; the same game without a logo; one with no art at all; systems with and
 * without the art pack. Rendered under desktopAudit only.
 */
@OptIn(ExperimentalTestApi::class)
class BrandedFacesAudit {
    private val now = 1_790_000_000_000L
    private val art: File by lazy { Files.createTempDirectory("fuse-faces").toFile() }

    private val pages = listOf(
        "small, wide, tall and square" to listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2),
        "full strip, three by two and a column" to listOf(4 to 1, 3 to 2, 1 to 2),
        "two by three and four by two" to listOf(2 to 3, 4 to 2),
        "four by three" to listOf(4 to 3),
    )

    private fun platform(id: String, name: String, short: String, accent: Long) =
        Platform(PlatformId(id), name, short, PlatformKind.CONSOLE, PlatformFamily.OTHER, accent = accent)

    private fun system(p: Platform, games: Int, withArt: Boolean): PlatformCard {
        val panel = File(art, "${p.id.value}-panel.png")
        val logo = File(art, "${p.id.value}-logo.png")
        if (withArt && !panel.exists()) {
            AuditSystemArt.panel(panel, p.accent)
            AuditSystemArt.logo(logo, p.shortName)
        }
        return PlatformCard(
            platform = p, gameCount = games,
            art = if (withArt) Art(boxart = panel.absolutePath, logo = logo.absolutePath) else Art(),
            emulatorName = null, emulatorInstalled = true, installedEmulators = 1,
            bios = BiosStatus(BiosState.NOT_REQUIRED), layout = LibraryLayout.ICON, romFolders = emptyList(),
        )
    }

    private fun game(id: Long, title: String, p: Platform, hero: Boolean, logo: Boolean, cover: Boolean = true): GameCard {
        val heroFile = File(art, "g$id-hero.png")
        val coverFile = File(art, "g$id-cover.png")
        val logoFile = File(art, "g$id-logo.png")
        if (hero && !heroFile.exists()) scene(heroFile, p.accent)
        if (cover && !coverFile.exists()) AuditCovers.cover(coverFile, title, p.accent)
        if (logo && !logoFile.exists()) AuditSystemArt.logo(logoFile, title.uppercase())
        return GameCard(
            id = GameId(id), platformId = p.id, title = title, platformShort = p.shortName, accent = p.accent,
            art = Art(
                hero = heroFile.takeIf { hero }?.absolutePath,
                boxart = coverFile.takeIf { cover }?.absolutePath,
                logo = logoFile.takeIf { logo }?.absolutePath,
            ),
            lastPlayedAt = now - 2 * 3_600_000L, playSeconds = 14 * 3_600L + 1_800L,
        )
    }

    /** A wide, bright scene for background art: sky, sun and hills in the game's colour. */
    private fun scene(file: File, color: Long) {
        val w = 1280
        val h = 720
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val base = Color(color.toInt(), true)
        g.paint = GradientPaint(0f, 0f, Color(255, 236, 200), 0f, h.toFloat(), base)
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 250, 230, 230)
        g.fill(Ellipse2D.Float(840f, 90f, 220f, 220f))
        g.color = base.darker()
        g.fill(Ellipse2D.Float(-200f, 430f, 900f, 600f))
        g.color = base.darker().darker()
        g.fill(Ellipse2D.Float(500f, 500f, 1000f, 600f))
        g.color = Color(255, 255, 255, 90)
        g.stroke = BasicStroke(6f)
        g.drawLine(0, 520, w, 470)
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    @Test
    fun brandedFaces() {
        val dir = Audit.dir
        assumeTrue("Only under desktopAudit", dir != null)
        assumeTrue(Audit.sizeEnabled(AuditSize.D))
        val dc = platform("dc", "Dreamcast", "DC", 0xFFE0702EL)
        val ps2 = platform("ps2", "PlayStation 2", "PS2", 0xFF3F5FBFL)
        val gba = platform("gba", "Game Boy Advance", "GBA", 0xFF6D4BC2L)
        val snes = platform("snes", "Super Nintendo", "SNES", 0xFF8A8FA8L)
        val switch = platform("switch", "Nintendo Switch", "Switch", 0xFFE4373CL)
        val systemsArt = listOf(system(dc, 24, true), system(ps2, 48, true), system(gba, 61, true), system(snes, 112, true), system(switch, 9, true))
        val systemsPlain = systemsArt.map { it.copy(art = Art()) }
        val velvet = game(1, "Velvet Orbit", dc, hero = true, logo = true)
        val noLogo = game(2, "Beacon Bay", gba, hero = true, logo = false)
        val bare = game(3, "Static Bloom", ps2, hero = false, logo = false, cover = false)
        val favourites = listOf(velvet, noLogo, game(4, "Cinder Circuit", ps2, hero = false, logo = false), game(5, "Tanuki Trails", switch, hero = false, logo = false), game(6, "Lanterns", snes, hero = false, logo = false))
        val collections = listOf("Couch co-op" to 12, "Weekend queue" to 5, "Racing" to 8, "RPG backlog" to 21, "Short games" to 7)
            .mapIndexed { i, (n, c) -> GameCollection(CollectionId(i.toLong()), n, CollectionKind.MANUAL, gameCount = c) }

        val cases = listOf(
            Triple(WidgetKind.CONTINUE_PLAYING, "with art and logo", HomeFeed(continuePlaying = listOf(velvet, noLogo), systems = systemsArt)),
            Triple(WidgetKind.CONTINUE_PLAYING, "without a logo", HomeFeed(continuePlaying = listOf(noLogo), systems = systemsArt)),
            Triple(WidgetKind.CONTINUE_PLAYING, "without any art", HomeFeed(continuePlaying = listOf(bare), systems = systemsPlain)),
            Triple(WidgetKind.SYSTEMS, "with the art pack", HomeFeed(continuePlaying = listOf(velvet), systems = systemsArt)),
            Triple(WidgetKind.SYSTEMS, "without art", HomeFeed(systems = systemsPlain)),
            Triple(WidgetKind.FAVORITES, "with covers", HomeFeed(favorites = favourites)),
            Triple(WidgetKind.COLLECTIONS, "with collections", HomeFeed(collections = collections)),
            Triple(WidgetKind.CARTRIDGE_DOWNLOADS, "installed and idle", HomeFeed()),
            Triple(WidgetKind.RECENTLY_ADDED, "as a catalogue", HomeFeed(recentlyAdded = favourites, systems = systemsArt)),
            Triple(WidgetKind.SYNC_STATUS, "online", HomeFeed()),
            Triple(WidgetKind.SYNC_DEVICES, "three devices", HomeFeed()),
            Triple(WidgetKind.CLOCK, "today", HomeFeed()),
            Triple(WidgetKind.PLAYTIME_WEEK, "a busy week", HomeFeed(playtime = io.github.matiyaaa.fuse.ui.shell.store.PlaytimeSummary(totalSeconds = 412_000, weekSeconds = 46_800, lastSevenDays = listOf(3_600L, 0L, 7_200L, 10_800L, 1_800L, 14_400L, 9_000L)))),
            Triple(WidgetKind.PLAYTIME_TOTAL, "all time", HomeFeed(playtime = io.github.matiyaaa.fuse.ui.shell.store.PlaytimeSummary(totalSeconds = 412_000, weekSeconds = 46_800, lastSevenDays = listOf(3_600L, 0L, 7_200L, 10_800L, 1_800L, 14_400L, 9_000L)))),
            Triple(WidgetKind.MOST_PLAYED, "a few games", HomeFeed(mostPlayed = favourites, systems = systemsArt)),
            Triple(WidgetKind.STORAGE, "a drive", HomeFeed(storage = io.github.matiyaaa.fuse.ui.shell.store.StorageSummary("Games", freeBytes = 182_000_000_000, totalBytes = 512_000_000_000))),
        )
        // Fuse Sync's widgets read a household: three people, three devices, a little history.
        val sync = run {
            val db = File(art, "sync-${System.nanoTime()}.db")
            val data = io.github.matiyaaa.fuse.data.FuseData(io.github.matiyaaa.fuse.data.db.DesktopDatabase.open(db.absolutePath))
            AuditSync(data.settings).also { it.household(asHost = true) }
        }
        for ((kind, label, feed) in cases) {
            if (!Audit.wants("widgets", "${kind.title()} $label")) continue
            val cartridge = CartridgeStatus(installed = true, connected = true, bridge = true)
            for ((name, sizes) in pages) {
                runDesktopComposeUiTest(AuditSize.D.widthPx, AuditSize.D.heightPx) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(AuditSize.D.density)) {
                            FuseTheme {
                              CompositionLocalProvider(io.github.matiyaaa.fuse.ui.shell.home.LocalSyncService provides sync) {
                                val cells = packBoard(sizes, 4)
                                Box(Modifier.fillMaxSize().background(Fuse.colors.ink).padding(start = 40.dp, top = 80.dp)) {
                                    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.6f)
                                    for (c in cells) {
                                        Box(
                                            Modifier
                                                .offset(x = 304.dp * c.column, y = 193.dp * c.row)
                                                .size(288.dp * c.columnSpan + 16.dp * (c.columnSpan - 1), 173.dp * c.rowSpan + 20.dp * (c.rowSpan - 1))
                                                .clip(shape),
                                        ) {
                                            BoardFace(kind, BoardSize(c.columnSpan, c.rowSpan), feed, cartridge, clock24h = false)
                                        }
                                    }
                                }
                              }
                            }
                        }
                    }
                    mainClock.advanceTimeBy(3_000)
                    waitForIdle()
                    mainClock.advanceTimeBy(1_000)
                    val frame = onRoot().captureToImage().toAwtImage()
                    val rgb = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                    rgb.createGraphics().apply { drawImage(frame, 0, 0, null); dispose() }
                    val file = File(dir, "D/widgets/${Audit.slug(kind.title() + " " + label)}--${Audit.slug(name)}.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(rgb, "png", file)
                    println("Audit shot: ${file.absolutePath}")
                }
            }
        }
    }
}
