package io.github.matiyaaa.fuse.data.settings

/**
 * The one way this device moves its saves between devices. Only one at a time: two would move the
 * same save files and fight over them. Fuse One's profiles, play time and household games, and
 * RomM's and Syncthing's other features, keep working whichever is chosen.
 */
enum class SaveProvider(val label: String) {
    FUSE_ONE("Fuse One"),
    ROMM("RomM"),
    SYNCTHING("Syncthing"),
    OFF("Off");

    companion object {
        /**
         * This device's choice. Before anyone chose, the one switched on (Syncthing first, as it is
         * only ever turned on on purpose, then Fuse One, then RomM), or [OFF].
         */
        fun of(s: AppSettings): SaveProvider {
            entries.firstOrNull { it.name == s.sync.saveProvider }?.let { return it }
            return when {
                s.syncthing.enabled -> SYNCTHING
                s.sync.enabled && s.sync.role.isNotEmpty() && s.sync.saves -> FUSE_ONE
                s.romm.enabled && s.romm.saveSync -> ROMM
                else -> OFF
            }
        }

        /**
         * [s] with [provider] the one moving saves here, and the other two not: Fuse One's save sync,
         * RomM's save sync and Syncthing's switch set to match. Syncthing's own service is switched
         * by its caller, which also owns its state.
         */
        fun choose(s: AppSettings, provider: SaveProvider): AppSettings = s.copy(
            sync = s.sync.copy(saveProvider = provider.name, saves = provider == FUSE_ONE),
            romm = s.romm.copy(saveSync = provider == ROMM),
            syncthing = s.syncthing.copy(enabled = provider == SYNCTHING),
        )

        /** [s] with the flags brought in line with [of]: never two providers on at once (settings from before the choice existed). */
        fun settle(s: AppSettings): AppSettings {
            val now = of(s)
            // Nothing on and nothing chosen: left as it is, so turning one on later makes it the one.
            if (now == OFF && s.sync.saveProvider.isEmpty()) return s
            val settled = choose(s, now).let { if (s.sync.saveProvider.isEmpty()) it.copy(sync = it.sync.copy(saveProvider = "")) else it }
            return if (settled == s) s else settled
        }
    }
}
