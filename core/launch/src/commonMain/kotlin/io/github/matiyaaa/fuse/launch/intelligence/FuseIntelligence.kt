package io.github.matiyaaa.fuse.launch.intelligence

import io.github.matiyaaa.fuse.launch.desktop.DesktopInstallKind
import io.github.matiyaaa.fuse.launch.linux.LinuxInstallKind
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One thing Intelligence found, with how it knows. */
@Serializable
data class Evidence(val value: String, val how: String, val at: Long)

/** What Fuse learned about one install of an emulator. */
@Serializable
data class Learned(
    /** The install as it was when this was learned: its version, where it is, and when its program changed. */
    val fingerprint: String,
    /** When this install was first seen. A game it ran before then doesn't verify this install. */
    val since: Long,
    /** Where it keeps its settings, once found. */
    val config: Evidence? = null,
    /** True when a look found no settings folder: looked for again only once the install changes. */
    val configNotFound: Boolean = false,
)

/** Everything Intelligence keeps, in Fuse's own file. */
@Serializable
data class Knowledge(
    val emulators: Map<String, Learned> = emptyMap(),
    /** Discoveries the person said are wrong, by emulator: never learned again. */
    val rejected: Map<String, List<String>> = emptyMap(),
)

/** The steps from finding an emulator to seeing it run a game. */
enum class Check(val title: String) {
    DETECTED("Detected"),
    INSTALLED("Installed"),
    EXECUTABLE("Can run"),
    CONFIGURED("Set up"),
    LAUNCH_VERIFIED("Launch verified"),
}

enum class CheckState { DONE, NOT_YET, CANT_CHECK }

data class CheckResult(val check: Check, val state: CheckState, val evidence: String)

/** How far Fuse has seen an emulator go on this device, step by step, each with its evidence. */
data class Verification(val emulator: EmulatorId, val checks: List<CheckResult>) {
    /** The furthest step done. */
    val furthest: Check? get() = checks.lastOrNull { it.state == CheckState.DONE }?.check

    operator fun get(check: Check): CheckResult? = checks.firstOrNull { it.check == check }
}

/** What one round of learning did: answers reused as they were, emulators looked into, and how many looks it took. */
data class LearnReport(val reused: Int, val investigated: Int, val looks: Int)

/**
 * Fuse Intelligence: what Fuse learns about emulators by itself, by looking and never touching.
 *
 * It only fills gaps. An emulator whose install hasn't changed since it was understood costs a
 * look or two to confirm the answer still holds, and nothing more; one Fuse already knows (in
 * [KnownFolders]) is answered from that first. Only a new or changed install, or an answer that
 * stopped holding, is looked into, and only for what is missing. Everything outside Fuse is seen
 * through [ReadOnlyLook], which cannot change anything; what is learned goes only to Fuse's own
 * [KnowledgeStore]. A discovery the person rejects is never made again.
 *
 * Not safe to call from two threads at once: the store takes turns.
 */
class FuseIntelligence(
    private val store: KnowledgeStore,
    look: ReadOnlyLook,
    private val clock: () -> Long,
) {
    private val counted = CountingLook(look)
    private val json = Json { ignoreUnknownKeys = true }
    private var knowledge: Knowledge = runCatching { store.read()?.let { json.decodeFromString(Knowledge.serializer(), it) } }.getOrNull() ?: Knowledge()

    /** What is known now (for tests and diagnostics). */
    val known: Knowledge get() = knowledge

    /**
     * Brings Fuse's understanding of [installed] up to date, looking only into what changed or is
     * missing. Built-in openers (shortcuts, scripts) have nothing to learn.
     */
    fun learn(installed: List<InstalledEmulator>): LearnReport {
        val before = counted.count
        var reused = 0
        var investigated = 0
        var next = knowledge
        for (e in installed) {
            if (builtIn(e)) continue
            val key = e.id.value
            val fp = fingerprint(e)
            val prev = next.emulators[key]
            val same = prev != null && prev.fingerprint == fp
            // Unchanged, and the answer still there: nothing to do.
            if (same && (prev!!.configNotFound || prev.config?.let { counted.isDirectory(it.value) } == true)) {
                reused++
                continue
            }
            investigated++
            val since = if (same) prev!!.since else clock()
            val rejected = next.rejected[key].orEmpty().toSet()
            val config = findConfig(e, rejected)
            next = next.copy(emulators = next.emulators + (key to Learned(fp, since, config, configNotFound = config == null)))
        }
        if (next != knowledge) save(next)
        return LearnReport(reused, investigated, counted.count - before)
    }

    /**
     * How far [emulator] has gone on this device: found, still there, able to run, set up, and a
     * game seen running through Fuse since this install appeared ([lastPlayed]: the latest end of
     * such a session on this device, and how many there were).
     */
    fun verify(emulator: InstalledEmulator, lastPlayed: Pair<Long, Long>?): Verification {
        val e = emulator
        val learned = knowledge.emulators[e.id.value]
        val checks = ArrayList<CheckResult>()
        checks += CheckResult(Check.DETECTED, CheckState.DONE, "Found via ${e.detectedVia}" + if (e.isFamilyMatch) ", as a build of a known emulator" else "")
        checks += installed(e)
        checks += executable(e)
        checks += when {
            e.host == Host.ANDROID -> CheckResult(Check.CONFIGURED, CheckState.CANT_CHECK, "Android keeps each app's settings private")
            learned?.config != null -> CheckResult(Check.CONFIGURED, CheckState.DONE, "${learned.config.how}: ${learned.config.value}")
            learned == null -> CheckResult(Check.CONFIGURED, CheckState.NOT_YET, "Not looked at yet")
            else -> CheckResult(Check.CONFIGURED, CheckState.NOT_YET, "No settings folder found yet. Opening it once usually makes one")
        }
        val since = learned?.since ?: 0L
        checks += when {
            lastPlayed == null -> CheckResult(Check.LAUNCH_VERIFIED, CheckState.NOT_YET, "No game started through Fuse has run in it on this device yet")
            lastPlayed.first < since -> CheckResult(Check.LAUNCH_VERIFIED, CheckState.NOT_YET, "Games ran in an earlier install; not this one yet")
            else -> CheckResult(Check.LAUNCH_VERIFIED, CheckState.DONE, if (lastPlayed.second == 1L) "A game started through Fuse ran in it" else "${lastPlayed.second} games started through Fuse ran in it")
        }
        return Verification(e.id, checks)
    }

    /** Says the settings folder found for [emulator] is wrong: it is forgotten and never learned again. */
    fun reject(emulator: EmulatorId) {
        val key = emulator.value
        val found = knowledge.emulators[key]?.config?.value ?: return
        save(knowledge.copy(
            emulators = knowledge.emulators - key,
            rejected = knowledge.rejected + (key to (knowledge.rejected[key].orEmpty() + found).distinct()),
        ))
    }

    /** Forgets everything learned about [emulator] (rejections stay): it is looked into again next time. */
    fun forget(emulator: EmulatorId) {
        if (emulator.value !in knowledge.emulators) return
        save(knowledge.copy(emulators = knowledge.emulators - emulator.value))
    }

    private fun save(next: Knowledge) {
        knowledge = next
        runCatching { store.write(json.encodeToString(Knowledge.serializer(), next)) }
    }

    // ---------------------------------------------------------------- looking

    private fun builtIn(e: InstalledEmulator): Boolean =
        e.detectedVia == LinuxInstallKind.BUILT_IN.label || e.detectedVia == DesktopInstallKind.BUILT_IN.label || e.appId.isBlank() || e.appId == "builtin"

    /** A path-like [InstalledEmulator.appId] (an executable, an AppImage, an app bundle), or null for a package or Flatpak id. */
    private fun path(e: InstalledEmulator): String? = e.appId.takeIf { e.host != Host.ANDROID && !flatpak(e) && (it.startsWith("/") || it.contains(":\\") || it.contains(":/")) }

    private fun flatpak(e: InstalledEmulator) = e.host == Host.LINUX && e.detectedVia == LinuxInstallKind.FLATPAK.label

    private fun flatpakApp(e: InstalledEmulator): String? = listOf(
        "${counted.home.trimEnd('/')}/.local/share/flatpak/app/${e.appId}",
        "/var/lib/flatpak/app/${e.appId}",
    ).firstOrNull { counted.isDirectory(it) }

    private fun fingerprint(e: InstalledEmulator): String {
        val changed = path(e)?.let { counted.modified(it) } ?: if (flatpak(e)) flatpakApp(e)?.let { counted.modified("$it/current") } else null
        return listOf(e.version.orEmpty(), e.detectedVia, e.appId, changed?.toString().orEmpty()).joinToString("|")
    }

    private fun installed(e: InstalledEmulator): CheckResult = when {
        e.host == Host.ANDROID -> CheckResult(Check.INSTALLED, CheckState.DONE, "Android lists ${e.appId} as installed")
        flatpak(e) -> flatpakApp(e)?.let { CheckResult(Check.INSTALLED, CheckState.DONE, "Flatpak has ${e.appId} in $it") }
            ?: CheckResult(Check.INSTALLED, CheckState.NOT_YET, "Flatpak lists ${e.appId}, but its folder wasn't found")
        else -> path(e)?.let { p ->
            if (counted.exists(p)) CheckResult(Check.INSTALLED, CheckState.DONE, p) else CheckResult(Check.INSTALLED, CheckState.NOT_YET, "$p isn't there any more")
        } ?: CheckResult(Check.INSTALLED, CheckState.DONE, "On the system path as ${e.appId}")
    }

    private fun executable(e: InstalledEmulator): CheckResult {
        val ran = runs(e)
        if (ran.state != CheckState.DONE) return ran
        return needsVulkan(e) ?: ran
    }

    /**
     * An emulator that draws only with Vulkan (shadPS4) can't start without a Vulkan library on the
     * system; looked for by name where Linux distributions and Windows keep it, never opened. A
     * Flatpak brings its own, so it isn't asked. Where none of the usual folders exists (an unusual
     * layout), Fuse says nothing rather than guess.
     */
    private fun needsVulkan(e: InstalledEmulator): CheckResult? {
        if (e.id.value.substringAfterLast('.') !in VULKAN_ONLY || flatpak(e)) return null
        val places = when (e.host) {
            Host.LINUX -> listOf("/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/lib/x86_64-linux-gnu", "/lib64")
                .map { it to "$it/libvulkan.so.1" }
            Host.WINDOWS -> listOf((counted.env("SystemRoot") ?: "C:/Windows").replace('\\', '/') + "/System32").map { it to "$it/vulkan-1.dll" }
            else -> return null
        }
        val looked = places.filter { counted.isDirectory(it.first) }
        if (looked.isEmpty()) return null
        if (looked.any { counted.exists(it.second) }) return null
        return CheckResult(Check.EXECUTABLE, CheckState.NOT_YET, "It draws only with Vulkan, and no Vulkan library was found. Install your graphics driver's Vulkan support")
    }

    private fun runs(e: InstalledEmulator): CheckResult {
        if (e.host == Host.ANDROID) return CheckResult(Check.EXECUTABLE, CheckState.CANT_CHECK, "Android opens it; Fuse can't look inside other apps")
        if (flatpak(e)) {
            val app = flatpakApp(e)
            return if (app != null && counted.isDirectory("$app/current/active/files")) CheckResult(Check.EXECUTABLE, CheckState.DONE, "Its Flatpak is deployed and ready to run")
            else CheckResult(Check.EXECUTABLE, CheckState.NOT_YET, "Its Flatpak isn't deployed")
        }
        val p = path(e) ?: return CheckResult(Check.EXECUTABLE, CheckState.DONE, "Runs from the system path")
        return when {
            e.host == Host.MACOS && p.endsWith(".app") ->
                if (counted.isDirectory("$p/Contents/MacOS")) CheckResult(Check.EXECUTABLE, CheckState.DONE, "Its app bundle has its program")
                else CheckResult(Check.EXECUTABLE, CheckState.NOT_YET, "Its app bundle has no program inside")
            counted.canExecute(p) -> CheckResult(Check.EXECUTABLE, CheckState.DONE, "Its program can run")
            counted.exists(p) -> CheckResult(Check.EXECUTABLE, CheckState.NOT_YET, "Found, but not allowed to run (on Linux, its executable bit is off)")
            else -> CheckResult(Check.EXECUTABLE, CheckState.NOT_YET, "Its program isn't there")
        }
    }

    /**
     * Where [e] keeps its settings: what Fuse knows first, then where emulators like it keep theirs
     * (a Flatpak's own folder, an AppImage's portable folders, the usual folders named after it).
     * Stops at the first that is there. Only folders are looked at, never what is in them.
     */
    private fun findConfig(e: InstalledEmulator, rejected: Set<String>): Evidence? {
        val now = clock()
        fun found(path: String, how: String) = path.takeIf { it !in rejected && counted.isDirectory(it) }?.let { Evidence(it, how, now) }
        if (e.host == Host.ANDROID) return null
        if (flatpak(e)) return found("${counted.home.trimEnd('/')}/.var/app/${e.appId}", "Its Flatpak folder, made the first time it ran")
        for (p in KnownFolders.of(e.id.value, e.host, counted)) found(p, "Where Fuse knows it keeps its settings")?.let { return it }
        val program = path(e)
        if (program != null) {
            // Portable installs keep their settings beside the program.
            if (e.host == Host.LINUX && program.endsWith(".AppImage", ignoreCase = true)) {
                found("$program.config", "Its AppImage's portable settings folder")?.let { return it }
                found("$program.home", "Its AppImage's portable home folder")?.let { return it }
            }
            val folder = program.replace('\\', '/').substringBeforeLast('/')
            if (e.host == Host.WINDOWS) {
                val names = counted.names(folder).map { it.lowercase() }
                if ("portable.txt" in names || "user" in names || names.any { it.endsWith(".ini") || it.endsWith(".cfg") }) {
                    return Evidence(folder, "Settings kept beside the program (a portable install)", now)
                }
            }
        }
        // The usual folders named after it, for an emulator Fuse doesn't know.
        val names = buildList {
            add(e.name)
            add(e.name.lowercase())
            add(e.name.lowercase().replace(' ', '-'))
            program?.replace('\\', '/')?.substringAfterLast('/')?.substringBefore('.')?.takeIf { it.isNotBlank() }?.let { add(it); add(it.lowercase()) }
            if (e.host == Host.LINUX && program == null) add(e.appId.substringAfterLast('/'))
        }.distinct()
        val home = counted.home.trimEnd('/')
        val bases = when (e.host) {
            Host.LINUX -> listOf("config/", "data/", "~/.")
            Host.WINDOWS -> listOfNotNull(counted.env("APPDATA"), counted.env("LOCALAPPDATA"), "$home/Documents").map { it.trimEnd('/', '\\') + "/" }
            Host.MACOS -> listOf("$home/Library/Application Support/", "$home/.config/")
            Host.ANDROID -> emptyList()
        }
        for (base in bases) for (n in names) {
            val path = if (e.host == Host.LINUX) KnownFolders.resolve(base + n, counted) else base + n
            found(path, "A folder named after it, where programs keep their settings")?.let { return it }
        }
        return null
    }
}

/** [ReadOnlyLook] that counts each look, so a round that did nothing can be told from one that did. */
private class CountingLook(private val look: ReadOnlyLook) : ReadOnlyLook {
    var count = 0
        private set

    override val home: String get() = look.home
    override fun env(name: String): String? = look.env(name)
    override fun exists(path: String): Boolean { count++; return look.exists(path) }
    override fun isDirectory(path: String): Boolean { count++; return look.isDirectory(path) }
    override fun canExecute(path: String): Boolean { count++; return look.canExecute(path) }
    override fun modified(path: String): Long? { count++; return look.modified(path) }
    override fun names(path: String): List<String> { count++; return look.names(path) }
}

/** Emulators that draw only with Vulkan (by the end of their id): shadPS4's renderer is Vulkan's alone. */
private val VULKAN_ONLY = setOf("shadps4")
