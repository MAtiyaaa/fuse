package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.focus.SectionedGridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.rememberPageState
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.reach.GameSection
import io.github.matiyaaa.fuse.ui.shell.reach.HouseholdTile
import io.github.matiyaaa.fuse.ui.shell.reach.SectionItem
import io.github.matiyaaa.fuse.ui.shell.reach.SectionedGameGrid
import io.github.matiyaaa.fuse.ui.shell.reach.householdMenu
import io.github.matiyaaa.fuse.ui.shell.romm.RommTile
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence
import kotlinx.coroutines.flow.flowOf

/**
 * Every game of one system, from everywhere, in one grid: this device's, RomM's and the household's
 * other devices', installed or not, each game once (a game here is shown as here; one on both RomM
 * and another device, as RomM's), in title order. What the All view's systems open.
 */
@Composable
fun SystemEverywhereScreen(app: AppState, platform: PlatformId, name: String, rommSlug: String?) {
    io.github.matiyaaa.fuse.ui.shell.app.CompanionShowsCopies(true)
    val store = app.store
    val localFlow = remember(platform) { store.library.games(GameQuery(platform = platform)) }
    val local by localFlow.collectAsState(initial = null)
    val rommFlow = remember(rommSlug) { rommSlug?.let { store.romm.games(it) } ?: flowOf(emptyList()) }
    val romm by rommFlow.collectAsState(initial = emptyList())
    val householdFlow = remember(platform) { store.reach.games(platform) }
    val household by householdFlow.collectAsState(initial = emptyList())
    val rommState by store.romm.state.collectAsState()
    val sel = rememberPageState(app.navigator, "system.everywhere.${platform.value}") { SectionedGridSelection() }

    fun key(title: String) = title.lowercase().filter { it.isLetterOrDigit() }
    val here = local.orEmpty()
    val seen = HashSet<String>().apply { here.forEach { add(key(it.title)) } }
    val items = ArrayList<Pair<String, SectionItem>>()
    for (c in here) {
        items += c.title to SectionItem(
            "here:" + c.id.value, c,
            tile = { selected, size, onClick, onLong -> GameIconTile(c, selected, size = size, onClick = onClick, onLongClick = onLong) },
            open = { app.activateGame(c) },
            options = { app.openContextMenu(app.gameMenu(c)) },
        )
    }
    for (g in romm) {
        if (g.presence == RommPresence.INSTALLED || g.presence == RommPresence.PARTLY_INSTALLED) continue
        if (!seen.add(key(g.card.title))) continue
        items += g.card.title to SectionItem(
            "romm:" + g.romId, g.card,
            tile = { selected, size, onClick, onLong -> RommTile(g, selected, size, onClick = onClick, onLongClick = onLong) },
            open = { app.go(Route.GameInfo(g.game ?: g.card.id)) },
            options = { app.openContextMenu(io.github.matiyaaa.fuse.ui.shell.romm.gameMenu(app, g)) },
        )
    }
    for (g in household) {
        if (!seen.add(key(g.card.title))) continue
        items += g.card.title to SectionItem(
            "device:" + g.id.value, g.card,
            tile = { selected, size, onClick, onLong -> HouseholdTile(g, selected, size, onClick = onClick, onLongClick = onLong) },
            open = { app.go(Route.GameInfo(g.id)) },
            options = { app.openContextMenu(householdMenu(app, g, romm = rommState.canUpload)) },
        )
    }
    val all = items.sortedBy { it.first.lowercase() }.map { it.second }
    val section = GameSection("all", name, FuseIcons.LibraryBig, null, all)
    SectionedGameGrid(app, sel, listOf(section), loading = local == null, empty = "No games for this system anywhere yet.", tag = "system.everywhere") {
        Column(Modifier.fillMaxWidth()) {
            FText(name, Fuse.type.title, maxLines = 1)
            val notHere = all.size - here.size
            FText(
                listOfNotNull("${all.size} ${if (all.size == 1) "game" else "games"}", "${here.size} on this device".takeIf { here.isNotEmpty() }, "$notHere elsewhere".takeIf { notHere > 0 }).joinToString("  ·  "),
                Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
            )
        }
    }
}
