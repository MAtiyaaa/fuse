package io.github.matiyaaa.fuse.ui.shell.cartridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.integrations.github.SemVer
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.CartridgeUploadItem
import io.github.matiyaaa.fuse.model.UploadState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.UploadHandoff
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Upload to RomM" for [card]: hands it to Cartridge, which shows every file (other discs, DLC and
 * updates included) and uploads after the user confirms there. Says what's needed when it can't.
 */
fun AppState.uploadToRomm(card: GameCard) {
    closeOverlays()
    val status = store.cartridge.status.value
    if (status.installed && !CartridgeProtocol.supportsUploads(status)) {
        cartridgeTooOld(status)
        return
    }
    scope.launch {
        when (store.cartridge.upload(card.id)) {
            UploadHandoff.OPENED -> Unit
            UploadHandoff.NOT_INSTALLED -> toasts.show("Install Cartridge from the Cartridge tab to upload games to RomM")
            UploadHandoff.TOO_OLD -> cartridgeTooOld(store.cartridge.status.value)
            UploadHandoff.NO_FILES -> toasts.show("Fuse can't find ${card.title}'s files. Rescan the library, then try again.", ToastKind.WARNING)
            UploadHandoff.FAILED -> toasts.show("Cartridge didn't open. Open it once, then try again.", ToastKind.ERROR)
        }
    }
}

/** Cartridge tab, "Upload a game": pick a system, then one of its games that isn't from RomM. */
fun AppState.uploadPicker() {
    val status = store.cartridge.status.value
    if (!CartridgeProtocol.supportsUploads(status)) {
        cartridgeTooOld(status)
        return
    }
    val systems = store.library.platforms.value.filter { it.gameCount > 0 }
    if (systems.isEmpty()) {
        toasts.show("Add a library folder first. Games you have here can then go to RomM.")
        return
    }
    choice = ChoiceSpec(
        title = "Upload a game to RomM",
        message = "Pick its system. Cartridge shows every file it would send and uploads after you confirm there.",
        options = systems.map { s ->
            MenuAction(
                "sys.${s.platform.id.value}", s.platform.name, FuseIcons.Chip,
                detail = "${s.gameCount} ${if (s.gameCount == 1) "game" else "games"}",
                trailing = Trailing.Chevron,
                onSelect = { gamePicker(s.platform.id, s.platform.name) },
            )
        },
    )
}

private fun AppState.gamePicker(platform: io.github.matiyaaa.fuse.model.PlatformId, name: String) {
    scope.launch {
        val games = store.library.games(GameQuery(platform = platform)).first()
        // Games Cartridge downloaded are on RomM already.
        val local = games.filter { it.rommRomId == null && !it.missing }.sortedBy { it.title.lowercase() }
        if (local.isEmpty()) {
            choice = null
            toasts.show("Every $name game here came from RomM already")
            return@launch
        }
        choice = ChoiceSpec(
            title = "Upload a $name game",
            message = if (local.size < games.size) "Games that came from RomM aren't listed." else null,
            options = local.map { g ->
                MenuAction(
                    "game.${g.id.value}", g.title, null,
                    detail = listOfNotNull(
                        g.discs.takeIf { it > 1 }?.let { "$it discs" },
                        g.dlc.takeIf { it > 0 }?.let { "$it DLC" },
                        g.updates.takeIf { it > 0 }?.let { "$it ${if (it == 1) "update" else "updates"}" },
                    ).joinToString("  ·  ").ifEmpty { null },
                    art = MenuArt(g.art.tile, square = true, fallbackTitle = g.title, accent = g.accent, wide = false),
                    onSelect = { uploadToRomm(g) },
                )
            },
        )
    }
}

/** This Cartridge can't take uploads: offer the newest one when it can, else say it's coming. */
private fun AppState.cartridgeTooOld(status: CartridgeStatus) {
    closeOverlays()
    confirm = ConfirmSpec(
        title = "Update Cartridge to upload games",
        message = "This Cartridge (${status.version ?: "an older version"}) can't take games from Fuse yet. A newer Cartridge shows each game's files and uploads them to your RomM server after you confirm.",
        confirmLabel = "Get the latest Cartridge",
        onConfirm = {
            scope.launch {
                val release = store.cartridge.latestRelease()
                val installed = status.version
                when {
                    release == null -> toasts.show("Couldn't reach GitHub to find Cartridge's latest release. Check your connection.")
                    installed != null && SemVer.atLeast(installed, release.tag.removePrefix("v")) ->
                        toasts.show("Cartridge ${release.tag.removePrefix("v")} is the newest release, and it can't take uploads yet. Its next update can.")
                    else -> store.cartridge.install(release).onFailure { toasts.show(it.message ?: "Couldn't install Cartridge", ToastKind.ERROR) }
                }
            }
        },
    )
}

/** What an upload is doing, in words. */
fun uploadStateText(u: CartridgeUploadItem): String = when (u.state) {
    UploadState.WAITING -> "Waiting in Cartridge"
    UploadState.UPLOADING -> u.progress?.let { "Uploading, ${(it * 100).toInt()}%" } ?: "Uploading"
    UploadState.SCANNING -> "Uploaded, RomM is adding it"
    UploadState.DONE -> "On RomM"
    UploadState.FAILED -> u.error ?: "Upload failed"
    UploadState.CANCELLED -> "Cancelled"
}

/**
 * The games handed to Cartridge to upload: the one going now as a card like the download's (art,
 * the bar in its system's colour, how far along and how long is left), then the latest few in a
 * quiet list under it.
 */
@Composable
internal fun UploadsPanel(uploads: List<CartridgeUploadItem>, modifier: Modifier = Modifier, maxOthers: Int = 4, platforms: List<io.github.matiyaaa.fuse.ui.shell.store.PlatformCard> = emptyList(), compact: Boolean = false, onOpen: () -> Unit = {}) {
    val c = Fuse.colors
    val current = uploads.firstOrNull { it.state == UploadState.UPLOADING || it.state == UploadState.WAITING }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (current != null) {
            val system = platforms.firstOrNull { it.platform.id.value == current.platformSlug }
            TransferCard(
                label = "UPLOADING TO ROMM",
                icon = FuseIcons.Upload,
                title = current.title,
                system = system,
                systemName = system?.platform?.name ?: current.platformSlug.uppercase(),
                slug = current.platformSlug,
                progress = current.progress,
                sizes = current.total?.let { "${bytesText(current.sent)} of ${bytesText(it)}" },
                timeLeft = rememberTimeLeft(current.sent, current.total),
                waiting = uploads.count { it !== current && it.active },
                selected = false,
                compact = compact,
                modifier = Modifier,
                note = listOfNotNull(system?.platform?.name ?: current.platformSlug.uppercase().ifEmpty { null }, current.files.takeIf { it > 1 }?.let { "$it files" }).joinToString("  ·  "),
                onClick = onOpen,
            )
        }
        val others = uploads.filter { it !== current }.take(maxOthers)
        if (others.isNotEmpty()) {
            Panel {
                Column(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    SectionLabel("Uploads to RomM")
                    for (u in others) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            FuseIcon(
                                when (u.state) {
                                    UploadState.DONE, UploadState.SCANNING -> FuseIcons.CircleCheck
                                    UploadState.FAILED -> FuseIcons.Warning
                                    UploadState.CANCELLED -> FuseIcons.CircleX
                                    else -> FuseIcons.Clock
                                },
                                size = 16.dp,
                                tint = when (u.state) {
                                    UploadState.DONE, UploadState.SCANNING -> c.success
                                    UploadState.FAILED -> c.warning
                                    else -> c.textMuted
                                },
                            )
                            FText(u.title, Fuse.type.body, maxLines = 1, modifier = Modifier.weight(1f))
                            FText(uploadStateText(u), Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.widthIn(max = 300.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Says once when an upload that was going finishes or fails, wherever the user is. */
@Composable
fun UploadFinishedToasts(app: AppState) {
    val status by app.store.cartridge.status.collectAsState()
    val seen = remember { HashMap<String, UploadState>() }
    LaunchedEffect(status.uploads) {
        for (u in status.uploads) {
            val was = seen.put(u.id, u.state) ?: continue
            if (was == u.state || !(was == UploadState.WAITING || was == UploadState.UPLOADING || was == UploadState.SCANNING)) continue
            when (u.state) {
                // Every byte is there once RomM starts adding it; that is when the upload is done for you.
                UploadState.SCANNING -> if (was == UploadState.UPLOADING || was == UploadState.WAITING) app.toasts.show("${u.title} is uploaded. RomM is adding it", ToastKind.SUCCESS)
                UploadState.DONE -> if (was != UploadState.SCANNING) app.toasts.show("${u.title} is on your RomM server now", ToastKind.SUCCESS)
                UploadState.FAILED -> app.toasts.show("Uploading ${u.title} failed${u.error?.let { ": $it" } ?: ""}", ToastKind.ERROR, durationMs = 7000)
                else -> Unit
            }
        }
    }
}
