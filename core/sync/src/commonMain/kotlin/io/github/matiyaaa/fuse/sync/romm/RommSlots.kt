package io.github.matiyaaa.fuse.sync.romm

/**
 * The slot a save is kept in on RomM, named for what it is on its console (a PlayStation memory
 * card, a Switch game's save data), so RomM's own pages say what each one is. With more than one
 * person on a device, each has their own: the slot carries their name, and a person's saves come
 * only from their own slot. Saves Fuse sent before 0.4.4 are in `autosave`, and are still read when
 * no one in particular is playing.
 */
object RommSlots {
    /** The slot Fuse sent every save to before 0.4.4. */
    const val LEGACY = "autosave"

    /** The most of a person's name a slot carries (RomM limits a slot's length). */
    private const val PERSON_CHARS = 40

    /** What a save of [format] (a save adapter's format, `psp.savedata`) is called on its console. */
    fun family(format: String): String = when {
        format.startsWith("psx.") -> "PlayStation Memory Card"
        format.startsWith("ps2") || format == "play.mcr" -> "PS2 Memory Card"
        format == "psp.savedata" -> "PSP Save Data"
        format == "ps3.savedata" -> "PS3 Save Data"
        format == "vita.savedata" -> "PS Vita Save Data"
        format == "ps4.savedata" -> "PS4 Save Data"
        format == "anyps5.savedata" -> "PS5 Save Data"
        format == "switch.savedata" -> "Switch Save Data"
        format == "3ds.savedata" -> "3DS Save Data"
        format == "nds.dsv" -> "DS Save"
        format == "wii.nand" -> "Wii Save Data"
        format == "wiiu.savedata" -> "Wii U Save Data"
        format == "gc.gci" -> "GameCube Memory Card"
        format == "n64.split" -> "N64 Save"
        format.startsWith("dc.vmu") -> "Dreamcast VMU"
        format == "x360.savedata" -> "Xbox 360 Save Data"
        format == "mame.nvram" -> "Arcade NVRAM"
        format == "model3.nvram" -> "Model 3 NVRAM"
        format == "sram" -> "Cartridge Save"
        format == "scummvm.saves" -> "ScummVM Saves"
        else -> "Save"
    }

    /** The slot for a save of [format], [person]'s own when one is named. */
    fun of(format: String, person: String?): String {
        val who = person?.trim()?.take(PERSON_CHARS)?.takeIf { it.isNotEmpty() }
        return if (who == null) family(format) else "${family(format)} ($who)"
    }

    /**
     * Whether a save in [slot] is [person]'s to take: their own slot when one is named; with no
     * one named, any slot but a person's (the family's own, `autosave`, or another app's).
     */
    fun belongs(slot: String?, format: String, person: String?): Boolean {
        if (person != null) return slot == of(format, person)
        return slot == null || !(slot.startsWith(family(format) + " (") && slot.endsWith(")"))
    }
}
