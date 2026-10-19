package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.SaveProvider
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import kotlinx.coroutines.launch

/**
 * Save Sync on this device: one of Fuse One, RomM and Syncthing moves its saves, RomM those of games
 * from RomM and Fuse One the rest, or none. Two moving the same game's save would fight over it, so
 * choosing one turns the others' save sync off here; every other device keeps its own choice.
 */
internal object SaveProviders {
    /** The ways this device can offer: Fuse One, RomM (and the two together) and Syncthing where Fuse has them, and Off. */
    fun offered(app: AppState): List<SaveProvider> = buildList {
        if (app.store.sync.service != null) add(SaveProvider.FUSE_ONE)
        if (app.store.romm.supported) add(SaveProvider.ROMM)
        if (app.store.sync.service != null && app.store.romm.supported) add(SaveProvider.ROMM_THEN_FUSE_ONE)
        if (app.store.syncthing != null) add(SaveProvider.SYNCTHING)
        add(SaveProvider.OFF)
    }

    fun icon(p: SaveProvider): ImageVector = when (p) {
        SaveProvider.FUSE_ONE -> FuseMarks.FuseOne
        SaveProvider.ROMM -> FuseIcons.Server
        SaveProvider.ROMM_THEN_FUSE_ONE -> FuseIcons.Layers2
        SaveProvider.SYNCTHING -> FuseIcons.FolderSync
        SaveProvider.OFF -> FuseIcons.CloudOff
    }

    fun detail(p: SaveProvider): String = when (p) {
        SaveProvider.FUSE_ONE -> "Fuse's own: each person's saves, play time and save history, from your host"
        SaveProvider.ROMM -> "Games from RomM keep their save and save states in step with RomM's"
        SaveProvider.ROMM_THEN_FUSE_ONE -> "RomM moves the saves of games from RomM, and Fuse One every other game's"
        SaveProvider.SYNCTHING -> "Your emulators' save folders, through the Syncthing you run"
        SaveProvider.OFF -> "Saves stay on this device. Your other devices keep syncing"
    }

    /** What this device uses now, read from its settings. */
    fun current(app: AppState): SaveProvider = SaveProvider.of(currentSettings(app))

    private fun currentSettings(app: AppState): AppSettings {
        val p = app.store.prefs.value
        return AppSettings(sync = app.store.sync.config.value, syncthing = p.syncthing.copy(enabled = app.store.syncthing?.state?.value.let { it != null && it !is io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.Off }), romm = p.romm)
    }
}

/** The row that shows how this device's saves move and opens the choice. */
internal fun saveProviderRow(app: AppState, section: String? = null, id: String = "saveprovider"): MenuAction {
    val now = SaveProviders.current(app)
    return MenuAction(
        id, "Save Sync", SaveProviders.icon(now),
        detail = when (now) {
            SaveProvider.OFF -> "Off on this device: saves stay here"
            SaveProvider.ROMM_THEN_FUSE_ONE -> "RomM moves the saves of games from RomM here, and Fuse One the rest. Never both for one game"
            else -> "Saves move with ${now.label} on this device. Only one moves saves at a time"
        },
        trailing = Trailing.Value(now.label), section = section,
        onSelect = { chooseSaveProvider(app) },
    )
}

/** The choice itself; [then] runs once one was chosen (setup moves on with it). */
internal fun chooseSaveProvider(app: AppState, then: (SaveProvider) -> Unit = {}) {
    val now = SaveProviders.current(app)
    app.choice = ChoiceSpec(
        title = "Save Sync on this device",
        icon = FuseIcons.RefreshCcw,
        message = "One of them moves this device's saves, or none. Your other devices keep their own choice.",
        options = SaveProviders.offered(app).map { p ->
            MenuAction(
                "provider.${p.name}", p.label, SaveProviders.icon(p),
                detail = SaveProviders.detail(p), trailing = Trailing.Check(p == now),
                onSelect = {
                    app.choice = null
                    if (p == now) { then(p); return@MenuAction }
                    app.scope.launch {
                        app.store.setSaveProvider(p)
                        app.toasts.show(chosenWords(app, p), ToastKind.SUCCESS)
                        then(p)
                    }
                },
            )
        },
    )
}

/** Said once a way is chosen, with what it still needs, if anything. */
private fun chosenWords(app: AppState, p: SaveProvider): String {
    val prefs = app.store.prefs.value
    return when (p) {
        SaveProvider.FUSE_ONE -> if (prefs.sync.enabled && prefs.sync.role.isNotEmpty()) "Fuse One moves this device's saves" else "Fuse One moves this device's saves once it's set up"
        SaveProvider.ROMM -> if (prefs.romm.enabled && prefs.romm.configured) "RomM moves this device's saves and save states for games from RomM" else "RomM moves this device's saves and save states once you sign in"
        SaveProvider.ROMM_THEN_FUSE_ONE -> if (prefs.romm.enabled && prefs.romm.configured) "RomM moves the saves of games from RomM, and Fuse One the rest" else "RomM moves the saves of games from RomM once you sign in, and Fuse One the rest"
        SaveProvider.SYNCTHING -> "Syncthing moves this device's save folders"
        SaveProvider.OFF -> "Save Sync is off on this device. Your other devices keep syncing"
    }
}

/** After choosing, where the chosen one is set up, when it isn't yet. */
internal fun setUpRoute(app: AppState, p: SaveProvider): Route? {
    val prefs = app.store.prefs.value
    return when (p) {
        SaveProvider.SYNCTHING -> Route.SyncthingSettings
        SaveProvider.ROMM, SaveProvider.ROMM_THEN_FUSE_ONE -> if (prefs.romm.enabled && prefs.romm.configured) null else Route.RommSetup(pairing = true)
        else -> null
    }
}
