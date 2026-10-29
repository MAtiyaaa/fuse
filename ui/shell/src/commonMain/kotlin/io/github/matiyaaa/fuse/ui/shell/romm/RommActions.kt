package io.github.matiyaaa.fuse.ui.shell.romm

import io.github.matiyaaa.fuse.ui.shell.reach.downloadAsking
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat
import kotlinx.coroutines.launch

/**
 * Upload to RomM, through RomM: Fuse works out what belongs to the game (its discs, update, DLC),
 * shows every file and the total before anything goes (unless the person turned that off), then the
 * upload runs in Downloads. A read-only sign-in is told what it needs instead.
 */
fun AppState.rommUpload(card: GameCard) {
    closeOverlays()
    scope.launch {
        val plan = store.romm.uploadPlan(card.id)
        if (plan == null) {
            toasts.show("Fuse can't find ${card.title}'s files. Rescan the library, then try again.", ToastKind.WARNING)
            return@launch
        }
        if (plan.needsPermission) {
            choice = ChoiceSpec(
                title = "Uploads need permission",
                message = "Fuse's RomM sign-in can only read. Pair Fuse again and allow uploads, then send ${card.title}.",
                icon = FuseIcons.Lock,
                options = listOf(MenuAction("perm.pair", "Pair Again", FuseIcons.Link, onSelect = { choice = null; go(Route.RommSetup(pairing = true)) })),
            )
            return@launch
        }
        plan.problem?.let {
            toasts.show(it, ToastKind.WARNING)
            return@launch
        }
        fun send() {
            scope.launch {
                val why = store.romm.upload(card.id, plan)
                if (why != null) toasts.show(why, ToastKind.WARNING)
                else toasts.show("${card.title} is going to RomM", ToastKind.SUCCESS, icon = io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.CloudUpload)
            }
        }
        if (!store.prefs.value.romm.confirmUploads) {
            send()
            return@launch
        }
        choice = ChoiceSpec(
            title = "Upload ${card.title} to RomM?",
            message = "${plan.files.size} ${if (plan.files.size == 1) "file" else "files"}, ${sizeOf(plan.totalBytes)}, to ${plan.platformName} on ${plan.serverName}. Files RomM already has exactly are left out.",
            icon = FuseIcons.CloudUpload,
            options = listOf(MenuAction("up.go", "Upload", FuseIcons.Upload, onSelect = { choice = null; send() })) +
                plan.files.mapIndexed { i, f ->
                    MenuAction(
                        "up.f.$i", f.name, if (f.kind == ContentKind.GAME) FuseIcons.Gamepad else FuseIcons.Package,
                        detail = listOfNotNull(f.folder.ifBlank { null }?.let { "In $it" }, sizeOf(f.sizeBytes)).joinToString("  ·  "),
                        enabled = false,
                    )
                },
        )
    }
}

/** Queues [what] of RomM game [romId] and says so. */
fun AppState.rommDownload(romId: Long, title: String, what: RommDownloadWhat = RommDownloadWhat.Game) {
    // With the household's devices linked, the game comes from wherever is best (another device at
    // home with exactly RomM's files first), RomM itself when no device has it. Copies of different
    // sizes (RomM's with its DLC, a device's without) are the person's to pick between.
    if (what == RommDownloadWhat.Game && store.reach.state.value.supported) {
        val id = io.github.matiyaaa.fuse.ui.shell.store.rommGameId(romId)
        downloadAsking(id, title) { store.reach.download(id) }
        return
    }
    scope.launch {
        val why = store.romm.download(romId, what)
        if (why != null) toasts.show(why, ToastKind.WARNING)
        else toasts.show("$title is on its way", ToastKind.SUCCESS, icon = FuseIcons.Download)
    }
}

/** Queues RomM game [romId] kept for later (a charger and Wi-Fi, no game running) and says so. */
fun AppState.rommDownloadLater(romId: Long, title: String) {
    scope.launch {
        val why = store.romm.downloadLater(romId)
        if (why != null) toasts.show(why, ToastKind.WARNING)
        else toasts.show("$title goes once this device is charging on Wi-Fi", ToastKind.SUCCESS, icon = FuseIcons.PlugZap)
    }
}

private fun gamesText(n: Int) = "$n ${if (n == 1) "game" else "games"}"

/**
 * Every game of [title] (a system or a collection on RomM) that isn't here yet: how much it is and
 * which drives it goes to are shown first, beside what is already on its way there, then it is
 * queued now or kept for later. When it doesn't all fit, what fits can go, or room can be made.
 */
fun AppState.rommDownloadAll(title: String, romIds: List<Long>) {
    closeOverlays()
    scope.launch {
        val most = io.github.matiyaaa.fuse.ui.shell.store.RommBatch.MOST
        val whole = store.romm.batchPlan(romIds)
        // More than one batch: the plan is the first batch's, so its room is what it says.
        val plan = if (whole.romIds.size > most) store.romm.batchPlan(whole.romIds.take(most)).copy(unplaced = whole.unplaced) else whole
        if (plan.romIds.isEmpty()) {
            toasts.show(
                if (plan.unplaced > 0) "Fuse can't tell where these go. Choose where games go in Settings, Addons, RomM, Library." else "Everything in $title is here or on its way",
                if (plan.unplaced > 0) ToastKind.WARNING else ToastKind.INFO,
            )
            return@launch
        }
        fun queueAll(ids: List<Long>, later: Boolean) {
            choice = null
            toasts.show("Adding ${gamesText(ids.size)} to Downloads", icon = FuseIcons.Download)
            scope.launch {
                val r = store.romm.downloadMany(ids, later)
                when {
                    r.queued == 0 -> toasts.show(r.problem ?: "None of them could be queued", ToastKind.WARNING)
                    r.failed > 0 -> toasts.show("${gamesText(r.queued)} on the way. ${r.failed} couldn't be: ${r.problem}", ToastKind.WARNING, icon = FuseIcons.Download)
                    later -> toasts.show("${gamesText(r.queued)} go once this device is charging on Wi-Fi", ToastKind.SUCCESS, icon = FuseIcons.PlugZap)
                    else -> toasts.show("${gamesText(r.queued)} on the way", ToastKind.SUCCESS, icon = FuseIcons.Download)
                }
            }
        }
        val count = whole.romIds.size
        val lines = buildList {
            add("${sizeOf(plan.bytes)} in all.")
            if (plan.unplaced > 0) add("${gamesText(plan.unplaced)} of systems Fuse can't place stay on RomM.")
            if (count > most) add("That's the first $most of $count: Fuse queues $most at a time so Downloads stays light, and the rest can follow once these are in.")
            if (!plan.fits) add("There isn't room for all of them beside what is already on its way.")
        }
        val later = "Goes once this device is charging on Wi-Fi, with no game running"
        val options = if (plan.fits) listOf(
            MenuAction("all.now", "Download Now", FuseIcons.Download, detail = "Each takes its turn in Downloads", onSelect = { queueAll(plan.romIds, later = false) }),
            MenuAction("all.later", "Download Later", FuseIcons.PlugZap, detail = later, onSelect = { queueAll(plan.romIds, later = true) }),
        ) else listOfNotNull(
            MenuAction(
                "all.fit", "Download What Fits", FuseIcons.Download, detail = "${gamesText(plan.fitting.size)}, ${sizeOf(plan.fittingBytes)}",
                onSelect = { queueAll(plan.fitting, later = false) },
            ).takeIf { plan.fitting.isNotEmpty() },
            MenuAction("all.fitlater", "What Fits, Later", FuseIcons.PlugZap, detail = later, onSelect = { queueAll(plan.fitting, later = true) }).takeIf { plan.fitting.isNotEmpty() },
            MenuAction("all.room", "Make Room", FuseIcons.Sparkles, detail = "Games not played for a long time, to look over before any go", onSelect = { choice = null; go(Route.Storage) }),
        )
        choice = ChoiceSpec(
            title = "Download ${gamesText(plan.romIds.size)} from $title?",
            message = lines.joinToString(" "),
            icon = FuseIcons.CloudDownload,
            options = options + plan.drives.mapIndexed { i, d ->
                MenuAction(
                    "all.drive.$i", d.label, if (d.fits) FuseIcons.HardDrive else FuseIcons.Warning,
                    detail = listOfNotNull(gamesText(d.games), sizeOf(d.bytes), d.free?.let { "${sizeOf(it)} free" }).joinToString("  ·  "),
                    enabled = false,
                )
            },
        )
    }
}

/**
 * A system's missing BIOS from the person's RomM server: Fuse works out which files the system
 * needs that are not here, shows them, and downloads them into the system's firmware folder. Files
 * already here, by name, are never replaced.
 */
fun AppState.rommBios(platform: io.github.matiyaaa.fuse.model.PlatformId, name: String) {
    closeOverlays()
    scope.launch {
        val picks = store.romm.biosPlan(all = false).filter { it.platform == platform.value }
        if (picks.isEmpty()) {
            toasts.show("Your RomM server has no $name firmware this device is missing", ToastKind.INFO, icon = FuseIcons.Info)
            return@launch
        }
        choice = ChoiceSpec(
            title = "$name BIOS from RomM",
            message = "${picks.size} ${if (picks.size == 1) "file" else "files"} from your server. Files already here are never replaced.",
            icon = FuseIcons.Chip,
            options = listOf(MenuAction("bios.go", "Download ${picks.size}", FuseIcons.Download, onSelect = {
                choice = null
                scope.launch {
                    val n = store.romm.downloadBios(picks)
                    toasts.show("$n $name BIOS ${if (n == 1) "file is" else "files are"} on the way", ToastKind.SUCCESS, icon = FuseIcons.Download)
                }
            })) + picks.map { p -> MenuAction("bios.${p.firmware.id}.${p.destination}", p.firmware.fileName, FuseIcons.File, detail = p.why, enabled = false) },
        )
    }
}
