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
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.Achievement
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.focus.packBoard
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.shell.home.BoardFace
import io.github.matiyaaa.fuse.ui.shell.home.title
import io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/**
 * The achievement widgets at every size, drawn straight from a sample RetroAchievements feed: the
 * audit's library has no network, so on the board they could only show their "connect" note.
 */
@OptIn(ExperimentalTestApi::class)
class WidgetFacesAudit {
    private val now = 1_790_000_000_000L
    private val recent = listOf(
        "First Light" to "Velvet Orbit", "Harbour Run" to "Beacon Bay", "No Hits Taken" to "Static Bloom",
        "Night Owl" to "Hollow Meridian", "Full Circuit" to "Cinder Circuit", "Collector" to "Aurora Outpost",
    ).mapIndexed { i, (title, game) ->
        RecentAchievement(
            Achievement(i.toLong(), 9_000L + i, title, "Sample", (i + 1) * 5, "", "", now - i * 3_600_000L, null),
            gameTitle = game, consoleName = "PlayStation", gameIconUrl = null, hardcore = i % 2 == 0, earnedAt = now - i * 3_600_000L,
        )
    }
    private fun state(title: String, console: String, earned: Int, total: Int, award: String? = null) =
        AchievementState(1, title, console, null, total, earned, earned, total * 5, earned * 5, award, fetchedAt = now)

    private val feed = HomeFeed(
        achievements = AchievementsFeed(
            user = AchievementUser("sample", null, null, 1_200, 0, 1_500, 4_200, null, null, now),
            recent = recent,
            inProgress = listOf(
                state("Velvet Orbit", "Dreamcast", 34, 52), state("Beacon Bay", "Game Boy Advance", 12, 40),
                state("Static Bloom", "PlayStation", 7, 31), state("Cinder Circuit", "PlayStation", 22, 25),
            ),
            recentlyMastered = listOf(
                state("Hollow Meridian", "PlayStation", 48, 48, "mastered"), state("Tanuki Trails", "Switch", 30, 30, "mastered"),
                state("Lanterns of Vell", "SNES", 22, 22, "mastered"),
            ),
        ),
    )

    private val pages = listOf(
        "small, wide, tall and square" to listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2),
        "full strip, three by two and a column" to listOf(4 to 1, 3 to 2, 1 to 2),
        "two by three and four by two" to listOf(2 to 3, 2 to 2, 2 to 1),
        "four by three" to listOf(4 to 3),
    )

    @Test
    fun achievementFaces() {
        val dir = Audit.dir
        assumeTrue("Only under desktopAudit", dir != null)
        assumeTrue(Audit.sizeEnabled(AuditSize.D))
        val kinds = listOf(WidgetKind.RECENT_ACHIEVEMENT, WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.ACHIEVEMENT_PROGRESS, WidgetKind.RECENTLY_MASTERED)
            .filter { Audit.wants("widgets", "${it.title()} with data") }
        for (kind in kinds) {
            for ((name, sizes) in pages) {
                runDesktopComposeUiTest(AuditSize.D.widthPx, AuditSize.D.heightPx) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(AuditSize.D.density)) {
                            FuseTheme {
                                // The board's measures at this size: 288 by 173 cells, 16 across and 20 down between.
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
                                            BoardFace(kind, BoardSize(c.columnSpan, c.rowSpan), feed, CartridgeStatus(), clock24h = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    mainClock.advanceTimeBy(2_000)
                    val frame = onRoot().captureToImage().toAwtImage()
                    val rgb = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                    rgb.createGraphics().apply { drawImage(frame, 0, 0, null); dispose() }
                    val file = File(dir, "D/widgets/${Audit.slug(kind.title() + " with data")}--${Audit.slug(name)}.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(rgb, "png", file)
                    println("Audit shot: ${file.absolutePath}")
                }
            }
        }
    }
}
