package io.github.matiyaaa.fuse.ui.shell.sync

import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import kotlinx.coroutines.launch

/**
 * How one game's saves sync, for Fuse One and RomM alike: everywhere (the default), received here
 * but never sent from here, off on this device, or off on every device of the person's (that one
 * travels with their profile).
 */
enum class SaveSyncMode(val label: String, val detail: String) {
    EVERYWHERE("Everywhere", "Its saves go to and come from your other devices"),
    RECEIVE_ONLY("Receive Only Here", "This device gets the newest save but never sends its own"),
    OFF_HERE("Off on This Device", "Its saves stay here: never sent, never received"),
    OFF_EVERYWHERE("Off Everywhere", "None of your devices sync its saves");

    companion object {
        fun of(s: SyncSettings, game: String): SaveSyncMode = when (game) {
            in s.offEverywhereGames -> OFF_EVERYWHERE
            in s.localOnlyGames -> OFF_HERE
            in s.receiveOnlyGames -> RECEIVE_ONLY
            else -> EVERYWHERE
        }
    }

    /** [s] with [game] set to this mode (and no other). */
    fun applyTo(s: SyncSettings, game: String): SyncSettings = s.copy(
        localOnlyGames = (s.localOnlyGames - game) + listOfNotNull(game.takeIf { this == OFF_HERE }),
        receiveOnlyGames = (s.receiveOnlyGames - game) + listOfNotNull(game.takeIf { this == RECEIVE_ONLY }),
        offEverywhereGames = (s.offEverywhereGames - game) + listOfNotNull(game.takeIf { this == OFF_EVERYWHERE }),
    )

    /** Whether saves come to this device. */
    val receives: Boolean get() = this == EVERYWHERE || this == RECEIVE_ONLY

    /** Whether this device sends its saves. */
    val sends: Boolean get() = this == EVERYWHERE
}

/** The row for [query]'s save sync, showing how it syncs now; selecting it offers the four ways. */
internal fun saveSyncRow(app: AppState, query: SaveQuery, title: String, settings: SyncSettings): MenuAction {
    val mode = SaveSyncMode.of(settings, query.game.id)
    return MenuAction(
        "savesync", "Save Sync", if (mode == SaveSyncMode.EVERYWHERE) FuseIcons.RefreshCcw else FuseIcons.CloudOff,
        detail = mode.detail,
        trailing = Trailing.Value(mode.label),
        onSelect = { saveSyncChoice(app, query, title) },
    )
}

/** The four ways [query]'s saves can sync, the current one checked. */
internal fun saveSyncChoice(app: AppState, query: SaveQuery, title: String) {
    val now = SaveSyncMode.of(app.store.sync.config.value, query.game.id)
    app.choice = ChoiceSpec(
        title = "Save Sync",
        icon = FuseIcons.RefreshCcw,
        message = "How $title's saves move between your devices, through Fuse One and RomM.",
        options = SaveSyncMode.entries.map { m ->
            MenuAction("mode.${m.name}", m.label, if (m == SaveSyncMode.EVERYWHERE) FuseIcons.RefreshCcw else FuseIcons.CloudOff, detail = m.detail, trailing = Trailing.Check(m == now), onSelect = {
                app.choice = null
                if (m == now) return@MenuAction
                app.scope.launch {
                    app.store.sync.configure { c -> m.applyTo(c, query.game.id) }
                    app.toasts.show(if (m == SaveSyncMode.EVERYWHERE) "$title's saves sync again" else "$title: ${m.label.lowercase()}", ToastKind.SUCCESS)
                }
            })
        },
    )
}
