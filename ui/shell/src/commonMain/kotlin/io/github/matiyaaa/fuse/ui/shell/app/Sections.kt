package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/**
 * How Fuse's sections present themselves on this device. This is the one place that decides the
 * one difference between platforms: where Fuse has its Store (Android), or Jellyfin is turned on,
 * the Cartridge section is **Addons**, holding Cartridge, the Store and Jellyfin; everywhere else it
 * is **Cartridge**, as it always was. The section keeps its saved identity ([Destination.CARTRIDGE]) either way, so a tab order or
 * a hidden tab carries over unchanged.
 */
@Immutable
class Sections(
    /** True where the Cartridge section is Addons (the Store is on here, or Jellyfin or Fuse Sync is). */
    val addons: Boolean,
    /** Cartridge runs here and is turned on in Fuse. With nothing else on, it is the section alone. */
    val cartridgeOn: Boolean = true,
) {
    fun label(d: Destination): String = when (d) {
        Destination.HOME -> "Home"
        Destination.LIBRARY -> "Library"
        Destination.SYSTEMS -> "Systems"
        Destination.ACHIEVEMENTS -> "Achievements"
        Destination.APPS -> "Apps"
        Destination.CARTRIDGE -> if (addons) "Addons" else "Cartridge"
    }

    fun icon(d: Destination): ImageVector = when (d) {
        Destination.HOME -> FuseIcons.Home
        Destination.LIBRARY -> FuseIcons.Library
        Destination.SYSTEMS -> FuseIcons.Chip
        Destination.ACHIEVEMENTS -> FuseIcons.Trophy
        Destination.APPS -> FuseIcons.Smartphone
        Destination.CARTRIDGE -> if (addons) FuseIcons.Blocks else FuseMarks.Cartridge
    }

    /**
     * Whether [d] has a tab, given what this device [offers] and Cartridge's [status]. Addons is there
     * while something in it is on (the Store, Jellyfin); Cartridge's own tab waits for Cartridge to be
     * installed and turned on. With all of them off, there is no tab at all.
     */
    fun showsTab(d: Destination, offers: Boolean, status: CartridgeStatus): Boolean = offers && when {
        d != Destination.CARTRIDGE -> true
        addons -> true
        else -> cartridgeOn && status.installed
    }

    /** Whether Settings offers a tab for [d]: Cartridge's only while Cartridge is turned on in Fuse. */
    fun offersTab(d: Destination, offers: Boolean, prefs: UiPrefs): Boolean =
        d != Destination.HOME && offers && (d != Destination.CARTRIDGE || addons || prefs.cartridgeEnabled)
}

/** How this device presents its sections (see [Sections]). */
internal val AppState.sections: Sections get() {
    val p = store.prefs.value
    return Sections(
        addons = (store.appStore.supported && p.storeEnabled) || (store.jellyfin != null && p.jellyfin.enabled) ||
            (store.sync.service != null && (p.sync.enabled || keepsOwnPeople)) || syncthingOn ||
            (store.streaming.supported && p.streaming.enabled && p.streaming.hosts.isNotEmpty()),
        cartridgeOn = platform.features.cartridge && p.cartridgeEnabled,
    )
}

/**
 * People kept on this device without a household (profiles made here, Fuse One's household not
 * set up): their saves are still kept apart, so the Sync page has something to show.
 */
internal val AppState.keepsOwnPeople: Boolean
    get() = store.sync.service?.profiles?.value?.isNotEmpty() == true

/** The tabs shown in the top line: Home first, then the user's order, each offered and present here. */
@Composable
internal fun rememberTabs(app: AppState, prefs: UiPrefs): List<Destination> {
    val cartridge by app.store.cartridge.status.collectAsState()
    // Profiles coming or going can bring the Sync page (and with it Addons) or take it away.
    app.store.sync.service?.profiles?.collectAsState()?.value
    val sections = app.sections
    return (listOf(Destination.HOME) + prefs.destinations.filter { it != Destination.HOME })
        .filter { sections.showsTab(it, app.offers(it), cartridge) }
}

/**
 * Opens the top line's order to arrange, with [held] (the tab that was held) already picked up:
 * the same sheet Settings, Home, Section order opens. Home stays first.
 */
internal fun AppState.arrangeTabs(held: Destination? = null) {
    val p = store.prefs.value
    val sections = sections
    // Exactly the tabs the top line shows, in its order.
    val cartridge = store.cartridge.status.value
    val shown = (listOf(Destination.HOME) + p.destinations.filter { it != Destination.HOME }).filter { sections.showsTab(it, offers(it), cartridge) }
    reorder = ReorderSpec(
        title = "Section order",
        icon = io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.MoveHorizontal,
        message = "The order of the top bar. Home always comes first. Move it with the D-pad and press A to put it down, or drag it by its grip",
        lifted = held?.takeIf { it != Destination.HOME }?.name,
        entries = shown.map { d -> io.github.matiyaaa.fuse.ui.designsystem.components.ReorderEntry(d.name, sections.label(d), sections.icon(d), locked = d == Destination.HOME) },
        onMoved = { keys ->
            store.updatePrefs { s ->
                val moved = keys.mapNotNull { k -> Destination.entries.firstOrNull { it.name == k } }
                // Sections hidden from this list (not offered here) keep their places after the rest.
                s.copy(destinations = moved + s.destinations.filter { it !in moved })
            }
        },
    )
}

/** Which part of Addons is showing. */
enum class AddonsPart { CARTRIDGE, STORE, JELLYFIN, SYNC, SYNCTHING, STREAMING }

/** Opens Streaming in Addons. */
internal fun AppState.openStreaming() {
    addonsPart = AddonsPart.STREAMING
    selectTab(Destination.CARTRIDGE)
}

/** Opens Jellyfin in Addons. */
internal fun AppState.openJellyfin() {
    addonsPart = AddonsPart.JELLYFIN
    selectTab(Destination.CARTRIDGE)
}

/** Opens whichever sync is in use in Addons: Fuse Sync's Hub (Sync), or Syncthing. */
internal fun AppState.openSyncHub() {
    addonsPart = if (syncthingOn) AddonsPart.SYNCTHING else AddonsPart.SYNC
    selectTab(Destination.CARTRIDGE)
}

/** Whether Fuse is using Syncthing here (kept by the app from Syncthing's state, so the tabs follow it). */
internal val AppState.syncthingOn: Boolean get() = syncthingActive

/** Opens Cartridge: its own section, or the Cartridge part of Addons. */
internal fun AppState.openCartridge() {
    addonsPart = AddonsPart.CARTRIDGE
    selectTab(Destination.CARTRIDGE)
}

/** Opens RomM's games: the Library's All view, where they sit with every other game. */
internal fun AppState.openRomm() {
    librarySegment = io.github.matiyaaa.fuse.ui.shell.library.LibrarySegment.EVERYWHERE
    selectTab(Destination.LIBRARY)
}

/** Opens the Store in Addons, on the page of the app with [key] when given. */
internal fun AppState.openStore(key: String? = null) {
    addonsPart = AddonsPart.STORE
    selectTab(Destination.CARTRIDGE)
    if (key != null) go(Route.StoreApp(key))
}
