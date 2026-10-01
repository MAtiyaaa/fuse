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
 * The room for a game, so every game has one: its own background, else its first screenshot, else
 * its system's background, else its wide art, else its box art blurred into a colour field, else a
 * glow in its colour. Its system's background stands in while its own art loads. It never keeps the
 * previous game's art. Games on their system's background share one image, so moving between them
 * leaves the room as it is.
 */
fun gameRoom(id: Any, art: Art, accent: Long, system: PlatformCard?): HeroSource {
    val systemHero = system?.art?.hero
    val color = accent.toColor()
    val cover = art.boxart ?: art.square ?: art.icon
    return when {
        art.hero != null -> HeroSource(id, art.hero, color, art.heroFocusX, art.heroFocusY, art.video, placeholder = systemHero)
        art.screenshot != null -> HeroSource(id, art.screenshot, color, video = art.video, placeholder = systemHero)
        systemHero != null -> HeroSource(id, systemHero, (system.platform.accent).toColor(), system.art.heroFocusX, system.art.heroFocusY, art.video)
        art.grid != null -> HeroSource(id, art.grid, color, video = art.video)
        cover != null -> HeroSource(id, cover, color, video = art.video, blurred = true)
        else -> HeroSource(id, null, color, video = art.video)
    }
}

/** The image [gameRoom] shows for a game, for warming it up ahead of time. */
fun roomArt(art: Art, system: PlatformCard?): Any? =
    art.hero ?: art.screenshot ?: system?.art?.hero ?: art.grid ?: art.boxart ?: art.square ?: art.icon

fun GameCard.room(system: PlatformCard?): HeroSource = gameRoom(id, art, accent, system)
