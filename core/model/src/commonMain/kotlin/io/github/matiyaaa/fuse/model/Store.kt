package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * Which edition of the Obtainium Emulation Pack the Store follows. The two really differ: the
 * Dual-Screen edition swaps some apps for forks built for devices with a second screen (Cemu) and
 * adds companions that only make sense there (Mjolnir, ES-DE Companion, Jarngreipr, Emulnk).
 */
@Serializable
enum class StoreVariant {
    STANDARD,
    DUAL_SCREEN,
}
