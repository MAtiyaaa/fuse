package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.Badge
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.Availability
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseCheck
import io.github.matiyaaa.fuse.ui.shell.store.Standing
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import io.github.matiyaaa.fuse.ui.shell.store.StoreJob
import io.github.matiyaaa.fuse.ui.shell.store.StoreState
import kotlin.math.abs

/** The Store's own colour where an app's category has none. */
internal const val STORE_TINT = 0xFF2BB673L

/** An app's colour: its category's, turned a little by its name so neighbours in a shelf differ. */
internal fun StoreApp.tint(): Color {
    val base = (color ?: STORE_TINT).toColor()
    val turn = (abs(name.hashCode()) % 7 - 3) / 3f
    return if (turn >= 0) lerp(base, Color(0xFF3B5BDB), turn * 0.18f) else lerp(base, Color(0xFFE8590C), -turn * 0.18f)
}

/** One or two letters for an app without an icon: the initials of a name in words, else its first letter. */
internal fun monogram(name: String): String {
    val words = name.split(' ', '-', '_', '(', ')').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * An app's mark: its own icon once it is installed, else a monogram on a lit squircle in its
 * colour (the pack carries no icons, and Fuse never invents one).
 */
@Composable
internal fun AppMark(app: StoreApp, icon: Any?, size: Dp, modifier: Modifier = Modifier) {
    val tint = app.tint()
    val shape = remember { SquircleShape.fraction(0.3f) }
    val letters: @Composable () -> Unit = {
        Box(
            Modifier.size(size).clip(shape)
                .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.12f), lerp(tint, Color.Black, 0.42f))))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
            contentAlignment = Alignment.Center,
        ) {
            val text = monogram(app.name)
            val style = if (size >= 72.dp) Fuse.type.display else Fuse.type.title
            FText(
                text,
                style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * (if (text.length > 1) 0.82f else 1f)),
                color = Color.White,
                maxLines = 1,
            )
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (icon == null) letters() else Artwork(icon, Modifier.size(size), contentScale = ContentScale.Fit, loading = false, fallback = letters)
    }
}

/** Short names of the systems an app plays ("PS2", "GC"), at most [max]. */
internal fun systemNames(ids: List<PlatformId>, max: Int = 3): List<String> =
    ids.mapNotNull { PlatformCatalog.byId(it)?.shortName }.distinct().take(max)

/** What a card says about an app's state, as a badge; null when there is nothing to say. */
@Composable
internal fun StateBadge(state: StoreState, app: StoreApp, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val job = state.jobs[app.key]
    when {
        job is StoreJob.Failed -> Badge("Didn't work", modifier, color = c.danger, filled = false, icon = FuseIcons.Alert)
        job is StoreJob.NeedsPermission -> Badge("Needs permission", modifier, color = c.warning, filled = false, icon = FuseIcons.Lock)
        job is StoreJob.Installing -> Badge(if (job.waitingTurn) "Waiting" else "Installing", modifier, color = c.accent, filled = false)
        job is StoreJob.Uninstalling -> Badge("Removing", modifier, color = c.textMuted, filled = false)
        job != null -> Badge(jobShort(job), modifier, color = c.accent, filled = false, icon = FuseIcons.ArrowDownToLine)
        app.key in state.installed -> when (state.standing(app.key)) {
            Standing.UPDATE -> Badge("Update", modifier, color = c.accent, icon = FuseIcons.CircleArrowDown)
            else -> Badge("Installed", modifier, color = c.success, filled = false, icon = FuseIcons.Check)
        }
        app.availability == Availability.TRACK_ONLY -> Badge("Track only", modifier, color = c.textMuted, filled = false, icon = FuseIcons.Eye)
        app.availability == Availability.MANUAL -> Badge("Manual", modifier, color = c.textMuted, filled = false, icon = FuseIcons.External)
        else -> Unit
    }
}

/** A job in a word or two, with its progress. */
internal fun jobShort(job: StoreJob): String = when (job) {
    StoreJob.Waiting -> "Queued"
    StoreJob.Resolving -> "Preparing"
    is StoreJob.Downloading -> job.progress?.let { "${(it * 100).toInt()}%" } ?: bytesText(job.bytes)
    StoreJob.Verifying -> "Checking"
    StoreJob.NeedsPermission -> "Needs permission"
    is StoreJob.Installing -> if (job.waitingTurn) "Waiting" else "Installing"
    StoreJob.Uninstalling -> "Removing"
    is StoreJob.Failed -> "Didn't work"
}

/** A job as a sentence, for the app's page. */
internal fun jobLine(job: StoreJob, name: String): String = when (job) {
    StoreJob.Waiting -> "Queued. Two downloads run at a time."
    StoreJob.Resolving -> "Finding the newest release."
    is StoreJob.Downloading -> listOfNotNull(
        "Downloading",
        job.total?.let { "${bytesText(job.bytes)} of ${bytesText(it)}" } ?: bytesText(job.bytes).takeIf { job.bytes > 0 },
    ).joinToString("  ·  ")
    StoreJob.Verifying -> "Checking the download is $name, as the Store lists it."
    StoreJob.NeedsPermission -> "Allow Fuse to install apps in Android's settings. The install carries on by itself."
    is StoreJob.Installing -> if (job.waitingTurn) "Waiting for the install before it to finish." else "Confirm in Android's installer."
    StoreJob.Uninstalling -> "Confirm in Android to remove $name."
    is StoreJob.Failed -> job.message
}

/** The progress of a job, 0..1, or null when it isn't known or there is none to show. */
internal fun jobProgress(job: StoreJob?): Float? = (job as? StoreJob.Downloading)?.progress

/** The main thing an app's page (and A on its card) does now. */
internal data class StoreAction(
    val label: String,
    val icon: ImageVector,
    val kind: ButtonKind,
    val busy: Boolean = false,
    val run: () -> Unit,
)

/**
 * The main action for [app], following its state: Install, Update, Open; Cancel while it
 * downloads; Allow installs when Android must first permit it; Try again after a failure; View
 * releases for apps that are only followed; the download page for apps installed by hand.
 */
internal fun storeAction(app: AppState, state: StoreState, item: StoreApp): StoreAction {
    val ops = app.store.appStore
    val key = item.key
    val release = (state.releases[key] as? ReleaseCheck.Ready)?.release
    val open = { url: String -> app.platform.openUrl(url) }
    val installed = key in state.installed
    return when (val job = state.jobs[key]) {
        StoreJob.Waiting, StoreJob.Resolving, is StoreJob.Downloading, StoreJob.Verifying ->
            StoreAction("Cancel", FuseIcons.Close, ButtonKind.SECONDARY, busy = true) { ops.cancel(key) }
        StoreJob.NeedsPermission -> StoreAction("Allow installs", FuseIcons.LockOpen, ButtonKind.PRIMARY) { ops.allowInstalls() }
        // Waiting on Android's confirmation can be given up from here too (it closes the confirmation).
        is StoreJob.Installing -> StoreAction("Cancel", FuseIcons.Close, ButtonKind.SECONDARY, busy = true) { ops.cancel(key) }
        StoreJob.Uninstalling -> StoreAction("Removing", FuseIcons.Trash, ButtonKind.SECONDARY, busy = true) {}
        is StoreJob.Failed -> when {
            job.uninstallFirst -> StoreAction("Uninstall first", FuseIcons.Trash, ButtonKind.DANGER) { ops.cancel(key); ops.uninstall(key) }
            job.retry -> StoreAction("Try again", FuseIcons.RotateCcw, ButtonKind.PRIMARY) { ops.install(key) }
            release?.pageUrl != null -> StoreAction("Download page", FuseIcons.External, ButtonKind.SECONDARY) { open(release.pageUrl) }
            else -> StoreAction("View source", FuseIcons.External, ButtonKind.SECONDARY) { open(item.sourceUrl) }
        }
        null -> when {
            item.availability == Availability.TRACK_ONLY ->
                StoreAction("View releases", FuseIcons.External, ButtonKind.SECONDARY) { open(release?.pageUrl ?: item.sourceUrl) }
            installed && state.standing(key) == Standing.UPDATE && item.availability == Availability.INSTALLABLE && release?.file != null ->
                StoreAction("Update" + (release.version?.let { " to ${it.removePrefix("v")}" } ?: ""), FuseIcons.CircleArrowDown, ButtonKind.PRIMARY) { ops.install(key) }
            installed -> StoreAction("Open", FuseIcons.Play, ButtonKind.PRIMARY) {
                if (!ops.launch(key)) app.toasts.show("${item.name} has nothing to open.")
            }
            item.availability == Availability.MANUAL || (release != null && release.file == null) ->
                StoreAction("Download page", FuseIcons.External, ButtonKind.SECONDARY) { open(release?.pageUrl ?: item.sourceUrl) }
            else -> StoreAction("Install" + (release?.file?.sizeBytes?.let { "  ·  ${bytesText(it)}" } ?: ""), FuseIcons.Download, ButtonKind.PRIMARY) { ops.install(key) }
        }
    }
}
