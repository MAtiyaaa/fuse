package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.model.StoreVariant
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.addons.addStoreApp
import io.github.matiyaaa.fuse.ui.shell.addons.detail
import io.github.matiyaaa.fuse.ui.shell.addons.title
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.openStore
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import kotlinx.coroutines.launch

/**
 * The Store (Android): which edition of the Obtainium Emulation Pack it follows, the catalogue and
 * when it was fetched, whether installed apps are checked for updates by themselves, and a GitHub
 * token for people who check many apps (GitHub allows 60 checks an hour without one).
 */
@Composable
fun storeRows(app: AppState): List<MenuAction> {
    val ops = app.store.appStore
    val s by ops.state.collectAsState()
    val p by app.store.prefs.collectAsState()
    return buildList {
        if (s.desktop) {
            addAll(desktopStoreRows(app, s))
            return@buildList
        }
        add(app.choiceRow(
            "variant", "Edition", if (app.platform.features.secondScreen) FuseIcons.DualScreen else FuseIcons.Store, p.storeVariant,
            // Dual-Screen is only offered where there is a second screen (or it is already chosen).
            listOfNotNull<Pair<StoreVariant?, String>>(
                StoreVariant.STANDARD to StoreVariant.STANDARD.title(),
                (StoreVariant.DUAL_SCREEN to StoreVariant.DUAL_SCREEN.title()).takeIf { app.platform.features.secondScreen || s.variant == StoreVariant.DUAL_SCREEN },
            ),
            detail = "Which edition of the Obtainium Emulation Pack the Store follows" + if (s.recommended == StoreVariant.DUAL_SCREEN) ". Dual-Screen suits this device" else "",
            optionDetail = { it?.detail() },
        ) { v -> if (v != null) app.scope.launch { ops.chooseVariant(v) } })
        val c = s.catalogue
        add(MenuAction(
            "catalogue", "Catalogue", FuseIcons.Store,
            detail = when {
                c == null && s.refreshing -> "Loading"
                c == null -> s.refreshProblem ?: "Loads when the Store first opens"
                else -> listOfNotNull("${c.apps.size} apps", c.packVersion, "fetched ${agoText(c.fetchedAt)}").joinToString("  ·  ") +
                    (s.refreshProblem?.let { ". The last check failed: $it" } ?: "")
            },
            trailing = Trailing.Value(if (s.refreshing) "Checking" else "Check now"),
            enabled = p.storeVariant != null,
            onSelect = { ops.refresh() },
        ))
        add(MenuAction(
            "packrepo", "Catalogue source", FuseIcons.Link,
            detail = s.packRepo?.let { "Following ${it.removePrefix("https://")}, a catalogue in the pack's format" }
                ?: "The Obtainium Emulation Pack. A fork that publishes the same files can be followed instead",
            trailing = Trailing.Value(if (s.packRepo == null) "Change" else "Custom"),
            onSelect = {
                app.textInput = TextInputSpec(
                    title = "Catalogue source", initial = s.packRepo.orEmpty(), placeholder = "github.com/owner/project, or empty for the pack",
                    capitalize = false, doneLabel = "Use",
                ) { url ->
                    app.scope.launch {
                        val why = ops.setPackRepo(url.ifBlank { null })
                        app.toasts.show(why ?: if (url.isBlank()) "Following the Obtainium Emulation Pack again" else "Following ${url.trim().removePrefix("https://")}")
                    }
                }
            },
        ))
        addAll(customAppRows(app, s))
        add(toggleRow(
            "autocheck", "Check installed apps for updates", FuseIcons.CircleArrowDown, p.storeAutoCheck,
            "A little after Fuse starts, at most twice a day. Updates are never installed without you",
        ) { v -> app.store.updatePrefs { it.copy(storeAutoCheck = v) } })
        add(MenuAction(
            "token", "GitHub token", FuseIcons.Key,
            detail = if (s.hasGitHubToken) "Set. Checks use your own GitHub limit" else "Optional. GitHub allows 60 checks an hour without one; a token with no permissions raises that",
            trailing = Trailing.Value(if (s.hasGitHubToken) "Change" else "Add"),
            onSelect = {
                app.textInput = TextInputSpec(title = "GitHub token", initial = "", placeholder = "A token with no permissions", secret = true, capitalize = false, doneLabel = "Save") { t ->
                    app.scope.launch { ops.setGitHubToken(t) }
                }
            },
        ))
        if (s.hasGitHubToken) {
            add(MenuAction("tokenremove", "Remove the GitHub token", FuseIcons.Trash, destructive = true, onSelect = {
                app.confirm = ConfirmSpec("Remove the GitHub token?", "Checks go back to GitHub's limit for everyone.", "Remove") { app.scope.launch { ops.setGitHubToken(null) } }
            }))
        }
        add(MenuAction("open", "Open the Store", FuseIcons.External, onSelect = { app.openStore() }))
        add(infoRow(
            "about", "Where apps come from",
            detail = "The Obtainium Emulation Pack (github.com/RJNY/Obtainium-Emulation-Pack) lists each app and its official source. Fuse downloads from those sources over HTTPS only, checks each download is the app listed, and Android's installer asks you every time",
            icon = FuseIcons.ShieldCheck,
        ))
    }
}

/** Apps added by their address: adding one, and each one added (selecting it takes it out again). */
private fun customAppRows(app: AppState, s: io.github.matiyaaa.fuse.ui.shell.store.StoreState): List<MenuAction> = buildList {
    add(MenuAction(
        "addapp", "Add an app", FuseIcons.Plus,
        detail = "By its GitHub address. It goes on Other, or a shelf you pick",
        trailing = Trailing.Chevron,
        onSelect = { app.addStoreApp() },
    ))
    s.catalogue?.apps.orEmpty().filter { it.custom }.forEach { a ->
        add(MenuAction(
            "custom.${a.key}", a.name, FuseIcons.Package,
            detail = "${a.categories.firstOrNull() ?: "Other"}  ·  ${a.sourceUrl.removePrefix("https://")}",
            trailing = Trailing.Value("Take out"),
            indent = 1,
            onSelect = {
                app.confirm = ConfirmSpec("Take ${a.name} out of the Store?", "It leaves the Store's list. If it is installed, it stays installed.", "Take out") {
                    app.scope.launch { app.store.appStore.removeCustom(a.key) }
                }
            },
        ))
    }
}

/**
 * The Store on a computer: where programs go, checking for updates, apps added by address, and
 * a GitHub token. Its list is Fuse's own, so there is no edition or catalogue to choose.
 */
@Composable
private fun desktopStoreRows(app: AppState, s: io.github.matiyaaa.fuse.ui.shell.store.StoreState): List<MenuAction> {
    val ops = app.store.appStore
    val p by app.store.prefs.collectAsState()
    return buildList {
        add(infoRow("folder", "Programs go to", detail = s.folder ?: "Your Applications folder", icon = FuseIcons.FolderOpen))
        add(toggleRow(
            "autocheck", "Check installed programs for updates", FuseIcons.CircleArrowDown, p.storeAutoCheck,
            "A little after Fuse starts. Updates are never installed without you",
        ) { v -> app.store.updatePrefs { it.copy(storeAutoCheck = v) } })
        addAll(customAppRows(app, s))
        add(MenuAction(
            "token", "GitHub token", FuseIcons.Key,
            detail = if (s.hasGitHubToken) "Set. Checks use your own GitHub limit" else "Optional. GitHub allows 60 checks an hour without one; a token with no permissions raises that",
            trailing = Trailing.Value(if (s.hasGitHubToken) "Change" else "Add"),
            onSelect = {
                app.textInput = TextInputSpec(title = "GitHub token", initial = "", placeholder = "A token with no permissions", secret = true, capitalize = false, doneLabel = "Save") { t ->
                    app.scope.launch { ops.setGitHubToken(t) }
                }
            },
        ))
        add(MenuAction("open", "Open the Store", FuseIcons.External, onSelect = { app.openStore() }))
        add(infoRow(
            "about", "Where programs come from",
            detail = "Each program's own GitHub releases, over HTTPS only, checked against the checksum GitHub publishes. Linux gets AppImages only, Windows portable zips, macOS apps from their disk images; nothing is repackaged and no installer is run",
            icon = FuseIcons.ShieldCheck,
        ))
    }
}
