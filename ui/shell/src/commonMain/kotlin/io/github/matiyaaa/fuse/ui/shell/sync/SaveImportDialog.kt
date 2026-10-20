package io.github.matiyaaa.fuse.ui.shell.sync

import io.github.matiyaaa.fuse.sync.SaveImportChoice
import io.github.matiyaaa.fuse.sync.SaveImportConfidence
import io.github.matiyaaa.fuse.sync.SaveImportPlan
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import kotlinx.coroutines.launch

/** Import is available locally too: the preview uses configured emulators, not an online host. */
internal fun openSaveImporter(app: AppState) {
    val service = app.store.sync.service ?: return
    fun choose(folder: Boolean) {
        app.choice = null
        app.scope.launch {
            val source = if (folder) app.platform.storage.pickFolder("Import a save folder") else app.platform.storage.pickSave("Import a save file or ZIP")
            if (source == null) return@launch
            app.toasts.show("Inspecting saves", icon = FuseIcons.Search)
            val (cards, queries) = cardsByGame(app)
            service.previewSaveImport(source, queries).onSuccess { plan ->
                // Saves copied off a Switch go through its own import, one game at a time.
                if (plan.entries.any { it.switch != null }) showSwitchImport(app, plan, cards, queries) else SaveImportPreview(app, plan).show()
            }
                .onFailure { app.toasts.show(it.message ?: "Couldn't inspect that save source", ToastKind.ERROR) }
        }
    }
    app.choice = ChoiceSpec("Import Saves", "Copy saves from another device or emulator. Fuse shows the destinations first and keeps existing saves in history. Sources are never moved or deleted.", listOf(
        MenuAction("file", "File or ZIP", FuseIcons.FileUp, detail = "One save or an archive containing many saves", onSelect = { choose(false) }),
        MenuAction("folder", "Folder", FuseIcons.FolderOpen, detail = "One game's saves or an entire emulator save hierarchy", onSelect = { choose(true) }),
        MenuAction("switch", "From a Switch", FuseIcons.SdCard, detail = "Saves backed up with JKSV or Checkpoint, from the SD card or a copy of its folders", onSelect = {
            app.choice = null
            if (app.platform.host == io.github.matiyaaa.fuse.model.Host.ANDROID) warnAboutTheCard(app) else app.scope.launch { chooseSwitchSource(app) }
        }),
        MenuAction("phone", "From Your Phone", FuseIcons.Smartphone, detail = "Share a JKSV folder or backup ZIP from your phone through Phone Link", onSelect = {
            app.choice = null
            fromPhone(app)
        }),
    ), FuseIcons.Save)
}

/**
 * Android's own warning, before a Switch's SD card is used here: Android checks and "repairs" a card
 * it mounts, and a Switch card's emuMMC settings files are what it tends to break, leaving the Switch
 * unable to start its emuMMC ("emuMMC is forced but not enabled"). The safer ways come first.
 */
private fun warnAboutTheCard(app: AppState) {
    app.choice = ChoiceSpec(
        "Before you use your Switch's card here",
        "Android checks every SD card put in it and \"repairs\" what it doesn't understand, and a Switch card is one it doesn't. " +
            "It can delete the small files your Switch needs to start its emuMMC, and then Hekate says \"emuMMC is forced but not enabled\". " +
            "Sending the saves from your phone, or a copy of the JKSV folder, never touches the card. If you use the card anyway, Fuse keeps a copy of those files and can put them back, and eject it before you take it out.",
        listOf(
            MenuAction("phone", "Send From Your Phone", FuseIcons.Smartphone, detail = "Recommended: share the JKSV folder or a ZIP through Phone Link", onSelect = {
                app.choice = null
                fromPhone(app)
            }),
            MenuAction("copy", "Choose a Copied Folder", FuseIcons.FolderOpen, detail = "The JKSV folder copied off the card, anywhere on this device", onSelect = {
                app.choice = null
                app.scope.launch { app.platform.storage.pickFolder("Choose your Switch's save backups (the JKSV folder, or one game's)")?.let { startSwitchImport(app, it) } }
            }),
            MenuAction("card", "Use the Card Anyway", FuseIcons.SdCard, detail = "Fuse only reads it. Eject it in Android before taking it out", onSelect = {
                app.choice = null
                app.scope.launch { chooseSwitchSource(app) }
            }),
        ),
        FuseIcons.Warning,
    )
}

/** Switch saves from a phone: Phone Link on, and its page shows how; the import opens by itself once they arrive. */
private fun fromPhone(app: AppState) {
    app.scope.launch {
        if (!app.store.prefs.value.phoneLinkEnabled) app.store.updatePrefs { it.copy(phoneLinkEnabled = true) }
        app.go(io.github.matiyaaa.fuse.ui.shell.app.Route.PhoneLink)
        app.toasts.show("On your phone, open Phone Link, then Tools, Send Switch Saves. The import opens here when they arrive", icon = FuseIcons.Smartphone)
    }
}

/**
 * Where a Switch's backups are: a Switch SD card Fuse sees plugged in (straight in, through an
 * adapter or a reader), else a folder or one ZIP the person picks (the card's JKSV folder copied
 * anywhere, a phone's storage, a USB drive). JKSV keeps each backup as a folder or a ZIP; both are read.
 */
private suspend fun chooseSwitchSource(app: AppState) {
    val service = app.store.sync.service ?: return
    val cards = service.switchCards()
    fun pick(zip: Boolean) {
        app.choice = null
        app.scope.launch {
            val path = if (zip) app.platform.storage.pickSave("Choose a JKSV or Checkpoint backup ZIP")
            else app.platform.storage.pickFolder("Choose your Switch's save backups (the JKSV folder, or one game's)")
            path?.let { startSwitchImport(app, it) }
        }
    }
    // A card that lost its emuMMC settings since Fuse last saw it: putting them back comes first.
    val repairs = cards.filter { it.emuMmcLost.isNotEmpty() }.map { c ->
        MenuAction("repair:${c.root}", "Put Back ${c.name}'s emuMMC Settings", FuseIcons.Wrench,
            detail = "${c.emuMmcLost.size} ${if (c.emuMmcLost.size == 1) "file" else "files"} your Switch needs to start its emuMMC went missing. Fuse kept a copy", onSelect = {
                app.choice = null
                app.scope.launch {
                    service.restoreEmuMmc(c)
                        .onSuccess { n -> app.toasts.show("Put back $n emuMMC ${if (n == 1) "file" else "files"}. Eject the card before taking it out", ToastKind.SUCCESS) }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't put the emuMMC settings back", ToastKind.ERROR) }
                }
            })
    }
    val found = repairs + cards.filter { it.path.isNotEmpty() }.map { c ->
        MenuAction("card:${c.path}", c.name, FuseIcons.SdCard, detail = "Plugged in: ${c.what}", onSelect = {
            app.choice = null
            startSwitchImport(app, c.path)
        })
    }
    app.choice = ChoiceSpec(
        "From a Switch",
        if (cards.isEmpty()) "Plug the Switch's SD card in (an adapter or card reader works), or copy its JKSV folder anywhere, then choose it. Backups can be folders or ZIPs."
        else "Fuse found your Switch's SD card. Or choose a copy of its JKSV folder, or one backup ZIP.",
        found + listOf(
            MenuAction("switch-folder", "Folder", FuseIcons.FolderOpen, detail = "The JKSV folder, Checkpoint's saves folder, or one game's folder", onSelect = { pick(false) }),
            MenuAction("switch-zip", "One ZIP", FuseIcons.FileUp, detail = "A single JKSV backup ZIP, from the card or wherever you copied it", onSelect = { pick(true) }),
            MenuAction("switch-phone", "From Your Phone", FuseIcons.Smartphone, detail = "Share the JKSV folder or a ZIP through Phone Link", onSelect = {
                app.choice = null
                fromPhone(app)
            }),
        ),
        FuseIcons.SdCard,
    )
}

/** The same dialog/menu primitives support controller, keyboard, touch and mouse correction. */
private class SaveImportPreview(private val app: AppState, private val plan: SaveImportPlan) {
    private val service = app.store.sync.service!!
    private val selected = plan.entries.mapNotNull { e -> e.selectedTarget?.let { e.id to it } }.toMap().toMutableMap()
    private var profile = service.activeProfile.value?.id

    fun show() {
        val who = service.profiles.value.firstOrNull { it.id == profile }
        app.choice = ChoiceSpec("Import Saves", "${plan.sourceName}. Review each destination. ${plan.unmatched.size} unmatched files stay untouched. Unsupported emulator states are never converted.", buildList {
            add(MenuAction("profile", "Whose Saves?", FuseIcons.Users, trailing = Trailing.Value(who?.name ?: "Choose a profile"), onSelect = {
                app.choice = ChoiceSpec("Whose Saves?", "Imported saves belong to one profile. Existing saves stay in their owner's history.", service.profiles.value.map { p ->
                    MenuAction(p.id, p.name, FuseIcons.UserRound, onSelect = { profile = p.id; show() })
                }, FuseIcons.Users)
            }))
            for (entry in plan.entries) {
                val target = entry.targets.firstOrNull { it.id == selected[entry.id] }
                add(MenuAction(entry.id, entry.name, FuseIcons.Save,
                    detail = if (target == null) "${entry.sourceFormat}. Choose a game or leave this out" else "${target.query.title} · ${target.query.platform} · ${target.query.emulatorId}\n${entry.sourceFormat} to ${target.format}${if (target.conversion) " (converted)" else ""}\n${target.destination}",
                    trailing = Trailing.Value(if (target == null) "Not selected" else confidence(target.confidence)), onSelect = {
                        app.choice = ChoiceSpec("Match Save", entry.name + ". Only compatible destinations are offered.", listOf(
                            MenuAction("skip", "Leave This Out", FuseIcons.CircleSlash, onSelect = { selected.remove(entry.id); show() }),
                        ) + entry.targets.map { t ->
                            MenuAction(t.id, t.query.title, FuseIcons.Gamepad,
                                detail = "${t.query.platform} · ${t.query.emulatorId} · ${confidence(t.confidence)}\n${t.destination}\n${entry.sourceFormat} to ${t.format}${if (t.conversion) " (converted)" else ""}",
                                onSelect = { selected[entry.id] = t.id; show() })
                        }, FuseIcons.Save)
                    }))
            }
            if (plan.unmatched.isNotEmpty()) add(MenuAction("unmatched", "Unmatched Content", FuseIcons.FileSearch,
                detail = "${plan.unmatched.size} files will not be imported", onSelect = {
                    app.choice = ChoiceSpec("Unmatched Content", "These files have no confidently supported save destination. Nothing writes them into an emulator.", plan.unmatched.mapIndexed { i, name ->
                        MenuAction("file.$i", name, FuseIcons.File, unavailableReason = "No supported destination")
                    } + MenuAction("back", "Back to Import", FuseIcons.ArrowLeft, onSelect = { show() }), FuseIcons.FileSearch)
                }))
            add(MenuAction("import", "Import ${selected.size} Saves", FuseIcons.FileUp,
                detail = "Current saves are kept first. Imported revisions enter normal Fuse One history, including while offline",
                unavailableReason = if (who == null) "Choose whose saves these are" else if (selected.isEmpty()) "Choose a save destination" else null,
                onSelect = { confirm() }))
            add(MenuAction("cancel", "Cancel Import", FuseIcons.CircleX, onSelect = {
                app.choice = null
                app.scope.launch { service.discardSaveImport(plan) }
            }))
        }, FuseIcons.Save)
    }

    private fun confirm() {
        val owner = profile ?: return
        val name = service.profiles.value.firstOrNull { it.id == owner }?.name ?: return
        app.choice = null
        app.confirm = ConfirmSpec("Import saves for $name?", "${selected.size} selected saves will replace their compatible local destinations. Fuse preserves the current saves in history first. Your source stays untouched.", "Import Saves") {
            app.scope.launch {
                service.importSaves(plan, selected.map { SaveImportChoice(it.key, it.value) }, owner)
                    .onSuccess { r ->
                        app.toasts.show("${r.imported} saves imported${if (r.queued) ", waiting to sync" else ""}${if (r.failures.isNotEmpty()) ". ${r.failures.size} failed" else ""}",
                            if (r.failures.isEmpty()) ToastKind.SUCCESS else ToastKind.ERROR, icon = FuseIcons.Save)
                        if (r.failures.isNotEmpty()) {
                            val failed = r.failures.map { it.entry }.toSet()
                            selected.keys.retainAll(failed)
                            app.choice = ChoiceSpec("Import Results", "${r.imported} saves were imported. These saves could not be imported.",
                                r.failures.map { failure -> MenuAction(failure.entry, failure.name, FuseIcons.Save, detail = failure.message, unavailableReason = "Import failed") } +
                                    MenuAction("retry", "Review Failed Saves", FuseIcons.ArrowLeft, onSelect = { show() }), FuseIcons.Save)
                        }
                    }
                    .onFailure { e -> app.toasts.show(e.message ?: "Couldn't import the selected saves", ToastKind.ERROR); show() }
            }
        }
    }

    private fun confidence(value: SaveImportConfidence): String = when (value) {
        SaveImportConfidence.TITLE_ID -> "Title ID"
        SaveImportConfidence.EXACT_NAME -> "Exact name"
        SaveImportConfidence.MANUAL -> "Confirm match"
    }
}
