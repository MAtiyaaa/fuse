package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.shell.cartridge.uploadToRomm
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.LaunchOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What confirming a game tile does: play it, or open its page when the user chose that. */
fun AppState.activateGame(card: GameCard) {
    if (store.prefs.value.openGamePage) go(Route.GameInfo(card.id)) else play(card)
}

/** The hint for confirming on a game tile, following [activateGame]. */
val AppState.gameConfirmLabel: String get() = if (store.prefs.value.openGamePage) "Open" else "Play"

/**
 * Starts a game. The launch veil appears immediately (so pressing Play always responds), the store
 * resolves the emulator and fires the launch, and anything other than a clean start is explained.
 */
fun AppState.play(card: GameCard, emulator: io.github.matiyaaa.fuse.model.EmulatorId? = null, discPath: String? = null) {
    if (launching != null) return
    // On two screens this may ask which one first; the veil only covers the launch itself.
    playOnChosenScreen(card) { display -> launch(card, emulator, discPath, display) }
}

private fun AppState.launch(card: GameCard, emulator: io.github.matiyaaa.fuse.model.EmulatorId?, discPath: String?, display: io.github.matiyaaa.fuse.model.LaunchDisplay?) {
    if (launching != null) return
    // The veil shows the room the game was lit by, with its own cover beside the title.
    val system = store.library.platforms.value.firstOrNull { it.platform.id == card.platformId }
    val room = card.room(system)
    launching = LaunchVeil(
        title = card.title,
        art = room.model,
        accent = card.accent,
        cover = card.art.square ?: card.art.boxart ?: card.art.icon,
        logo = card.art.logo?.takeIf { store.prefs.value.showLogo },
        system = system?.platform?.name,
        artFocusX = room.focusX,
        artFocusY = room.focusY,
        artBlurred = room.blurred,
    )
    platform.sounds.play(SoundCue.LAUNCH)
    scope.launch {
        when (val outcome = store.library.launch(card.id, emulator, discPath, display)) {
            LaunchOutcome.Started -> {
                // The veil lifts when Fuse is paused by the emulator; this is only a safety net.
                delay(4_000)
                launching = null
            }
            is LaunchOutcome.OpenedAppOnly -> {
                delay(600)
                launching = null
                toasts.show("${outcome.appName}: ${outcome.reason}", ToastKind.INFO, durationMs = 6000)
            }
            is LaunchOutcome.NeedsEmulator -> {
                launching = null
                platform.sounds.play(SoundCue.ERROR)
                choice = ChoiceSpec(
                    icon = FuseIcons.Chip,
                    title = "No emulator for ${outcome.platformName}",
                    message = if (outcome.suggestions.isEmpty()) {
                        "Install an emulator for this system, then come back. Fuse notices new apps automatically."
                    } else {
                        "Install one of these, then come back. Fuse notices new apps automatically."
                    },
                    options = outcome.suggestions.mapIndexed { i, s ->
                        MenuAction("s$i", s, FuseIcons.Package, onSelect = { choice = null })
                    } + MenuAction("ok", "OK", FuseIcons.Check, section = "", onSelect = { choice = null }),
                )
            }
            is LaunchOutcome.Failed -> {
                launching = null
                platform.sounds.play(SoundCue.ERROR)
                toasts.show(outcome.message, ToastKind.ERROR, durationMs = 6000)
            }
            is LaunchOutcome.Unsupported -> {
                launching = null
                platform.sounds.play(SoundCue.ERROR)
                toasts.show(outcome.message, ToastKind.WARNING, durationMs = 7000)
            }
        }
    }
}

/** Takes [card] off Continue Playing until it is played again. */
fun AppState.dismissFromContinue(card: GameCard) {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    store.updatePrefs { it.copy(continueDismissed = it.continueDismissed + (card.id.value.toString() to now)) }
    toasts.show("Removed from Continue playing")
}

/**
 * The options menu for a game (Context button, Select, or a long press). [extra] actions go right
 * after Play, for the shelf or list the game was opened from.
 */
fun AppState.gameMenu(card: GameCard, fromDetail: Boolean = false, extra: List<MenuAction> = emptyList()): ContextMenuSpec {
    val lib = store.library
    fun run(block: suspend () -> Unit) {
        closeOverlays()
        scope.launch { block() }
    }
    val actions = buildList {
        add(MenuAction("play", "Play", FuseIcons.Play, onSelect = { closeOverlays(); play(card) }))
        addAll(extra)
        add(MenuAction("media", "Manage Media", FuseIcons.Images, trailing = Trailing.Chevron, onSelect = {
            closeOverlays(); go(Route.Media(MediaOwner.OfGame(card.id), card.title))
        }))
        if (!fromDetail) add(MenuAction("info", "Game Info", FuseIcons.Info, onSelect = { closeOverlays(); go(Route.GameInfo(card.id)) }))
        add(
            MenuAction(
                "fav", if (card.favorite) "Remove from Favourites" else "Add to Favourites", FuseIcons.Heart,
                onSelect = { run { lib.setFavorite(card.id, !card.favorite) } },
            ),
        )
        if (store.prefs.value.collectionsEnabled) {
            add(MenuAction("collection", "Add to Collection", FuseIcons.ListPlus, trailing = Trailing.Chevron, onSelect = { collectionPicker(card.id, card.title) }))
        }
        add(MenuAction("pin", "Pin to Home", FuseIcons.Pin, onSelect = { run { lib.setPinned(card.id, true); toasts.show("Pinned to Home") } }))
        add(MenuAction("system", "System", FuseIcons.Layers, detail = if (card.isApp) "Android, or back to being an app" else "If it landed in the wrong one", trailing = Trailing.Value(card.platformShort), onSelect = { systemPicker(card) }))
        // An Android game always starts as its app.
        if (!card.isApp) add(MenuAction("emulator", "Emulator", FuseIcons.Chip, trailing = Trailing.Chevron, onSelect = { emulatorPicker(card) }))
        if (hasTwoScreens && !io.github.matiyaaa.fuse.launch.DualScreenPlatforms.usesSecondScreen(card.platformId)) {
            add(MenuAction("screen", "Screen", FuseIcons.DualScreen, detail = "Top, bottom, or ask when it starts", trailing = Trailing.Chevron, onSelect = { screenPicker(card) }))
        }
        add(MenuAction("rescrape", "Find Details and Art", FuseIcons.Wand, detail = "Fills what's missing, including a proper title. Your own art and names stay", onSelect = {
            closeOverlays()
            store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, game = card.id)
            toasts.show("Looking for details and art for ${card.title}")
        }))
        add(MenuAction("rename", "Rename Display Title", FuseIcons.TextCursor, detail = "The file keeps its name", onSelect = {
            closeOverlays()
            textInput = TextInputSpec("Display title", card.title) { title ->
                scope.launch { lib.rename(card.id, title.ifBlank { null }) }
            }
        }))
        if (!card.isApp) add(MenuAction("folder", "Folder Behaviour", FuseIcons.FolderOpen, trailing = Trailing.Chevron, onSelect = { folderPolicyPicker(card) }))
        // Only while Cartridge support is on and Cartridge is installed.
        if (store.cartridge.status.value.installed) {
            if (card.rommRomId != null) {
                add(MenuAction("cartridge", "Open in Cartridge", FuseIcons.CloudDownload, onSelect = {
                    closeOverlays(); store.cartridge.open(CartridgeRoute.Game(card.rommRomId))
                }))
            } else {
                add(MenuAction("find", "Find in Cartridge", FuseIcons.CloudDownload, onSelect = {
                    closeOverlays(); store.cartridge.open(CartridgeRoute.Search(card.title, null))
                }))
                // Games Cartridge downloaded are on RomM already, and apps have no files to send.
                if (!card.isApp) add(MenuAction("upload", "Upload to RomM", FuseIcons.Upload, detail = "Through Cartridge, with its other discs, DLC and updates", onSelect = { uploadToRomm(card) }))
            }
        }
        add(MenuAction("hide", "Hide", FuseIcons.EyeOff, onSelect = { run { lib.setHidden(card.id, true); toasts.show("Hidden. Show hidden games from Library options.") } }))
        if (card.isApp) {
            add(MenuAction("notgame", "Not a game", FuseIcons.AppWindow, detail = "Leaves your games and stays in Apps", onSelect = {
                run { lib.removeFromFuse(card.id); toasts.show("${card.title} is an app again. It's in Apps") }
            }))
        } else add(MenuAction("remove", "Remove from Fuse", FuseIcons.Trash, destructive = true, detail = "Your files are not touched", onSelect = {
            closeOverlays()
            confirm = ConfirmSpec(
                title = "Remove ${card.title} from Fuse?",
                message = "Fuse forgets this entry, its custom art and settings. The game's files stay exactly where they are. A rescan brings it back.",
                confirmLabel = "Remove from Fuse",
                destructive = true,
                onConfirm = { scope.launch { lib.removeFromFuse(card.id) } },
            )
        }))
    }
    return ContextMenuSpec(title = card.title, subtitle = card.platformShort, art = card.art.tile, actions = actions, accent = card.accent)
}

fun AppState.collectionPicker(game: GameId, title: String) {
    scope.launch {
        val cols = store.collections.collections.value
        val member = store.collections.membership(game)
        choice = ChoiceSpec(
            icon = FuseIcons.Bookmark,
            title = "Collections",
            message = title,
            options = cols.map { col ->
                val inIt = col.id in member
                MenuAction("c${col.id.value}", col.name, FuseIcons.Bookmark, trailing = Trailing.Check(inIt), onSelect = {
                    scope.launch {
                        if (inIt) store.collections.remove(col.id, game) else store.collections.add(col.id, game)
                        choice = null
                        toasts.show(if (inIt) "Removed from ${col.name}" else "Added to ${col.name}")
                    }
                })
            } + MenuAction("new", "New Collection", FuseIcons.Plus, onSelect = {
                choice = null
                textInput = TextInputSpec("New collection", "", "Collection name") { name ->
                    if (name.isNotBlank()) scope.launch {
                        val id = store.collections.create(name.trim())
                        store.collections.add(id, game)
                        toasts.show("Added to ${name.trim()}")
                    }
                }
            }),
        )
        contextMenu = null
    }
}

fun AppState.emulatorPicker(card: GameCard) {
    val options = store.emulators.optionsFor(card.platformId)
    contextMenu = null
    choice = ChoiceSpec(
        icon = FuseIcons.Chip,
        title = "Emulator for ${card.title}",
        message = "The platform's choice is used unless you pick one here.",
        options = listOf(
            MenuAction("default", "Use the platform's emulator", FuseIcons.Layers, onSelect = {
                scope.launch { store.library.setEmulator(card.id, null) }
                choice = null
            }),
        ) + options.map { o ->
            val locate = !o.installed && store.emulators.canLocate
            MenuAction(
                "e${o.id}", o.name, FuseIcons.Chip,
                detail = if (locate) "Not found. Show Fuse where it is" else o.note ?: if (o.installed) null else "Not installed",
                unavailableReason = if (o.installed || locate) null else "Not installed on this device",
                onSelect = {
                    if (locate) {
                        startLocate(LocateRequest(o.id, o.name, game = card.id))
                    } else {
                        scope.launch { store.library.setEmulator(card.id, o.id) }
                        choice = null
                    }
                },
            )
        },
    )
}

fun AppState.folderPolicyPicker(card: GameCard) {
    contextMenu = null
    fun pick(policy: FolderPolicy?) {
        scope.launch {
            store.library.setFolderPolicy(card.id, policy)
            toasts.show("Folder behaviour updated. Fuse rescans this system in the background.")
        }
        choice = null
    }
    choice = ChoiceSpec(
        icon = FuseIcons.FolderOpen,
        title = "Folder behaviour",
        message = "How Fuse treats this game's folder. Nothing on disk changes.",
        options = listOf(
            MenuAction("inherit", "Use the platform's setting", FuseIcons.Layers, onSelect = { pick(null) }),
            MenuAction("auto", "Automatic", FuseIcons.Sparkles, detail = "Decide from what's inside (RomM folders, disc sets, extracted games)", onSelect = { pick(FolderPolicy.AUTO) }),
            MenuAction("game", "Folder is the game", FuseIcons.Box, detail = "The emulator is given the folder, or the right file inside it", onSelect = { pick(FolderPolicy.FOLDER_AS_GAME) }),
            MenuAction("browse", "Open as a folder", FuseIcons.FolderOpen, detail = "Choose what to launch each time", onSelect = { pick(FolderPolicy.FOLDER_BROWSER) }),
            MenuAction("files", "Files only", FuseIcons.File, detail = "Every file inside is its own game", onSelect = { pick(FolderPolicy.FILE) }),
        ),
    )
}

/** Installs the downloaded Fuse update: restarts into it (Linux) or hands it to Android's installer. */
fun AppState.applyUpdate() {
    scope.launch {
        store.updates.apply()
            .onSuccess { restart -> if (restart) platform.restart() }
            .onFailure { toasts.show(it.message ?: "The update couldn't be installed") }
    }
}
