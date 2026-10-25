package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.sync.DeviceInfo
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.ProfileReport
import io.github.matiyaaa.fuse.sync.SyncActivity
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ambientOn
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A colour for each device, the same everywhere on the Sync page: its place on the household map,
 * the versions it made in a game's strip, and its share of the play time.
 */
internal class DeviceTints(private val byId: Map<String, Color>, private val fallback: Color) {
    fun of(id: String): Color = byId[id] ?: fallback

    companion object {
        private val PALETTE = listOf(0xFF5EB8FF, 0xFF7BD389, 0xFFB18CFF, 0xFFFFB454, 0xFFFF7A9E, 0xFF4FD1C5, 0xFFE8D35E, 0xFF8FA3FF).map { Color(it) }

        /** By the devices' ids, so a device keeps its colour whichever device shows the page. */
        fun of(devices: List<DeviceInfo>, fallback: Color) =
            DeviceTints(devices.sortedBy { it.id }.mapIndexed { i, d -> d.id to PALETTE[i % PALETTE.size] }.toMap(), fallback)
    }
}

/** How things stand, in a few words with an icon and a colour: what the hero's status says. */
internal data class SyncLook(val title: String, val detail: String, val icon: ImageVector, val tint: Color, val busy: Boolean = false)

@Composable
internal fun syncLook(status: SyncStatus, activity: List<SyncActivity>, hostName: String, syncing: Boolean): SyncLook {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    val last = activity.firstOrNull { it.kind == SyncActivity.CHANGES || it.kind == SyncActivity.SAVE }
    return when (status) {
        is SyncStatus.Online -> when {
            status.working || syncing -> SyncLook("Syncing", "With $hostName", FuseIcons.RefreshCcw, c.accent, busy = true)
            status.pending > 0 -> SyncLook(if (status.pending == 1) "1 change waiting" else "${status.pending} changes waiting", "Sent with the next sync", FuseIcons.Clock, c.warning)
            else -> SyncLook("Up to date", last?.let { "Last change " + TimeWords.relative(it.at, now, offset) } ?: "With $hostName", FuseIcons.CloudCheck, c.success)
        }
        is SyncStatus.Offline -> SyncLook(
            "Offline",
            (if (status.pending > 0) "${status.pending} kept here until $hostName is back" else "Saves and profiles keep working here"),
            FuseIcons.CloudOff, c.warning,
        )
        is SyncStatus.Connecting -> SyncLook("Connecting", "To ${status.hostName}", FuseIcons.RefreshCcw, c.accent, busy = true)
        is SyncStatus.NeedsAttention -> SyncLook("Needs you", status.reason, FuseIcons.Warning, c.danger)
        SyncStatus.NotSetUp, SyncStatus.Off -> SyncLook("This device only", "Each person's saves are kept apart here", FuseIcons.HardDrive, c.accent)
    }
}

/**
 * The top of the Sync page: who is playing, how things stand and their totals, with the things to
 * do, beside a live map of the household (the host in the middle, every device linked to it, a
 * light running along each link that is up while it syncs). On a narrow screen the map steps aside
 * and the rest keeps its room; on a short one the totals do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SyncHero(
    hostName: String,
    host: Boolean,
    alone: Boolean,
    look: SyncLook,
    profile: ProfileInfo?,
    report: ProfileReport?,
    devices: List<DeviceInfo>,
    self: String,
    tints: DeviceTints,
    actions: List<SyncAction>,
    selected: Int,
    busy: Boolean,
    labels: Boolean,
    compact: Boolean,
    /** A very short screen (the AYN Thor's upper one at its largest): one line, the games keep the room. */
    slim: Boolean,
    onAction: (Int) -> Unit,
) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    if (slim) {
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.horizontalGradient(listOf(look.tint.copy(alpha = 0.14f), c.surface.copy(alpha = 0.9f))))
                .border(1.dp, c.hairline, shape)
                .padding(horizontal = Space.m, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (profile != null) ProfileAvatar(profile.avatar, 36.dp, ring = c.accent) else SyncMark(36.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(profile?.name ?: "No one playing", Fuse.type.bodyStrong, maxLines = 1)
                StatusChip(look, detail = false)
            }
            Spacer(Modifier.width(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { i, a ->
                    HeroAction(a, selected = i == selected, label = false, primary = i == 0, busy = i == 0 && busy) { onAction(i) }
                }
            }
        }
        return
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape)
            .drawWithCache {
                val wash = Brush.linearGradient(
                    0f to look.tint.copy(alpha = 0.16f), 0.45f to c.surface.copy(alpha = 0.88f), 1f to c.surface.copy(alpha = 0.94f),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                )
                val glow = Brush.radialGradient(
                    0f to c.accent.copy(alpha = 0.22f), 1f to Color.Transparent,
                    center = Offset(size.width * 0.8f, size.height * 0.45f), radius = size.height * 1.1f,
                )
                onDrawBehind {
                    drawRect(wash)
                    drawRect(glow)
                }
            }
            .border(1.dp, c.hairline, shape),
    ) {
        val showMap = maxWidth >= MAP_FROM
        val mapWidth = minOf(MAP_MAX, maxWidth * 0.46f)
        // Beside the map the hero keeps its height and spreads out; without it, it is as tall as
        // what it holds, each part a step apart.
        Row(Modifier.fillMaxWidth().then(if (showMap) Modifier.height(if (compact) HERO_COMPACT else HERO) else Modifier)) {
            Column(
                Modifier.weight(1f).then(if (showMap) Modifier.fillMaxHeight() else Modifier)
                    .padding(horizontal = Space.l, vertical = if (compact) Space.m else Space.l),
                verticalArrangement = if (showMap) Arrangement.SpaceBetween else Arrangement.spacedBy(if (compact) Space.s else Space.m),
            ) {
                // Who is playing, and where.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile != null) ProfileAvatar(profile.avatar, if (compact) 40.dp else 52.dp, ring = c.accent)
                    else SyncMark(if (compact) 40.dp else 52.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(profile?.name ?: "No one playing", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                        FText(
                            when {
                                alone -> "On this device"
                                host -> "On $hostName, this computer"
                                else -> "Synced with $hostName"
                            },
                            Fuse.type.caption, color = c.textMuted, maxLines = 1,
                        )
                    }
                }
                // How things stand (without the map beside, its detail goes under it on up to two lines).
                if (showMap) {
                    StatusChip(look)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        StatusChip(look, detail = false)
                        if (look.detail.isNotEmpty()) FText(look.detail, Fuse.type.caption, color = c.textMuted, maxLines = 2)
                    }
                }
                // The totals, as large figures (on a narrow screen, three even columns with a hairline
                // between, so they keep to one line).
                if (!compact && report != null) {
                    val figures = listOf(
                        (playtimeText(report.playSeconds).takeIf { report.playSeconds > 0 } ?: "0 h") to "played",
                        "${report.games.count { it.slots.isNotEmpty() }}" to "games with saves",
                        sizeText(report.savesBytes) to if (alone) "kept here" else "on the host",
                    )
                    if (showMap) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                            for ((value, label) in figures) Figure(value, label)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            figures.forEachIndexed { i, (value, label) ->
                                if (i > 0) Box(Modifier.padding(horizontal = Space.m).width(1.dp).fillMaxHeight().background(c.hairline))
                                Figure(value, label, small = true, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                // The things to do.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    actions.forEachIndexed { i, a ->
                        HeroAction(a, selected = i == selected, label = labels, primary = i == 0, busy = i == 0 && busy) { onAction(i) }
                    }
                }
            }
            if (showMap) {
                HouseholdMap(
                    hostName = hostName, host = host, alone = alone, devices = devices, self = self, tints = tints,
                    syncing = look.busy, compact = compact,
                    modifier = Modifier.width(mapWidth).fillMaxHeight(),
                )
            }
        }
    }
}

/** How things stand, as a soft pill in its colour: an icon (turning while it syncs), a title and a line. */
@Composable
private fun StatusChip(look: SyncLook, detail: Boolean = true) {
    val c = Fuse.colors
    Row(
        Modifier.clip(CircleShape).background(look.tint.copy(alpha = 0.14f)).border(1.dp, look.tint.copy(alpha = 0.32f), CircleShape)
            .padding(start = Space.s, end = Space.m, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(look.tint.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            if (look.busy) Spinner(size = 14.dp, color = look.tint) else FuseIcon(look.icon, size = 14.dp, tint = look.tint)
        }
        Spacer(Modifier.width(Space.s))
        FText(look.title, Fuse.type.label, color = c.text, maxLines = 1)
        if (detail && look.detail.isNotEmpty()) {
            FText("  ·  ${look.detail}", Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.widthIn(max = 420.dp))
        }
    }
}

/** A total: a large figure over what it counts (a size smaller, and the words on up to two lines, when [small]). */
@Composable
private fun Figure(value: String, label: String, small: Boolean = false, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Column(modifier) {
        FText(value, if (small) Fuse.type.titleSmall else Fuse.type.title, color = c.text, maxLines = 1)
        FText(label, Fuse.type.caption, color = c.textMuted, maxLines = if (small) 2 else 1)
    }
}

/** One of the few things to do: the first filled in the accent, the rest quiet; lit when chosen. */
@Composable
private fun HeroAction(a: SyncAction, selected: Boolean, label: Boolean, primary: Boolean, busy: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(
        when {
            selected -> c.text
            primary -> c.accent
            else -> c.text.copy(alpha = 0.08f)
        },
        Fuse.motion.tween(Durations.FAST), label = "heroAction",
    )
    val fg = when {
        selected -> c.ink
        primary -> c.onAccent
        else -> c.text
    }
    Row(
        Modifier.height(ACTION).clip(CircleShape).background(bg)
            .fuseClickable(shape = CircleShape, onClick = onClick)
            .padding(horizontal = if (label) Space.m + 2.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) Spinner(size = 18.dp, color = fg) else FuseIcon(a.icon, size = Size.iconS, tint = fg)
        if (label) {
            Spacer(Modifier.width(Space.s))
            FText(a.label, Fuse.type.label, color = fg, maxLines = 1)
        }
    }
}

/**
 * The household as a map: the host in the middle, glowing in the accent, and every linked device on
 * an orbit around it in its own colour, a line to the host from each. A device heard from lately
 * has a solid link with a light running along it to the host (quicker while syncing) and a soft
 * pulse; one away has a dotted link. This device is marked You. Without a household, this device
 * stands alone beside a dotted place for a host.
 */
@Composable
private fun HouseholdMap(
    hostName: String,
    host: Boolean,
    alone: Boolean,
    devices: List<DeviceInfo>,
    self: String,
    tints: DeviceTints,
    syncing: Boolean,
    compact: Boolean,
    modifier: Modifier,
) {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    // Round the host: every other device (the host computer's own Fuse is the host itself).
    val around = remember(devices, self, host) {
        devices.filterNot { it.revoked || (host && it.id == self) }.sortedWith(compareBy<DeviceInfo>({ it.id != self }, { it.name.lowercase() }))
    }
    // At most this many round the host, "+N" for the rest counting as one.
    val cap = if (compact) MAX_NODES_COMPACT else MAX_NODES
    val shown = around.take(if (around.size > cap) cap - 1 else cap)
    val more = around.size - shown.size
    val moving = Fuse.motion.ambientOn(Fuse.quality)
    val clock = if (moving) rememberLoopClock(label = "householdMap") else null
    val travel = clock?.animateFloat(0f, 1f, infiniteRepeatable(tween(if (syncing) 1_200 else 2_600, easing = Curves.Linear)), label = "link light")
    val pulse = clock?.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = Curves.Linear)), label = "pulse")
    val hostNode = if (compact) HOST_NODE_COMPACT else HOST_NODE
    val node = if (compact) NODE_COMPACT else NODE
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        val cx = w * 0.5f
        val cy = h * if (compact) 0.42f else 0.44f
        val rx = w * 0.36f
        val ry = h * if (compact) 0.3f else 0.36f
        val count = if (alone) 1 else shown.size + if (more > 0) 1 else 0
        // Down the two sides of the orbit, the left first, so none sits over or under the host's
        // name: one on a side a little off level, two or three a side evenly from high to low.
        val left = (count + 1) / 2
        fun angle(i: Int): Double {
            val onLeft = i < left
            val many = if (onLeft) left else count - left
            val k = if (onLeft) i else i - left
            val off = if (many <= 1) (if (onLeft) -LONE_TILT else LONE_TILT) else -SIDE_SPREAD + k * 2 * SIDE_SPREAD / (many - 1)
            return (if (onLeft) 180.0 - off else off) * PI / 180.0
        }
        fun at(i: Int) = Pair(cx + rx * cos(angle(i)).toFloat(), cy + ry * sin(angle(i)).toFloat())
        // Three down a side leave room under each for its name only.
        val crowded = count > 4
        val online = shown.map { now - it.lastSeen < ONLINE_MS }

        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(cx.toPx(), cy.toPx())
            // The orbit, faint.
            drawOval(
                c.text.copy(alpha = 0.07f),
                topLeft = Offset(center.x - rx.toPx(), center.y - ry.toPx()),
                size = GeoSize(rx.toPx() * 2, ry.toPx() * 2),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))),
            )
            // The host's glow.
            drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.32f), Color.Transparent), center, hostNode.toPx() * 1.6f), hostNode.toPx() * 1.6f, center)
            for (i in 0 until count) {
                val (x, y) = at(i)
                val p = Offset(x.toPx(), y.toPx())
                val up = !alone && i < shown.size && online[i]
                val tint = if (!alone && i < shown.size) tints.of(shown[i].id) else c.textFaint
                if (up) {
                    drawLine(
                        Brush.linearGradient(listOf(tint.copy(alpha = 0.75f), c.accent.copy(alpha = 0.75f)), p, center),
                        p, center, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    )
                    // A light on its way to the host.
                    val t = travel?.value?.let { (it + i * 0.23f) % 1f }
                    if (t != null) {
                        val q = Offset(p.x + (center.x - p.x) * t, p.y + (center.y - p.y) * t)
                        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), tint.copy(alpha = 0.5f), Color.Transparent), q, 7.dp.toPx()), 7.dp.toPx(), q)
                    }
                    // A soft pulse round it.
                    val pr = pulse?.value?.let { (it + i * 0.31f) % 1f }
                    if (pr != null) {
                        drawCircle(tint.copy(alpha = 0.35f * (1f - pr)), node.toPx() / 2 * (1f + pr * 0.7f), p, style = Stroke(2.dp.toPx()))
                    }
                } else {
                    drawLine(
                        c.text.copy(alpha = 0.18f), p, center, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())),
                    )
                }
            }
        }

        // The host in the middle.
        Node(cx, cy, hostNode) {
            Box(
                Modifier.size(hostNode).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(lerp(c.accent, Color.White, 0.12f), lerp(c.accent, Color.Black, 0.3f))))
                    .border(2.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(if (alone) FuseIcons.MonitorSmartphone else FuseIcons.Server, size = hostNode * 0.42f, tint = c.onAccent)
            }
        }
        NodeLabel(cx, cy + hostNode / 2 + 4.dp, if (alone) "This device" else if (host) "This computer" else hostName, if (alone) null else "The host", strong = true)

        if (alone) {
            // A place for a host, not filled yet.
            val (x, y) = at(0)
            Node(x, y, node) {
                Box(
                    Modifier.size(node).clip(CircleShape)
                        .drawBehind {
                            drawCircle(c.text.copy(alpha = 0.35f), size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
                        },
                    contentAlignment = Alignment.Center,
                ) { FuseIcon(FuseIcons.Plus, size = node * 0.4f, tint = c.textMuted) }
            }
            NodeLabel(x, y + node / 2 + 4.dp, "A host", "Not set up yet", strong = false)
        } else {
            shown.forEachIndexed { i, d ->
                val (x, y) = at(i)
                val tint = tints.of(d.id)
                val up = online[i]
                Node(x, y, node) {
                    Box(
                        Modifier.size(node).clip(CircleShape).background(c.surfaceRaised)
                            .border(2.dp, if (up) tint else tint.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        FuseIcon(if (d.platform == "ANDROID") FuseIcons.Smartphone else FuseIcons.Laptop, size = node * 0.44f, tint = if (up) tint else c.textMuted)
                    }
                }
                NodeLabel(x, y + node / 2 + 4.dp, d.name, if (crowded) null else if (d.id == self) "You" else if (up) "Online" else null, strong = d.id == self)
            }
            if (more > 0) {
                val (x, y) = at(shown.size)
                Node(x, y, node) {
                    Box(Modifier.size(node).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        FText("+$more", Fuse.type.label, color = c.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Something of [size] centred on ([x], [y]). */
@Composable
private fun Node(x: Dp, y: Dp, size: Dp, content: @Composable () -> Unit) {
    Box(Modifier.offset(x - size / 2, y - size / 2).size(size)) { content() }
}

/** A node's name under it, centred on [x] from [top], with a quieter line under that. */
@Composable
private fun NodeLabel(x: Dp, top: Dp, name: String, line: String?, strong: Boolean) {
    val c = Fuse.colors
    Column(
        Modifier.offset(x - LABEL / 2, top).width(LABEL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FText(name, if (strong) Fuse.type.label else Fuse.type.caption, color = c.text, maxLines = 1, align = TextAlign.Center)
        if (line != null) FText(line, Fuse.type.caption, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
    }
}

/**
 * A game's versions as a strip, oldest to newest: a dot each in the colour of the device that made
 * it, the one in use ringed, so where a save has been is plain at a glance.
 */
@Composable
internal fun VersionStrip(versions: List<io.github.matiyaaa.fuse.sync.VersionReport>, tints: DeviceTints, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val shown = versions.sortedBy { it.at }.takeLast(STRIP_DOTS)
    if (shown.isEmpty()) return
    Canvas(modifier.width(STRIP_STEP * (STRIP_DOTS - 1) + STRIP_DOT * 2).height(STRIP_DOT * 2 + 4.dp)) {
        val step = STRIP_STEP.toPx()
        val r = STRIP_DOT.toPx() / 2 + 1.dp.toPx()
        val y = size.height / 2
        val start = size.width - step * (shown.size - 1) - r * 2
        drawLine(c.text.copy(alpha = 0.12f), Offset(start + r, y), Offset(size.width - r, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        shown.forEachIndexed { i, v ->
            val p = Offset(start + r + step * i, y)
            val tint = tints.of(v.deviceId)
            drawCircle(tint, STRIP_DOT.toPx() / 2, p)
            if (v.current) drawCircle(tint, STRIP_DOT.toPx() / 2 + 3.5.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Play time by device, as a ring of each device's share in its colour, the total in the middle, and a key. */
@Composable
internal fun PlayRing(play: Map<String, Long>, names: Map<String, String>, tints: DeviceTints) {
    val c = Fuse.colors
    val total = play.values.sum().takeIf { it > 0 } ?: return
    val parts = play.entries.sortedByDescending { it.value }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(RING), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2
                val arc = Rect(inset, inset, size.width - inset, size.height - inset)
                drawArc(c.text.copy(alpha = 0.08f), 0f, 360f, false, arc.topLeft, arc.size, style = Stroke(stroke))
                var from = -90f
                val gap = if (parts.size > 1) 3f else 0f
                for ((d, sec) in parts) {
                    val sweep = 360f * sec / total
                    drawArc(tints.of(d), from + gap / 2, (sweep - gap).coerceAtLeast(1f), false, arc.topLeft, arc.size, style = Stroke(stroke, cap = StrokeCap.Butt))
                    from += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FText(playtimeText(total), Fuse.type.bodyStrong, maxLines = 1)
                FText("in all", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for ((d, sec) in parts.take(5)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tints.of(d)))
                    Spacer(Modifier.width(Space.s))
                    FText(names[d] ?: "A device no longer linked", Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f))
                    FText(playtimeText(sec), Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The host, from this device: signing in as the host here (once, with its username and password)
 * so this device manages the household, or saying it already does.
 */
@Composable
internal fun HostAccessCard(hostName: String, managing: Boolean, onSignIn: () -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Card("The host", FuseIcons.Server, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background((if (managing) c.success else c.accent).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                FuseIcon(if (managing) FuseIcons.ShieldCheck else FuseIcons.LogIn, size = Size.iconS, tint = if (managing) c.success else c.accent)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(if (managing) "Signed in as the host" else "Manage $hostName from here", Fuse.type.bodyStrong, maxLines = 2)
                FText(
                    if (managing) "Devices, the account and the address from outside, from this device. It stays signed in for good"
                    else "Sign in once with the host's username and password. It stays signed in through every update",
                    Fuse.type.caption, color = c.textMuted, maxLines = 3,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(ACTION).clip(CircleShape)
                .background(if (managing) c.text.copy(alpha = 0.08f) else c.accent)
                .fuseClickable(shape = CircleShape, onClick = if (managing) onManage else onSignIn).padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val fg = if (managing) c.text else c.onAccent
            FuseIcon(if (managing) FuseIcons.Settings else FuseIcons.LogIn, size = Size.iconS, tint = fg)
            Spacer(Modifier.width(Space.s))
            FText(if (managing) "Manage the Host" else "Sign In as the Host", Fuse.type.label, color = fg, maxLines = 1)
        }
    }
}

/** The map shows from this width; its widest; the hero's height, and on a short screen. */
private val MAP_FROM = 720.dp
private val MAP_MAX = 520.dp
private val HERO = 248.dp
private val HERO_COMPACT = 176.dp
private val ACTION = 44.dp

/**
 * The host's node and each device's, and on a short screen; a name under one; how many round the
 * host, "+N" among them (two a side on a short screen, three on a tall one); how far a lone device
 * sits off level and how far up and down a side the rest spread, in degrees.
 */
private val HOST_NODE = 68.dp
private val HOST_NODE_COMPACT = 52.dp
private val NODE = 44.dp
private val NODE_COMPACT = 36.dp
private val LABEL = 112.dp
private const val MAX_NODES = 6
private const val MAX_NODES_COMPACT = 4
private const val LONE_TILT = 22.0
private const val SIDE_SPREAD = 55.0

/** A game's version strip: its dots, how far apart, and how many. */
private val STRIP_DOT = 10.dp
private val STRIP_STEP = 20.dp
private const val STRIP_DOTS = 6

/** The play time ring's size. */
private val RING = 108.dp

/** A device heard from within this long counts as online. */
private const val ONLINE_MS = 2 * 60_000L
