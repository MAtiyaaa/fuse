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
import kotlinx.coroutines.flow.first
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
    // Moonlight as its emulator: the game plays on a computer at home and streams here.
    if (emulator == null && discPath == null) streamPickFor(card)?.let { streamInstead(card, it); return }
    // On two screens this may ask which one first; the veil only covers the launch itself.
    playOnChosenScreen(card) { display -> launch(card, emulator, discPath, display) }
}

/** Starts [card] to be played with others: hosting it here, or joining someone's game ([together]). */
internal fun AppState.playTogether(card: GameCard, together: io.github.matiyaaa.fuse.ui.shell.store.Together) {
    if (launching != null) return
    choice = null
    launch(card, null, null, null, together = together)
}

private fun AppState.launch(card: GameCard, emulator: io.github.matiyaaa.fuse.model.EmulatorId?, discPath: String?, display: io.github.matiyaaa.fuse.model.LaunchDisplay?, skipSaveCheck: Boolean = false, playAnyway: Boolean = false, together: io.github.matiyaaa.fuse.ui.shell.store.Together? = null) {
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
        val outcome = store.library.launch(card.id, emulator, discPath, display, skipSaveCheck, playAnyway, together) { stage ->
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
                    installThenPlay(card) { launching = null; launch(card, emulator, discPath, display, together = together) }
                    return@launch
                }
                launching = null
                showProblem(outcome.problem, card, retry = { launch(card, emulator, discPath, display, together = together) })
            }
            // Both sides played since they last synced: the person picks, then the game starts.
            is LaunchOutcome.SaveConflict -> {
                launching = null
                saveConflict = io.github.matiyaaa.fuse.ui.shell.sync.SaveConflictSpec(outcome.conflict) { launch(card, emulator, discPath, display, skipSaveCheck = true, together = together) }
            }
            // Another device is on this game, or still sending its save: wait for it, or play here.
            is LaunchOutcome.SyncBusy -> {
                launching = null
                syncBusy(card, outcome.busy, play = { launch(card, emulator, discPath, display, playAnyway = true, together = together) }, retry = { launch(card, emulator, discPath, display, together = together) })
            }
            is LaunchOutcome.SyncWaiting -> {
                launching = null
                syncWaiting(card, outcome.waiting, play = { launch(card, emulator, discPath, display, playAnyway = true, together = together) }, retry = { launch(card, emulator, discPath, display, together = together) })
            }
            // This device's save and RomM's both changed: which one to play with, then the game starts.
            is LaunchOutcome.RommSaveConflict -> {
                launching = null
                rommSaveConflict(card, outcome.conflict) { launch(card, emulator, discPath, display, together = together) }
            }
            // Syncthing kept two versions of the save: which one to play with, then the game starts.
            is LaunchOutcome.SyncthingConflict -> {
                launching = null
                syncthingConflict(card, outcome.conflicts) { launch(card, emulator, discPath, display, skipSaveCheck = true, together = together) }
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

/**
 * A newer save of [card] than this device's is known of and isn't here: still coming from a slow
 * host, or waiting on Fuse One while this device is offline. The game never starts quietly with the
 * older save: wait (the save keeps coming meanwhile), play with this device's save (the newer one
 * stays safe, and the person picks between them later), or not now.
 */
private fun AppState.syncWaiting(card: GameCard, waiting: io.github.matiyaaa.fuse.sync.LaunchGate.Waiting, play: () -> Unit, retry: () -> Unit) {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val made = waiting.at?.let { " It was made ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it, now)}." }.orEmpty()
    val whose = waiting.from?.let { "Your save from $it" } ?: "Your newest save"
    choice = ChoiceSpec(
        title = if (waiting.offline) "$whose is on Fuse One" else "$whose is still on its way",
        icon = if (waiting.offline) FuseIcons.CloudOff else FuseIcons.CloudDownload,
        message = if (waiting.offline) {
            "This device is offline, so ${card.title} didn't start with the older save here.$made"
        } else {
            "Fuse One is taking a while to send it, so ${card.title} didn't start with the older save here.$made"
        },
        options = listOf(
            MenuAction(
                "wait", if (waiting.offline) "Try Again" else "Keep Waiting", if (waiting.offline) FuseIcons.Refresh else FuseIcons.Hourglass,
                detail = if (waiting.offline) "Once this device is back online" else "It keeps coming meanwhile, and the game starts once it's here",
                onSelect = { choice = null; retry() },
            ),
            MenuAction(
                "here", "Play With This Device's Save", FuseIcons.Play,
                detail = "The newer save stays safe; you'll choose between the two afterwards",
                onSelect = { choice = null; play() },
            ),
            MenuAction("not", "Not Now", FuseIcons.Close, onSelect = { choice = null }),
        ),
    )
}

private const val BUSY_QUIET_MS = 2 * 60_000L
private const val BUSY_WAIT_STEP_MS = 4_000L
private const val BUSY_WAIT_TRIES = 30

/**
 * This device's save of [card] and RomM's both changed since they last agreed (or never agreed and
 * differ): keep this device's (it goes to RomM, RomM's stays in its history), or take RomM's (this
 * device's is kept aside first). Then [play].
 */
private fun AppState.rommSaveConflict(card: GameCard, conflict: io.github.matiyaaa.fuse.sync.romm.RommSaveConflict, play: () -> Unit) {
    val service = store.rommSaves ?: return
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val from = conflict.server.originDeviceName ?: "RomM"
    fun settle(keepHere: Boolean) {
        choice = null
        scope.launch {
            service.settle(conflict, keepHere)
                .onSuccess {
                    toasts.show(if (keepHere) "Kept this device's save; RomM keeps the other in its history" else "Using the save from $from", ToastKind.SUCCESS)
                    play()
                }
                .onFailure { toasts.show(it.message ?: "Couldn't settle the two saves", ToastKind.ERROR) }
        }
    }
    choice = ChoiceSpec(
        title = "Two saves of ${card.title}",
        icon = FuseIcons.GitCompare,
        message = "This device and RomM each have a different save of it. The one you don't pick is kept, so nothing is lost.",
        options = listOf(
            MenuAction(
                "here", "Keep This Device's", FuseIcons.HardDrive,
                detail = "Changed ${io.github.matiyaaa.fuse.ui.shell.components.agoText(conflict.hereAt, now)}",
                onSelect = { settle(true) },
            ),
            MenuAction(
                "romm", "Use RomM's", FuseIcons.CloudDownload,
                detail = "From $from" + (rommTime(conflict.server.updatedAt)?.let { ", ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it, now)}" } ?: ""),
                onSelect = { settle(false) },
            ),
            MenuAction("not", "Not Now", FuseIcons.Close, onSelect = { choice = null }),
        ),
    )
}

/** RomM's ISO 8601 time in milliseconds, or null when it can't be read. */
private fun rommTime(text: String): Long? = runCatching { kotlin.time.Instant.parse(text).toEpochMilliseconds() }.getOrNull()

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
        if (card.pinned) {
            add(MenuAction("pin", "Unpin from Home", FuseIcons.PinOff, onSelect = { run { lib.setPinned(card.id, false); toasts.show("Unpinned from Home") } }))
        } else {
            add(MenuAction("pin", "Pin to Home", FuseIcons.Pin, detail = "In Home's Pinned games", onSelect = {
                run {
                    lib.setPinned(card.id, true)
                    // Home shows its pinned games, so the one just pinned is there to find.
                    store.updatePrefs { p -> p.copy(home = p.home.showing(io.github.matiyaaa.fuse.model.WidgetKind.PINNED_GAMES)) }
                    toasts.show("Pinned to Home, in Pinned games")
                }
            }))
        }
        // Steam's library (computers): the game starts through Fuse, with its art.
        if (platform.steam != null && !card.isApp) {
            if (card.id.value in steamGameIds) {
                add(MenuAction("steam", "Remove from Steam", FuseIcons.Gamepad, detail = "Only the entry Fuse made", onSelect = { closeOverlays(); removeFromSteam(listOf(card), card.title) }))
            } else {
                add(MenuAction("steam", "Add to Steam", FuseIcons.Gamepad, detail = "Starts with Fuse Fast Start, with its art", onSelect = { closeOverlays(); addToSteam(listOf(card), card.title) }))
            }
            // Fast Start or straight in its emulator, for this game alone (where Steam can have that).
            if (platform.steam?.directEntries == true) {
                val own = store.prefs.value.steam.startOverrides[card.id.value]
                add(MenuAction(
                    "steamstart", "Steam Entry", FuseIcons.Rocket,
                    detail = "How it starts from Steam", trailing = Trailing.Value(
                        when (own) { true -> "Direct"; false -> "Fast Start"; null -> if (store.prefs.value.steam.directEntries) "Direct" else "Fast Start" },
                    ),
                    onSelect = { steamStartPicker(card) },
                ))
            }
        }
        // The save files on this device, as its emulator keeps them: with any save sync, or none.
        if (!card.isApp) add(MenuAction("localsaves", "Saves on This Device", FuseIcons.HardDrive, detail = "The save files its emulator keeps here", trailing = Trailing.Chevron, onSelect = { localSavesSheet(card.id, card.title) }))
        // Fuse Sync: this game's saves through time, from every device (a host keeps them).
        if (!card.isApp && syncProfile != null && store.prefs.value.sync.role.isNotEmpty()) {
            add(MenuAction("savesync", "Save Sync", FuseIcons.RefreshCcw, detail = "Everywhere, receive only here, or off", trailing = Trailing.Chevron, onSelect = {
                scope.launch {
                    val q = store.sync.saveQuery(card.id) ?: return@launch toasts.show("Fuse can't tell where ${card.title} saves yet", ToastKind.WARNING)
                    io.github.matiyaaa.fuse.ui.shell.sync.saveSyncChoice(this@gameMenu, q, card.title)
                }
            }))
            add(MenuAction("saves", "Save History", FuseIcons.History, detail = "Every version of its saves, from every device", trailing = Trailing.Chevron, onSelect = {
                closeOverlays(); go(Route.SaveHistory(card.id, card.title))
            }))
        }
        add(MenuAction("system", "System", FuseIcons.Layers, detail = if (card.isApp) "Android, or back to being an app" else "If it landed in the wrong one", trailing = Trailing.Value(card.platformShort), onSelect = { systemPicker(card) }))
        // An Android game always starts as its app.
        if (!card.isApp) add(MenuAction("emulator", "Emulator", FuseIcons.Chip, trailing = Trailing.Chevron, onSelect = { emulatorPicker(card) }))
        if (!card.isApp) add(MenuAction("parts", "Updates and DLC", FuseIcons.Puzzle, detail = "If this is an update or DLC of another game, or to give back one you added", trailing = Trailing.Chevron, onSelect = { partsPicker(card) }))
        // Patches and cheats in the emulator's own files, which Fuse changes on a computer.
        if (!card.isApp && platform.host != io.github.matiyaaa.fuse.model.Host.ANDROID) {
            val retroArch = store.emulators.installed.value.any { it.id.value.substringAfter('.').startsWith("retroarch") }
            when (card.platformId.value) {
                "ps2" -> add(MenuAction("patches", "Patches, Cheats and Textures", FuseIcons.Bandage, detail = "Widescreen, 60 FPS and other fixes, cheats, and texture packs of your own, in its emulator's own folders", trailing = Trailing.Chevron, onSelect = { gamePatchPicker(card) }))
                "psx" -> add(MenuAction("patches", "Patches, Cheats and Textures", FuseIcons.Bandage, detail = "Widescreen and other fixes, cheats, and texture packs of your own, in its emulator's own folders", trailing = Trailing.Chevron, onSelect = { gamePatchPicker(card) }))
                "psp" -> add(MenuAction("patches", "Cheats and Textures", FuseIcons.Wand, detail = "This game's cheats, and texture packs of your own, in its emulator's own folders", trailing = Trailing.Chevron, onSelect = { gamePatchPicker(card) }))
                "ngc", "wii" -> add(MenuAction("patches", "Cheats, Textures and Mods", FuseIcons.Wand, detail = "Gecko and Action Replay codes, texture packs and graphics mods of your own, in Dolphin's own folders", trailing = Trailing.Chevron, onSelect = { gamePatchPicker(card) }))
                "ps3", "ps4" -> add(MenuAction("patches", "Patches and Mods", FuseIcons.Bandage, detail = "Its emulator's patches for this game, and mods of your own, in its own folders", trailing = Trailing.Chevron, onSelect = { gamePatchPicker(card) }))
                "switch", "wiiu" -> add(MenuAction("patches", "Mods", FuseIcons.Puzzle, detail = "Mods of your own and from GameBanana or Nexus Mods, where its emulator reads them", trailing = Trailing.Chevron, onSelect = { gamePatchPicker(card) }))
                else -> if (retroArch) add(MenuAction("patches", "Cheats", FuseIcons.Wand, detail = "This game's cheats, used when it starts in RetroArch", trailing = Trailing.Chevron, onSelect = { gamePatchPicker(card) }))
            }
            // With others: netplay, ad hoc, NetPlay or an online service, as the game's emulator plays together.
            if (card.platformId.value in TOGETHER_SYSTEMS || retroArch && io.github.matiyaaa.fuse.launch.RetroArchCores.coresFor(platform.host, card.platformId).isNotEmpty()) {
                add(MenuAction("together", "Play Together", FuseIcons.Users, detail = "Host it for your household or join someone's game, as its emulator plays with others", trailing = Trailing.Chevron, onSelect = { playTogetherSheet(card) }))
            }
            // The emulator's own settings for this game, where Fuse can change them.
            if (card.platformId.value in setOf("ps2", "psx", "psp", "ngc", "wii", "ps3")) {
                add(MenuAction("emusettings", "Emulator Settings", FuseIcons.Sliders, detail = "Resolution, renderer and every other setting for this game, in its emulator's own settings", trailing = Trailing.Chevron, onSelect = { emulatorSettingsSheet(card) }))
            }
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
        if (!card.isApp && platform.features.frameGeneration) {
            val ready = store.library.frameGenerationReady()
            add(MenuAction(
                "framegen", "Frame Generation", FuseIcons.Layers2,
                detail = if (ready) "More frames shown than the emulator draws, through lsfg-vk" else "Needs lsfg-vk set up with Lossless Scaling on this computer",
                trailing = Trailing.Chevron, enabled = ready, onSelect = { frameGenerationPicker(card) },
            ))
        }
        add(MenuAction("rescrape", "Find Details and Art", FuseIcons.Wand, detail = "Fills what's missing, including a proper title. Your own art and names stay", onSelect = {
            closeOverlays()
            findDetailsAndReport(card)
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
        // A name of the person's own: one press puts back the one it had.
        if (card.customTitle) {
            add(MenuAction("restoretitle", "Restore Original Title", FuseIcons.Undo, detail = "Shows the name its details or file give it again", onSelect = {
                closeOverlays()
                scope.launch {
                    lib.rename(card.id, null)
                    toasts.show("Its original title is back")
                }
            }))
        }
        if (!card.isApp) {
            add(MenuAction("reset", "Reset Name and Details", FuseIcons.RotateCcw, detail = "For a game mixed up with another. The art you chose stays", onSelect = {
                closeOverlays()
                confirm = ConfirmSpec(
                    "Reset ${card.title}?",
                    "Fuse forgets the names (yours too) and the details and art sources gave this game, and it goes back to its file name. The art you chose stays. Find Details and Art looks again.",
                    "Reset",
                ) {
                    scope.launch {
                        lib.rename(card.id, null)
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
        // The Fuse Library: to another of the household's devices (now, or once it is back).
        if (!card.isApp && card.unavailable == null && !card.missing && store.reach.state.value.supported) {
            add(MenuAction("reach.send", "Send to Another Device", FuseIcons.Share, detail = "Fetched by that device, from wherever is best", trailing = Trailing.Chevron, onSelect = {
                sendPicker(card.id, card.title)
            }))
        }
        if (!card.isApp) add(MenuAction("folder", "Folder Behaviour", FuseIcons.FolderOpen, trailing = Trailing.Chevron, onSelect = { folderPolicyPicker(card) }))
        // A translation, a hack or a fix, as its own copy beside the game: the game stays as it is.
        if (!card.isApp && card.platformId.value !in NO_ROM_PATCHES) add(MenuAction("patch", "Apply a Patch", FuseIcons.Bandage, detail = "An IPS, UPS, BPS or xdelta file. Makes a patched copy; this game stays as it is", onSelect = { applyPatch(card) }))
        // RomM, when it is the RomM integration in use: games not on RomM go up from here.
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
        add(MenuAction("hide", "Hide", FuseIcons.EyeOff, onSelect = { run { lib.setHidden(card.id, true); toasts.show("Hidden. It's in the Library's Hidden tab") } }))
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
    return ContextMenuSpec(
        title = card.title, subtitle = card.platformShort, art = card.art.tile, actions = actions, accent = card.accent,
        tabs = listOf(
            MenuTab("Game", FuseIcons.Gamepad, listOf("play", "together", "info", "fav", "collection", "pin", "steam", "reach.send", "hide", "notgame", "remove") + extra.map { it.id }),
            MenuTab("Saves", FuseIcons.Save, listOf("savesync", "saves")),
            MenuTab("Setup", FuseIcons.Sliders, listOf("system", "emulator", "emusettings", "patches", "parts", "packages", "screen", "compat", "folder", "patch", "move")),
            MenuTab("Details", FuseIcons.Images, listOf("media", "rescrape", "rename", "restoretitle", "reset", "romm.upload", "cartridge", "find", "upload")),
        ),
    )
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
        val streamed = store.prefs.value.streaming.games[card.id.value.toString()]?.host
        // The ports set up here that play this game (Play With): picked, they start in the emulator's place.
        val ports = if (store.ports.supported) store.ports.portsFor(card.id) else emptyList()
        val withPort = store.ports.playWith(card.id)
        fun noPort() { if (withPort != null) scope.launch { store.ports.setPlayWith(card.id, null) } }
        choice = ChoiceSpec(
            icon = FuseIcons.Chip,
            title = if (ports.isEmpty()) "Emulator for ${card.title}" else "Play ${card.title} With",
            message = if (ports.isEmpty()) "The system's choice is used unless you pick one here." else "An emulator, or a port of it rebuilt to run natively. The system's emulator is used unless you pick one here.",
            options = listOf(
                MenuAction("default", "Use the system's emulator", FuseIcons.Layers, onSelect = {
                    scope.launch { store.library.setEmulator(card.id, null) }
                    noPort()
                    setStreamPick(card, null)
                    choice = null
                }),
            ) + ports.map { v ->
                MenuAction(
                    "port.${v.port.id}", v.port.name, FuseIcons.Boxes,
                    detail = "Fuse Ports: ${v.port.game} rebuilt to run natively. Its saves and time are kept with the port's",
                    trailing = Trailing.Check(withPort == v.port.id),
                    onSelect = {
                        choice = null
                        scope.launch {
                            store.ports.setPlayWith(card.id, v.port.id)
                            toasts.show("${card.title} plays with ${v.port.name} from now on", ToastKind.SUCCESS, icon = FuseIcons.Boxes)
                        }
                    },
                )
            } + streamChoices(streamed) { hostId ->
                setStreamPick(card, io.github.matiyaaa.fuse.data.settings.StreamPick(hostId))
                choice = null
                toasts.show("${card.title} streams with Moonlight from now on")
            } + options.sortedBy { it.unavailable != null }.map { o ->
                val locate = !o.installed && store.emulators.canLocate
                // One that isn't here, where nothing else keeps it from this game, is a step from installing.
                val missing = !o.installed && !locate && (o.unavailable == null || "install" in o.unavailable.lowercase())
                val storeKey = if (missing && store.appStore.supported) {
                    io.github.matiyaaa.fuse.ui.shell.apps.storeAppFor(o.name, store.appStore.state.value.catalogue?.apps.orEmpty())?.key
                } else {
                    null
                }
                val site = if (missing && storeKey == null) store.emulators.homepage(o.id) else null
                MenuAction(
                    "e${o.id}", o.name, if (storeKey != null || site != null) FuseIcons.Download else FuseIcons.Chip,
                    detail = when {
                        locate -> "Not found. Show Fuse where it is"
                        storeKey != null -> "Not installed. Install it from the Store"
                        site != null -> "Not installed. Get it from its website"
                        else -> o.note ?: if (o.installed) null else "Not installed"
                    },
                    unavailableReason = if (locate || storeKey != null || site != null) null else o.unavailable ?: if (o.installed) null else "Not installed on this device",
                    onSelect = {
                        if (storeKey != null) {
                            choice = null
                            openStore(storeKey)
                        } else if (site != null) {
                            choice = null
                            platform.openUrl(site)
                        } else if (locate) {
                            startLocate(LocateRequest(o.id, o.name, game = card.id))
                        } else {
                            scope.launch { store.library.setEmulator(card.id, o.id) }
                            noPort()
                            setStreamPick(card, null)
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
        val moves = if (d.installed) store.emulators.familyMoves(id) else null
        val actions = buildList {
            if (d.installed) {
                add(MenuAction("open", "Open ${d.name}", FuseIcons.External, detail = "Its own settings, controls and firmware", onSelect = {
                    contextMenu = null
                    scope.launch { store.emulators.openEmulator(id) }
                }))
                if (id.value.substringAfter('.') in io.github.matiyaaa.fuse.launch.settings.NativeSettings.changeable && platform.host != io.github.matiyaaa.fuse.model.Host.ANDROID) {
                    add(MenuAction("settings", "Settings for Every Game", FuseIcons.Sliders, detail = "Resolution, renderer and every other setting, in its own settings", trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron, onSelect = {
                        emulatorSettingsSheet(null, id)
                    }))
                }
                // Another emulator of its family on this computer: its keys, saves, installed content and mods brought over.
                moves?.offers?.forEach { f ->
                    add(MenuAction(
                        "bring.${f.id}", "Bring Over From ${f.name}", FuseIcons.FolderInput,
                        detail = "Keys, saves, installed updates and add-ons, and mods. ${f.name} keeps everything",
                        trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron,
                        onSelect = { planBringOver(id, d.name, f.id) },
                    ))
                }
                moves?.done?.forEach { m ->
                    add(MenuAction(
                        "takeback.${m.id}", "Take Back What Came From ${m.from}", FuseIcons.Undo,
                        detail = "The ${if (m.files == 1) "file" else "${m.files} files"} Fuse brought over go; everything else stays",
                        onSelect = {
                            contextMenu = null
                            confirm = ConfirmSpec(
                                "Take back what came from ${m.from}?",
                                "The files Fuse copied from ${m.from} leave ${d.name}'s folder. Anything ${d.name} made since stays, and ${m.from} still has everything.",
                                "Take Back", destructive = true,
                            ) {
                                scope.launch {
                                    if (store.emulators.takeBackFamilyMove(id, m.id)) toasts.show("Taken back", ToastKind.SUCCESS)
                                    else toasts.show("Couldn't take everything back. What's left is kept aside, so try again", ToastKind.WARNING)
                                    showEmulator(id)
                                }
                            }
                        },
                    ))
                }
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
            // What Fuse has seen for itself, step by step: never more than it observed.
            d.verification?.let { v ->
                val checked = "Checked on this device"
                for (c in v.checks) {
                    val icon = when (c.state) {
                        io.github.matiyaaa.fuse.launch.intelligence.CheckState.DONE -> FuseIcons.CircleCheck
                        io.github.matiyaaa.fuse.launch.intelligence.CheckState.NOT_YET -> FuseIcons.CircleDot
                        io.github.matiyaaa.fuse.launch.intelligence.CheckState.CANT_CHECK -> FuseIcons.EyeOff
                    }
                    val word = when (c.state) {
                        io.github.matiyaaa.fuse.launch.intelligence.CheckState.DONE -> "Yes"
                        io.github.matiyaaa.fuse.launch.intelligence.CheckState.NOT_YET -> "Not yet"
                        io.github.matiyaaa.fuse.launch.intelligence.CheckState.CANT_CHECK -> "Can't check"
                    }
                    add(MenuAction(
                        "check.${c.check.name}", c.check.title, icon, detail = c.evidence,
                        trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Value(word), section = checked,
                    ))
                }
                val settings = v[io.github.matiyaaa.fuse.launch.intelligence.Check.CONFIGURED]
                if (settings?.state == io.github.matiyaaa.fuse.launch.intelligence.CheckState.DONE) {
                    add(MenuAction(
                        "reject", "That Isn't Its Settings Folder", FuseIcons.CircleSlash,
                        detail = "Fuse forgets it and never picks it again. Nothing in it is touched", section = checked,
                        onSelect = {
                            contextMenu = null
                            scope.launch {
                                store.emulators.rejectSettingsFolder(id)
                                toasts.show("Fuse won't use that folder for ${d.name} again", icon = FuseIcons.CircleSlash)
                            }
                        },
                    ))
                }
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
            subtitle = when {
                !d.installed -> "Not installed on this device"
                else -> when (d.verification?.furthest) {
                    io.github.matiyaaa.fuse.launch.intelligence.Check.LAUNCH_VERIFIED -> "Launch verified on this device"
                    io.github.matiyaaa.fuse.launch.intelligence.Check.CONFIGURED -> "Set up. No game started through Fuse yet"
                    io.github.matiyaaa.fuse.launch.intelligence.Check.EXECUTABLE -> "Can run. Not set up yet"
                    else -> "Installed"
                }
            },
            icon = FuseIcons.Joystick,
            actions = actions,
        ))
    }
}

private val PACKAGE_SYSTEMS = setOf("ps3", "psvita", "3ds", "new-nintendo-3ds")

/**
 * The game's patches and cheats in its emulator's own settings (RetroArch, PCSX2, DuckStation, PPSSPP), with a
 * check for each that is on. Fuse turns on any that are off and turns off only those it turned on;
 * ones set in the emulator say so and stay as they are.
 */
/**
 * Find Details and Art for one game, and once it ends what it found: art and details added, a choice
 * waiting in Identify game, sources resting, or that nothing more was out there.
 */
fun AppState.findDetailsAndReport(card: GameCard) {
    val media = store.media
    media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, game = card.id)
    toasts.show("Looking for details and art for ${card.title}")
    scope.launch {
        // This fill: started (not finished), then finished.
        kotlinx.coroutines.withTimeoutOrNull(5_000) { media.fillProgress.first { it != null && !it.finished } } ?: return@launch
        val end = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { media.fillProgress.first { it == null || it.finished } } ?: return@launch
        toasts.show(
            when {
                end == null || end.cancelled -> return@launch
                end.needsYou.isNotEmpty() -> "Several games match ${card.title}. Choose it in Identify game"
                end.added > 0 || end.details > 0 -> listOfNotNull(
                    if (end.added > 0) "${end.added} ${if (end.added == 1) "image" else "images"}" else null,
                    if (end.details > 0) "its details" else null,
                ).joinToString(" and ").replaceFirstChar { it.uppercase() } + " found for ${card.title}"
                end.paused.isNotEmpty() -> "Nothing new for now: ${end.paused.joinToString(", ")} ${if (end.paused.size == 1) "is" else "are"} resting. Try again later"
                else -> "${card.title} already has everything the sources offer"
            },
        )
    }
}

fun AppState.gamePatchPicker(card: GameCard) {
    contextMenu = null
    scope.launch {
        when (val list = store.library.gamePatches(card.id)) {
            is io.github.matiyaaa.fuse.ui.shell.store.GamePatchList.Unavailable -> {
                // No patch list of its emulator's own (Dolphin, the Switch's, Cemu): what it takes of a person's own, if anything.
                val own = store.library.enhancements(card.id)
                if (own.kinds.isEmpty() && own.added.isEmpty()) {
                    showProblem(Problem(list.title, list.reason, ProblemKind.EMULATOR, Severity.INFO, reassurance = null))
                    return@launch
                }
                val emulator = ownEmulatorName(card)
                choice = ChoiceSpec(
                    icon = FuseIcons.Puzzle,
                    title = if (emulator != null) "$emulator for ${card.title}" else "Mods for ${card.title}",
                    message = "What you add goes where ${emulator ?: "the game's emulator"} reads it, and you see exactly what goes where first. Each comes out again as it went in.",
                    options = ownEnhancementRows(card, own, emulator ?: "its emulator"),
                )
            }
            is io.github.matiyaaa.fuse.ui.shell.store.GamePatchList.Ready -> {
                val emulator = list.emulator
                val own = store.library.enhancements(card.id)
                val empty = list.patches.isEmpty() && list.cheats.isEmpty()
                if (empty && own.added.isEmpty() && own.kinds.isEmpty()) {
                    showProblem(Problem(
                        "No patches or cheats for ${card.title}", list.whereFrom,
                        ProblemKind.EMULATOR, Severity.INFO, reassurance = null,
                    ))
                    return@launch
                }
                fun row(s: io.github.matiyaaa.fuse.launch.patches.PatchStatus, cheat: Boolean) = run {
                    val about = listOfNotNull(s.patch.description, s.patch.author?.let { "by $it" }).joinToString("  ·  ").ifEmpty { null }
                    MenuAction(
                        "${if (cheat) "cheat" else "patch"}.${s.patch.name}", s.patch.shown, if (cheat) FuseIcons.Wand else FuseIcons.Bandage,
                        detail = when (s.state) {
                            PatchState.ON_BY_FUSE -> listOfNotNull("On, turned on in Fuse", about).joinToString("  ·  ")
                            else -> about
                        },
                        trailing = Trailing.Check(s.on),
                        unavailableReason = when (s.state) {
                            PatchState.ON_IN_EMULATOR -> "On, turned on in $emulator. Change it there"
                            PatchState.ON_FOR_ALL_GAMES -> "On for every game in $emulator's settings"
                            PatchState.OFF_IN_EMULATOR -> "Turned off for this game in $emulator"
                            else -> null
                        },
                        section = if (cheat) "Cheats" else "Patches",
                        onSelect = {
                            scope.launch {
                                val ok = store.library.setGamePatch(card.id, s.patch.name, !s.on, cheat = cheat)
                                if (!ok) toasts.show("Couldn't change $emulator's settings for this game", ToastKind.WARNING)
                                gamePatchPicker(card)
                            }
                        },
                    )
                }
                val note = when {
                    list.cheats.isEmpty() -> ""
                    list.cheatsOn -> " Cheats are on for this game in $emulator."
                    list.cheatsNote.isEmpty() -> ""
                    else -> " " + list.cheatsNote
                }
                val id = if (list.crc.isEmpty()) list.serial else "${list.serial}, CRC ${list.crc}"
                choice = ChoiceSpec(
                    icon = if (list.patches.isEmpty()) FuseIcons.Wand else FuseIcons.Bandage,
                    title = if (empty) "$emulator for ${card.title}" else if (list.cheats.isEmpty()) "$emulator patches" else if (list.patches.isEmpty()) "$emulator cheats" else "$emulator patches and cheats",
                    message = listOfNotNull(
                        if (empty) list.whereFrom else "$id. Changes apply the next time the game starts in $emulator. Fuse only turns off what it turned on.$note",
                        list.warning,
                    ).joinToString(" "),
                    options = list.patches.map { row(it, cheat = false) } + list.cheats.map { row(it, cheat = true) } +
                        ownEnhancementRows(card, own, emulator),
                )
            }
        }
    }
}

/** The emulator a game's own cheats, textures and mods go to, by its system, for the sheet's words; null where several could (the Switch). */
private fun ownEmulatorName(card: GameCard): String? = when (card.platformId.value) {
    "ngc", "wii" -> "Dolphin"
    "wiiu" -> "Cemu"
    "ps3" -> "RPCS3"
    "ps4" -> "shadPS4"
    else -> null
}

/**
 * An emulator's own settings, for one game ([card]) or for every game ([emulator]), grouped as the
 * emulator groups them. Each says whether it is the game's own or follows every game's; choosing one
 * changes it in the emulator's own files, and what Fuse changed can be put back.
 */
fun AppState.emulatorSettingsSheet(card: GameCard?, emulator: io.github.matiyaaa.fuse.model.EmulatorId? = null) =
    emulatorSettingsSheet(card?.id, card?.title, emulator)

/** [emulatorSettingsSheet] for the game [game] (called [gameTitle]), or every game in [emulator]. */
fun AppState.emulatorSettingsSheet(game: GameId?, gameTitle: String?, emulator: io.github.matiyaaa.fuse.model.EmulatorId? = null) {
    contextMenu = null
    scope.launch {
        when (val view = store.library.emulatorSettings(game, emulator)) {
            is io.github.matiyaaa.fuse.ui.shell.store.EmulatorSettingsView.Unavailable -> showProblem(Problem(
                view.title, view.reason, ProblemKind.EMULATOR, Severity.INFO, reassurance = null,
            ))
            is io.github.matiyaaa.fuse.ui.shell.store.EmulatorSettingsView.Ready -> {
                // Only Every Setting (RPCS3): straight there.
                if (view.values.isEmpty() && view.profiles.isEmpty() && !view.canRestore) {
                    everySettingSheet(game, gameTitle, emulator)
                    return@launch
                }
                val forGame = game != null
                val active = view.profiles.firstOrNull { it.active }
                val profileRow = if (!forGame || view.profiles.isEmpty()) emptyList() else listOf(
                    MenuAction(
                        "profile", "Profile", FuseIcons.Layers,
                        detail = active?.about ?: "Vanilla, Performance, Visual Quality, Battery Saver or your own",
                        trailing = Trailing.Value(active?.name ?: "Your Own Mix"),
                        section = "Profile",
                        onSelect = { settingProfilesSheet(game, gameTitle, emulator, view) },
                    ),
                )
                val rows = view.values.map { v ->
                    val s = v.setting
                    MenuAction(
                        "setting.${s.id}", s.label, settingIcon(s.category),
                        detail = when (v.source) {
                            io.github.matiyaaa.fuse.launch.settings.SettingSource.GAME -> "This game's own. Every game: ${s.labelFor(v.everyGame)}"
                            io.github.matiyaaa.fuse.launch.settings.SettingSource.EVERY_GAME -> if (forGame) "Same as every game" else null
                            io.github.matiyaaa.fuse.launch.settings.SettingSource.DEFAULT -> if (forGame) "Same as every game" else "${view.emulator}'s default"
                        },
                        trailing = Trailing.Value(v.label),
                        section = s.category,
                        onSelect = { settingPicker(game, gameTitle, emulator, view.emulator, v) },
                    )
                }
                val every = MenuAction(
                    "every", "Every Setting", FuseIcons.LayoutList,
                    detail = "All of ${view.emulator}'s settings, as its own code names them, with search",
                    trailing = Trailing.Chevron,
                    section = "More",
                    onSelect = { everySettingSheet(game, gameTitle, emulator, from = { emulatorSettingsSheet(game, gameTitle, emulator) }) },
                )
                val restore = if (!view.canRestore) emptyList() else listOf(
                    MenuAction(
                        "restore", "Put Back What Fuse Changed", FuseIcons.Undo,
                        detail = "Only what Fuse changed here. Anything changed in ${view.emulator} since stays",
                        section = "Changed in Fuse",
                        onSelect = {
                            scope.launch {
                                val ok = store.library.restoreEmulatorSettings(game, emulator)
                                if (ok) toasts.show("Put back as it was", ToastKind.SUCCESS) else toasts.show("Couldn't change ${view.emulator}'s settings", ToastKind.WARNING)
                                emulatorSettingsSheet(game, gameTitle, emulator)
                            }
                        },
                    ),
                )
                choice = ChoiceSpec(
                    icon = FuseIcons.Sliders,
                    title = if (forGame) "${view.emulator} settings for ${gameTitle ?: view.game}" else "${view.emulator} settings for every game",
                    message = if (forGame) {
                        "A setting of this game's own replaces every game's. Changes apply the next time it starts in ${view.emulator}."
                    } else {
                        "Every game uses these unless it has its own. Close ${view.emulator} first; changes apply the next time it starts."
                    },
                    options = profileRow + rows + every + restore,
                )
            }
        }
    }
}

/**
 * The profiles a game can be given in its emulator: Fuse's own (Vanilla, Performance, Visual
 * Quality, Battery Saver), made only of settings that emulator has, then the person's own, with the
 * one the game has now checked. Saving the game's settings as one of their own, and deleting one,
 * come last.
 */
private fun AppState.settingProfilesSheet(
    game: GameId?,
    gameTitle: String?,
    emulator: io.github.matiyaaa.fuse.model.EmulatorId?,
    view: io.github.matiyaaa.fuse.ui.shell.store.EmulatorSettingsView.Ready,
) {
    val title = gameTitle ?: view.game ?: "this game"
    fun apply(p: io.github.matiyaaa.fuse.ui.shell.store.SettingProfileView) {
        choice = null
        scope.launch {
            if (store.library.applySettingProfile(game, emulator, p.id)) toasts.show("$title has ${p.name} now", ToastKind.SUCCESS)
            else toasts.show("Couldn't change ${view.emulator}'s settings", ToastKind.WARNING)
            emulatorSettingsSheet(game, gameTitle, emulator)
        }
    }
    val own = view.profiles.filter { it.own }
    val rows = view.profiles.map { p ->
        MenuAction(
            "profile.${p.id}", p.name, if (p.own) FuseIcons.Bookmark else profileIcon(p.id),
            detail = p.about,
            trailing = Trailing.Check(p.active),
            section = if (p.own) "Your Own" else "From Fuse",
            onSelect = { apply(p) },
        )
    }
    val save = MenuAction(
        "profile.save", "Save These as a Profile", FuseIcons.Plus,
        detail = "$title's ${view.emulator} settings now, to give your other games. It follows you to your other devices",
        section = "Your Own",
        onSelect = {
            choice = null
            textInput = TextInputSpec(
                title = "Name this profile",
                initial = "",
                placeholder = "Sharp and Smooth",
                doneLabel = "Save",
                message = "It keeps $title's ${view.emulator} settings as they are now.",
            ) { name ->
                textInput = null
                if (name.isBlank()) return@TextInputSpec
                scope.launch {
                    if (store.library.saveSettingProfile(game, emulator, name.trim())) toasts.show("Saved as ${name.trim()}", ToastKind.SUCCESS)
                    else toasts.show("Couldn't save it", ToastKind.WARNING)
                    emulatorSettingsSheet(game, gameTitle, emulator)
                }
            }
        },
    )
    val delete = if (own.isEmpty()) emptyList() else listOf(
        MenuAction(
            "profile.delete", "Delete One of Yours", FuseIcons.Trash,
            detail = "Games given it keep their settings",
            trailing = Trailing.Chevron,
            section = "Your Own",
            onSelect = {
                choice = ChoiceSpec(
                    icon = FuseIcons.Trash,
                    title = "Delete which profile?",
                    message = "Games given it keep their settings as they are.",
                    options = own.map { p ->
                        MenuAction("delete.${p.id}", p.name, FuseIcons.Bookmark, detail = p.about, destructive = true, onSelect = {
                            choice = null
                            scope.launch {
                                store.library.deleteSettingProfile(p.id)
                                toasts.show("${p.name} deleted")
                                emulatorSettingsSheet(game, gameTitle, emulator)
                            }
                        })
                    },
                )
            },
        ),
    )
    choice = ChoiceSpec(
        icon = FuseIcons.Layers,
        title = "Profiles for $title",
        message = "Starting points, not promises: how a game runs depends on this computer. ${view.emulator}'s own fixes for the game apply whichever profile it has, and Put Back undoes a profile like any change.",
        options = rows + save + delete,
    )
}

private fun profileIcon(id: String) = when (id) {
    io.github.matiyaaa.fuse.launch.settings.SettingProfiles.VANILLA -> FuseIcons.Gamepad
    io.github.matiyaaa.fuse.launch.settings.SettingProfiles.PERFORMANCE -> FuseIcons.Gauge
    io.github.matiyaaa.fuse.launch.settings.SettingProfiles.QUALITY -> FuseIcons.Sparkles
    io.github.matiyaaa.fuse.launch.settings.SettingProfiles.BATTERY -> FuseIcons.BatteryMedium
    else -> FuseIcons.Layers
}

/** The values one setting can take, with the one in use checked; for a game, following every game's comes first. */
private fun AppState.settingPicker(
    game: GameId?,
    gameTitle: String?,
    emulator: io.github.matiyaaa.fuse.model.EmulatorId?,
    name: String,
    v: io.github.matiyaaa.fuse.launch.settings.SettingValue,
) {
    val s = v.setting
    val forGame = game != null
    val own = !forGame || v.source == io.github.matiyaaa.fuse.launch.settings.SettingSource.GAME
    fun apply(value: String?) {
        scope.launch {
            val ok = store.library.setEmulatorSetting(game, emulator, s.id, value)
            if (!ok) toasts.show("Couldn't change $name's settings", ToastKind.WARNING)
            emulatorSettingsSheet(game, gameTitle, emulator)
        }
    }
    val follow = if (!forGame) emptyList() else listOf(
        MenuAction(
            "follow", "Same as Every Game", FuseIcons.Layers, detail = s.labelFor(v.everyGame),
            trailing = Trailing.Check(!own), onSelect = { apply(null) },
        ),
    )
    choice = ChoiceSpec(
        icon = settingIcon(s.category),
        title = s.label,
        message = s.about ?: if (forGame) "For this game in $name." else "For every game in $name.",
        options = follow + s.choices.map { c ->
            MenuAction(
                "value.${c.value}", c.label, settingIcon(s.category),
                detail = if (s.same(c.value, s.default)) "$name's default" else null,
                trailing = Trailing.Check(own && s.same(c.value, v.value)),
                onSelect = { apply(c.value) },
            )
        },
    )
}

private fun settingIcon(category: String) = when (category) {
    "Display" -> FuseIcons.Scaling
    else -> FuseIcons.Monitor
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

/**
 * Goes back to the version of Fuse the last update replaced (or returns to the newer one, after
 * going back), once the person has read what it means.
 */
fun AppState.goBackToPreviousFuse(previous: io.github.matiyaaa.fuse.ui.shell.store.PreviousRelease) {
    val current = store.updates.currentVersion
    val newer = io.github.matiyaaa.fuse.integrations.github.SemVer.isNewer(previous.version, current)
    val verb = if (newer) "Return to" else "Go back to"
    choice = ChoiceSpec(
        title = "$verb Fuse ${previous.version}",
        icon = FuseIcons.History,
        message = buildString {
            append("Fuse closes and starts again in ${previous.version}. ")
            when {
                newer -> append("It brings your library up to date again from how it is now. ")
                previous.libraryGoesBack -> append("Your library goes back to how it was before $current updated it; what changed since is kept in a copy beside it. ")
            }
            append("Your games, emulators and their saves stay as they are, and $current is kept, to come back to the same way.")
        },
        options = listOf(
            MenuAction("go", "$verb ${previous.version}", FuseIcons.History, onSelect = {
                choice = null
                scope.launch {
                    store.updates.goBack()
                        .onSuccess { restart -> if (restart) platform.restart() }
                        .onFailure { toasts.show(it.message ?: "Fuse couldn't go back") }
                }
            }),
            MenuAction("stay", "Stay on $current", FuseIcons.Close, onSelect = { choice = null }),
        ),
    )
}

/** The kinds a person can say a game really is, with their names in a sentence. */
private val PART_KINDS = listOf(
    io.github.matiyaaa.fuse.model.ContentKind.UPDATE to "An update",
    io.github.matiyaaa.fuse.model.ContentKind.DLC to "DLC",
    io.github.matiyaaa.fuse.model.ContentKind.PATCH to "A patch",
    io.github.matiyaaa.fuse.model.ContentKind.MOD to "A mod",
)

/**
 * Updates and DLC, from a game's options: say this game is really part of another (it then lists
 * with that game's content, keeps its play time there, and stays so through every rescan), or give
 * back as a game of its own any part the person attached here before.
 */
fun AppState.partsPicker(card: GameCard) {
    contextMenu = null
    scope.launch {
        val mine = store.library.userParts(card.id)
        choice = ChoiceSpec(
            icon = FuseIcons.Puzzle,
            title = "Updates and DLC",
            message = "Fuse attaches updates and DLC to their game by itself. When it took one for a game of its own, say which game it belongs to.",
            options = buildList {
                add(MenuAction("part.of", "Part of another game", FuseIcons.Link, detail = "${card.title} is an update, DLC, patch or mod of another ${card.platformShort} game", trailing = Trailing.Chevron, onSelect = { partKindPicker(card) }))
                for ((id, title, kind) in mine) {
                    add(MenuAction(
                        "part.back.${id.value}", "Make \"$title\" a game again", FuseIcons.Undo,
                        detail = "Added here as ${kind.slug}. Its play time stays with ${card.title}",
                        section = "Added by you",
                        onSelect = {
                            choice = null
                            scope.launch {
                                if (store.library.detachContent(id) != null) toasts.show("$title is a game of its own again", ToastKind.SUCCESS)
                                else toasts.show("$title couldn't be separated", ToastKind.ERROR)
                            }
                        },
                    ))
                }
            },
        )
    }
}

private fun AppState.partKindPicker(card: GameCard) {
    choice = ChoiceSpec(
        icon = FuseIcons.Puzzle,
        title = "What is ${card.title}?",
        options = PART_KINDS.map { (kind, words) ->
            MenuAction("kind.${kind.name}", words, null, onSelect = { partOwnerPicker(card, kind, words) })
        },
    )
}

/** The game it belongs to: the same system's games, the likeliest (by name) first. */
private fun AppState.partOwnerPicker(card: GameCard, kind: io.github.matiyaaa.fuse.model.ContentKind, words: String) {
    scope.launch {
        val games = store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery(platform = card.platformId)).first()
            .filter { it.id != card.id && !it.isApp }
        val base = io.github.matiyaaa.fuse.library.parse.FilenameParser.parse(card.title).baseTitle.lowercase()
        fun likeness(t: String): Int {
            val other = io.github.matiyaaa.fuse.library.parse.FilenameParser.parse(t).baseTitle.lowercase()
            return when {
                other == base -> 0
                base.startsWith(other) || other.startsWith(base) -> 1
                base.split(' ').firstOrNull() == other.split(' ').firstOrNull() -> 2
                else -> 3
            }
        }
        val sorted = games.sortedWith(compareBy({ likeness(it.title) }, { it.title.lowercase() }))
        choice = ChoiceSpec(
            icon = FuseIcons.Link,
            title = "Which game is it for?",
            message = "$words of a ${card.platformShort} game. It moves into that game's updates and DLC, with its play time, and stays there.",
            options = sorted.map { g ->
                MenuAction(
                    "owner.${g.id.value}", g.title, null,
                    section = if (likeness(g.title) < 3) "Likely" else "Every ${card.platformShort} game",
                    onSelect = {
                        choice = null
                        scope.launch {
                            if (store.library.attachAsContent(card.id, g.id, kind)) {
                                if (navigator.current == Route.GameInfo(card.id)) navigator.pop()
                                toasts.show("${card.title} is with ${g.title} now. Its options there can make it a game again", ToastKind.SUCCESS)
                            } else {
                                toasts.show("${card.title} can't join ${g.title}", ToastKind.ERROR)
                            }
                        }
                    },
                )
            },
        )
    }
}

/**
 * Picks a patch and applies it to [card] as a copy beside the game, saying what came of it: the
 * copy (and whether the patch was checked against this game), a patch for another version of the
 * game, a game already patched, or why it couldn't be done.
 */
internal fun AppState.applyPatch(card: GameCard) {
    closeOverlays()
    scope.launch {
        // A computer: Fuse's own picker, made for a controller, opening in the game's folder (where
        // its patches usually are). Android: the system's picker, which document access needs.
        if (platform.host != io.github.matiyaaa.fuse.model.Host.ANDROID) {
            val folder = runCatching { store.library.game(card.id).first()?.game?.location?.path }.getOrNull()
                ?.let { io.github.matiyaaa.fuse.library.FsPath.parent(it) }
            go(Route.PickFile(FilePurpose.PATCH, patch = PatchPick(card, folder)))
            return@launch
        }
        val patch = platform.storage.pickPatch("Choose a patch for ${card.title}") ?: return@launch
        patchWith(card, patch)
    }
}

/** Makes a patched copy of [card] with the patch file at [patch], and says how it went. */
internal fun AppState.patchWith(card: GameCard, patch: String) {
    scope.launch {
        toasts.show("Patching ${card.title}...", icon = FuseIcons.Bandage)
        when (val r = store.library.applyPatch(card.id, patch)) {
            is io.github.matiyaaa.fuse.ui.shell.store.PatchApplied.Made -> {
                val notes = listOfNotNull(
                    if (r.checked) "The ${r.format} patch confirmed this is the game it is for." else "${r.format} patches don't say which game they are for: if the copy doesn't start, the patch is for another version.",
                    if (r.headerRemoved) "Its copier header was left off, as the patch expects." else null,
                )
                choice = ChoiceSpec(
                    title = "Patched copy made",
                    icon = FuseIcons.Bandage,
                    message = "\"${r.name}\" is beside ${card.title}, and in your library. ${notes.joinToString(" ")} ${card.title} itself is unchanged.",
                    options = listOfNotNull(
                        r.game?.let { made -> MenuAction("open", "Open the Patched Copy", FuseIcons.Play, onSelect = { choice = null; go(Route.GameInfo(made)) }) },
                        MenuAction("done", "Done", FuseIcons.Check, onSelect = { choice = null }),
                    ),
                )
            }
            is io.github.matiyaaa.fuse.ui.shell.store.PatchApplied.WrongGame -> choice = ChoiceSpec(
                title = "This patch is for another version",
                icon = FuseIcons.Bandage,
                message = "The ${r.format} patch is for a game whose checksum is ${r.expected}; this copy of ${card.title} is ${r.actual}. Patching it would make a broken game, so nothing was made. The patch's notes usually say which dump it needs (a region or revision).",
                options = listOf(MenuAction("ok", "OK", FuseIcons.Check, onSelect = { choice = null })),
            )
            io.github.matiyaaa.fuse.ui.shell.store.PatchApplied.AlreadyPatched -> toasts.show("${card.title} already has this patch", icon = FuseIcons.Bandage)
            is io.github.matiyaaa.fuse.ui.shell.store.PatchApplied.Failed -> toasts.show(r.reason, ToastKind.WARNING)
        }
    }
}

/** Systems whose emulators Fuse knows a way of playing together in (PCSX2, PPSSPP, Dolphin, RPCS3), besides RetroArch's. */
private val TOGETHER_SYSTEMS = setOf("ps2", "psp", "ngc", "wii", "ps3")

/** Systems whose games are installed packages or programs, where ROM patches (IPS, UPS, BPS, xdelta) don't apply. */
private val NO_ROM_PATCHES = setOf("switch", "ps3", "ps4", "ps5", "psvita", "wiiu", "xbox", "xbox360", "win", "win3x", "win9x", "steam", "android", "dos", "mac", "scummvm", "pico8", "flash", "j2me")

/**
 * What bringing [from]'s things over to [emulator] (called [name]) would copy, then doing it: only
 * files [name] doesn't have, from the folders that hold a person's things, with what stays behind
 * said. Nothing is copied before the person agrees, and a restoration point makes it undoable.
 */
private fun AppState.planBringOver(emulator: io.github.matiyaaa.fuse.model.EmulatorId, name: String, from: String) {
    contextMenu = null
    scope.launch {
        when (val p = store.emulators.planFamilyMove(emulator, from)) {
            is io.github.matiyaaa.fuse.ui.shell.store.FamilyMovePreview.Unavailable -> showProblem(Problem(p.title, p.reason, ProblemKind.EMULATOR, Severity.INFO))
            is io.github.matiyaaa.fuse.ui.shell.store.FamilyMovePreview.Ready -> confirm = ConfirmSpec(
                title = "Bring ${p.from}'s things over to ${p.to}?",
                message = listOfNotNull(
                    "${if (p.files == 1) "1 file" else "${p.files} files"} from ${p.carried.joinToString(", ")} in ${p.fromFolder} are copied to ${p.toFolder}." +
                        if (p.truncated) " That's as many as Fuse copies at once; bring over again for the rest." else "",
                    "Left behind: ${p.leftOut.joinToString("; ")}.",
                    "Close both emulators first. ${p.from} keeps everything, nothing ${p.to} has is replaced, and a restoration point means Take Back removes exactly what was copied.",
                ).joinToString("\n\n"),
                confirmLabel = "Bring Over",
            ) {
                scope.launch {
                    toasts.show("Bringing over ${p.from}'s things")
                    val why = store.emulators.bringOver(p.token)
                    if (why == null) toasts.show("${p.to} has ${p.from}'s things now", ToastKind.SUCCESS)
                    else showProblem(Problem("Couldn't bring them over", why, ProblemKind.EMULATOR, Severity.ATTENTION))
                    showEmulator(emulator)
                }
            }
        }
    }
}

/**
 * A newer save of [title] in a folder [emulator] used before (the Flatpak beside a native install, a
 * data folder since moved): choosing it asks, then copies it to where the emulator reads it now.
 */
private fun AppState.olderSaveRow(id: io.github.matiyaaa.fuse.model.GameId, title: String, emulator: String, o: io.github.matiyaaa.fuse.sync.OldSave) = MenuAction(
    "older.${o.where}",
    if ("/.var/app/" in o.where) "In the Flatpak's Folder" else "In an Earlier Folder",
    FuseIcons.FolderInput,
    detail = (if (o.inUseModifiedAt == null) "None in use yet. " else "Newer than the one in use. ") + o.where,
    trailing = Trailing.Value(io.github.matiyaaa.fuse.ui.shell.components.agoText(o.modifiedAt ?: 0L)),
    section = "From Before",
    onSelect = {
        choice = null
        confirm = ConfirmSpec(
            title = "Use the Earlier Save?",
            message = "Fuse copies $title's save from ${o.where} to where $emulator reads it now. " +
                (if (o.inUseModifiedAt != null) "The save there now is kept in Fuse's kept folder first. " else "") +
                "The earlier folder stays as it is.",
            confirmLabel = "Use This Save",
        ) {
            scope.launch {
                store.library.useOldSave(id, o).fold(
                    onSuccess = { toasts.show("The earlier save of $title is in use", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck) },
                    onFailure = { toasts.show(it.message ?: "Fuse couldn't bring that save in", ToastKind.WARNING) },
                )
            }
        }
    },
)

/** [title]'s save files on this device, newest first, with where they are. */
internal fun AppState.localSavesSheet(id: io.github.matiyaaa.fuse.model.GameId, title: String) {
    scope.launch {
        val found = store.library.localSaves(id)
        choice = ChoiceSpec(
            title = "Saves on This Device",
            icon = FuseIcons.HardDrive,
            message = found.note ?: if (found.older.isNotEmpty()) {
                "$title, as ${found.emulator ?: "its emulator"} keeps it here. A folder it used before holds a newer save: choose it to use that one."
            } else {
                "$title, as ${found.emulator ?: "its emulator"} keeps it here. Fuse only reads these; a save sync moves them."
            },
            options = found.older.map { o -> olderSaveRow(id, title, found.emulator ?: "its emulator", o) } + found.saves.map { s ->
                MenuAction(
                    "save.${s.path}", s.name,
                    when (s.kind) {
                        io.github.matiyaaa.fuse.sync.SaveKind.STATE -> FuseIcons.Layers
                        io.github.matiyaaa.fuse.sync.SaveKind.MEMORY_CARD -> FuseIcons.Memory
                        else -> FuseIcons.Save
                    },
                    detail = "${s.kind.label}, ${io.github.matiyaaa.fuse.ui.shell.sync.sizeText(s.bytes)}. ${s.path}",
                    trailing = Trailing.Value(io.github.matiyaaa.fuse.ui.shell.components.agoText(s.modifiedAt)),
                    section = if (found.older.isNotEmpty()) "In Use" else null,
                    onSelect = {},
                )
            },
        )
    }
}

