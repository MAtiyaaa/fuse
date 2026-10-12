package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.data.settings.StreamPick
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlinx.coroutines.launch

/**
 * Moonlight as an emulator: a game (or every game of a system) can play by streaming it from a
 * computer at home instead of here. Its Play then wakes that computer when it sleeps and starts
 * Moonlight on the game's app there, as the Library's streaming shelf does. Nothing about the game
 * changes: choosing an emulator again plays it here.
 */
internal fun AppState.streamPickFor(card: GameCard): StreamPick? {
    val s = store.prefs.value.streaming
    if (!s.enabled || !store.streaming.supported) return null
    val pick = s.games[card.id.value.toString()] ?: s.systems[card.platformId.value]?.let { StreamPick(it) } ?: return null
    return pick.takeIf { p -> s.hosts.any { it.id == p.host } }
}

/** Streams [card] as [pick] says: its app there, or (none named) the app named as the game, or one chosen now. */
internal fun AppState.streamInstead(card: GameCard, pick: StreamPick) {
    val host = store.prefs.value.streaming.hosts.firstOrNull { it.id == pick.host } ?: return
    val app = pick.app.ifBlank { host.apps.firstOrNull { it.equals(card.title, ignoreCase = true) }.orEmpty() }
    if (app.isNotBlank()) {
        store.streaming.stream(host.id, app)
        return
    }
    // The computer names its apps its own way: chosen once, then kept for this game.
    choice = ChoiceSpec(
        title = "Which app on ${host.name} is ${card.title}?",
        icon = FuseIcons.Cast,
        message = "Fuse keeps the choice for this game. The apps are the ones entered for ${host.name} in Settings, Addons, Streaming.",
        options = host.apps.map { a ->
            MenuAction("app.$a", a, FuseIcons.Play, onSelect = {
                choice = null
                setStreamPick(card, StreamPick(host.id, a))
                store.streaming.stream(host.id, a)
            })
        } + MenuAction("app.new", "Type Its Name", FuseIcons.Keyboard, detail = "As ${host.name} lists it in Sunshine or Apollo", onSelect = {
            choice = null
            textInput = TextInputSpec("The app's name on ${host.name}", card.title, card.title) { typed ->
                val name = typed.trim()
                if (name.isEmpty()) return@TextInputSpec
                scope.launch {
                    runCatching { store.streaming.changeHost(host.id) { h -> if (name in h.apps) h else h.copy(apps = h.apps + name) } }
                }
                setStreamPick(card, StreamPick(host.id, name))
                store.streaming.stream(host.id, name)
            }
        }),
    )
}

/** Keeps [pick] as how [card] plays (null: here again, with its emulator). */
internal fun AppState.setStreamPick(card: GameCard, pick: StreamPick?) {
    val key = card.id.value.toString()
    store.updatePrefs { p ->
        val games = if (pick == null) p.streaming.games - key else p.streaming.games + (key to pick)
        p.copy(streaming = p.streaming.copy(games = games))
    }
}

/** Keeps [hostId] as where every game of [platform] streams from (null: they play here). */
internal fun AppState.setSystemStream(platform: PlatformId, hostId: String?) {
    store.updatePrefs { p ->
        val systems = if (hostId == null) p.streaming.systems - platform.value else p.streaming.systems + (platform.value to hostId)
        p.copy(streaming = p.streaming.copy(systems = systems))
    }
}

/** The "stream from" choices for an emulator picker: one per computer, while streaming is on. */
internal fun AppState.streamChoices(chosenHost: String?, onPick: (String) -> Unit): List<MenuAction> {
    val s = store.prefs.value.streaming
    if (!s.enabled || !store.streaming.supported) return emptyList()
    return s.hosts.map { h ->
        MenuAction(
            "stream.${h.id}", "Moonlight, from ${h.name}", FuseIcons.Cast,
            detail = if (store.streaming.client == null) "Get Moonlight first: it streams the game from ${h.name}" else "Plays on ${h.name} and streams here, waking it when it sleeps",
            unavailableReason = if (store.streaming.client == null) "Moonlight isn't installed" else null,
            trailing = if (h.id == chosenHost) Trailing.Check(true) else Trailing.None,
            onSelect = { onPick(h.id) },
        )
    }
}
