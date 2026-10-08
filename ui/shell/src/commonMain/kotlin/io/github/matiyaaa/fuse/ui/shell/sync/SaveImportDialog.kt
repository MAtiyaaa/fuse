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
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.flow.first
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
            val games = app.store.library.games(GameQuery(includeHidden = true)).first()
            val queries = games.mapNotNull { app.store.sync.saveQuery(it.id) }
            service.previewSaveImport(source, queries).onSuccess { plan -> SaveImportPreview(app, plan).show() }
                .onFailure { app.toasts.show(it.message ?: "Couldn't inspect that save source", ToastKind.ERROR) }
        }
    }
    app.choice = ChoiceSpec("Import Saves", "Copy saves from another device or emulator. Fuse shows the destinations first and keeps existing saves in history. Sources are never moved or deleted.", listOf(
        MenuAction("file", "File or ZIP", FuseIcons.FileUp, detail = "One save or an archive containing many saves", onSelect = { choose(false) }),
        MenuAction("folder", "Folder", FuseIcons.FolderOpen, detail = "One game's saves or an entire emulator save hierarchy", onSelect = { choose(true) }),
    ), FuseIcons.Save)
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
                detail = "Current saves are kept first. Imported revisions enter normal Fuse Sync history, including while offline",
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
