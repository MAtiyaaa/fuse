package io.github.matiyaaa.fuse.services

import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import io.github.matiyaaa.fuse.launch.android.AndroidFamilies
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.storage.StorageVolumes
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds installed emulators the way ES-DE does: every catalog package is looked up, and an activity
 * only counts when it exists and is exported. Launcher apps that are not in the catalog are matched
 * by family (forks and renamed builds) through [AndroidEmulatorCatalog.identify]. Package visibility
 * comes from the manifest's `<queries>`.
 */
class AndroidEmulatorDetector(
    context: Context,
    private val volumes: StorageVolumes,
) : EmulatorDetector {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    override suspend fun detect(): List<InstalledEmulator> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, InstalledEmulator>()
        val catalogPackages = AndroidEmulatorCatalog.allPackages().toSet()

        fun consider(pkg: String) {
            if (pkg == appContext.packageName) return
            val info = PackageSupport.packageInfo(pm, pkg) ?: return
            val label = PackageSupport.label(pm, info)
            val matches = AndroidEmulatorCatalog.identify(pkg, label) { activity ->
                PackageSupport.isExportedActivity(pm, pkg, activity)
            }
            for (match in matches) {
                val installed = AndroidEmulatorCatalog.toInstalled(match, label, info.versionName)
                found.putIfAbsent("${installed.id.value}|$pkg", installed)
            }
        }

        catalogPackages.forEach(::consider)

        // Forks and renamed builds: only launcher apps whose package or label names a known family.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = PackageSupport.queryActivities(pm, launcher)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it !in catalogPackages }
        for (pkg in launcherPackages) {
            val label = PackageSupport.packageInfo(pm, pkg)?.let { PackageSupport.label(pm, it) }.orEmpty()
            if (AndroidFamilies.familyOf(pkg, label) != null) consider(pkg)
        }
        found.values.toList()
    }

    /**
     * RetroArch `system/` folders Fuse can read. Other emulators keep firmware in their private
     * `Android/data` folder, which Android 11+ hides from every other app.
     */
    override fun biosFolders(installed: List<InstalledEmulator>): List<String> {
        val primary = volumes.primaryRoot
        val candidates = buildList {
            val retroArch = installed.filter { it.id.value.startsWith("retroarch") }
            if (retroArch.isNotEmpty()) {
                add("$primary/RetroArch/system")
                retroArch.forEach { add("$primary/Android/data/${it.appId.substringBefore('/')}/files/system") }
            }
        }
        return candidates.distinct().filter { File(it).let { f -> f.isDirectory && f.canRead() && f.list() != null } }
    }

    override fun unreadablePaths(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        return volumes.mounted().flatMap { v ->
            val root = v.root.trimEnd('/')
            listOf("$root/Android/data", "$root/Android/obb")
        }.toSet()
    }
}
