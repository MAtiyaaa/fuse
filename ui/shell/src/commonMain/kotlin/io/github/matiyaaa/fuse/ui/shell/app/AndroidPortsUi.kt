package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.AndroidPort
import io.github.matiyaaa.fuse.model.AndroidPortKind
import io.github.matiyaaa.fuse.model.AndroidPortStatus
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortOffer
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice
import io.github.matiyaaa.fuse.ui.shell.store.PortMatch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How [this] port was made, in a few words. */
internal fun AndroidPort.madeLine(): String = when (kind) {
    AndroidPortKind.RECOMPILATION -> "Recompiled for Android"
    AndroidPortKind.DECOMPILATION -> "Rebuilt from its decompilation"
    AndroidPortKind.SOURCE_PORT -> "Ported from its source"
    AndroidPortKind.RUNTIME_PORT -> "Its own code, run natively"
    AndroidPortKind.REIMPLEMENTATION -> "Recreated natively"
}

internal fun AndroidPortStatus.word(): String = when (this) {
    AndroidPortStatus.STABLE -> "stable"
    AndroidPortStatus.BETA -> "beta"
    AndroidPortStatus.ALPHA -> "alpha"
}

/** Whose word [this] port's entry is. */
internal fun AndroidPort.trustLine(): String = when {
    verified != null -> "Verified by Fuse's nightly check, $verified"
    checked -> "Checked by Fuse"
    else -> "Listed by Open Ports Games, unchecked"
}

/**
 * Play With's Android ports for [card] ([offers], best first): an installed one plays the game from
 * now on, one that isn't is installed from its project first, and any app the person has can be
 * attached to the game.
 */
internal fun AppState.androidPortChoices(card: GameCard, offers: List<AndroidPortOffer>): List<MenuAction> {
    val rows = offers.map { o ->
        val p = o.view.port
        val installed = o.view.installed
        val made = "${p.madeLine()}, ${p.status.word()}"
        val rom = if (o.match == PortMatch.ROM) ". Made for your ROM" else ""
        MenuAction(
            "aport.${p.id}", p.name,
            when {
                !installed -> FuseIcons.Download
                o.match == PortMatch.ATTACHED || p.checked -> FuseIcons.Boxes
                else -> FuseIcons.ShieldAlert
            },
            detail = when {
                o.match == PortMatch.ATTACHED -> "Your app, attached to this game. Its saves stay in the app"
                installed -> "$made. ${p.trustLine()}$rom. Its saves stay in the app"
                !p.installable -> "Not installed. $made. ${p.trustLine()}. Get it from its project's page"
                else -> "Not installed. $made. ${p.trustLine()}$rom"
            },
            trailing = Trailing.Check(o.chosen),
            onSelect = {
                choice = null
                when {
                    installed -> scope.launch {
                        val appId = o.view.appId ?: return@launch
                        val problem = store.ports.attach(appId, card.id)
                        if (problem != null) {
                            toasts.show(problem, ToastKind.WARNING)
                        } else {
                            toasts.show("${card.title} plays with ${p.name} from now on", ToastKind.SUCCESS, icon = FuseIcons.Boxes)
                            if (o.match != PortMatch.ATTACHED) portFirstStart(card, p.name, p.needs)
                        }
                    }
                    !p.installable -> platform.openUrl(p.repo)
                    else -> {
                        store.ports.installAndroid(p.id, card.id)
                        toasts.show("Installing ${p.name}. Android asks you to confirm, then ${card.title} plays with it", ToastKind.INFO, icon = FuseIcons.Download, durationMs = 6_000L)
                    }
                }
            },
        )
    }
    val attach = MenuAction(
        "aport.attach", "Attach an Installed App", FuseIcons.Link,
        detail = "A recomp or port you installed yourself, played in the emulator's place",
        onSelect = { attachAppPicker(card) },
    )
    return rows + attach
}

/** The installed apps, to attach one to [card] as a recomp or port of it. */
private fun AppState.attachAppPicker(card: GameCard) {
    scope.launch {
        val list = store.apps.everyApp().first().filter { it.entry.kind != AppKind.EMULATOR && it.entry.kind != AppKind.STREAMING }
        // Ones Fuse knows as recomps or ports first.
        val sorted = list.sortedWith(compareBy({ if (it.entry.kind == AppKind.PORT) 0 else 1 }, { it.entry.displayTitle.lowercase() }))
        choice = ChoiceSpec(
            icon = FuseIcons.Link,
            title = "Which app plays ${card.title}?",
            message = if (sorted.isEmpty()) "No app here can be attached." else "It becomes a recomp or port of ${card.title}, and Play starts it from now on. Its options in Apps change that back.",
            options = sorted.map { a ->
                MenuAction(
                    "app.${a.entry.id}", a.entry.displayTitle, null,
                    detail = if (a.entry.kind == AppKind.PORT) "Recomp or port" else null,
                    art = MenuArt(a.icon, square = true, fallbackTitle = a.entry.displayTitle, wide = false),
                    onSelect = {
                        choice = null
                        scope.launch {
                            val problem = store.ports.attach(a.entry.id, card.id)
                            if (problem != null) toasts.show(problem, ToastKind.WARNING)
                            else toasts.show("${card.title} plays with ${a.entry.displayTitle} from now on", ToastKind.SUCCESS, icon = FuseIcons.Link)
                        }
                    },
                )
            },
        )
    }
}

/**
 * What [port] asks for on its first start, and where the person's own copy of [card] is: the
 * app takes the game's files itself, into its own storage.
 */
internal fun AppState.portFirstStart(card: GameCard, port: String, needs: String?) {
    scope.launch {
        val path = store.library.game(card.id).first()?.game?.location?.launchPath?.takeIf { it.isNotBlank() && !it.startsWith("app:") }
        choice = ChoiceSpec(
            icon = FuseIcons.Boxes,
            title = "$port asks for your game the first time",
            message = listOfNotNull(
                needs ?: "It asks for ${card.title}'s files on its first start, and keeps what it needs.",
                path?.let { "Yours is at $it" },
            ).joinToString(". ").let { if (it.endsWith('.')) it else "$it." },
            options = listOfNotNull(
                path?.let { p ->
                    MenuAction("copy", "Copy Path", FuseIcons.Copy, detail = "To find it in the app's file picker", onSelect = {
                        choice = null
                        scope.launch {
                            if (platform.writeClipboardText(p)) toasts.show("Path copied", icon = FuseIcons.Copy)
                            else toasts.show("Fuse couldn't copy it here.", ToastKind.WARNING)
                        }
                    })
                },
                MenuAction("play", "Play Now", FuseIcons.Play, onSelect = {
                    choice = null
                    play(card)
                }),
            ),
        )
    }
}

/**
 * "What game is this?" for the app [label] ([packageName]): the games the port Fuse knows it as
 * plays first, then the likeliest by its name (or by [query], typed), and a search. [onPick] gets
 * the game chosen.
 */
internal fun AppState.pickPortGame(label: String, packageName: String, query: String? = null, onPick: suspend (PortGameChoice) -> Unit) {
    scope.launch {
        val guess = store.ports.gamesForApp(packageName, label, query)
        val rows = guess.games.map { g ->
            MenuAction(
                "g.${g.id.value}", g.title, FuseIcons.Gamepad,
                detail = if (g.plays && guess.port != null) "${g.system}  ·  ${guess.port.name} plays it" else g.system,
                onSelect = {
                    choice = null
                    scope.launch { onPick(g) }
                },
            )
        }
        val search = MenuAction("search", "Search the Library", FuseIcons.Search, section = "", onSelect = {
            textInput = TextInputSpec("Search the library", query ?: label, doneLabel = "Search", message = "The game $label plays") { q ->
                pickPortGame(label, packageName, q.trim().ifEmpty { null }, onPick)
            }
        })
        choice = ChoiceSpec(
            icon = FuseIcons.Boxes,
            title = "What game is $label?",
            message = when {
                rows.isEmpty() && query != null -> "No game in the library is like \"$query\". Search another name."
                rows.isEmpty() -> "Fuse found no game in the library like it. Search for the game it plays."
                guess.port != null -> "Fuse knows it as ${guess.port.name}, a port of ${guess.port.game}. Play on the game you choose starts it."
                else -> "The game it plays. Play on that game starts it from now on."
            },
            options = rows + search,
        )
    }
}

/**
 * Makes the app [appId] ([label], [packageName]) a recomp or port, and finds the game it plays, or
 * asks ([ask]: always, to change it).
 */
internal fun AppState.makeAppAPort(appId: String, label: String, packageName: String, ask: Boolean = false) {
    scope.launch {
        store.apps.setKind(appId, AppKind.PORT)
        val guess = store.ports.gamesForApp(packageName, label)
        val plays = guess.games.filter { it.plays }
        val only = plays.singleOrNull()?.takeIf { !ask }
        if (only != null) {
            val problem = store.ports.attach(appId, only.id)
            if (problem != null) toasts.show(problem, ToastKind.WARNING)
            else toasts.show("$label plays ${only.title} from now on. Its options in Apps change that", ToastKind.SUCCESS, icon = FuseIcons.Boxes, durationMs = 6_000L)
            return@launch
        }
        pickPortGame(label, packageName) { g ->
            val problem = store.ports.attach(appId, g.id)
            if (problem != null) toasts.show(problem, ToastKind.WARNING)
            else toasts.show("${g.title} plays with $label from now on", ToastKind.SUCCESS, icon = FuseIcons.Boxes)
        }
    }
}
