package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.PlatformId

/** Every place the interface can show. Roots are the tabs; the rest are pushed on top. */
sealed interface Route {
    data class Root(val destination: Destination) : Route
    data class PlatformGames(val platform: PlatformId) : Route
    data class CollectionGames(val collection: CollectionId, val name: String) : Route
    data object Collections : Route
    data object Storage : Route
    /** Phone Link pairing: the QR code, sign-in and signed-in phones. */
    data object PhoneLink : Route
    data class GameInfo(val game: GameId) : Route
    /** Manage media; [identify] opens Identify game straight away (a game a fill couldn't name). */
    data class Media(val owner: MediaOwner, val title: String, val identify: Boolean = false) : Route
    /** Settings at [section], with the row labelled [row] chosen (from search). */
    data class Settings(val section: String? = null, val row: String? = null) : Route
    data class PlatformSettings(val platform: PlatformId) : Route
    data object Search : Route
    data object Controls : Route
    data object Licenses : Route

    /**
     * Release notes as a page: Fuse [version] ([name], its codename) over the parts of its notes.
     * [installed] is the version running now; otherwise it is an update on offer.
     */
    data class ReleaseNotes(val version: String, val name: String?, val markdown: String, val installed: Boolean) : Route

    /** An app's page in the Store, by its key in the catalogue. */
    data class StoreApp(val key: String) : Route

    /** Jellyfin: its settings, an item's page, a library's grid, and Jellyfin's own search. */
    data object JellyfinSettings : Route
    data class MediaPage(val id: String) : Route
    data class MediaLibrary(val id: String, val name: String, val kind: String? = null) : Route
    data object MediaSearch : Route

    /** Fuse Sync by Fuse: its settings, setting it up ([host]: this computer as the host, else connecting), and a game's saves through time. */
    data object SyncSettings : Route
    data object SyncthingSettings : Route

    /** Where each emulator keeps its saves here, and a folder to choose where Fuse can't find them (Fuse Sync and Syncthing). */
    data object SaveFolders : Route
    data class SyncSetup(val host: Boolean) : Route
    data class SaveHistory(val game: GameId, val title: String) : Route

    /** One game as the Fuse Sync host keeps it: play time by device, every save, version and file. */
    data class SyncGame(val game: String, val name: String) : Route

    /** Downloads: every transfer Fuse makes for the person, in one place (the top line's Downloads button). */
    data object Downloads : Route

    /** Jellyfin films and episodes kept on this device: play, delete, move to another drive. */
    data object OfflineMedia : Route

    /** Fuse RomM's settings (Settings, Addons, Fuse RomM) and its setup ([pairing]: straight to signing in). */
    data object RommSettings : Route
    data class RommSetup(val pairing: Boolean = false) : Route

    /** A system's games on the RomM server, or a collection's. */
    data class RommGames(val slug: String?, val name: String, val collection: String? = null) : Route

    /** The Remote Library through Fuse Sync: other devices' games, recently added and by system. */
    data object HouseholdLibrary : Route

    /** One system's games on the household's other devices (every system's when [platform] is null). */
    data class HouseholdGames(val platform: io.github.matiyaaa.fuse.model.PlatformId?, val name: String) : Route

    /** Where play time went: today, this week, this month, per day, per game and per system. */
    data object PlayTime : Route
    data object Themes : Route
    data object Onboarding : Route
    data class FolderBrowser(val game: GameId) : Route

    /** A PS3 or Vita game's packages, updates, DLC and licences, and installing them into its emulator. */
    data class GameContent(val game: GameId) : Route

    /** Fuse's own file picker, for "Add a game", for locating an emulator ([locate]) and for a licence ([licence]). */
    data class PickFile(val purpose: FilePurpose, val locate: LocateRequest? = null, val licence: LicencePick? = null) : Route
}

/** What a file is picked for. */
enum class FilePurpose { APK, GAME, EMULATOR, THEME, LICENCE }

/** A licence to pick for [contentId] of [game] (a PS3 `.rap`, or a Vita `.rif`, `work.bin` or file with its zRIF). */
data class LicencePick(val game: GameId, val contentId: String, val vita: Boolean)

/**
 * An emulator to locate, and what then uses it: a system ([platform]) or a game ([game]) the user
 * was choosing an emulator for.
 */
data class LocateRequest(val emulator: EmulatorId, val name: String, val platform: PlatformId? = null, val game: GameId? = null)

/** Which way the last navigation went, so transitions can move forward or reverse. */
enum class NavDirection { FORWARD, BACK, LATERAL }

/**
 * A simple, explicit back stack. Selection state for each route is kept in [memory] so returning to
 * a page (back from Game Info, or switching tabs) restores exactly where you were.
 */
@Stable
class Navigator(start: Route) {
    val stack = mutableStateListOf(start)
    var direction by mutableStateOf(NavDirection.LATERAL)
        private set

    val current: Route get() = stack.last()
    val root: Route.Root? get() = stack.first() as? Route.Root

    private val memory = LinkedHashMap<String, Any>()

    /**
     * Off by the user's choice (Settings, Home, Remember where you were): switching tabs forgets
     * where you were in each, so every tab opens at its start.
     */
    var forgetsTabs = false

    fun push(route: Route) {
        if (current == route) return
        direction = NavDirection.FORWARD
        stack.add(route)
    }

    /** Returns false at a root, so the caller can decide what Back means there. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        direction = NavDirection.BACK
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun selectRoot(destination: Destination) {
        val target = Route.Root(destination)
        if (stack.size == 1 && stack[0] == target) return
        direction = NavDirection.LATERAL
        if (forgetsTabs && root != target) memory.clear()
        stack.clear()
        stack.add(target)
    }

    fun replace(route: Route) {
        direction = NavDirection.LATERAL
        stack.clear()
        stack.add(route)
    }

    /**
     * A view inside a tab was switched (Addons' Store and Jellyfin): with Remember where you were
     * off, it opens at its start too.
     */
    fun switchedView() {
        if (forgetsTabs) memory.clear()
    }

    /** Drops what was remembered for [key], so the route starts fresh next time. */
    fun forget(key: String) {
        memory.remove(key)
    }

    /** Remembered per-route UI state (selections, scroll anchors). Bounded so memory stays small. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> remembered(key: String, create: () -> T): T {
        val existing = memory.remove(key)
        val value = (existing as? T) ?: create()
        memory[key] = value
        if (memory.size > 64) memory.remove(memory.keys.first())
        return value
    }
}

/** Per-route state that survives leaving and returning to the route. */
@Composable
fun <T : Any> rememberRouteState(navigator: Navigator, key: String, create: () -> T): T =
    remember(key) { navigator.remembered(key, create) }

/**
 * Like [rememberRouteState], but opening the route anew (a forward push) starts fresh. Only Back
 * returns to where you were.
 */
@Composable
fun <T : Any> rememberPageState(navigator: Navigator, key: String, create: () -> T): T =
    remember(key) {
        if (navigator.direction == NavDirection.FORWARD) navigator.forget(key)
        navigator.remembered(key, create)
    }
