package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.model.StoreVariant
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
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
        add(app.choiceRow(
            "variant", "Edition", FuseIcons.DualScreen, p.storeVariant,
            listOf<Pair<StoreVariant?, String>>(StoreVariant.STANDARD to StoreVariant.STANDARD.title(), StoreVariant.DUAL_SCREEN to StoreVariant.DUAL_SCREEN.title()),
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
