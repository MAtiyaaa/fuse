package io.github.matiyaaa.fuse.ui.shell.romm

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
 * Upload to RomM, through Fuse RomM: Fuse works out what belongs to the game (its discs, update, DLC),
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
    scope.launch {
        val why = store.romm.download(romId, what)
        if (why != null) toasts.show(why, ToastKind.WARNING)
        else toasts.show("$title is on its way", ToastKind.SUCCESS, icon = FuseIcons.Download)
    }
}
