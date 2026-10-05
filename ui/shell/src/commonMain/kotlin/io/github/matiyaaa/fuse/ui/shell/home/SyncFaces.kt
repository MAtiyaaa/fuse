package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.sync.DeviceInfo
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import io.github.matiyaaa.fuse.ui.shell.sync.localOffsetMillis
import io.github.matiyaaa.fuse.ui.shell.sync.syncWords

/** Fuse Sync for the widgets that show it; null while it is off (its widgets then aren't on Home). */
internal val LocalSyncService = staticCompositionLocalOf<SyncService?> { null }

/** A device heard from within this long counts as online. */
private const val ONLINE_MS = 2 * 60_000L

/**
 * Fuse Sync at a glance: whether everything is up to date, syncing, or waiting for the host, which
 * way it reaches the host, and whose profile this is. Its mark turns while it works.
 */
@Composable
internal fun SyncStatusFace(face: FaceSize) {
    val svc = LocalSyncService.current
    Framed(WidgetKind.SYNC_STATUS, null, null, tint = Fuse.colors.accent) {
        if (svc == null) {
            EmptyFace(FuseIcons.RefreshCcw, "Fuse Sync", "Turn on Fuse Sync in Settings, Addons")
            return@Framed
        }
        val status by svc.status.collectAsState()
        val profile by svc.activeProfile.collectAsState()
        val activity by svc.activity.collectAsState()
        val words = syncWords(status)
        val tone = when (words.ok) {
            true -> Fuse.colors.success
            false -> Fuse.colors.warning
            null -> Fuse.colors.textMuted
        }
        val working = (status as? SyncStatus.Online)?.working == true || status is SyncStatus.Connecting
        val pending = when (val s = status) {
            is SyncStatus.Online -> s.pending
            is SyncStatus.Offline -> s.pending
            else -> 0
        }
        when (face) {
            FaceSize.SMALL -> {
                WidgetHeader(FuseIcons.RefreshCcw, "Fuse Sync", short = "Sync")
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateMark(tone, working, 34.dp)
                    Spacer(Modifier.width(Space.s))
                    FText(words.title, Fuse.type.bodyStrong, maxLines = 2)
                }
            }
            FaceSize.WIDE -> {
                WidgetHeader(FuseIcons.RefreshCcw, "Fuse Sync", trailing = pending.takeIf { it > 0 }?.let { "$it waiting" })
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateMark(tone, working, 48.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(words.title, Fuse.type.titleSmall, maxLines = 1)
                        WidgetCaption(words.detail)
                    }
                    profile?.let { p ->
                        Spacer(Modifier.width(Space.m))
                        ProfileTag(p.avatar, p.name)
                    }
                }
            }
            else -> {
                WidgetHeader(FuseIcons.RefreshCcw, "Fuse Sync", trailing = pending.takeIf { it > 0 }?.let { "$it waiting" })
                Spacer(Modifier.height(Space.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateMark(tone, working, 56.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(words.title, Fuse.type.title, maxLines = 1)
                        WidgetCaption(words.detail)
                    }
                }
                profile?.let { p ->
                    Spacer(Modifier.height(Space.m))
                    ProfileTag(p.avatar, p.name)
                }
                Spacer(Modifier.height(Space.m))
                RecentLines(activity.take(if (face == FaceSize.LARGE) 4 else 2).map { it.text to it.at })
            }
        }
    }
}

/**
 * The devices on Fuse Sync: which are online now (a green dot), when the rest were last seen, and
 * whose profile each one plays as.
 */
@Composable
internal fun SyncDevicesFace(face: FaceSize) {
    val svc = LocalSyncService.current
    Framed(WidgetKind.SYNC_DEVICES, null, null, tint = Fuse.colors.accent) {
        if (svc == null) {
            EmptyFace(FuseIcons.MonitorSmartphone, "Your devices", "Turn on Fuse Sync in Settings, Addons")
            return@Framed
        }
        val devices by svc.devices.collectAsState()
        val profiles by svc.profiles.collectAsState()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val linked = devices.filterNot { it.revoked }.sortedByDescending { it.lastSeen }
        val online = linked.count { now - it.lastSeen < ONLINE_MS }
        WidgetHeader(FuseIcons.MonitorSmartphone, "Your devices", short = "Devices", trailing = when {
            linked.isEmpty() -> null
            online > 0 -> "$online online"
            else -> "${linked.size}"
        })
        if (linked.isEmpty()) {
            Spacer(Modifier.weight(1f))
            WidgetCaption("Connect another device to see it here")
            return@Framed
        }
        when (face) {
            FaceSize.SMALL -> {
                Spacer(Modifier.weight(1f))
                DeviceStack(linked.take(4), now)
                Spacer(Modifier.height(Space.s))
                WidgetCaption(if (online == 1) "1 online now" else "$online online now")
            }
            FaceSize.WIDE -> {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    for (d in linked.take(4)) {
                        DeviceChip(d, now, profiles.firstOrNull { it.id == d.profile }?.avatar, Modifier.weight(1f))
                    }
                }
            }
            else -> {
                Spacer(Modifier.height(Space.m))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val fit = ((maxHeight + Space.s) / (DEVICE_ROW + Space.s)).toInt().coerceAtLeast(1)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (d in linked.take(fit)) DeviceRow(d, now, profiles.firstOrNull { it.id == d.profile }?.avatar)
                    }
                }
            }
        }
    }
}

/** The state as a lit disc: green when up to date, amber when waiting; its arrows turn while it works. */
@Composable
private fun StateMark(tone: Color, working: Boolean, size: Dp) {
    val turn = remember { FuselineValue(0f) }
    val reduced = Fuse.motion.reduced
    PageEffect(working, reduced) {
        if (!working || reduced) {
            turn.snapTo(0f)
            return@PageEffect
        }
        while (true) {
            turn.snapTo(0f)
            turn.animateTo(1f, tween(1_100, easing = Curves.Linear))
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.radialGradient(listOf(tone.copy(alpha = 0.32f), tone.copy(alpha = 0.1f)))),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(
            FuseIcons.RefreshCcw, size = size * 0.46f, tint = tone,
            modifier = Modifier.graphicsLayer { rotationZ = -turn.value * 360f },
        )
    }
}

@Composable
private fun ProfileTag(avatar: String, name: String) {
    val c = Fuse.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.text.copy(alpha = 0.07f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(avatar, 28.dp)
        Spacer(Modifier.width(Space.s))
        FText(name, Fuse.type.label, maxLines = 1)
        Spacer(Modifier.width(Space.m))
    }
}

@Composable
private fun ColumnScope.RecentLines(lines: List<Pair<String, Long>>) {
    val c = Fuse.colors
    if (lines.isEmpty()) return
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        for ((text, at) in lines) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(c.accent))
                Spacer(Modifier.width(Space.s))
                FText(text, Fuse.type.caption, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                FText(TimeWords.relative(at, now, offset), Fuse.type.caption, color = c.textFaint, maxLines = 1)
            }
        }
    }
}

private fun deviceIcon(d: DeviceInfo) = when (d.platform) {
    "ANDROID" -> FuseIcons.Smartphone
    else -> FuseIcons.Laptop
}

/** Devices as overlapping discs, each with its online dot. */
@Composable
private fun DeviceStack(devices: List<DeviceInfo>, now: Long) {
    val c = Fuse.colors
    Box(Modifier.height(36.dp).width(36.dp + 24.dp * (devices.size - 1))) {
        devices.forEachIndexed { i, d ->
            Box(Modifier.offset(x = 24.dp * i).size(36.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.surfaceRaised).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    FuseIcon(deviceIcon(d), size = Size.iconS, tint = c.text)
                }
                OnlineDot(now - d.lastSeen < ONLINE_MS, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Box(modifier.size(11.dp).clip(CircleShape).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) c.success else c.textFaint))
    }
}

@Composable
private fun DeviceChip(d: DeviceInfo, now: Long, avatar: String?, modifier: Modifier) {
    val c = Fuse.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                    FuseIcon(deviceIcon(d), size = Size.iconXS, tint = c.text)
                }
                OnlineDot(now - d.lastSeen < ONLINE_MS, Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp))
            }
            if (avatar != null) {
                Spacer(Modifier.width(Space.xs))
                ProfileAvatar(avatar, 20.dp)
            }
        }
        Spacer(Modifier.height(Space.xs))
        FText(d.name, Fuse.type.label, maxLines = 1)
        FText(seenText(d, now), Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1)
    }
}

@Composable
private fun DeviceRow(d: DeviceInfo, now: Long, avatar: String?) {
    val c = Fuse.colors
    Row(Modifier.fillMaxWidth().height(DEVICE_ROW), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                FuseIcon(deviceIcon(d), size = Size.iconS, tint = c.text)
            }
            OnlineDot(now - d.lastSeen < ONLINE_MS, Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(d.name, Fuse.type.bodyStrong, maxLines = 1)
            FText(seenText(d, now), Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        if (avatar != null) ProfileAvatar(avatar, 26.dp)
    }
}

private fun seenText(d: DeviceInfo, now: Long): String = when {
    now - d.lastSeen < ONLINE_MS -> if (d.connection == "REMOTE") "Online, from outside" else "Online"
    d.lastSeen > 0 -> "Seen ${TimeWords.relative(d.lastSeen, now, localOffsetMillis(now))}"
    else -> "Not seen yet"
}

private val DEVICE_ROW = 48.dp
