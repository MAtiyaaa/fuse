package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.isAchievements
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.offersToAdd
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import io.github.matiyaaa.fuse.model.PlatformId

/** Something that can be put on a board from its Add picker. */
internal class Addable(val key: String, val title: String, val icon: ImageVector, val widget: HomeWidget, val detail: String? = null)

/**
 * A board of items arranged by hand, with pages, like a phone's home screen: Home's widgets, or
 * the Systems page's systems. The board itself ([ChannelBoard], [BoardPages]) is the same for both
 * (moving and resizing by touch, mouse and controller, pages, Undo and Reset, the remove badge and
 * the Add tile); a space says where its arrangement is kept, what its items look like and do, and
 * what adding, removing and resetting mean for it. Each is kept with the rest of the settings, so it
 * follows the person's profile where settings do.
 */
internal abstract class BoardSpace {
    /** Kept with the route: where the selection and pages are remembered ("home", "systems"). */
    abstract val key: String

    /** What the board is called ("Home", "Systems"). */
    abstract val name: String

    /** What one item is called ("widget", "system"). */
    abstract val item: String

    /** The arrangement as kept in [p]. */
    abstract fun config(p: UiPrefs): HomeLayoutConfig

    /** [p] with [c] kept as the arrangement. */
    abstract fun keep(p: UiPrefs, c: HomeLayoutConfig): UiPrefs

    /** The items of [page] that show now (items switched off or hidden wait off the board). */
    abstract fun shown(c: HomeLayoutConfig, page: Int): List<HomeWidget>

    abstract fun title(w: HomeWidget): String

    /** Room above the board, under the top line. */
    open val top: Dp get() = Size.hudHeight

    /**
     * Columns of a board this [width] (dp); [narrow] for a phone held upright or a very thin screen,
     * [small] for a small screen (a handheld's, either way up).
     */
    abstract fun columns(narrow: Boolean, small: Boolean, width: Dp): Int

    /** A cell's height for cells [cellW] wide. */
    abstract fun cellHeight(cellW: Dp, narrow: Boolean): Dp

    /** Whether [w] turns through several things (a carousel), with the triggers. */
    open fun carousel(w: HomeWidget): Boolean = false

    @Composable
    abstract fun Face(w: HomeWidget, size: BoardSize, at: Int)

    /** Its glow when chosen. */
    @Composable
    abstract fun glow(w: HomeWidget, at: Int): Color

    /** The room behind the board while [w] is chosen (with [at] in front, for a carousel). */
    abstract fun hero(w: HomeWidget?, at: Int): HeroSource?

    /** Told whenever the chosen item changes, for whatever shows it beside the board. */
    open fun chosen(w: HomeWidget?) {}

    abstract fun open(w: HomeWidget, at: Int)

    /** Whether a first tap on [w] only chooses it (it plays a game), a second opening it. */
    open fun firstTapChooses(w: HomeWidget): Boolean = false

    /** What can be added to [page] now. */
    abstract fun addable(c: HomeLayoutConfig, page: Int): List<Addable>

    /** [c] with [a] added to [page]. */
    open fun add(c: HomeLayoutConfig, page: Int, a: Addable): HomeLayoutConfig {
        val list = c.boardWidgets(page)
        return c.withBoard(page, (list + a.widget.copy(order = list.size)).mapIndexed { i, w -> w.copy(order = i) })
    }

    /** [list] (a page's items) without [w]. */
    open fun removed(list: List<HomeWidget>, w: HomeWidget): List<HomeWidget> = list.filterNot { it.id == w.id }

    /** Saying how Reset works on [page], and doing it. */
    abstract fun resetTitle(page: Int): String
    abstract fun resetMessage(page: Int): String
    abstract fun resetLabel(page: Int): String
    abstract fun reset(page: Int)
    abstract fun resetDone(page: Int): String

    /** Whose arrangement this is when it follows a profile: this device's own (true), everyone's (false), or null where it can't be chosen. */
    open val own: Boolean? get() = null
    open fun setOwn(own: Boolean) {}

    /** What an empty page after the first says. */
    abstract val emptyPage: String

    /** What removing a page with things on it does, asked first. */
    abstract val removePageMessage: String

    /** The menu for [w] (or the board when null), with the board's own [actions] in it. */
    abstract fun menu(w: HomeWidget?, arranging: Boolean, actions: List<MenuAction>): ContextMenuSpec

    /** What the board's items read from their surroundings. */
    @Composable
    open fun Provide(content: @Composable () -> Unit) = content()
}

/** Home's widgets on the Channels board. */
internal class HomeSpace(
    private val app: AppState,
    private val prefs: UiPrefs,
    private val feed: HomeFeed,
    private val cartridge: CartridgeStatus,
    private val achievementsOn: Boolean,
    private val systems: Map<PlatformId, PlatformCard>,
) : BoardSpace() {
    override val key = "home"
    override val name = "Home"
    override val item = "widget"

    override fun config(p: UiPrefs) = p.home
    override fun keep(p: UiPrefs, c: HomeLayoutConfig) = p.copy(home = c)

    override fun shown(c: HomeLayoutConfig, page: Int) = c.boardWidgets(page).filter { w ->
        w.visible && app.offers(w.kind) &&
            (w.kind != WidgetKind.CARTRIDGE_DOWNLOADS || (cartridge.installed && prefs.cartridgeEnabled)) &&
            (w.kind != WidgetKind.COLLECTIONS || prefs.collectionsEnabled) &&
            (!w.kind.isAchievements || achievementsOn)
    }

    override fun title(w: HomeWidget) = w.kind.title()
    override fun columns(narrow: Boolean, small: Boolean, width: Dp) = if (narrow) 2 else 4

    // Never shorter than a handheld's cells, which every widget's face is made to fit; a small
    // screen scrolls the board rather than cut a widget's words off.
    override fun cellHeight(cellW: Dp, narrow: Boolean): Dp = (cellW * if (narrow) 0.86f else 0.6f).coerceIn(CELL_MIN, CELL_MAX)

    override fun carousel(w: HomeWidget) = w.kind.isCarousel

    @Composable
    override fun Face(w: HomeWidget, size: BoardSize, at: Int) = BoardFace(w.kind, size, feed, cartridge, prefs.clock24h)

    @Composable
    override fun glow(w: HomeWidget, at: Int): Color =
        shownGame(w.kind, at)?.accent?.toColor() ?: widgetTint(w.kind, feed, cartridge).let { if (it == Fuse.colors.text) Fuse.colors.accent else it }

    override fun hero(w: HomeWidget?, at: Int): HeroSource? {
        val game = w?.let { shownGame(it.kind, at) } ?: return null
        return game.room(systems[game.platformId])
    }

    private fun shownGame(kind: WidgetKind, at: Int) = boardGames(kind, feed).let { it.getOrNull(at) ?: it.firstOrNull() }

    override fun open(w: HomeWidget, at: Int) = app.openWidget(w.kind, feed, at)

    override fun firstTapChooses(w: HomeWidget) = w.kind in PLAY_WIDGETS

    override fun addable(c: HomeLayoutConfig, page: Int) = WidgetKind.entries
        .filter { k -> c.boardWidgets(page).none { it.kind == k } && app.offersToAdd(k) }
        .map { k -> Addable("add.$k", k.title(), widgetIcon(k), HomeWidget(k.name.lowercase(), k, 0)) }

    override fun resetTitle(page: Int) = if (page == 0) "Put Home back as it came?" else "Clear this page?"
    override fun resetMessage(page: Int) = if (page == 0) {
        "On this device, every widget returns to its first place and size, and widgets you added go" +
            (if (app.store.sync.inUse) ". Your Home on your other devices stays as it is." else ".") + " Undo brings your board back."
    } else {
        "Its widgets come off. The page stays, and Undo brings them back."
    }
    override fun resetLabel(page: Int) = if (page == 0) "Reset Home" else "Clear page"
    override fun reset(page: Int) {
        if (page == 0) {
            // This device's alone, and kept for Undo Home Reset in Settings and Fuse Sync too.
            app.store.resetHome { it.withBoard(page, HomeLayoutConfig.DefaultBoard) }
        } else {
            app.store.updatePrefs { p -> p.copy(home = p.home.withBoard(page, emptyList())) }
        }
    }
    override fun resetDone(page: Int) = if (page == 0) "Home is back as it came on this device" else "This page is clear"

    // With Fuse Sync and settings following a profile: whose Home this is, this device's or everyone's.
    override val own: Boolean? get() = prefs.sync.let { s -> if (app.syncProfile != null && s.settings) s.homeScope == "DEVICE" else null }
    override fun setOwn(own: Boolean) {
        app.scope.launch {
            app.store.sync.setOwnHome(own)
            app.toasts.show(if (own) "This Home is now this device's own. The profile's is kept for later" else "Home now follows you to every device")
        }
    }

    override val emptyPage = "Put the widgets you want together here: what you're playing, your systems, the time. The right stick or a swipe turns between pages."
    override val removePageMessage = "Its widgets come off Home with it. The games, apps and everything they show stay as they are."

    override fun menu(w: HomeWidget?, arranging: Boolean, actions: List<MenuAction>) = ContextMenuSpec(
        title = w?.kind?.title() ?: "Home",
        subtitle = "Home",
        actions = actions + if (arranging) emptyList() else app.homeStyleActions(),
    )

    @Composable
    override fun Provide(content: @Composable () -> Unit) {
        val time = rememberClockText(prefs.clock24h)
        val cartridgeIcon = remember { if (app.store.apps.supported) io.github.matiyaaa.fuse.ui.shell.store.AppIconModel(io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol.PACKAGE_NAME) else null }
        CompositionLocalProvider(
            LocalHomeTime provides time,
            LocalCartridgeIcon provides cartridgeIcon.takeIf { cartridge.installed },
            LocalSyncService provides app.store.sync.service.takeIf { prefs.sync.enabled },
            content = content,
        )
    }

    private companion object {
        /** The smallest and largest cell height, so widgets stay readable on a handheld and sane on a TV. */
        val CELL_MIN = 104.dp
        val CELL_MAX = 240.dp

        /** Widgets whose confirm plays a game, so a first tap only shows it. */
        val PLAY_WIDGETS = setOf(WidgetKind.CONTINUE_PLAYING, WidgetKind.RECENTLY_PLAYED, WidgetKind.PINNED_GAMES, WidgetKind.CURRENT_GAME)
    }
}

/** Home's board space as it stands now. */
@Composable
internal fun rememberHomeSpace(app: AppState): HomeSpace {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val feed by store.homeFeed.collectAsState()
    val cartridge by store.cartridge.status.collectAsState()
    val achievementsOn by store.achievements.configured.collectAsState()
    val systems = rememberSystems(app)
    return remember(prefs, feed, cartridge, achievementsOn, systems) { HomeSpace(app, prefs, feed, cartridge, achievementsOn, systems) }
}
