package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.integrations.obtainium.VersionStanding
import io.github.matiyaaa.fuse.integrations.obtainium.VersionText

/**
 * Whether an installed app is current, honestly. When Fuse installed it (and Android still has that
 * build), Fuse knows exactly which release it was, so a different newest release is an update.
 * Otherwise the version Android reports is compared with the newest release's, and only versions of
 * the same kind are compared: anything else is [Standing.UNKNOWN], never a made-up update.
 */
internal object StoreStanding {
    fun of(installed: InstalledApp, release: StoreRelease): Standing {
        val record = installed.record?.takeIf { it.versionCode == installed.versionCode }
        if (record != null) {
            val latest = release.version
            val mine = record.version
            return when {
                latest != null && mine != null -> when {
                    latest == mine -> Standing.CURRENT
                    VersionText.standing(mine, latest) == VersionStanding.CURRENT -> Standing.CURRENT
                    else -> Standing.UPDATE
                }
                // A download page that names no version: the file it offers tells.
                release.file != null && record.file != null -> if (release.file.url == record.file) Standing.CURRENT else Standing.UPDATE
                else -> Standing.UNKNOWN
            }
        }
        val name = installed.versionName?.takeIf { it.isNotBlank() } ?: return Standing.UNKNOWN
        val latest = release.version ?: return Standing.UNKNOWN
        return when (VersionText.standing(name, latest)) {
            VersionStanding.CURRENT -> Standing.CURRENT
            VersionStanding.BEHIND -> Standing.UPDATE
            VersionStanding.UNKNOWN -> Standing.UNKNOWN
        }
    }
}
