package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Notices an SD card or drive that holds no games folder yet and asks, once, whether to set it up
 * for games ([offerDriveSetup]). Yes or no, the answer is kept, so a drive is never asked about
 * twice; Storage can set it up later. Asked only once setup is done and while nothing else is
 * going on ([busy], an open dialog).
 */
@Composable
internal fun DriveWatch(app: AppState, busy: () -> Boolean) {
    val volumes by app.store.sources.volumes.collectAsState()
    val statuses by app.store.sources.status.collectAsState()
    val prefs by app.store.prefs.collectAsState()
    // Drives asked about while Fuse runs, closed without an answer: asked again next time only.
    val askedNow = remember { HashSet<String>() }
    LaunchedEffect(volumes, statuses, prefs.drivesAsked, prefs.onboardingDone) {
        if (!prefs.onboardingDone) return@LaunchedEffect
        // A card that was just put in mounts in steps; look once it has settled.
        delay(SETTLE_MS)
        val holding = statuses.mapNotNull { it.volume?.id ?: it.source.volume?.id }.toSet()
        val drive = volumes.firstOrNull { v ->
            v.offersGames() && v.id !in holding && v.id !in prefs.drivesAsked && v.id !in askedNow
        } ?: return@LaunchedEffect
        while (busy() || app.overlayOpen) delay(WAIT_MS)
        askedNow += drive.id
        app.offerDriveSetup(drive.id, drive.label, firstTime = true)
    }
}

/** A drive worth asking about: removable, writable, the kind games are carried on. */
internal fun StorageVolume.offersGames(): Boolean =
    !readOnly && mountPath.isNotEmpty() && kind != VolumeKind.INTERNAL && kind != VolumeKind.NETWORK && kind != VolumeKind.OPTICAL &&
        (removable || kind == VolumeKind.SD_CARD || kind == VolumeKind.USB)

/**
 * Asks how to set up the drive [volumeId] for games: an Emulation folder at its top, one in a
 * folder the user picks, or (when [firstTime]) not at all, which Fuse remembers.
 */
internal fun AppState.offerDriveSetup(volumeId: String, label: String, firstTime: Boolean) {
    fun remember() = store.updatePrefs { it.copy(drivesAsked = (it.drivesAsked + volumeId).distinct()) }
    fun setUp(at: String?) {
        remember()
        scope.launch {
            val done = store.storage.setUpDrive(volumeId, at)
            if (done == null) {
                toasts.show("Fuse couldn't make folders on $label. Check that it isn't read only", ToastKind.ERROR)
            } else {
                toasts.show("$label is ready: a folder for each of ${done.systems} systems, in your library", ToastKind.SUCCESS, icon = FuseIcons.SdCard)
            }
        }
    }
    choice = ChoiceSpec(
        title = if (firstTime) "$label connected" else "Set up $label",
        message = "Fuse can set it up for games: an Emulation folder with ROMs, a folder for each system inside, and bios for firmware. " +
            "Games put there show up in your library.",
        icon = FuseIcons.SdCard,
        options = buildList {
            add(MenuAction("top", "Set it up for games", FuseIcons.FolderPlus, detail = "At the top of $label", onSelect = {
                choice = null
                setUp(null)
            }))
            add(MenuAction("pick", "Choose where", FuseIcons.FolderOpen, detail = "A folder on $label of your choosing", onSelect = {
                choice = null
                scope.launch {
                    val at = platform.storage.pickFolder("Where should the Emulation folder go?") ?: return@launch
                    setUp(at)
                }
            }))
            if (firstTime) {
                add(MenuAction("no", "Not this drive", FuseIcons.CircleX, detail = "Fuse won't ask again. Storage can set it up later", onSelect = {
                    choice = null
                    remember()
                }))
            }
        },
    )
}

private const val SETTLE_MS = 2_500L
private const val WAIT_MS = 1_000L
