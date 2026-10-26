package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.withFrameNanos
import coil3.ImageLoader
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.PreviewAction
import io.github.matiyaaa.fuse.ui.shell.app.TextPreviewSpec
import io.github.matiyaaa.fuse.ui.shell.store.JobActivity
import io.github.matiyaaa.fuse.ui.shell.store.LibraryProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Fuse Performance Observatory (Settings, Performance): what Fuse measures of itself, in one place.
 * When it opens it watches two seconds of Fuse's own frames, times a few real reads of the library,
 * and reads what the picture caches hold, what each background job has cost (the Background Task
 * Scheduler's own record) and what the system reports of the device. All of it can be measured
 * again, and copied or saved as a report. Nothing here runs while it is closed.
 */
fun AppState.showObservatory(images: ImageLoader?) {
    val sheet = { rows: List<MenuAction>, message: String ->
        choice = ChoiceSpec(icon = FuseIcons.Dashboard, title = "Performance Observatory", message = message, options = rows)
    }
    sheet(listOf(MenuAction("obs.wait", "Measuring", FuseIcons.Hourglass, detail = "Two seconds of frames, then the library's reads", enabled = false)), MEASURING)
    scope.launch {
        val frames = FrameSample.of(sampleFrames(SAMPLE_MS), displayMs = io.github.matiyaaa.fuse.ui.fuseline.FramePacing.intervalNanos / 1_000_000f)
        val probe = runCatching { store.observatory.probe() }.getOrDefault(LibraryProbe())
        val jobs = kotlinx.coroutines.withTimeoutOrNull(1_000) { store.observatory.jobs.first { it.isNotEmpty() } }.orEmpty()
        val device = platform.performance.value
        val measured = Measured(frames, probe, jobs, PictureCaches.of(images), device.map { Triple(it.key, it.label, it.value) }, heapMb())
        // Closed while measuring: nothing to show.
        if (choice?.title != "Performance Observatory") return@launch
        sheet(rowsFor(measured, images), summaryOf(measured))
    }
}

/** Everything one look of the Observatory measured. */
internal class Measured(
    val frames: FrameSample,
    val probe: LibraryProbe,
    val jobs: List<JobActivity>,
    val pictures: PictureCaches,
    /** What the system reports (key, label, value), as the performance overlay shows it. */
    val device: List<Triple<String, String, String>>,
    /** Fuse's own working memory (the virtual machine's heap): used and the most it may use. */
    val heap: Pair<Long, Long>,
)

/** Frames Fuse drew while it was watched: how often, and how long each took to come. */
internal class FrameSample private constructor(intervalsMs: FloatArray, val displayMs: Float) {
    private val sorted = intervalsMs.sortedArray()
    val count = sorted.size
    val seconds = intervalsMs.sum() / 1000f
    val fps = if (seconds > 0f) count / seconds else 0f
    fun percentile(p: Int): Float = if (count == 0) 0f else sorted[((count - 1) * p / 100.0).roundToInt()]
    val worst = sorted.lastOrNull() ?: 0f

    /** Frames that came more than half an interval late: a skipped refresh, which is what a stutter looks like. */
    val late = sorted.count { it > displayMs * LATE_RATIO }

    companion object {
        const val LATE_RATIO = 1.5f
        fun of(intervalsMs: FloatArray, displayMs: Float) =
            FrameSample(intervalsMs, displayMs.takeIf { it in 2f..100f } ?: (intervalsMs.sortedArray().getOrNull(intervalsMs.size / 2) ?: 16.7f))
    }
}

/** What the picture caches hold: decoded pictures in memory, and files on disk. */
internal class PictureCaches(val memoryUsed: Long, val memoryMax: Long, val diskUsed: Long, val diskMax: Long) {
    companion object {
        fun of(images: ImageLoader?): PictureCaches = PictureCaches(
            images?.memoryCache?.size ?: 0L, images?.memoryCache?.maxSize ?: 0L,
            images?.diskCache?.size ?: 0L, images?.diskCache?.maxSize ?: 0L,
        )
    }
}

/** The interval between each of the frames drawn in the next [millis], in milliseconds. */
private suspend fun sampleFrames(millis: Long): FloatArray {
    val out = ArrayList<Float>(256)
    var last = withFrameNanos { it }
    val end = last + millis * 1_000_000L
    while (last < end) {
        val now = withFrameNanos { it }
        out += (now - last) / 1_000_000f
        last = now
    }
    return out.toFloatArray()
}

private fun heapMb(): Pair<Long, Long> {
    val rt = Runtime.getRuntime()
    return ((rt.totalMemory() - rt.freeMemory()) shr 20) to (rt.maxMemory() shr 20)
}

private fun AppState.rowsFor(m: Measured, images: ImageLoader?): List<MenuAction> = buildList {
    val f = m.frames
    add(MenuAction(
        "obs.fps", "Frame rate", FuseIcons.Activity, section = "Frames",
        detail = "${f.count} frames in ${tenths(f.seconds)} seconds while this was open; the display comes every ${tenths(f.displayMs)} ms",
        trailing = Trailing.Value("${tenths(f.fps)} fps"),
    ))
    add(MenuAction(
        "obs.frametime", "Frame time", FuseIcons.Timer, section = "Frames",
        detail = "Slowest in 20: ${tenths(f.percentile(95))} ms  ·  in 100: ${tenths(f.percentile(99))} ms  ·  worst ${tenths(f.worst)} ms",
        trailing = Trailing.Value("${tenths(f.percentile(50))} ms"),
    ))
    add(MenuAction(
        "obs.late", "Missed refreshes", FuseIcons.Gauge, section = "Frames",
        detail = "Frames that came more than half a refresh late. A few while something loads is normal; many in a row is a stutter",
        trailing = Trailing.Value("${f.late} of ${f.count}"),
    ))
    val p = m.probe
    add(MenuAction("obs.games", "Games", FuseIcons.LibraryBig, section = "Library", trailing = Trailing.Value(count(p.games))))
    p.countsMs?.let { add(MenuAction("obs.counts", "Home's counts", FuseIcons.Home, section = "Library", detail = "Each system's games, read from the library", trailing = Trailing.Value(ms(it)))) }
    p.indexMs?.let { add(MenuAction("obs.index", "Reading every game for search", FuseIcons.Database, section = "Library", detail = "Done in the background after the library changes, so a search doesn't wait for it", trailing = Trailing.Value(ms(it)))) }
    p.searchMs?.let { add(MenuAction("obs.search", "A search", FuseIcons.Search, section = "Library", detail = "Through every game, from the search index", trailing = Trailing.Value(ms(it)))) }
    val pics = m.pictures
    if (pics.memoryMax > 0) {
        add(MenuAction(
            "obs.memcache", "Pictures in memory", FuseIcons.Images, section = "Pictures",
            detail = "Decoded art kept ready to draw. When it is full the picture used longest ago goes first",
            trailing = Trailing.Level((pics.memoryUsed.toFloat() / pics.memoryMax).coerceIn(0f, 1f), "${mb(pics.memoryUsed)} of ${mb(pics.memoryMax)}"),
        ))
    }
    if (pics.diskMax > 0) {
        add(MenuAction(
            "obs.diskcache", "Pictures on disk", FuseIcons.HardDrive, section = "Pictures",
            detail = "Downloaded and resized art, kept for the next start and for offline",
            trailing = Trailing.Level((pics.diskUsed.toFloat() / pics.diskMax).coerceIn(0f, 1f), "${mb(pics.diskUsed)} of ${mb(pics.diskMax)}"),
        ))
    }
    for (j in m.jobs) {
        add(MenuAction(
            "obs.job.${j.label}", j.label, FuseIcons.Layers, section = "Background work",
            detail = jobDetail(j),
            trailing = Trailing.Value(if (j.running > 0) "Running" else if (j.runs == 1) "1 run" else "${j.runs} runs"),
        ))
    }
    add(MenuAction(
        "obs.heap", "Fuse's working memory", FuseIcons.Memory, section = "This device",
        detail = "What Fuse holds now, of the most the system lets it use",
        trailing = Trailing.Level((m.heap.first.toFloat() / m.heap.second.coerceAtLeast(1)).coerceIn(0f, 1f), "${m.heap.first} of ${m.heap.second} MB"),
    ))
    for ((key, label, value) in m.device) add(MenuAction("obs.dev.$key", label, metricIcon(key), section = "This device", trailing = Trailing.Value(value)))
    add(MenuAction("obs.again", "Measure again", FuseIcons.Refresh, section = "Share", onSelect = { showObservatory(images) }))
    add(MenuAction(
        "obs.report", "Report", FuseIcons.FileText, section = "Share",
        detail = "All of this as text to copy or save, to compare later or send with a bug report",
        trailing = Trailing.Chevron,
        onSelect = { showObservatoryReport(m) },
    ))
}

/** Each reading's own picture: heat a thermometer, memory a memory chip, the battery a battery. */
private fun metricIcon(key: String) = when {
    "temp" in key -> FuseIcons.Thermometer
    "battery" in key -> FuseIcons.BatteryMedium
    "memory" in key -> FuseIcons.Memory
    "frame" in key -> FuseIcons.Activity
    else -> FuseIcons.Chip
}

private fun jobDetail(j: JobActivity): String = if (j.runs == 0 && j.held == 0) {
    "Not run since Fuse started"
} else {
    listOfNotNull(
        "last ${ms(j.lastMs.toDouble())}",
        "longest ${ms(j.longestMs.toDouble())}",
        "${ms(j.totalMs.toDouble())} in all",
        j.held.takeIf { it > 0 }?.let { if (it == 1) "waited for a game once" else "waited for a game $it times" },
    ).joinToString("  ·  ").replaceFirstChar { it.uppercase() }
}

private fun summaryOf(m: Measured): String {
    val f = m.frames
    val smooth = f.count > 0 && f.late * 20 <= f.count
    return (if (smooth) "Fuse is keeping up with the display." else "Fuse missed ${f.late} of ${f.count} refreshes while this was open.") +
        " Measured on this device just now; it changes with what Fuse is doing."
}

internal fun observatoryReport(m: Measured, version: String): String = buildString {
    val f = m.frames
    appendLine("Fuse $version, Performance Observatory")
    appendLine()
    appendLine("Frames (${f.count} in ${tenths(f.seconds)} s, display every ${tenths(f.displayMs)} ms)")
    appendLine("  rate ${tenths(f.fps)} fps")
    appendLine("  median ${tenths(f.percentile(50))} ms, p95 ${tenths(f.percentile(95))} ms, p99 ${tenths(f.percentile(99))} ms, worst ${tenths(f.worst)} ms")
    appendLine("  missed refreshes ${f.late}")
    appendLine()
    val p = m.probe
    appendLine("Library (${p.games} games)")
    p.countsMs?.let { appendLine("  counts per system ${ms(it)}") }
    p.indexMs?.let { appendLine("  every game read for search ${ms(it)}") }
    p.searchMs?.let { appendLine("  a search ${ms(it)}") }
    appendLine()
    appendLine("Pictures")
    appendLine("  memory ${mb(m.pictures.memoryUsed)} of ${mb(m.pictures.memoryMax)}")
    appendLine("  disk ${mb(m.pictures.diskUsed)} of ${mb(m.pictures.diskMax)}")
    appendLine()
    appendLine("Background work")
    for (j in m.jobs) appendLine("  ${j.label}: ${j.runs} runs, last ${j.lastMs} ms, longest ${j.longestMs} ms, total ${j.totalMs} ms, held ${j.held}")
    appendLine()
    appendLine("This device")
    appendLine("  Fuse's working memory ${m.heap.first} of ${m.heap.second} MB")
    for ((_, label, value) in m.device) appendLine("  $label $value")
}

private fun AppState.showObservatoryReport(m: Measured) {
    choice = null
    val text = observatoryReport(m, store.updates.currentVersion)
    textPreview = TextPreviewSpec(
        title = "Performance report",
        message = "What the Observatory measured just now. It holds no folder, game or user names.",
        text = text,
        icon = FuseIcons.FileText,
        actions = listOf(
            PreviewAction("Copy", FuseIcons.Copy) {
                scope.launch {
                    val ok = platform.writeClipboardText(text)
                    toasts.show(if (ok) "Copied the report" else "Copying isn't possible here", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
                }
            },
            PreviewAction("Save", FuseIcons.Save, primary = true) {
                scope.launch {
                    val where = platform.saveFile("fuse-performance-${store.updates.currentVersion}.txt", "text/plain", text.encodeToByteArray())
                    if (where != null) {
                        textPreview = null
                        toasts.show("Saved $where", ToastKind.SUCCESS)
                    }
                }
            },
        ),
    )
}

private const val SAMPLE_MS = 2_000L
private const val MEASURING = "Watching two seconds of Fuse's own frames, then timing the library's reads. Keep still for a moment."

private fun tenths(v: Float): String {
    val t = (v * 10).roundToInt()
    return "${t / 10}.${kotlin.math.abs(t % 10)}"
}

private fun ms(v: Double): String = if (v < 10) "${tenths(v.toFloat())} ms" else "${v.roundToInt()} ms"

private fun mb(bytes: Long): String = if (bytes < 1L shl 20) "${bytes shr 10} KB" else "${bytes shr 20} MB"

private fun count(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
