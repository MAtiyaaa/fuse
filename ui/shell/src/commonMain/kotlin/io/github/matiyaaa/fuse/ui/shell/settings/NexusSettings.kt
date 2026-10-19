package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.integrations.nexus.NexusMods
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.store.NexusAccount
import kotlinx.coroutines.launch

/**
 * Nexus Mods, with the person's own key: whose it is and whether downloads come straight from
 * Nexus Mods (Premium) or through the website, the key itself (kept on this device only, never shared
 * with the household or shown again), and where to get one. Only on a computer, where mods are added.
 */
@Composable
fun nexusRows(app: AppState): List<MenuAction> {
    if (app.platform.host == io.github.matiyaaa.fuse.model.Host.ANDROID) return emptyList()
    val stored by app.store.credentials.stored.collectAsState()
    val has = SecretKeys.NEXUS_API_KEY in stored
    var account by remember { mutableStateOf<NexusAccount?>(null) }
    LaunchedEffect(stored) { account = if (has) app.store.library.nexusAccount() else NexusAccount.NoKey }
    return buildList {
        add(
            when (val a = account) {
                null -> infoRow("nexus.status", "Your Account", "Checking", detail = "Asking Nexus Mods whose key this is", icon = FuseIcons.Hourglass)
                NexusAccount.NoKey -> infoRow("nexus.status", "Your Account", "Not set up", detail = "Add your own key to find mods for your games there, in each game's Patches and Cheats", icon = FuseIcons.Puzzle)
                is NexusAccount.Ready -> infoRow(
                    "nexus.status", "Your Account", a.name,
                    detail = if (a.premium) "Premium: files download straight from Nexus Mods" else "Files come through the website: each takes its Mod Manager Download link, or the file you downloaded",
                    icon = FuseIcons.CircleCheck,
                )
                is NexusAccount.Problem -> infoRow("nexus.status", "Your Account", "Not working", detail = a.reason, icon = FuseIcons.Warning)
            },
        )
        add(MenuAction(
            "nexus.key", "Your Nexus Mods Key", FuseIcons.Key,
            detail = "From your account's API Access page. Kept encrypted on this device only, never shared with your household",
            trailing = Trailing.Value(if (has) "Saved" else "Not set"),
            onSelect = {
                app.textInput = TextInputSpec("Your Nexus Mods key", "", placeholder = "Paste your personal API key", secret = true, capitalize = false, doneLabel = "Save") { v ->
                    app.scope.launch { if (v.isBlank()) app.store.credentials.remove(SecretKeys.NEXUS_API_KEY) else app.store.credentials.put(SecretKeys.NEXUS_API_KEY, v) }
                }
            },
        ))
        add(MenuAction(
            "nexus.page", "Get Your Key", FuseIcons.External, detail = "nexusmods.com, your account, API Access",
            onSelect = { app.platform.openUrl(NexusMods.KEY_PAGE) },
        ))
        if (has) add(MenuAction(
            "nexus.remove", "Remove the Key", FuseIcons.Trash, destructive = true,
            detail = "Mods already added stay as they are",
            onSelect = {
                app.confirm = ConfirmSpec(
                    title = "Remove your Nexus Mods key?",
                    message = "Fuse forgets it on this device. Mods you added stay in place, and the games you linked to Nexus Mods are remembered.",
                    confirmLabel = "Remove",
                    destructive = true,
                    onConfirm = { app.scope.launch { app.store.credentials.remove(SecretKeys.NEXUS_API_KEY) } },
                )
            },
        ))
    }
}
