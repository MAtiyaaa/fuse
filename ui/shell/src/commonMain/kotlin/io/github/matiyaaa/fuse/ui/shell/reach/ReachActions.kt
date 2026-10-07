package io.github.matiyaaa.fuse.ui.shell.reach

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.CopyCheck
import io.github.matiyaaa.fuse.ui.shell.store.CopyPlace
import io.github.matiyaaa.fuse.ui.shell.store.CopyView
import io.github.matiyaaa.fuse.ui.shell.store.SendState
import io.github.matiyaaa.fuse.ui.shell.store.SendTarget
import kotlinx.coroutines.launch

internal fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** The picture for one of the household's devices, by its system. */
fun deviceIcon(platform: String): ImageVector = when (platform.uppercase()) {
    "ANDROID" -> FuseIcons.Smartphone
    "LINUX", "WINDOWS", "MACOS" -> FuseIcons.Monitor
    else -> FuseIcons.MonitorSmartphone
}

/** The picture for a copy of a game. */
fun copyIcon(copy: CopyView): ImageVector = when (copy.place) {
    CopyPlace.HERE -> FuseIcons.MonitorSmartphone
    CopyPlace.DEVICE -> deviceIcon(copy.platform)
    CopyPlace.ROMM -> FuseIcons.Server
}

/** How sure Fuse is about a copy, in words. */
fun checkWords(check: CopyCheck): String = when (check) {
    CopyCheck.VERIFIED -> "Same files as the others"
    CopyCheck.CHECKING -> "Checking its files"
    CopyCheck.DIFFERENT -> "A different version"
    CopyCheck.UNKNOWN -> "Nothing to compare it with yet"
}

/** "Downloaded 3 Oct" or "Added 3 Oct", or null when Fuse doesn't know when. */
fun copyDate(copy: CopyView, now: Long): String? {
    if (copy.date <= 0) return null
    val verb = when {
        copy.place == CopyPlace.ROMM -> "On RomM since"
        copy.downloaded -> "Downloaded"
        else -> "Added"
    }
    return "$verb ${dayText(copy.date, now)}"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Today", "Yesterday", "3 Oct", or "3 Oct 2025" for another year. */
fun dayText(at: Long, now: Long): String {
    val day = 86_400_000L
    val today = now / day
    val then = at / day
    if (then == today) return "today"
    if (then == today - 1) return "yesterday"
    val (y, m, d) = civil(then)
    val (thisYear, _, _) = civil(today)
    return if (y == thisYear) "$d ${MONTHS[m - 1]}" else "$d ${MONTHS[m - 1]} $y"
}

/** Days since 1970 to (year, month, day), by the civil calendar. */
private fun civil(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}

/** Brings [id] here from wherever is best, and says so. */
fun AppState.reachDownload(id: GameId, title: String) {
    scope.launch {
        val why = store.reach.download(id)
        if (why != null) toasts.show(why, ToastKind.WARNING)
        else toasts.show("$title is on its way", ToastKind.SUCCESS, icon = FuseIcons.Download)
    }
}

/**
 * Send to: this device ("Download Here") and every other device of the household, each saying
 * whether it takes the game now, once it is back, or already has it. Choosing one asks it.
 */
fun AppState.sendPicker(id: GameId, title: String) {
    closeOverlays()
    scope.launch {
        val targets = store.reach.sendTargets(id)
        if (targets.size <= 1) {
            toasts.show("No other device is in this household yet. Add one in Settings, Addons, Fuse Sync.", ToastKind.INFO, icon = FuseIcons.Info)
            return@launch
        }
        val now = nowMs()
        choice = ChoiceSpec(
            title = "Send $title",
            message = "Fuse brings it from wherever is best: another device at home, RomM, or another device through Fuse Sync.",
            icon = FuseIcons.Share,
            options = targets.map { t -> sendOption(id, title, t, now) },
        )
    }
}

private fun AppState.sendOption(id: GameId, title: String, t: SendTarget, now: Long): MenuAction {
    val here = t.state == SendState.THIS_DEVICE || (t.state == SendState.HAS_IT && t.device == store.reach.self)
    val label = if (here) (if (t.state == SendState.HAS_IT) "This Device" else "Download Here") else t.name
    val detail = when (t.state) {
        SendState.THIS_DEVICE -> t.name
        SendState.READY -> "Online now"
        SendState.QUEUED -> "Away" + (if (t.lastSeen > 0) " since ${agoText(t.lastSeen, now)}" else "") + ". Sent once it is back"
        SendState.HAS_IT -> "Already there"
        SendState.REFUSES -> "Doesn't take games from other devices"
    }
    return MenuAction(
        "send.${t.device}", label, if (here) FuseIcons.Download else deviceIcon(t.platform), detail = detail,
        enabled = t.state == SendState.THIS_DEVICE || t.state == SendState.READY || t.state == SendState.QUEUED,
        onSelect = {
            choice = null
            scope.launch {
                val why = store.reach.sendTo(id, t.device)
                when {
                    why != null -> toasts.show(why, ToastKind.WARNING)
                    here -> toasts.show("$title is on its way", ToastKind.SUCCESS, icon = FuseIcons.Download)
                    t.state == SendState.QUEUED -> toasts.show("${t.name} gets $title once it is back", ToastKind.SUCCESS, icon = FuseIcons.Share)
                    else -> toasts.show("${t.name} is fetching $title", ToastKind.SUCCESS, icon = FuseIcons.Share)
                }
            }
        },
    )
}

/** Sends another device's game to RomM from that device (or this game, from here), and says so. */
fun AppState.reachUploadToRomm(id: GameId, title: String) {
    closeOverlays()
    scope.launch {
        val why = store.reach.uploadToRomm(id)
        if (why != null) toasts.show(why, ToastKind.WARNING)
        else toasts.show("$title is going to RomM from the device that has it", ToastKind.SUCCESS, icon = FuseIcons.CloudUpload)
    }
}

/**
 * One copy of a game, from "Available on": where it is, how big, whether it is the same as the
 * others, when it came, and what can be done with it from here.
 */
fun AppState.copyMenu(id: GameId, title: String, copy: CopyView, hereAlready: Boolean, canUpload: Boolean, rommHasIt: Boolean) {
    val now = nowMs()
    val facts = listOfNotNull(
        when {
            copy.place == CopyPlace.HERE -> "On this device"
            copy.online -> "Online now"
            copy.lastSeen > 0 -> "Away since ${agoText(copy.lastSeen, now)}"
            else -> "Away"
        },
        copy.sizeBytes.takeIf { it > 0 }?.let(::bytesText),
        copyDate(copy, now)?.let { d -> copy.from?.let { "$d from $it" } ?: d },
    )
    val actions = buildList {
        add(MenuAction("copy.check", checkWords(copy.check), if (copy.check == CopyCheck.VERIFIED) FuseIcons.CircleCheck else FuseIcons.Info, enabled = false))
        if (!hereAlready && copy.place != CopyPlace.HERE) add(MenuAction("copy.here", "Download Here", FuseIcons.Download, detail = "From wherever is best", onSelect = { closeOverlays(); reachDownload(id, title) }))
        add(MenuAction("copy.send", "Send to Another Device", FuseIcons.Share, trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron, onSelect = { sendPicker(id, title) }))
        if (canUpload && !rommHasIt && copy.place != CopyPlace.ROMM) {
            val from = if (copy.place == CopyPlace.HERE) "this device" else copy.name
            add(MenuAction("copy.romm", "Upload to RomM", FuseIcons.CloudUpload, detail = "Sent by $from", onSelect = { reachUploadToRomm(id, title) }))
        }
        if (copy.place == CopyPlace.ROMM) add(MenuAction("copy.dl", "Downloads", FuseIcons.Download, onSelect = { closeOverlays(); go(Route.Downloads) }))
    }
    openContextMenu(ContextMenuSpec(title = copy.name, subtitle = facts.joinToString("  ·  "), icon = copyIcon(copy), actions = actions))
}
