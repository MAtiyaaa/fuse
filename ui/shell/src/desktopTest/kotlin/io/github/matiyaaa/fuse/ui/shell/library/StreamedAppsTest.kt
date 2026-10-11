package io.github.matiyaaa.fuse.ui.shell.library

import io.github.matiyaaa.fuse.data.settings.StreamHostSettings
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.StreamHostView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Apps streamed from the household's computers are one tile each, whichever computers offer them. */
class StreamedAppsTest {
    private fun host(id: String, name: String, vararg apps: String) =
        StreamHostView(StreamHostSettings(id, name, "192.168.1.$id", apps = apps.toList()), online = true)

    @Test
    fun anAppOnSeveralComputersIsOneTileListingThemAll() {
        val apps = streamedApps(listOf(host("1", "Gaming PC", "Desktop", "Elden Ring"), host("2", "Living Room", "elden ring ", "Hades"))) { null }
        assertEquals(listOf("Desktop", "Elden Ring", "Hades"), apps.map { it.name })
        val elden = apps.single { it.name == "Elden Ring" }
        assertEquals(listOf("Gaming PC", "Living Room"), elden.hosts.map { it.host.name })
        assertEquals("Streams from 2 computers", elden.where)
        assertEquals("Streams from Living Room", apps.single { it.name == "Hades" }.where)
    }

    @Test
    fun aStreamedAppIsNeverALibraryGameButCanWearItsArt() {
        val art = Art(square = "elden.png")
        val apps = streamedApps(listOf(host("1", "Gaming PC", "Elden Ring", "Desktop"))) { key -> art.takeIf { key == "elden ring" } }
        assertEquals(art, apps.first().card.art)
        assertEquals(Art.None, apps.last().card.art)
        assertTrue(apps.all { it.card.id.value < 0 }, "never a library game's id")
    }
}
