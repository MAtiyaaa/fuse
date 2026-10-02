package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.ApkInstall
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Group names in system lists: the likely ones first, then the rest from A to Z. */
private const val SUGGESTED = "Suggested"
private const val EVERY_SYSTEM = "Every system"

/** "Sega, 1994": who made the system and when, to tell similar names apart. */
private fun Platform.maker(): String? = listOfNotNull(manufacturer, releaseYear?.toString()).joinToString(", ").ifEmpty { null }

/** "a game", "an app" or "an emulator". */
private fun AppKind.noun(): String = when (this) {
    AppKind.GAME -> "a game"
    AppKind.APP -> "an app"
    AppKind.EMULATOR -> "an emulator"
    AppKind.STREAMING -> "a streaming app"
    AppKind.TOOL -> "a tool"
}

/** What saying [kind] does to [title], for a message after the choice. */
private fun AppState.kindDone(title: String, kind: AppKind): String = when (kind) {
    AppKind.GAME -> if (store.apps.gamesInLibrary) "$title is in Android with your games" else "$title is under Games in Apps"
    AppKind.APP -> "$title is an app"
    AppKind.EMULATOR -> "$title is under Emulators in Apps"
    AppKind.STREAMING -> "$title is under Streaming in Apps"
    AppKind.TOOL -> "$title is under Tools in Apps"
}

/** "Type" in an app's options: a game joins the Android system, an emulator lists under Emulators. */
fun AppState.appKindPicker(app: AppCard) {
    contextMenu = null
    val entry = app.entry
    fun pick(kind: AppKind?) {
        choice = null
        scope.launch { store.apps.setKind(entry.id, kind) }
        toasts.show(kindDone(entry.displayTitle, kind ?: entry.detectedKind))
    }
    val gameDetail = if (store.apps.gamesInLibrary) "Joins the Android system with box art, details and play time" else "Lists under Games in Apps"
    choice = ChoiceSpec(
        icon = FuseIcons.CircleHelp,
        title = "What is ${entry.displayTitle}?",
        message = if (entry.chosenKind == null) "Fuse took it for ${entry.detectedKind.noun()}." else "You said it's ${entry.kind.noun()}.",
        options = listOf(
            MenuAction("game", "Game", FuseIcons.Gamepad, detail = gameDetail, trailing = Trailing.Check(entry.kind == AppKind.GAME), onSelect = { pick(AppKind.GAME) }),
            MenuAction("app", "App", FuseIcons.AppWindow, detail = "Stays in Apps only", trailing = Trailing.Check(entry.kind == AppKind.APP), onSelect = { pick(AppKind.APP) }),
            MenuAction("emulator", "Emulator", FuseIcons.Chip, detail = "Lists under Emulators in Apps", trailing = Trailing.Check(entry.kind == AppKind.EMULATOR), onSelect = { pick(AppKind.EMULATOR) }),
            MenuAction("streaming", "Streaming", FuseIcons.Cast, detail = "Plays games from your PC or console. Lists under Streaming", trailing = Trailing.Check(entry.kind == AppKind.STREAMING), onSelect = { pick(AppKind.STREAMING) }),
            MenuAction("tool", "Tool", FuseIcons.Wrench, detail = "Frontends, drivers and other helpers. Lists under Tools", trailing = Trailing.Check(entry.kind == AppKind.TOOL), onSelect = { pick(AppKind.TOOL) }),
        ) + listOfNotNull(
            if (entry.chosenKind != null) {
                MenuAction("auto", "Let Fuse decide", FuseIcons.Sparkles, detail = "Fuse takes it for ${entry.detectedKind.noun()}", section = "", onSelect = { pick(null) })
            } else {
                null
            },
        ),
    )
}

/**
 * "System" in a game's options: files the game under another system for good (a rescan keeps it),
 * or back under the one its folder says. An Android game can go back to being an app.
 */
fun AppState.systemPicker(card: GameCard) {
    contextMenu = null
    scope.launch {
        val game = store.library.game(card.id).first()?.game ?: return@launch
        val current = game.platformId
        val folder = game.scannedPlatformId ?: current
        fun pick(platform: Platform) {
            choice = null
            if (platform.id == current) return
            scope.launch { store.library.setPlatform(card.id, platform.id) }
            toasts.show("${card.title} is in ${platform.name} now")
        }
        val appId = game.appId
        val suggested = if (appId != null) emptyList() else PlatformCatalog.forExtension(FsPath.extension(game.location.launchPath))
        val first = (listOfNotNull(PlatformCatalog.byId(folder)) + suggested).distinctBy { it.id }
        val rest = PlatformCatalog.all.filter { p -> first.none { it.id == p.id } }.sortedBy { it.name.lowercase() }
        fun row(p: Platform, detail: String?, section: String) = MenuAction(
            "p.${p.id.value}", p.name, null,
            detail = detail ?: p.maker(),
            trailing = Trailing.Check(p.id == current),
            section = section,
            onSelect = { pick(p) },
        )
        val ext = FsPath.extension(game.location.launchPath)
        choice = ChoiceSpec(
            icon = FuseIcons.Layers,
            title = "System for ${card.title}",
            message = if (appId != null) {
                "Android games start as their app whatever system they are in."
            } else {
                "Fuse keeps your choice when it rescans. Your files stay where they are."
            },
            options = buildList {
                if (appId != null) {
                    add(MenuAction("app", "Not a game", FuseIcons.AppWindow, detail = "Back to Apps only", onSelect = {
                        choice = null
                        scope.launch { store.apps.setKind(appId, AppKind.APP) }
                        toasts.show("${card.title} is an app again. It's in Apps")
                    }))
                }
                first.forEach { p ->
                    add(row(p, when {
                        appId != null && p.id == folder -> "Where Android games go"
                        p.id == folder -> "The system its folder says"
                        else -> "Takes .$ext files"
                    }, SUGGESTED))
                }
                rest.forEach { add(row(it, null, EVERY_SYSTEM)) }
            },
        )
    }
}

/** Settings, Library, "Add a game": an installed app, an APK file, or a game file from anywhere. */
fun AppState.addGame() {
    val apps = store.apps
    choice = ChoiceSpec(
        icon = FuseIcons.CirclePlus,
        title = "Add a game",
        message = "Fuse finds the games in your library folders by itself. Add one from anywhere else here.",
        options = listOfNotNull(
            if (apps.gamesInLibrary) {
                MenuAction(
                    "app", "An app on this device", FuseIcons.Smartphone,
                    detail = "An installed game joins the Android system", trailing = Trailing.Chevron,
                    onSelect = { appGamePicker() },
                )
            } else {
                null
            },
            if (apps.gamesInLibrary) {
                MenuAction(
                    "apk", "An APK file", FuseIcons.Package,
                    detail = "Android installs it after you confirm, then it joins your games", trailing = Trailing.Chevron,
                    onSelect = { choice = null; go(Route.PickFile(FilePurpose.APK)) },
                )
            } else {
                null
            },
            MenuAction(
                "file", "A game file", FuseIcons.File,
                detail = "A ROM or game file anywhere on this device, for the system you pick", trailing = Trailing.Chevron,
                onSelect = { choice = null; go(Route.PickFile(FilePurpose.GAME)) },
            ),
        ),
    )
}

/** Installed apps that aren't games yet; the one picked becomes a game. */
private fun AppState.appGamePicker() {
    scope.launch {
        val list = store.apps.everyApp().first().filter { it.entry.kind != AppKind.GAME }
        choice = ChoiceSpec(
            icon = FuseIcons.Smartphone,
            title = "Which app is a game?",
            message = if (list.isEmpty()) "Every app here is a game already." else "It joins the Android system with box art, details and play time. Its options can change it back.",
            options = list.map { a ->
                MenuAction(
                    "a.${a.entry.id}", a.entry.displayTitle, null,
                    detail = if (a.entry.kind == AppKind.EMULATOR) "Emulator" else null,
                    art = MenuArt(a.icon, square = true, fallbackTitle = a.entry.displayTitle, wide = false),
                    onSelect = {
                        choice = null
                        scope.launch { store.apps.setKind(a.entry.id, AppKind.GAME) }
                        toasts.show(kindDone(a.entry.displayTitle, AppKind.GAME))
                    },
                )
            },
        )
    }
}

/** The picked APK: Android installs it after the user confirms, then it joins the Android system. */
internal fun AppState.installApkGame(path: String) {
    val name = FsPath.name(path)
    confirm = ConfirmSpec(
        title = "Install $name?",
        message = "Android asks you to confirm, and may ask to let Fuse install apps first. Once it's installed, Fuse adds it to the Android system with your games. The file stays where it is.",
        confirmLabel = "Install",
        onConfirm = {
            scope.launch {
                when (val r = store.apps.installGame(path)) {
                    is ApkInstall.Started -> {
                        navigator.pop()
                        toasts.show("Installing ${r.label}. It joins your games once Android is done")
                    }
                    is ApkInstall.Failed -> toasts.show(r.message, ToastKind.ERROR, durationMs = 6000)
                }
            }
        },
    )
}

/** The picked game file: the system it belongs to, then it joins the library. */
internal fun AppState.addGameFile(path: String) {
    val name = FsPath.name(path)
    val suggested = PlatformCatalog.forExtension(FsPath.extension(path))
    val rest = PlatformCatalog.all.filter { p -> suggested.none { it.id == p.id } }.sortedBy { it.name.lowercase() }
    fun add(platform: PlatformId, platformName: String) {
        choice = null
        scope.launch {
            val id = store.library.addGameFile(path, platform)
            if (id == null) {
                toasts.show("Fuse can't read $name. Check that it's still there.", ToastKind.ERROR)
            } else {
                navigator.pop()
                toasts.show("$name is in $platformName now", ToastKind.SUCCESS)
            }
        }
    }
    choice = ChoiceSpec(
        icon = FuseIcons.FileText,
        title = "Which system is $name for?",
        message = if (suggested.isEmpty()) "Fuse doesn't know this kind of file. Pick the system whose emulator opens it." else null,
        options = suggested.map { p ->
            MenuAction("s.${p.id.value}", p.name, FuseIcons.Sparkles, detail = "Takes .${FsPath.extension(path)} files", section = SUGGESTED, onSelect = { add(p.id, p.name) })
        } + rest.map { p ->
            // Without suggestions the list is every system, and needs no heading to say so.
            MenuAction("p.${p.id.value}", p.name, null, detail = p.maker(), section = if (suggested.isEmpty()) null else EVERY_SYSTEM, onSelect = { add(p.id, p.name) })
        },
    )
}
