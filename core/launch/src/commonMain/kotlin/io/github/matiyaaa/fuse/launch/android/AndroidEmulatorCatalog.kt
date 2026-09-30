package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator

/** A detected Android app mapped to a catalog entry. */
data class AndroidMatch(
    val def: AndroidEmulatorDef,
    val pkg: String,
    /** The verified, exported activity, or null (open-app-only entries, or a family match whose activity is missing). */
    val activity: String?,
    val isFamilyMatch: Boolean,
)

/** One package/activity pair the Android detector should check, ES-DE style. */
data class AndroidDetectionEntry(
    val emulatorId: EmulatorId,
    val pkg: String,
    /** Fully qualified activity; null means the package alone identifies it (open-app-only entries). */
    val activity: String?,
    /** True when the package name is shared by unrelated apps: only trust it if [activity] exists and is exported. */
    val requiresActivityCheck: Boolean,
)

/**
 * All Android adapters and the lookups the Android app needs for detection and the manifest.
 *
 * Detection contract: for each installed package the Android app calls [identify] with a check that
 * says whether a fully qualified activity exists **and is exported**. It turns each [AndroidMatch]
 * into an [InstalledEmulator] with [toInstalled]; `appId` is `package/activity` when an activity was
 * verified, else `package`.
 */
object AndroidEmulatorCatalog {
    /** Package names reused by unrelated apps (spoofed builds). Never trust them without the activity. */
    val SPOOFED_PACKAGES: Set<String> = setOf(
        "com.ludashi.benchmark", "com.ludashi.aibench", "com.tencent.ig", "com.antutu.ABenchMark",
        "com.antutu.benchmark.full", "com.miHoYo.Yuanshen", "com.miHoYo.Yuanshen.nightly",
    )

    val defs: List<AndroidEmulatorDef> = AndroidConsoleDefs.all + AndroidPcDefs.all

    private val byId: Map<String, AndroidEmulatorDef> = defs.associateBy { it.id }

    /** Packages that need an activity check: spoofed ones plus any package more than one entry uses. */
    val activityCheckPackages: Set<String> = SPOOFED_PACKAGES +
        defs.flatMap { d -> d.packages.map { it to d.id } }.groupBy({ it.first }, { it.second })
            .filterValues { it.distinct().size > 1 }.keys

    /** Adapters for every entry plus the native app adapter. */
    val adapters: List<EmulatorAdapter> = defs.map { AndroidIntentAdapter(it, activityCheckPackages) } + NativeAppAdapter

    fun def(id: String): AndroidEmulatorDef? = byId[id]

    fun requiresActivityCheck(pkg: String): Boolean = pkg in activityCheckPackages

    /** Sorted, unique package names for the manifest's `<queries>` (Android 11+ package visibility). */
    fun allPackages(): List<String> = defs.flatMap { it.packages }.distinct().sorted()

    /** Every package/activity pair to check, in catalog order. */
    fun detectionEntries(): List<AndroidDetectionEntry> = defs.flatMap { d ->
        d.apps.map { AndroidDetectionEntry(EmulatorId(d.id), it.pkg, it.activityClass, requiresActivityCheck(it.pkg)) }
    }

    /**
     * Catalog entries an installed app is. Exact package matches win; each needs one of its activities
     * to exist ([hasActivity]: exists and exported). Entries with only a package (open-app-only) match on
     * the package. Without an exact match, [AndroidFamilies.familyOf] decides.
     */
    fun identify(pkg: String, label: String = "", hasActivity: (String) -> Boolean): List<AndroidMatch> {
        val exact = defs.mapNotNull { d ->
            val entries = d.apps.filter { it.pkg == pkg }
            if (entries.isEmpty()) return@mapNotNull null
            val activities = entries.mapNotNull { it.activityClass }.distinct()
            when {
                activities.isEmpty() -> AndroidMatch(d, pkg, null, isFamilyMatch = false)
                else -> activities.firstOrNull(hasActivity)?.let { AndroidMatch(d, pkg, it, isFamilyMatch = false) }
            }
        }
        if (exact.isNotEmpty()) {
            val ids = exact.map { it.def.id }.toSet()
            return exact.filter { m -> m.def.supersededBy.none { it in ids } }
        }
        val family = AndroidFamilies.familyOf(pkg, label)?.let(::def) ?: return emptyList()
        val activity = family.apps.mapNotNull { it.activityClass }.distinct().firstOrNull(hasActivity)
        return listOf(AndroidMatch(family, pkg, activity, isFamilyMatch = true))
    }

    /** The [InstalledEmulator] for a match; [label] is the app's own name ("Citron Nightly"). */
    fun toInstalled(match: AndroidMatch, label: String? = null, version: String? = null): InstalledEmulator =
        InstalledEmulator(
            id = EmulatorId(match.def.id),
            name = label ?: match.def.name,
            host = Host.ANDROID,
            appId = match.activity?.let { "${match.pkg}/$it" } ?: match.pkg,
            version = version,
            platforms = match.def.platforms,
            detectedVia = if (match.isFamilyMatch) "Package (${match.def.name} family)" else "Package",
            isFamilyMatch = match.isFamilyMatch,
        )
}
