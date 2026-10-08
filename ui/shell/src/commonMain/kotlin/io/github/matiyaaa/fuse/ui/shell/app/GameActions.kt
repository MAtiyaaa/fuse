package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.shell.reach.sendPicker

import io.github.matiyaaa.fuse.launch.patches.PatchState
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
import io.github.matiyaaa.fuse.ui.shell.romm.rommUpload
import io.github.matiyaaa.fuse.ui.shell.settings.canMoveGames
import io.github.matiyaaa.fuse.ui.shell.settings.moveGames
import io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.LaunchOutcome
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the launch veil stays when the game opened on the other screen, beside Fuse. */
internal const val SECOND_SCREEN_VEIL_MS = 900L

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

private fun AppState.launch(card: GameCard, emulator: io.github.matiyaaa.fuse.model.EmulatorId?, discPath: String?, display: io.github.matiyaaa.fuse.model.LaunchDisplay?, skipSaveCheck: Boolean = false, playAnyway: Boolean = false) {
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
    var job: kotlinx.coroutines.Job? = null
    // Bringing the save up to date can wait on the host: Back calls it off until the game starts.
    val callOff: () -> Unit = {
        if (launching?.cancel != null) {
            job?.cancel()
            launching = null
            toasts.show("${card.title} wasn't started", ToastKind.INFO)
        }
    }
    job = scope.launch {
        val outcome = store.library.launch(card.id, emulator, discPath, display, skipSaveCheck, playAnyway) { stage ->
            launching = when (stage) {
                io.github.matiyaaa.fuse.ui.shell.store.LaunchStage.CHECKING_SAVE -> launching?.copy(status = "Checking your save", cancel = callOff)
                io.github.matiyaaa.fuse.ui.shell.store.LaunchStage.STARTING -> launching?.copy(status = null, cancel = null)
            }
        }
        when (outcome) {
            LaunchOutcome.Started, is LaunchOutcome.Synced -> {
                if (outcome is LaunchOutcome.Synced) toasts.show(outcome.note, ToastKind.INFO, icon = FuseIcons.CloudCheck)
                // On the other screen the game opens beside Fuse, which stays in front here: the
                // veil only marks the moment. Otherwise the emulator covers Fuse, and the veil is
                // gone by the time Fuse is back.
                delay(if (display == io.github.matiyaaa.fuse.model.LaunchDisplay.SECONDARY) SECOND_SCREEN_VEIL_MS else 4_000L)
                launching = null
            }
            is LaunchOutcome.OpenedAppOnly -> {
                delay(600)
                launching = null
                toasts.show("${outcome.appName}: ${outcome.reason}", ToastKind.INFO, durationMs = 6000)
            }
            is LaunchOutcome.Problem -> {
                // A package that isn't installed yet installs now, then plays: no detour through its page.
                if (!skipSaveCheck && outcome.problem.actions.any { it is io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.InstallContent }) {
                    installThenPlay(card) { launching = null; launch(card, emulator, discPath, display) }
                    return@launch
                }
                launching = null
                showProblem(outcome.problem, card, retry = { launch(card, emulator, discPath, display) })
            }
            // Both sides played since they last synced: the person picks, then the game starts.
            is LaunchOutcome.SaveConflict -> {
                launching = null
                saveConflict = io.github.matiyaaa.fuse.ui.shell.sync.SaveConflictSpec(outcome.conflict) { launch(card, emulator, discPath, display, skipSaveCheck = true) }
            }
            // Another device is on this game, or still sending its save: wait for it, or play here.
            is LaunchOutcome.SyncBusy -> {
                launching = null
                syncBusy(card, outcome.busy, play = { launch(card, emulator, discPath, display, playAnyway = true) }, retry = { launch(card, emulator, discPath, display) })
            }
            // Syncthing kept two versions of the save: which one to play with, then the game starts.
            is LaunchOutcome.SyncthingConflict -> {
                launching = null
                syncthingConflict(card, outcome.conflicts) { launch(card, emulator, discPath, display, skipSaveCheck = true) }
            }
        }
    }
}

/**
 * Installs [card]'s package (with its updates and DLC) behind the launch veil, which says which step
 * it is on, then [play]s it. If it can't install by itself (a licence key to paste, an installer that
 * failed), its page opens with what it needs, and nothing is started.
 */
private suspend fun AppState.installThenPlay(card: GameCard, play: () -> Unit) {
    val content = store.content
    launching = launching?.copy(status = "Installing")
    val watching = scope.launch {
        content.progress.collect { p ->
            if (p != null && p.gameId == card.id) launching = launching?.copy(status = if (p.of > 1) "Installing ${p.step} of ${p.of}" else "Installing")
        }
    }
    val report = runCatching { content.install(card.id) }.getOrNull()
    watching.cancel()
    val ready = report != null && report.failed == null && !report.cancelled && content.view(card.id)?.plan?.gameInstalled == true
    if (ready) {
        toasts.show("${card.title} is installed", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
        play()
    } else {
        launching = null
        if (report?.cancelled != true) toasts.show(report?.message ?: "${card.title} needs a hand to install", ToastKind.WARNING)
        go(Route.GameContent(card.id))
    }
}

/**
 * Another device has [card] going, or just stopped and is still sending its save. Three cases, told
 * plainly: still playing right now (play here anyway, or not now), gone quiet mid-game (asleep or
 * switched off: play with the newest save the host has), or sending (wait a moment for it).
 */
private fun AppState.syncBusy(card: GameCard, busy: io.github.matiyaaa.fuse.sync.LaunchGate.Busy, play: () -> Unit, retry: () -> Unit) {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val quiet = busy.playing && now - busy.at > BUSY_QUIET_MS
    val last = busy.lastSave?.let { "Its newest save reached the host ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it, now)}." }
        ?: "None of its saves for this game have reached the host yet."
    fun waitForIt() {
        choice = null
        toasts.show("Waiting for ${busy.device} to send its save", ToastKind.INFO, icon = FuseIcons.Hourglass)
        scope.launch {
            // Looks again every few seconds; the game starts by itself once the save is in, or after a while regardless.
            val svc = store.sync.service
            repeat(BUSY_WAIT_TRIES) {
                delay(BUSY_WAIT_STEP_MS)
                val q = store.sync.saveQuery(card.id) ?: return@launch play()
                if (svc == null || svc.busyWith(q) == null) return@launch retry()
            }
            toasts.show("${busy.device} hasn't sent it yet. Playing with the newest save here.", ToastKind.WARNING)
            play()
        }
    }
    val options = when {
        busy.playing && !quiet -> listOf(
            MenuAction("here", "Play Here Anyway", FuseIcons.Play, detail = "Both will have a save; you'll pick one later", onSelect = { choice = null; play() }),
            MenuAction("not", "Not Now", FuseIcons.Close, onSelect = { choice = null }),
        )
        busy.playing -> listOf(
            MenuAction("here", "Play With the Newest Save", FuseIcons.Play, detail = last, onSelect = { choice = null; play() }),
            MenuAction("wait", "Wait for ${busy.device}", FuseIcons.Hourglass, detail = "Wake it and go back to Fuse there; its save comes over", onSelect = { waitForIt() }),
        )
        else -> listOf(
            MenuAction("wait", "Wait for It", FuseIcons.Hourglass, detail = "Starts by itself once the save is in", onSelect = { waitForIt() }),
            MenuAction("here", "Play Without It", FuseIcons.Play, detail = last, onSelect = { choice = null; play() }),
        )
    }
    choice = ChoiceSpec(
        title = when {
            busy.playing && !quiet -> "${busy.device} is playing ${card.title}"
            busy.playing -> "${busy.device} was playing ${card.title}"
            else -> "${busy.device} is sending its save"
        },
        icon = FuseIcons.CloudUpload,
        message = when {
            busy.playing && !quiet -> "It's being played there right now. $last"
            busy.playing -> "It went quiet ${io.github.matiyaaa.fuse.ui.shell.components.agoText(busy.at, now)} (asleep or switched off) before it stopped the game. $last"
            else -> "${card.title} just stopped there. Its save will be here in a moment."
        },
        options = options,
    )
}

private const val BUSY_QUIET_MS = 2 * 60_000L
private const val BUSY_WAIT_STEP_MS = 4_000L
private const val BUSY_WAIT_TRIES = 30

/**
 * Two versions of [card]'s save, kept by Syncthing when this device and another both changed it:
 * keep this device's, or take the other's. The one not kept goes to Syncthing's old versions, so
 * nothing is lost; then [play].
 */
private fun AppState.syncthingConflict(card: GameCard, conflicts: List<io.github.matiyaaa.fuse.sync.syncthing.SyncthingConflict>, play: () -> Unit) {
    val service = store.syncthing ?: return
    val first = conflicts.first()
    val other = first.device ?: "your other device"
    fun settle(keepThis: Boolean) {
        choice = null
        scope.launch {
            val failed = conflicts.map { service.resolve(it, keepThis) }.firstOrNull { it.isFailure }
            if (failed != null) {
                toasts.show(failed.exceptionOrNull()?.message ?: "Couldn't settle the two saves", io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.ERROR)
            } else {
                toasts.show(if (keepThis) "Kept this device's save" else "Using the save from $other", io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.SUCCESS)
                play()
            }
        }
    }
    choice = ChoiceSpec(
        title = "Two versions of ${card.title}'s save",
        icon = FuseIcons.GitCompare,
        message = "Syncthing kept both, because this device and $other each changed it. The one you don't pick stays in Syncthing's old versions.",
        options = listOf(
            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                "this", "Keep This Device's", FuseIcons.MonitorSmartphone,
                detail = "Saved ${io.github.matiyaaa.fuse.ui.shell.components.agoText(first.thisModified)}",
                onSelect = { settle(keepThis = true) },
            ),
            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                "other", "Use the One From $other", FuseIcons.RefreshCcw,
                detail = "Saved ${io.github.matiyaaa.fuse.ui.shell.components.agoText(first.otherModified)}" +
                    if (first.otherModified > first.thisModified) ", the newer one" else "",
                onSelect = { settle(keepThis = false) },
            ),
        ) + if (conflicts.size > 1) {
            listOf(io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("n", "${conflicts.size} files in all", FuseIcons.Layers, detail = conflicts.joinToString(", ") { it.name }, section = "", onSelect = {}))
        } else {
            emptyList()
        },
    )
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
        // Fuse Sync: this game's saves through time, from every device (a host keeps them).
        if (!card.isApp && syncProfile != null && store.prefs.value.sync.role.isNotEmpty()) {
            add(MenuAction("saves", "Save History", FuseIcons.History, detail = "Every version of its saves, from every device", trailing = Trailing.Chevron, onSelect = {
                closeOverlays(); go(Route.SaveHistory(card.id, card.title))
            }))
        }
        add(MenuAction("system", "System", FuseIcons.Layers, detail = if (card.isApp) "Android, or back to being an app" else "If it landed in the wrong one", trailing = Trailing.Value(card.platformShort), onSelect = { systemPicker(card) }))
        // An Android game always starts as its app.
        if (!card.isApp) add(MenuAction("emulator", "Emulator", FuseIcons.Chip, trailing = Trailing.Chevron, onSelect = { emulatorPicker(card) }))
        if (!card.isApp && card.platformId.value == "ps2") {
            add(MenuAction("patches", "PCSX2 Patches", FuseIcons.Bandage, detail = "Widescreen, 60 FPS and other fixes PCSX2 has for this game", trailing = Trailing.Chevron, onSelect = { pcsx2PatchPicker(card) }))
        }
        if (!card.isApp && card.platformId.value == "ps3") {
            add(MenuAction("compat", "How It Runs in RPCS3", FuseIcons.Gauge, detail = "From RPCS3's compatibility list. Asks rpcs3.net with the game's title id", trailing = Trailing.Chevron, onSelect = { showCompatibility(card) }))
        }
        // RPCS3 and Vita3K install packages (the game, updates, extra content) from their command line.
        if (!card.isApp && card.platformId.value in PACKAGE_SYSTEMS) {
            add(MenuAction("packages", "Installed Content", FuseIcons.PackageOpen, detail = "The game, its updates, DLC and licences, installed by Fuse", trailing = Trailing.Chevron, onSelect = { openGameContent(card) }))
        }
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
                scope.launch {
                    lib.rename(card.id, title.ifBlank { null })
                    // A corrected name brings the right game's details, not the old match's.
                    if (title.isNotBlank() && title.trim() != card.title && store.media.followRename(card.id, card.title)) {
                        toasts.show("Looking for details for ${title.trim()}")
                    }
                }
            }
        }))
        if (!card.isApp) {
            add(MenuAction("reset", "Reset Name and Details", FuseIcons.RotateCcw, detail = "For a game mixed up with another. Your own name and art stay", onSelect = {
                closeOverlays()
                confirm = ConfirmSpec(
                    "Reset ${card.title}?",
                    "Fuse forgets the name, details and art sources gave this game and goes back to its file name. Your own name and the art you chose stay. Find Details and Art looks again.",
                    "Reset",
                ) {
                    scope.launch {
                        if (store.media.resetDetails(card.id)) toasts.show("Reset. It goes by its file name again")
                    }
                }
            }))
        }
        // To an SD card or another drive, where there is one to write to.
        if (!card.isApp && card.unavailable == null && canMoveGames) {
            add(MenuAction("move", "Move to Another Drive", FuseIcons.FolderSync, detail = "To an SD card or another drive. Play time and art stay", trailing = Trailing.Chevron, onSelect = {
                closeOverlays()
                scope.launch {
                    val bytes = store.storage.size(card.id) ?: 0L
                    moveGames(listOf(card.id), listOf(card.title), bytes)
                }
            }))
        }
        // The Remote Library: to another of the household's devices (now, or once it is back).
        if (!card.isApp && card.unavailable == null && !card.missing && store.reach.state.value.supported) {
            add(MenuAction("reach.send", "Send to Another Device", FuseIcons.Share, detail = "Fetched by that device, from wherever is best", trailing = Trailing.Chevron, onSelect = {
                sendPicker(card.id, card.title)
            }))
        }
        if (!card.isApp) add(MenuAction("folder", "Folder Behaviour", FuseIcons.FolderOpen, trailing = Trailing.Chevron, onSelect = { folderPolicyPicker(card) }))
        // Fuse RomM, when it is the RomM integration in use: games not on RomM go up from here.
        if (store.prefs.value.romm.enabled && store.romm.supported && !card.isApp && card.rommRomId == null) {
            add(MenuAction("romm.upload", "Upload to RomM", FuseIcons.Upload, detail = "With its other discs, DLC and updates, through Downloads", onSelect = {
                rommUpload(card)
            }))
        }
        // Only while Cartridge support is on and Cartridge is installed.
        if (store.cartridge.status.value.installed && !store.prefs.value.romm.enabled) {
            if (card.rommRomId != null) {
                add(MenuAction("cartridge", "Open in Cartridge", FuseIcons.CloudDownload, onSelect = {
                    closeOverlays(); store.cartridge.open(CartridgeRoute.Game(card.rommRomId))
                }))
            } else {
                add(MenuAction("find", "Find in Cartridge", FuseIcons.CloudDownload, onSelect = {
                    // Cartridge finds a game by every word of the search, so only the name goes:
                    // "(USA, Europe) (SGB Enhanced)" in a file name would match nothing on RomM.
                    closeOverlays(); store.cartridge.open(CartridgeRoute.Search(io.github.matiyaaa.fuse.library.parse.SearchTitles.clean(card.title), card.platformId.value))
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
    contextMenu = null
    scope.launch {
        // Each emulator is checked against this game, so one that can't open it says why.
        val options = store.emulators.optionsForGame(card.id).ifEmpty { store.emulators.optionsFor(card.platformId) }
        choice = ChoiceSpec(
            icon = FuseIcons.Chip,
            title = "Emulator for ${card.title}",
            message = "The system's choice is used unless you pick one here.",
            options = listOf(
                MenuAction("default", "Use the system's emulator", FuseIcons.Layers, onSelect = {
                    scope.launch { store.library.setEmulator(card.id, null) }
                    choice = null
                }),
            ) + options.sortedBy { it.unavailable != null }.map { o ->
                val locate = !o.installed && store.emulators.canLocate
                MenuAction(
                    "e${o.id}", o.name, FuseIcons.Chip,
                    detail = if (locate) "Not found. Show Fuse where it is" else o.note ?: if (o.installed) null else "Not installed",
                    unavailableReason = if (locate) null else o.unavailable ?: if (o.installed) null else "Not installed on this device",
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
}

/**
 * An emulator's page: what it is and how Fuse found it, the systems it runs and is chosen for, how
 * well Fuse knows it, its limits, and actions (open it, try it with a game, its website, forget
 * where it was located).
 */
fun AppState.showEmulator(id: io.github.matiyaaa.fuse.model.EmulatorId) {
    scope.launch {
        val d = store.emulators.details(id) ?: return@launch
        val test = if (d.installed) store.emulators.testGame(id) else null
        val actions = buildList {
            if (d.installed) {
                add(MenuAction("open", "Open ${d.name}", FuseIcons.External, detail = "Its own settings, controls and firmware", onSelect = {
                    contextMenu = null
                    scope.launch { store.emulators.openEmulator(id) }
                }))
                add(MenuAction(
                    "test", if (test != null) "Try it with ${test.title}" else "Try it with a game", FuseIcons.Play,
                    detail = when {
                        test == null -> null
                        d.opensAppOnly -> "Opens ${d.name}; it doesn't take games from other apps"
                        else -> "Starts this game in ${d.name}, without changing which emulator it uses"
                    },
                    unavailableReason = if (test == null) "No game on its systems is in your library" else null,
                    onSelect = {
                        contextMenu = null
                        if (test != null) play(test, emulator = id)
                    },
                ))
            }
            add(MenuAction(
                "systems", if (d.systems.size == 1) d.systems.single() else "${d.systems.size} systems", FuseIcons.Chip,
                detail = when {
                    d.chosenFor.isNotEmpty() -> "Chosen for ${d.chosenFor.joinToString(", ")}"
                    d.systems.size in 2..8 -> d.systems.joinToString(", ")
                    else -> null
                },
                section = "Runs",
            ))
            add(MenuAction("support", "How Fuse starts it", if (d.opensAppOnly) FuseIcons.AppWindow else FuseIcons.Rocket, detail = if (d.opensAppOnly) "Opens the app; choose the game there" else d.support, section = "Runs"))
            d.limitations.forEachIndexed { i, l ->
                add(MenuAction("limit.$i", l, FuseIcons.Info, section = "Good to know"))
            }
            if (d.installed) {
                add(MenuAction(
                    "found", d.foundVia?.let { "Found via $it" } ?: "Installed", FuseIcons.Search,
                    detail = d.locatedAt ?: d.appId,
                    section = "On this device",
                ))
            }
            if (d.locatedAt != null) {
                add(MenuAction("forget", "Forget where it is", FuseIcons.Undo, detail = "Fuse looks for it again by itself", section = "On this device", onSelect = {
                    contextMenu = null
                    scope.launch { store.emulators.forget(id) }
                }))
            }
            d.homepage?.let { url ->
                add(MenuAction("site", "Website", FuseIcons.Globe, detail = url, trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron, section = "On this device", onSelect = {
                    contextMenu = null
                    platform.openUrl(url)
                }))
            }
        }
        openContextMenu(ContextMenuSpec(
            title = d.name + (d.version?.let { "  $it" } ?: ""),
            subtitle = if (d.installed) "Installed" else "Not installed on this device",
            icon = FuseIcons.Joystick,
            actions = actions,
        ))
    }
}

private val PACKAGE_SYSTEMS = setOf("ps3", "psvita", "3ds", "new-nintendo-3ds")

/**
 * The game's PCSX2 patches with a check for each that is on. Fuse turns on any that are off and
 * turns off only those it turned on; ones set in PCSX2 say so and stay as they are.
 */
fun AppState.pcsx2PatchPicker(card: GameCard) {
    contextMenu = null
    scope.launch {
        when (val list = store.library.pcsx2Patches(card.id)) {
            is io.github.matiyaaa.fuse.ui.shell.store.Pcsx2PatchList.Unavailable -> showProblem(Problem(
                list.title, list.reason, ProblemKind.EMULATOR, Severity.INFO, reassurance = null,
            ))
            is io.github.matiyaaa.fuse.ui.shell.store.Pcsx2PatchList.Ready -> {
                if (list.patches.isEmpty()) {
                    showProblem(Problem(
                        "No patches for ${card.title}",
                        "PCSX2 has none filed under ${list.serial} (CRC ${list.crc})" + if (list.bundledRead) "." else ", in its patches folder. Its bundled patches couldn't be read on this system; check the game's Patches tab in PCSX2.",
                        ProblemKind.EMULATOR, Severity.INFO, reassurance = null,
                    ))
                    return@launch
                }
                choice = ChoiceSpec(
                    icon = FuseIcons.Bandage,
                    title = "PCSX2 patches",
                    message = "${list.serial}, CRC ${list.crc}. Changes apply the next time the game starts in PCSX2. Fuse only turns off what it turned on.",
                    options = list.patches.map { s ->
                        val about = listOfNotNull(s.patch.description, s.patch.author?.let { "by $it" }).joinToString("  ·  ").ifEmpty { null }
                        MenuAction(
                            "patch.${s.patch.name}", s.patch.name, FuseIcons.Bandage,
                            detail = when (s.state) {
                                PatchState.ON_BY_FUSE -> listOfNotNull("On, turned on in Fuse", about).joinToString("  ·  ")
                                else -> about
                            },
                            trailing = Trailing.Check(s.on),
                            unavailableReason = when (s.state) {
                                PatchState.ON_IN_PCSX2 -> "On, turned on in PCSX2. Change it there"
                                PatchState.ON_FOR_ALL_GAMES -> "On for every game in PCSX2's settings"
                                PatchState.OFF_IN_PCSX2 -> "Turned off for this game in PCSX2"
                                else -> null
                            },
                            onSelect = {
                                scope.launch {
                                    val ok = store.library.setPcsx2Patch(card.id, s.patch.name, !s.on)
                                    if (!ok) toasts.show("Couldn't change PCSX2's settings for this game", ToastKind.WARNING)
                                    pcsx2PatchPicker(card)
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

/** What RPCS3's compatibility list says about [card], as a sheet with a link to its report. */
fun AppState.showCompatibility(card: GameCard) {
    contextMenu = null
    toasts.show("Asking RPCS3's compatibility list")
    scope.launch {
        val problem = when (val a = store.library.rpcs3Compatibility(card.id)) {
            is CompatibilityAnswer.Listed -> {
                val e = a.entry
                Problem(
                    title = "${e.status.label} in RPCS3",
                    message = e.status.meaning + (e.date?.let { " Last tested on $it, as ${e.titleId}." } ?: ""),
                    kind = ProblemKind.EMULATOR,
                    severity = when (e.status) {
                        io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Status.PLAYABLE -> Severity.HEALTHY
                        io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Status.INGAME -> Severity.INFO
                        io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Status.INTRO, io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Status.LOADABLE -> Severity.ATTENTION
                        io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Status.NOTHING -> Severity.BROKEN
                    },
                    reassurance = null,
                    actions = listOfNotNull(e.reportUrl?.let { io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.OpenLink(it, "Read the report") }),
                    details = "RPCS3 compatibility list, rpcs3.net/compatibility. Ratings are the RPCS3 team's; results depend on your settings and hardware.",
                )
            }
            CompatibilityAnswer.NoTitleId -> Problem(
                "No title id for ${card.title}",
                "RPCS3's list is searched by title id (like BLUS30443). Put it in the file or folder name, or keep the game as a folder with its PARAM.SFO.",
                ProblemKind.FILE, Severity.INFO, reassurance = null,
            )
            is CompatibilityAnswer.NotListed -> Problem(
                "${a.titleId} isn't on RPCS3's list",
                "Nobody has reported how this release runs yet. Other regions of the same game may be listed.",
                ProblemKind.EMULATOR, Severity.INFO, reassurance = null,
                actions = listOf(io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.OpenLink("https://rpcs3.net/compatibility?g=" + a.titleId, "Search the list")),
            )
            CompatibilityAnswer.Unreachable -> Problem(
                "Couldn't reach rpcs3.net",
                "Check your connection and try again.",
                ProblemKind.NETWORK, Severity.ATTENTION, reassurance = null,
                actions = listOf(io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.Retry()),
            )
        }
        showProblem(problem, card) { showCompatibility(card) }
    }
}

/** A PS3 or Vita game's packages, updates, DLC and licences, and installing them ([Route.GameContent]). */
fun AppState.openGameContent(card: GameCard) {
    closeOverlays()
    go(Route.GameContent(card.id))
}

/** Uses the licence at [path] for [pick], for this run of Fuse only, then goes back to the game's content. */
fun AppState.pickLicence(pick: LicencePick, path: String) {
    contentPicks["${pick.game.value}|${pick.contentId}"] = path
    navigator.pop()
    toasts.show("Licence chosen. Fuse checks it before installing", ToastKind.SUCCESS)
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
