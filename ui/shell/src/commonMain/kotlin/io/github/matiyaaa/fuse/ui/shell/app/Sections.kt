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
            (store.streaming.supported && p.streaming.enabled && p.streaming.hosts.isNotEmpty()) ||
            // RomM moving this device's saves: its RomM Saves tab.
            (store.romm.supported && p.romm.enabled && p.romm.configured && p.romm.saveSync),
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

/** The tabs the top line shows, in its order: Home first, then the person's. */
internal fun AppState.shownTabs(): List<Destination> {
    val p = store.prefs.value
    val cartridge = store.cartridge.status.value
    return (listOf(Destination.HOME) + p.destinations.filter { it != Destination.HOME }).filter { sections.showsTab(it, offers(it), cartridge) }
}

/**
 * Picks [tab] up on the top line, to move along it in place (the D-pad, a finger or the mouse), as
 * Addons' tabs move. Home stays first, so it is never picked up. Null picks up the first that moves.
 */
internal fun AppState.liftTab(tab: Destination? = null) {
    val shown = shownTabs()
    val t = tab ?: shown.firstOrNull { it != Destination.HOME } ?: return
    if (t == Destination.HOME) {
        toasts.show("Home always comes first", icon = io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.Home)
        return
    }
    focusZone = FocusZone.TABS
    hudButton = null
    liftedTab = t
}

/** Moves the picked-up tab [by] places along the top line (never before Home). True when it moved. */
internal fun AppState.moveLiftedTab(by: Int): Boolean {
    val t = liftedTab ?: return false
    val shown = shownTabs()
    val at = shown.indexOf(t)
    val to = at + by
    if (at < 0 || to < 1 || to > shown.lastIndex) return false
    val next = shown.toMutableList().apply { add(to, removeAt(at)) }
    // Sections not shown here (not offered on this device) keep their places after the rest.
    store.updatePrefs { s -> s.copy(destinations = next + s.destinations.filter { it !in next }) }
    return true
}

/** Puts the picked-up tab down where it is. */
internal fun AppState.dropTab() {
    liftedTab = null
}

/** Which part of Addons is showing. */
enum class AddonsPart { CARTRIDGE, STORE, JELLYFIN, SYNC, SYNCTHING, STREAMING, ROMM_SAVES }

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
