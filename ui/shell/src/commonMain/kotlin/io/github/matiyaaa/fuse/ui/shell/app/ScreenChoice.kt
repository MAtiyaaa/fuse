package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.launch.DualScreenPlatforms
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SettingScope
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How long a screen picked at launch is kept: just this time, for this game or app, or for its whole group. */
enum class ScreenMemory { ONCE, ITEM, GROUP }

/** A second screen that games and apps can open on. */
val AppState.hasTwoScreens: Boolean get() = platform.features.launchOnOtherDisplay

/** What the screens are called: the main one is the top screen on dual-screen handhelds. */
fun screenName(display: LaunchDisplay): String = when (display) {
    LaunchDisplay.PRIMARY -> "Top screen"
    LaunchDisplay.SECONDARY -> "Bottom screen"
    LaunchDisplay.ASK -> "Ask each time"
}

/**
 * Asks which screen to open [subject] on ([ScreenPromptOverlay]): the two screens side by side, and
 * ticks to keep the answer for [itemLabel] or [groupLabel].
 */
fun AppState.askScreen(
    verb: String,
    subject: String,
    art: Any?,
    accent: Long,
    itemLabel: String,
    groupLabel: String,
    onPick: (LaunchDisplay, ScreenMemory) -> Unit,
) {
    screenPrompt = ScreenPromptSpec(verb, subject, art, accent, itemLabel, groupLabel, onPick)
}

/**
 * Plays [card] on the screen its settings name, asking first when they say to ask and the device
 * has two screens. Games that draw on both screens (DS, 3DS, Wii U) never ask.
 */
internal fun AppState.playOnChosenScreen(card: GameCard, start: (LaunchDisplay?) -> Unit) {
    if (!hasTwoScreens || DualScreenPlatforms.usesSecondScreen(card.platformId)) {
        start(null)
        return
    }
    scope.launch {
        val stored = store.settings.observe(ScopedSettings.LaunchScreen, card.platformId, card.id).first().value
        if (stored != LaunchDisplay.ASK) {
            start(stored)
            return@launch
        }
        // A long system name gives way to its short one, so the tick fits.
        val system = store.library.platforms.value.firstOrNull { it.platform.id == card.platformId }?.platform?.name
            ?.takeIf { it.length <= 18 } ?: card.platformShort
        askScreen("Play", card.title, card.art.hero ?: card.art.tile, card.accent, "Always for this game", "Always for $system") { display, memory ->
            when (memory) {
                ScreenMemory.ONCE -> Unit
                ScreenMemory.ITEM -> scope.launch { store.settings.set(ScopedSettings.LaunchScreen, ScopeRef.game(card.id), display) }
                ScreenMemory.GROUP -> scope.launch { store.settings.set(ScopedSettings.LaunchScreen, ScopeRef.platform(card.platformId), display) }
            }
            start(display)
        }
    }
}

/** Opens an app on the screen its settings name, asking first when they say to ask and there are two. */
fun AppState.openApp(app: AppCard) {
    if (!hasTwoScreens) {
        scope.launch { store.apps.launch(app) }
        return
    }
    val prefs = store.prefs.value.display
    val stored = prefs.appScreens[app.entry.id] ?: prefs.appScreen
    if (stored != LaunchDisplay.ASK) {
        scope.launch { store.apps.launch(app, stored) }
        return
    }
    askScreen("Open", app.entry.displayTitle, app.icon, ACCENT_APPS, "Always for this app", "Always for all apps") { display, memory ->
        when (memory) {
            ScreenMemory.ONCE -> Unit
            ScreenMemory.ITEM -> store.updatePrefs { it.copy(display = it.display.copy(appScreens = it.display.appScreens + (app.entry.id to display))) }
            ScreenMemory.GROUP -> store.updatePrefs { it.copy(display = it.display.copy(appScreen = display)) }
        }
        scope.launch { store.apps.launch(app, display) }
    }
}

/** Game options, Screen: where this game opens, or its system's setting. */
fun AppState.screenPicker(card: GameCard) {
    closeOverlays()
    scope.launch {
        val resolved = store.settings.observe(ScopedSettings.LaunchScreen, card.platformId, card.id).first()
        val own = resolved.value.takeIf { resolved.from == SettingScope.GAME && !resolved.isDefault }
        val system = store.library.platforms.value.firstOrNull { it.platform.id == card.platformId }?.platform?.name ?: card.platformShort
        fun pick(value: LaunchDisplay?) {
            choice = null
            scope.launch {
                if (value == null) store.settings.clear(ScopedSettings.LaunchScreen, ScopeRef.game(card.id))
                else store.settings.set(ScopedSettings.LaunchScreen, ScopeRef.game(card.id), value)
            }
        }
        choice = ChoiceSpec(
            title = "Open ${card.title} on",
            options = listOf(
                MenuAction("inherit", "Use the $system setting", FuseIcons.Layers, trailing = Trailing.Check(own == null), onSelect = { pick(null) }),
            ) + LaunchDisplay.entries.map { d ->
                MenuAction("d.${d.name}", screenName(d), screenIcon(d), trailing = Trailing.Check(own == d), onSelect = { pick(d) })
            },
        )
    }
}

/** App options, Screen: where this app opens, or the setting for all apps. */
fun AppState.appScreenPicker(app: AppCard) {
    closeOverlays()
    val own = store.prefs.value.display.appScreens[app.entry.id]
    fun pick(value: LaunchDisplay?) {
        choice = null
        store.updatePrefs {
            val screens = if (value == null) it.display.appScreens - app.entry.id else it.display.appScreens + (app.entry.id to value)
            it.copy(display = it.display.copy(appScreens = screens))
        }
    }
    choice = ChoiceSpec(
        title = "Open ${app.entry.displayTitle} on",
        options = listOf(
            MenuAction("inherit", "Use the setting for all apps", FuseIcons.Layers, trailing = Trailing.Check(own == null), onSelect = { pick(null) }),
        ) + LaunchDisplay.entries.map { d ->
            MenuAction("d.${d.name}", screenName(d), screenIcon(d), trailing = Trailing.Check(own == d), onSelect = { pick(d) })
        },
    )
}

/** The glow an app's screen gets (apps have no system colour). */
private const val ACCENT_APPS = 0xFF7C8CFF

fun screenIcon(display: LaunchDisplay) = when (display) {
    LaunchDisplay.PRIMARY -> FuseIcons.PanelTop
    LaunchDisplay.SECONDARY -> FuseIcons.PanelBottom
    LaunchDisplay.ASK -> FuseIcons.DualScreen
}
