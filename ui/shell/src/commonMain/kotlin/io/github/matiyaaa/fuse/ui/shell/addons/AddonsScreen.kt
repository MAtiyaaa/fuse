package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.shell.app.AddonsPart
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.cartridge.CartridgeContent
import io.github.matiyaaa.fuse.ui.shell.components.COMPACT_TAB
import io.github.matiyaaa.fuse.ui.shell.components.CompactTab
import io.github.matiyaaa.fuse.ui.shell.components.CompactTabs
import io.github.matiyaaa.fuse.ui.shell.components.LocalSubTabs
import io.github.matiyaaa.fuse.ui.shell.components.SubTabsState
import io.github.matiyaaa.fuse.ui.shell.components.TabReorder
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs

/** Parts never moved by the user sort after the ones they placed. */
private const val ORDER_REST = 100

/** The height the Addons tabs take below the top line. */
private val TABS = 56.dp

/**
 * Addons: Cartridge (where it runs and is turned on), the Store (Android) and Jellyfin (once turned
 * on in Settings), as views of one section. Up from the top of any reaches the tabs, Left and Right
 * switch them, Down returns. It opens on Cartridge when Cartridge is installed, else on the Store,
 * else on Jellyfin, and remembers which was shown. With one part, Addons is just that part, without
 * tabs.
 */
@Composable
fun AddonsScreen(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val cartridge by app.store.cartridge.status.collectAsState()
    val store by app.store.appStore.state.collectAsState()
    // Cartridge only where it runs (Android and Linux) and is turned on; Jellyfin only once turned on.
    // In the order the user dragged them into; parts they never moved keep their usual place after.
    val parts = buildList {
        if (prefs.cartridgeEnabled && app.platform.features.cartridge) add(AddonsPart.CARTRIDGE)
        if (app.store.appStore.supported) add(AddonsPart.STORE)
        if (prefs.jellyfin.enabled && app.store.jellyfin != null) add(AddonsPart.JELLYFIN)
        if (isEmpty()) add(AddonsPart.CARTRIDGE)
    }.sortedBy { p -> prefs.addonsOrder.indexOf(p.name).let { if (it < 0) ORDER_REST + p.ordinal else it } }
    val part = app.addonsPart?.takeIf { it in parts }
        ?: AddonsPart.CARTRIDGE.takeIf { cartridge.installed && it in parts }
        ?: AddonsPart.STORE.takeIf { it in parts }
        ?: parts.first()
    var tabsFocused by remember { mutableStateOf(false) }
    val inTabs = tabsFocused && parts.size > 1 && app.focusZone == FocusZone.CONTENT
    // The tab picked up to move (held A on the tabs, or a finger or the mouse holding one).
    var lifted by remember { mutableStateOf<AddonsPart?>(null) }
    fun move(from: Int, to: Int) {
        if (from !in parts.indices || to !in parts.indices || from == to) return
        val next = parts.toMutableList().apply { add(to, removeAt(from)) }
        val names = next.map { it.name }
        // Parts not shown now (Jellyfin turned off) keep their saved place at the end.
        app.store.updatePrefs { p -> p.copy(addonsOrder = names + p.addonsOrder.filter { it !in names }) }
    }
    fun show(p: AddonsPart) {
        app.focusZone = FocusZone.CONTENT
        if (p != part) app.navigator.switchedView()
        app.addonsPart = p
    }

    PageEffect(inTabs, lifted) {
        if (!inTabs) return@PageEffect
        app.hints = if (lifted != null) listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Done"))
        else listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.HOLD_CONFIRM, "Hold to move"))
    }
    // Registered before the content's own layers, so it hears what they leave: Up from their top.
    InputLayer(
        enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen && parts.size > 1,
        longPress = inTabs && lifted == null,
    ) { e ->
        val carried = lifted
        if (inTabs && carried != null) {
            val at = parts.indexOf(carried)
            when (e.action) {
                NavAction.LEFT -> if (at > 0) { move(at, at - 1); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (at < parts.lastIndex) { move(at, at + 1); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT, NavAction.BACK, NavAction.REORDER, NavAction.DOWN -> { lifted = null; NavResult.ACTIVATED }
                else -> NavResult.BLOCKED
            }
        } else if (inTabs) {
            when (e.action) {
                NavAction.LEFT -> parts.getOrNull(parts.indexOf(part) - 1)?.let { show(it); NavResult.MOVED } ?: NavResult.BLOCKED
                NavAction.RIGHT -> parts.getOrNull(parts.indexOf(part) + 1)?.let { show(it); NavResult.MOVED } ?: NavResult.BLOCKED
                NavAction.DOWN, NavAction.SELECT -> { tabsFocused = false; NavResult.MOVED }
                NavAction.REORDER -> { lifted = part; NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        } else if (e.action == NavAction.UP) {
            tabsFocused = true
            NavResult.MOVED
        } else {
            NavResult.IGNORED
        }
    }
    // Leaving the tabs puts down whatever the controller held.
    LaunchedEffect(inTabs) { if (!inTabs) lifted = null }

    val entry = rememberReveal()
    val many = parts.size > 1
    // The full tabs, and the folded ones the page scrolls under: the page is clipped below the
    // folded ones and keeps the rest of the full tabs' room above its first row.
    val openTop = Size.hudHeight + TABS + Space.s
    val foldedTop = Size.hudHeight + COMPACT_TAB + Space.s * 2
    val tabs = remember(many) { SubTabsState(extraTop = if (many) openTop - foldedTop else 0.dp) }
    // Up into the tabs opens them again, wherever the page is.
    val fold by fuselineFloat(
        if (many && tabs.collapsed && !inTabs) 1f else 0f,
        Fuse.motion.focusSpring(),
        label = "fold",
    )
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSubTabs provides tabs) {
            key(part) {
                val top = if (many) foldedTop else Size.hudHeight
                when (part) {
                    AddonsPart.CARTRIDGE -> CartridgeContent(app, embedded = true, active = !inTabs, topPadding = top)
                    AddonsPart.STORE -> StoreContent(app, active = !inTabs, topPadding = top)
                    AddonsPart.JELLYFIN -> io.github.matiyaaa.fuse.ui.shell.jellyfin.JellyfinContent(app, active = !inTabs, topPadding = top)
                }
            }
        }
        if (many) {
            val items = parts.map { p ->
                when (p) {
                    AddonsPart.CARTRIDGE -> ViewTab("Cartridge", icon = FuseMarks.Cartridge, badge = (cartridge.activeDownloads + cartridge.queuedDownloads).takeIf { it > 0 }?.toString())
                    AddonsPart.STORE -> ViewTab("Store", icon = FuseIcons.Store, badge = store.updates.size.takeIf { it > 0 }?.toString())
                    AddonsPart.JELLYFIN -> ViewTab("Jellyfin", icon = FuseIcons.Clapperboard)
                }
            }
            // Open: the named tabs, which lift away and shrink toward the top left as the page scrolls.
            if (fold < 1f) {
                Column {
                    Spacer(Modifier.height(Size.hudHeight + Space.xs))
                    ViewTabs(
                        items = items,
                        active = parts.indexOf(part),
                        focused = parts.indexOf(part).takeIf { inTabs },
                        onSelect = { i -> show(parts[i]); tabsFocused = false },
                        reorder = TabReorder(
                            lifted = lifted?.let { parts.indexOf(it) }?.takeIf { it >= 0 && inTabs },
                            onMove = ::move,
                        ),
                        modifier = Modifier.reveal(entry, 0).graphicsLayer {
                            alpha = 1f - fold
                            val s = 1f - 0.45f * fold
                            scaleX = s
                            scaleY = s
                            translationY = -10.dp.toPx() * fold
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    )
                }
            }
            // Folded: just the icons, half the size, in a small pill where the tabs were.
            if (fold > 0f) {
                CompactTabs(
                    items = items.map { CompactTab(it.label, it.icon ?: FuseIcons.Store, it.badge) },
                    active = parts.indexOf(part),
                    onSelect = { i -> show(parts[i]); tabsFocused = false },
                    modifier = Modifier.padding(start = Space.gutter, top = Size.hudHeight + Space.s).graphicsLayer {
                        alpha = fold
                        val s = 0.7f + 0.3f * fold
                        scaleX = s
                        scaleY = s
                        translationY = 10.dp.toPx() * (1f - fold)
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                )
            }
        }
    }
}
