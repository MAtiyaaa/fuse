package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/** The systems by id, for games whose background falls back to their system's. */
@Composable
fun rememberSystems(app: AppState): Map<PlatformId, PlatformCard> {
    val list by app.store.library.platforms.collectAsState()
    return remember(list) { list.associateBy { it.platform.id } }
}

/**
 * The room for a game: its own background, else its system's background, else its wide art, else a
 * glow in its colour. It never keeps the previous game's art. Games on their system's background
 * share one image, so moving between them leaves the room as it is.
 */
fun gameRoom(id: Any, art: Art, accent: Long, system: PlatformCard?): HeroSource {
    val systemHero = system?.art?.hero
    return when {
        art.hero != null -> HeroSource(id, art.hero, accent.toColor(), art.heroFocusX, art.heroFocusY, art.video)
        systemHero != null -> HeroSource(id, systemHero, (system.platform.accent).toColor(), system.art.heroFocusX, system.art.heroFocusY, art.video)
        else -> HeroSource(id, art.grid, accent.toColor(), video = art.video)
    }
}

fun GameCard.room(system: PlatformCard?): HeroSource = gameRoom(id, art, accent, system)
