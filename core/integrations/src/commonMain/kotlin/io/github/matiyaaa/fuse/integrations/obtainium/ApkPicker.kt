package io.github.matiyaaa.fuse.integrations.obtainium

/** A file a release offers: its [name], where it is, and (from GitHub) its size and `sha256:` digest. */
data class ApkLink(val name: String, val url: String, val sizeBytes: Long? = null, val digest: String? = null)

/** What a release offers this device. */
sealed interface ApkChoice {
    /** The APK to install. */
    data class Install(val apk: ApkLink) : ApkChoice

    /** Nothing Fuse can install by itself; [reason] says why (a zip, a split bundle, no file for this device). */
    data class Manual(val reason: String) : ApkChoice
}

/**
 * Chooses the file to install from what a release offers, by the pack's rules, the way Obtainium
 * does: the pack's file filter (or its inverse), then the device's processor types when the pack
 * asks for that, then the pack's preferred index among what is left.
 */
object ApkPicker {
    private val apkExtensions = listOf(".apk")
    private val bundleExtensions = listOf(".xapk", ".apkm", ".apks")

    /** Obtainium's names for each Android processor type, as they appear in file names. */
    private val abiAliases = mapOf(
        "arm64-v8a" to listOf("aarch64", "arm64"),
        "armeabi-v7a" to listOf("armv7", "armeabi"),
        "x86_64" to listOf("x64"),
    )

    /** True for a file Obtainium treats as an app: an APK or a split bundle (and a zip when the pack allows zips). */
    fun isAppFile(name: String, includeZips: Boolean = false): Boolean {
        val lower = name.lowercase()
        return (apkExtensions + bundleExtensions).any { lower.endsWith(it) } || (includeZips && lower.endsWith(".zip"))
    }

    fun isApk(name: String): Boolean = apkExtensions.any { name.lowercase().endsWith(it) }

    /** The pack's file filter: [pattern] must match the file's name (or must not, when [invert]). */
    fun filter(links: List<ApkLink>, pattern: String?, invert: Boolean, key: (ApkLink) -> String = { it.name }): List<ApkLink> {
        if (pattern.isNullOrEmpty()) return links
        val regex = VersionText.compile(pattern) ?: return emptyList()
        return links.filter { regex.containsMatchIn(key(it)) != invert }
    }

    /**
     * Keeps the files for the first of [abis] (the device's, best first) that narrows the list, as
     * Obtainium does; a list it can't narrow is left as it is (a universal APK, or one file).
     */
    fun byArch(links: List<ApkLink>, abis: List<String>, key: (ApkLink) -> String = { it.name }): List<ApkLink> {
        if (links.size <= 1) return links
        for (abi in abis) {
            val names = listOf(abi) + abiAliases[abi].orEmpty()
            val regex = Regex(".*(?:${names.joinToString("|") { Regex.escape(it) }}).*", RegexOption.IGNORE_CASE)
            val narrowed = links.filter { regex.matches(key(it)) }
            if (narrowed.isNotEmpty() && narrowed.size < links.size) return narrowed
        }
        return links
    }

    /**
     * The file to install from [files] (every app file of the release) for a device with [abis].
     * Only a plain APK is ever chosen: Fuse hands exactly one APK to Android's installer, so a
     * release that comes as a zip or a split bundle is [ApkChoice.Manual].
     */
    fun choose(files: List<ApkLink>, rules: PackRules, preferredIndex: Int?, abis: List<String>): ApkChoice {
        val filtered = filter(files, rules.apkFilter, rules.invertApkFilter)
        val forDevice = if (rules.filterByArch) byArch(filtered, abis) else filtered
        val apks = forDevice.filter { isApk(it.name) }
        if (apks.isEmpty()) {
            return when {
                forDevice.any { it.name.lowercase().endsWith(".zip") } -> ApkChoice.Manual("This release comes as a zip, so it's installed by hand.")
                forDevice.any { f -> bundleExtensions.any { f.name.lowercase().endsWith(it) } } -> ApkChoice.Manual("This release comes as a split bundle, which Android can't install from one file.")
                else -> ApkChoice.Manual("This release has no APK for this device.")
            }
        }
        if (apks.size == 1) return ApkChoice.Install(apks[0])
        // Still more than one: one built for this device's processor, a universal build, or the
        // pack's preferred one, in that order.
        val narrowed = byArch(apks, abis)
        if (narrowed.size == 1) return ApkChoice.Install(narrowed[0])
        narrowed.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return ApkChoice.Install(it) }
        val index = preferredIndex?.takeIf { it in narrowed.indices } ?: 0
        return ApkChoice.Install(narrowed[index])
    }
}
