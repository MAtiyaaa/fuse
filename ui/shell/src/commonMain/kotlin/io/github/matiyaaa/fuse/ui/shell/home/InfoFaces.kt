package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ------------------------------------------------------------------------------------- clock

/** Today, as the clock shows it: the weekday, the date, the month's short name and the day number. */
private class Today(val weekday: String, val date: String, val month: String, val day: Int, val minuteOfDay: Int, val hour: Int, val minute: Int)

private fun today(): Today {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val (weekday, date) = todayParts()
    return Today(weekday, date, t.month.name.take(3), t.day, t.hour * 60 + t.minute, t.hour, t.minute)
}

/**
 * The clock: the time set large in Sora with its units small, a calendar leaf with the month and
 * the day, and a track of the day with where it stands now (night, dawn, day, dusk). Larger faces
 * add a dial: minute marks round its edge, the quarters numbered, hands with round ends and a lit
 * hub. It turns over with Home's clock, once a minute, never in between.
 */
@Composable
internal fun ColumnScope.ClockFaceNew(clock24h: Boolean, face: FaceSize) {
    val time = LocalHomeTime.current ?: rememberClockText(clock24h)
    val now = remember(time) { today() }
    val room = LocalWidgetRoom.current
    when (face) {
        FaceSize.SMALL -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(FuseIcons.Clock3, size = Size.iconXS, tint = Fuse.colors.textMuted)
                Spacer(Modifier.width(Space.s - Space.xxs))
                FText(now.weekday.take(3).uppercase(), Fuse.type.overline, color = Fuse.colors.textMuted, maxLines = 1)
                Spacer(Modifier.weight(1f))
                FText(now.date, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            WidgetValue(time)
            Spacer(Modifier.height(if (room.compact) Space.xs else Space.s))
            DayTrack(now.minuteOfDay, Modifier.fillMaxWidth(), labels = false)
        }
        FaceSize.WIDE -> {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                CalendarLeaf(now, if (room.compact) 50.dp else 60.dp)
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f)) {
                    WidgetValue(time)
                    WidgetCaption(now.weekday)
                }
            }
            if (!room.compact) {
                Spacer(Modifier.height(Space.s))
                DayTrack(now.minuteOfDay, Modifier.fillMaxWidth(), labels = false)
            }
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val side = maxWidth > maxHeight * 1.25f
            val dial = (if (side) maxHeight * 0.9f else min(maxWidth, maxHeight * 0.62f) * 0.94f).coerceAtMost(if (face == FaceSize.LARGE) 380.dp else 240.dp)
            val big = dial >= 200.dp
            val words: @Composable () -> Unit = {
                Column(horizontalAlignment = if (side) Alignment.Start else Alignment.CenterHorizontally) {
                    FText(time, (if (big) Fuse.type.hero else Fuse.type.display).tabular(), maxLines = 1)
                    Spacer(Modifier.height(Space.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CalendarLeaf(now, if (big) 52.dp else 40.dp)
                        Spacer(Modifier.width(Space.m))
                        Column {
                            FText(now.weekday, if (big) Fuse.type.bodyStrong else Fuse.type.label, maxLines = 1)
                            FText(now.date, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                        }
                    }
                }
            }
            if (side) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    Dial(now, Modifier.size(dial))
                    words()
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Dial(now, Modifier.size(dial))
                    Spacer(Modifier.height(Space.m))
                    words()
                }
            }
        }
    }
}

/** A calendar's leaf: the month on a band of the accent, the day under it, large. */
@Composable
private fun CalendarLeaf(now: Today, size: Dp) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(size * 0.2f)
    Column(
        Modifier.width(size).height(size * 1.08f).clip(shape).background(c.text.copy(alpha = if (c.isDark) 0.08f else 0.06f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(size * 0.32f).background(Brush.verticalGradient(listOf(lerp(c.accent, Color.White, 0.1f), c.accent))),
            contentAlignment = Alignment.Center,
        ) {
            FText(now.month.uppercase(), Fuse.type.overline.copy(fontSize = Fuse.type.overline.fontSize * (size / 60.dp).coerceIn(0.8f, 1.2f)), color = Color.White, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            FText(
                now.day.toString(),
                (if (size >= 56.dp) Fuse.type.title else Fuse.type.titleSmall).tabular(),
                maxLines = 1, align = TextAlign.Center,
            )
        }
    }
}

/**
 * The day as a track: night blue at either end, warm through the day, with a lit dot where it is
 * now. [labels] marks six, noon and six where there is room.
 */
@Composable
private fun DayTrack(minuteOfDay: Int, modifier: Modifier, labels: Boolean) {
    val c = Fuse.colors
    val night = lerp(Color(0xFF2B3A67), c.surfaceRaised, 0.35f)
    val dawn = Color(0xFFE88D5A)
    val day = Color(0xFFF2C66D)
    val dot = c.text
    Canvas(modifier.height(10.dp)) {
        val h = 4.dp.toPx()
        val y = size.height / 2
        val brush = Brush.horizontalGradient(
            0f to night, 0.22f to night, 0.28f to dawn, 0.38f to day, 0.62f to day, 0.74f to dawn, 0.8f to night, 1f to night,
        )
        drawRoundRect(brush, topLeft = Offset(0f, y - h / 2), size = GeoSize(size.width, h), cornerRadius = CornerRadius(h / 2), alpha = 0.55f)
        val x = size.width * (minuteOfDay / 1440f)
        drawCircle(dot.copy(alpha = 0.18f), radius = size.height / 2 + 2.dp.toPx(), center = Offset(x, y))
        drawCircle(dot, radius = size.height / 2 - 1.dp.toPx(), center = Offset(x, y))
    }
}

/**
 * A clock dial: sixty minute marks (the hours longer, the quarters numbered on a large dial), an
 * hour and a minute hand with round ends, and a hub in the accent with a ring of the face.
 */
@Composable
private fun Dial(now: Today, modifier: Modifier) {
    val c = Fuse.colors
    val faceTop = c.text.copy(alpha = if (c.isDark) 0.075f else 0.055f)
    val faceBottom = c.text.copy(alpha = if (c.isDark) 0.025f else 0.02f)
    val rim = c.text.copy(alpha = if (c.isDark) 0.14f else 0.11f)
    val minor = c.textFaint.copy(alpha = 0.5f)
    val major = c.textMuted
    val hand = c.text
    val accent = c.accent
    val numberStyle = Fuse.type.label.copy(color = c.textMuted)
    val measurer = rememberTextMeasurer()
    val hours = now.hour % 12 + now.minute / 60f
    Canvas(modifier) {
        val r = size.minDimension / 2
        val centre = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.verticalGradient(listOf(faceTop, faceBottom), startY = centre.y - r, endY = centre.y + r), r, centre)
        drawCircle(rim, r - 0.75.dp.toPx(), centre, style = Stroke(1.5.dp.toPx()))
        val showNumbers = r >= 80.dp.toPx()
        for (i in 0 until 60) {
            val a = i / 60f * 2f * PI.toFloat()
            val hourMark = i % 5 == 0
            if (showNumbers && i % 15 == 0) continue
            val outer = r * 0.9f
            val len = if (hourMark) r * 0.09f else r * 0.035f
            val from = Offset(centre.x + sin(a) * (outer - len), centre.y - cos(a) * (outer - len))
            val to = Offset(centre.x + sin(a) * outer, centre.y - cos(a) * outer)
            drawLine(if (hourMark) major else minor, from, to, strokeWidth = if (hourMark) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round)
        }
        if (showNumbers) {
            for ((i, n) in listOf("12", "3", "6", "9").withIndex()) {
                val a = i / 4f * 2f * PI.toFloat()
                val layout = measurer.measure(n, numberStyle)
                val at = Offset(centre.x + sin(a) * r * 0.8f - layout.size.width / 2f, centre.y - cos(a) * r * 0.8f - layout.size.height / 2f)
                drawText(layout, topLeft = at)
            }
        }
        // Hands: a soft shadow under each, then the hand, round at both ends.
        rotate(hours / 12f * 360f, centre) {
            drawLine(Color.Black.copy(alpha = 0.18f), centre + Offset(1.dp.toPx(), 2.dp.toPx()), Offset(centre.x, centre.y - r * 0.5f) + Offset(1.dp.toPx(), 2.dp.toPx()), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(hand, centre, Offset(centre.x, centre.y - r * 0.5f), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        }
        rotate(now.minute / 60f * 360f, centre) {
            drawLine(Color.Black.copy(alpha = 0.16f), centre + Offset(1.dp.toPx(), 2.dp.toPx()), Offset(centre.x, centre.y - r * 0.76f) + Offset(1.dp.toPx(), 2.dp.toPx()), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(hand, Offset(centre.x, centre.y + r * 0.1f), Offset(centre.x, centre.y - r * 0.76f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(accent, 5.dp.toPx(), centre)
        drawCircle(lerp(accent, Color.White, 0.6f), 2.dp.toPx(), centre)
    }
}

// ----------------------------------------------------------------------------------- storage

/**
 * The library's drive: how much is free, said large, and how full it is as a ring (or a bar on a
 * strip) lit from the accent into a deeper shade of it, turning to the warning colour when nearly
 * full. The drive's name rides along; larger faces add what is used, free and in all.
 */
@Composable
internal fun ColumnScope.StorageFaceNew(feed: HomeFeed, face: FaceSize) {
    val c = Fuse.colors
    val s = feed.storage ?: return
    val used = (1f - s.freeBytes.toFloat() / s.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
    val low = storageLow(feed)
    val tone = if (low) c.warning else c.accent
    val percent = "${(used * 100).toInt()}%"
    val room = LocalWidgetRoom.current
    when (face) {
        FaceSize.SMALL -> {
            WidgetHeader(FuseIcons.HardDrive, s.label, if (low) c.warning else c.textMuted, short = "Storage")
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ring = if (room.small) 40.dp else 48.dp
                CapacityRing(used, tone, ring, stroke = 5.dp) {
                    FText(percent, Fuse.type.caption.tabular(), maxLines = 1)
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    WidgetValue(bytesText(s.freeBytes))
                    if (!room.tiny) WidgetCaption("free", color = c.textMuted)
                }
            }
        }
        FaceSize.WIDE -> {
            WidgetHeader(FuseIcons.HardDrive, "Storage", if (low) c.warning else c.textMuted, trailing = s.label)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                WidgetValue("${bytesText(s.freeBytes)} free", Modifier.weight(1f))
                Spacer(Modifier.width(Space.m))
                FText("$percent used", Fuse.type.numericSmall, color = if (low) c.warning else c.textMuted, maxLines = 1)
            }
            Spacer(Modifier.height(Space.s))
            CapacityBar(used, tone, Modifier.fillMaxWidth())
            if (!room.compact) {
                Spacer(Modifier.height(Space.xs + Space.xxs))
                WidgetCaption("${bytesText(s.totalBytes - s.freeBytes)} of ${bytesText(s.totalBytes)} used")
            }
        }
        else -> {
            WidgetHeader(FuseIcons.HardDrive, "Storage", if (low) c.warning else c.textMuted, trailing = s.label)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = maxWidth > maxHeight * 1.3f
                val ring = (if (side) maxHeight * 0.86f else min(maxWidth, maxHeight * 0.7f) * 0.86f).coerceIn(Size.thumbL, if (face == FaceSize.LARGE) 280.dp else 190.dp)
                val big = ring >= 190.dp
                val dial: @Composable () -> Unit = {
                    CapacityRing(used, tone, ring, stroke = if (big) 16.dp else 11.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FText(bytesText(s.freeBytes), (if (big) Fuse.type.display else Fuse.type.title).tabular(), maxLines = 1)
                            FText("free", if (big) Fuse.type.label else Fuse.type.caption, color = c.textMuted, maxLines = 1)
                        }
                    }
                }
                val legend: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(if (big) Space.m else Space.s)) {
                        LegendRow(tone, "Used", bytesText(s.totalBytes - s.freeBytes), big)
                        LegendRow(c.text.copy(alpha = 0.16f), "Free", bytesText(s.freeBytes), big)
                        LegendRow(null, "In all", bytesText(s.totalBytes), big)
                    }
                }
                if (side) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) { dial(); legend() }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        dial()
                        Spacer(Modifier.height(Space.m))
                        WidgetCaption("$percent used of ${bytesText(s.totalBytes)}")
                    }
                }
            }
        }
    }
}

/** How full, as a ring: a faint track and the used part lit from [tone] into a deeper shade, round at its ends. */
@Composable
private fun CapacityRing(used: Float, tone: Color, size: Dp, stroke: Dp, content: @Composable () -> Unit) {
    val c = Fuse.colors
    val track = c.text.copy(alpha = if (c.isDark) 0.09f else 0.07f)
    val deep = lerp(tone, Color.Black, 0.28f)
    val light = lerp(tone, Color.White, 0.18f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = GeoSize(this.size.width - w, this.size.height - w)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            if (used > 0.004f) {
                val sweep = 360f * used
                // A glow under the lit part, then the part itself.
                drawArc(tone.copy(alpha = 0.12f), -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(w * 1.9f, cap = StrokeCap.Round))
                drawArc(
                    Brush.sweepGradient(0f to light, used.coerceAtLeast(0.05f) to deep, 1f to light, center = Offset(this.size.width / 2, this.size.height / 2)),
                    -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** How full, as a bar: a track, and the used part lit along its length, round at both ends. */
@Composable
private fun CapacityBar(used: Float, tone: Color, modifier: Modifier) {
    val c = Fuse.colors
    val track = c.text.copy(alpha = if (c.isDark) 0.09f else 0.07f)
    val light = lerp(tone, Color.White, 0.18f)
    val deep = lerp(tone, Color.Black, 0.2f)
    Canvas(modifier.height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (used > 0.004f) {
            val w = (size.width * used).coerceAtLeast(size.height)
            drawRoundRect(Brush.horizontalGradient(listOf(light, deep), endX = w), size = GeoSize(w, size.height), cornerRadius = r)
        }
    }
}

@Composable
private fun LegendRow(color: Color?, label: String, value: String, big: Boolean) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        val swatch = RoundedCornerShape(3.dp)
        Box(
            Modifier.size(if (big) 12.dp else 9.dp).clip(swatch)
                .then(if (color != null) Modifier.background(color) else Modifier.border(1.5.dp, c.textFaint, swatch)),
        )
        Spacer(Modifier.width(Space.s))
        Column {
            FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            FText(value, (if (big) Fuse.type.bodyStrong else Fuse.type.label).tabular(), maxLines = 1)
        }
    }
}
