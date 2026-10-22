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
    AppKind.PORT -> "a recomp or port"
}

/** What saying [kind] does to [title], for a message after the choice. */
private fun AppState.kindDone(title: String, kind: AppKind): String = when (kind) {
    AppKind.GAME -> if (store.apps.gamesInLibrary) "$title is in Android with your games" else "$title is under Games in Apps"
    AppKind.APP -> "$title is an app"
    AppKind.EMULATOR -> "$title is under Emulators in Apps"
    AppKind.STREAMING -> "$title is under Streaming in Apps"
    AppKind.TOOL -> "$title is under Tools in Apps"
    AppKind.PORT -> "$title is a recomp or port"
}

/** "Type" in an app's options: a game joins the Android system, an emulator lists under Emulators. */
fun AppState.appKindPicker(app: AppCard) {
    contextMenu = null
    val entry = app.entry
    fun pick(kind: AppKind?) {
        choice = null
        scope.launch {
            store.apps.setKind(entry.id, kind)
            // No longer a port: it plays no game, and Play goes back to the game's emulator.
            if (entry.kind == AppKind.PORT && kind != AppKind.PORT && entry.plays != null) store.ports.detach(entry.id)
        }
        toasts.show(kindDone(entry.displayTitle, kind ?: entry.detectedKind))
    }
    val gameDetail = if (store.apps.gamesInLibrary) "Joins the Android system with box art, details and play time" else "Lists under Games in Apps"
    choice = ChoiceSpec(
        icon = FuseIcons.CircleHelp,
        title = "What is ${entry.displayTitle}?",
        message = if (entry.chosenKind == null) "Fuse took it for ${entry.detectedKind.noun()}." else "You said it's ${entry.kind.noun()}.",
        options = listOf(
            MenuAction("game", "Game", FuseIcons.Gamepad, detail = gameDetail, trailing = Trailing.Check(entry.kind == AppKind.GAME), onSelect = { pick(AppKind.GAME) }),
        ) + listOfNotNull(
            // Android: a recomp or port of a game in the library, which Play on that game starts.
            if (store.ports.androidApps) {
                MenuAction("port", "Recomp or Port", FuseIcons.Boxes, detail = "A game of its own in Ports, or played in a game's place", trailing = Trailing.Check(entry.kind == AppKind.PORT), onSelect = {
                    choice = null
                    makeAppAPort(entry.id, entry.displayTitle, entry.packageName)
                })
            } else {
                null
            },
        ) + listOf(
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
            MenuAction(
                "folder", "A game folder", FuseIcons.FolderPlus,
                detail = "A folder with a game and its updates, DLC or discs, read as the library reads folders", trailing = Trailing.Chevron,
                onSelect = {
                    choice = null
                    scope.launch {
                        val path = platform.storage.pickFolder("Choose the game's folder") ?: return@launch
                        addGameFolder(path)
                    }
                },
            ),
        ),
    )
}

/** The picked folder: for the system chosen next, its game with every part of it joins the library. */
internal fun AppState.addGameFolder(path: String) {
    val name = FsPath.name(path)
    val all = PlatformCatalog.all.sortedBy { it.name.lowercase() }
    fun add(platform: PlatformId, platformName: String) {
        choice = null
        scope.launch {
            val ids = store.library.addGameFolder(path, platform)
            toasts.show(
                when (ids.size) {
                    0 -> "Fuse found no $platformName game in $name"
                    1 -> "$name is in $platformName now, with every part of it Fuse found"
                    else -> "${ids.size} games from $name are in $platformName now"
                },
                if (ids.isEmpty()) ToastKind.ERROR else ToastKind.SUCCESS,
            )
        }
    }
    choice = ChoiceSpec(
        icon = FuseIcons.Folder,
        title = "Which system is $name for?",
        message = "Fuse reads the folder the way it reads a system's folder: updates, DLC and discs inside it join their game.",
        options = all.map { p ->
            MenuAction("sys.${p.id.value}", p.name, null, detail = p.maker(), onSelect = { add(p.id, p.name) })
        },
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

/**
 * The picked APK: Android installs it after the user confirms. A port Fuse knows is said to be one
 * and attached to its game; any other is a game unless the person says what it is ("What is it?").
 */
internal fun AppState.installApkGame(path: String) {
    val name = FsPath.name(path)
    scope.launch {
        val port = if (store.ports.androidApps) store.ports.apkPort(path) else null
        val game = port?.games?.singleOrNull()
        confirm = ConfirmSpec(
            title = if (port != null) "Install ${port.port.name}?" else "Install $name?",
            message = when {
                port == null -> "Android asks you to confirm, and may ask to let Fuse install apps first. Once it's installed, Fuse asks what it is. The file stays where it is."
                game != null -> "It's ${port.port.game}, ${port.port.madeLine().replaceFirstChar { it.lowercase() }}. Android asks you to confirm; then ${game.title} plays with it. The file stays where it is."
                else -> "It's ${port.port.game}, ${port.port.madeLine().replaceFirstChar { it.lowercase() }}. Android asks you to confirm; then Fuse asks which game it plays. The file stays where it is."
            },
            confirmLabel = "Install",
            onConfirm = {
                scope.launch {
                    when (val r = store.apps.installGame(path)) {
                        is ApkInstall.Started -> {
                            navigator.pop()
                            when {
                                game != null -> {
                                    store.ports.attachOnceInstalled(r.packageName, game.id)
                                    toasts.show("Installing ${r.label}. ${game.title} plays with it once Android is done", icon = FuseIcons.Boxes)
                                }
                                port != null -> portForInstall(r.label, r.packageName)
                                else -> {
                                    toasts.show("Installing ${r.label}")
                                    whatIsNewApp(r.label, r.packageName)
                                }
                            }
                        }
                        is ApkInstall.Failed -> toasts.show(r.message, ToastKind.ERROR, durationMs = 6000)
                    }
                }
            },
        )
    }
}

/** "What is it?" for an app Android is installing from an APK: a game unless the person says otherwise. */
private fun AppState.whatIsNewApp(label: String, packageName: String) {
    fun said(kind: AppKind) {
        choice = null
        scope.launch { store.apps.expect(packageName, kind) }
        toasts.show(kindDone(label, kind))
    }
    choice = ChoiceSpec(
        icon = FuseIcons.CircleHelp,
        title = "What is $label?",
        message = "Fuse takes it for a game. Its options in Apps change that later too.",
        options = listOf(
            MenuAction("game", "Game", FuseIcons.Gamepad, detail = if (store.apps.gamesInLibrary) "Joins the Android system with box art, details and play time" else "Lists under Games in Apps", trailing = Trailing.Check(true), onSelect = { said(AppKind.GAME) }),
        ) + listOfNotNull(
            if (store.ports.androidApps) {
                MenuAction("port", "Recomp or Port", FuseIcons.Boxes, detail = "A game of its own in Ports, or played in a game's place", onSelect = {
                    choice = null
                    portForInstall(label, packageName)
                })
            } else {
                null
            },
        ) + listOf(
            MenuAction("app", "App", FuseIcons.AppWindow, detail = "Stays in Apps only", onSelect = { said(AppKind.APP) }),
            MenuAction("emulator", "Emulator", FuseIcons.Chip, detail = "Lists under Emulators in Apps", onSelect = { said(AppKind.EMULATOR) }),
            MenuAction("streaming", "Streaming", FuseIcons.Cast, detail = "Plays games from your PC or console. Lists under Streaming", onSelect = { said(AppKind.STREAMING) }),
            MenuAction("tool", "Tool", FuseIcons.Wrench, detail = "Frontends, drivers and other helpers. Lists under Tools", onSelect = { said(AppKind.TOOL) }),
        ),
    )
}

/** "What game is this?" for a recomp or port Android is installing, attached to the game once it is in. */
private fun AppState.portForInstall(label: String, packageName: String) {
    val alone: suspend () -> Unit = {
        store.apps.expect(packageName, AppKind.PORT, null)
        toasts.show("$label is in Ports as a game of its own once Android has installed it", ToastKind.SUCCESS, icon = FuseIcons.Boxes)
    }
    pickPortGame(label, packageName, alone = alone) { g ->
        store.ports.attachOnceInstalled(packageName, g.id)
        toasts.show("${g.title} plays with $label once Android has installed it", ToastKind.SUCCESS, icon = FuseIcons.Boxes)
    }
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
