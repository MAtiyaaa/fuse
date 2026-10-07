package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferLive
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.downloads.etaText
import io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf
import io.github.matiyaaa.fuse.ui.shell.downloads.speedText
import io.github.matiyaaa.fuse.ui.shell.downloads.statusWords
import io.github.matiyaaa.fuse.ui.shell.platform.RommImages
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/** A transfer the Downloads page has in focus: the second screen shows its game and how it is going. */
data class TransferSpot(val id: String)

/**
 * The room for a transfer: its game's own room once the game is in the library, else its cover
 * blurred into a room over its system's, else its system's room.
 */
@Composable
internal fun transferRoom(store: FuseStore, systems: List<PlatformCard>, spot: TransferSpot): HeroSource? {
    val rows by store.transfers.rows.collectAsState()
    val row = rows.firstOrNull { it.item.id == spot.id } ?: return null
    val system = row.item.platform?.let { p -> systems.firstOrNull { it.platform.id == PlatformId(p) } }
    val game = row.game
    if (game != null) {
        val flow = remember(game) { store.library.game(game) }
        val detail by flow.collectAsState(initial = null)
        detail?.let { d -> return gameRoom(spot, d.art, d.platform.accent, systems.firstOrNull { it.platform.id == d.platform.id }) }
    }
    val accent = system?.platform?.accent?.toColor() ?: Fuse.colors.accent
    val cover = RommImages.model(row.item.art.cover)
    return when {
        cover != null -> HeroSource(spot, cover, accent, blurred = true, placeholder = system?.art?.hero)
        system != null -> systemRoom(system).copy(id = spot)
        else -> HeroSource(spot, null, accent)
    }
}

/**
 * A transfer on the second screen, like a game there: its logo (or its name) over its art, and
 * where a game shows its achievements, a card with how it is going: down or up, how far, how fast
 * and how long is left. It follows the bytes as they move.
 */
@Composable
internal fun FocusedTransfer(store: FuseStore, spot: TransferSpot, logoArea: @Composable (logo: Any?, title: String, below: @Composable () -> Unit) -> Unit) {
    val rows by store.transfers.rows.collectAsState()
    val row = rows.firstOrNull { it.item.id == spot.id } ?: return
    val liveFlow = remember(spot.id) { store.transfers.live(spot.id) }
    val live by liveFlow.collectAsState()
    val game = row.game
    val detail = if (game != null) {
        val flow = remember(game) { store.library.game(game) }
        flow.collectAsState(initial = null).value
    } else null
    val logo = detail?.art?.logo ?: RommImages.model(row.item.art.logo)
    logoArea(logo, detail?.game?.displayTitle ?: row.item.title) {
        Spacer(Modifier.height(Space.xl))
        TransferCard(row.item, live, device = row.deviceName)
    }
}

/** How a transfer is going, in the achievements card's place and shape. */
@Composable
internal fun TransferCard(t: TransferItem, live: TransferLive, modifier: Modifier = Modifier, device: String? = null) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Radius.l)
    val tint = when (t.status) {
        TransferStatus.DONE -> c.success
        TransferStatus.FAILED -> c.danger
        TransferStatus.PAUSED, TransferStatus.CANCELLED -> c.textMuted
        else -> c.accent
    }
    val total = live.totalBytes ?: t.totalBytes
    val done = live.doneBytes.takeIf { it > 0 } ?: t.doneBytes
    val progress = when (t.status) {
        TransferStatus.DONE -> 1f
        else -> live.progress ?: t.progress
    }
    val ownHeadline = when (t.status) {
        TransferStatus.ACTIVE -> when (t.phase) {
            TransferPhase.STARTING -> "Starting"
            TransferPhase.VERIFYING -> "Checking it arrived whole"
            TransferPhase.PLACING -> "Putting it in place"
            TransferPhase.FINISHING -> if (t.upload) "RomM is adding it" else "Adding it to your library"
            else -> if (t.upload) "Uploading" else "Downloading"
        }
        TransferStatus.QUEUED -> if (t.upload) "Waiting to upload" else "Waiting to download"
        TransferStatus.WAITING -> when (t.waiting) {
            io.github.matiyaaa.fuse.transfer.WaitReason.NETWORK -> "Waiting for the connection"
            io.github.matiyaaa.fuse.transfer.WaitReason.DRIVE -> "Waiting for ${t.waitingFor ?: "its drive"}"
            io.github.matiyaaa.fuse.transfer.WaitReason.PLAYING -> "Paused while you play"
            io.github.matiyaaa.fuse.transfer.WaitReason.WIFI -> "Waiting for Wi-Fi"
            io.github.matiyaaa.fuse.transfer.WaitReason.DEVICE -> "Waiting for ${t.waitingFor ?: "the other device"}"
            null -> "Waiting"
        }
        TransferStatus.PAUSED -> "Paused"
        TransferStatus.FAILED -> "It didn't work"
        TransferStatus.DONE -> if (t.upload) "Sent" else "Downloaded"
        TransferStatus.CANCELLED -> "Cancelled"
    }
    // Another device's transfer says which device: "Downloading to Thor".
    val headline = if (device == null) ownHeadline else when (t.status) {
        TransferStatus.ACTIVE -> if (t.upload) "Uploading from $device" else "Downloading to $device"
        TransferStatus.WAITING -> if (t.source == "request") "Waiting for $device" else "$ownHeadline on $device"
        else -> "$ownHeadline on $device"
    }
    val moving = t.status == TransferStatus.ACTIVE && (t.phase == null || t.phase == TransferPhase.TRANSFERRING)
    // The second line: amounts, speed and time left while it moves, else what it waits for or why it stopped.
    val amount = if (total != null && total > 0) "${sizeOf(done)} of ${sizeOf(total)}" else null
    val detail = when {
        moving -> listOfNotNull(amount, live.speed.takeIf { it > 0 }?.let(::speedText)).joinToString("  ·  ").ifEmpty { null }
        t.status == TransferStatus.DONE -> total?.let(::sizeOf)
        t.status == TransferStatus.ACTIVE -> amount
        t.status == TransferStatus.WAITING && t.waiting == io.github.matiyaaa.fuse.transfer.WaitReason.NETWORK -> listOfNotNull(amount, "tries again by itself").joinToString("  ·  ")
        t.status == TransferStatus.WAITING || t.status == TransferStatus.PAUSED || t.status == TransferStatus.QUEUED -> amount
        else -> statusWords(t, live, null).takeIf { it != headline }
    }
    val left = if (moving) live.etaSeconds?.let(::etaText) else null
    Row(
        modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.ink.copy(alpha = 0.6f))
            .border(1.dp, c.text.copy(alpha = 0.08f), shape)
            .padding(start = 10.dp, end = Space.m, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            when {
                t.status == TransferStatus.ACTIVE && !moving -> Spinner(size = 16.dp, color = tint)
                else -> FuseIcon(
                    when (t.status) {
                        TransferStatus.DONE -> FuseIcons.CircleCheck
                        TransferStatus.FAILED -> FuseIcons.Warning
                        TransferStatus.PAUSED -> FuseIcons.Pause
                        TransferStatus.QUEUED, TransferStatus.WAITING -> FuseIcons.Clock
                        TransferStatus.CANCELLED -> FuseIcons.CircleX
                        TransferStatus.ACTIVE -> if (t.upload) FuseIcons.Upload else FuseIcons.Download
                    },
                    size = 17.dp, tint = tint,
                )
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(headline, Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f))
                if (progress != null && t.status != TransferStatus.DONE && t.status != TransferStatus.CANCELLED) {
                    FText("${(progress * 100).toInt()}%", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (progress != null) {
                ProgressBar(progress, Modifier.fillMaxWidth(), color = tint, height = 4.dp)
            } else {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)))
            }
            if (detail != null || left != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FText(detail.orEmpty(), Fuse.type.caption, color = if (t.status == TransferStatus.FAILED) c.danger else c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                    if (left != null) {
                        Spacer(Modifier.width(Space.s))
                        FText(left, Fuse.type.caption, color = c.text, maxLines = 1)
                    }
                }
            }
        }
    }
}
