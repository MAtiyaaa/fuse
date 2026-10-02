package io.github.matiyaaa.fuse.ui.shell.audit

import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The screens the audit renders at, as a device would report them: pixels, density, and the dp
 * layout that produces. [M] gets every screen; the others get the key screens.
 */
internal enum class AuditSize(val widthPx: Int, val heightPx: Int, val density: Float, val note: String) {
    M(1920, 1080, 1.5f, "1080p handheld"),
    H(1920, 1080, 2.25f, "6 inch Android handheld"),
    D(1280, 800, 1.0f, "Steam Deck class"),
    P(2400, 1080, 2.75f, "phone, landscape"),
    V(1080, 2400, 2.75f, "phone, portrait"),
    T(3840, 2160, 2.0f, "TV"),
    U(3440, 1080, 1.0f, "31:9 ultrawide desktop"),

    /** The second screen of a dual-screen handheld, for the companion. */
    C(1080, 1240, 2.5f, "second screen of a dual-screen handheld"),
    ;

    val widthDp: Int get() = (widthPx / density).roundToInt()
    val heightDp: Int get() = (heightPx / density).roundToInt()

    /** For the manifest, for example "M 1280x720dp". */
    val label: String get() = "$name ${widthDp}x${heightDp}dp"
}

@Serializable
internal data class AuditShot(val file: String, val group: String, val screen: String, val state: String, val size: String)

@Serializable
internal data class AuditGap(val size: String, val group: String, val screen: String, val state: String, val reason: String) {
    val key: String get() = "$size|$group|$screen|$state"
}

@Serializable
internal data class AuditSizeInfo(val id: String, val pixels: String, val density: Float, val dp: String, val note: String)

@Serializable
internal data class AuditManifest(
    val command: String,
    val sizes: List<AuditSizeInfo>,
    val shots: List<AuditShot>,
    val uncovered: List<AuditGap>,
)

/**
 * Where the audit writes and what it renders, from the system properties the `desktopAudit` Gradle
 * task passes, plus the manifest of every PNG. The manifest is rewritten after each shot, merged
 * with what an earlier run left in the folder, so a run limited with `fuse.audit.only` or
 * `fuse.audit.sizes` keeps the rest.
 */
internal object Audit {
    const val DIR_PROPERTY = "fuse.audit.dir"
    const val ONLY_PROPERTY = "fuse.audit.only"
    const val SIZES_PROPERTY = "fuse.audit.sizes"
    const val COMMAND = "timeout 2400 ./gradlew :ui:shell:desktopAudit -Pfuse.audit.dir=<folder> " +
        "[-Pfuse.audit.only=home,library/all] [-Pfuse.audit.sizes=M,H] --no-daemon --console=plain " +
        "-Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process"

    val dir: File? = System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)

    private val only: List<String> = list(ONLY_PROPERTY).map { it.lowercase() }
    private val sizes: Set<String> = list(SIZES_PROPERTY).map { it.uppercase() }.toSet()

    private fun list(property: String): List<String> =
        System.getProperty(property).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun sizeEnabled(size: AuditSize): Boolean = sizes.isEmpty() || size.name in sizes

    /** True when [group]/[screen] should be rendered: no filter, or a prefix matches the id, the group or the screen. */
    fun wants(group: String, screen: String): Boolean {
        if (only.isEmpty()) return true
        val id = "$group/$screen".lowercase()
        return only.any { p -> id.startsWith(p) || screen.lowercase().startsWith(p) }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val shots = LinkedHashMap<String, AuditShot>()
    private val gaps = LinkedHashMap<String, AuditGap>()
    private var loaded = false

    @Synchronized
    fun shot(entry: AuditShot) {
        load()
        shots[entry.file] = entry
        gaps.remove(AuditGap(entry.size, entry.group, entry.screen, entry.state, "").key)
        write()
    }

    @Synchronized
    fun uncovered(gap: AuditGap) {
        load()
        gaps[gap.key] = gap
        println("Audit: not covered: ${gap.size} ${gap.group}/${gap.screen} (${gap.state}): ${gap.reason}")
        write()
    }

    private fun manifestFile(): File? = dir?.let { File(it, "manifest.json") }

    private fun load() {
        if (loaded) return
        loaded = true
        val file = manifestFile() ?: return
        if (!file.isFile) return
        val previous = runCatching { json.decodeFromString(AuditManifest.serializer(), file.readText()) }.getOrNull() ?: return
        previous.shots.filter { File(it.file).isFile }.forEach { shots[it.file] = it }
        previous.uncovered.forEach { gaps[it.key] = it }
    }

    private fun write() {
        val file = manifestFile() ?: return
        file.parentFile.mkdirs()
        val manifest = AuditManifest(
            command = COMMAND,
            sizes = AuditSize.entries.map {
                AuditSizeInfo(it.name, "${it.widthPx}x${it.heightPx}", it.density, "${it.widthDp}x${it.heightDp}", it.note)
            },
            shots = shots.values.filter { File(it.file).isFile }.sortedWith(compareBy({ it.size }, { it.file })),
            uncovered = gaps.values.sortedWith(compareBy({ it.size }, { it.group }, { it.screen })),
        )
        val tmp = File(file.parentFile, "manifest.json.tmp")
        tmp.writeText(json.encodeToString(AuditManifest.serializer(), manifest))
        tmp.renameTo(file)
    }

    /** Lower-case, dash-separated file name part. */
    fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }
}
