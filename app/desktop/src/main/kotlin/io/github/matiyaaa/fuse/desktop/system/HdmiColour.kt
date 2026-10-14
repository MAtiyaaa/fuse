package io.github.matiyaaa.fuse.desktop.system

/**
 * Whether the screen is likely sent in reduced colour, and what to do about it, for Settings.
 *
 * On Linux, AMD's and Intel's open drivers can't use HDMI 2.1's faster signalling (the HDMI Forum
 * hasn't let its specification be implemented in open source), so a 4K screen at 100 Hz or more
 * over HDMI gets HDMI 2.0's bandwidth, which only fits YCbCr 4:2:0 in 8 bits, in TV range. Every
 * soft gradient on screen then shows its steps, whatever an app does to hide them; at 60 Hz the
 * same cable carries full RGB. NVIDIA's own driver isn't held back the same way.
 */
internal object HdmiColour {
    /** The note for [connector] (the kernel's name, `HDMI-A-1`) at [width] by [height] and [refreshHz], or null. */
    fun note(connector: String?, width: Int, height: Int, refreshHz: Double, driver: String?, linux: Boolean = DesktopOs.current == DesktopOs.LINUX): String? {
        if (!linux || connector == null || !connector.startsWith("HDMI", ignoreCase = true)) return null
        if (driver?.contains("nvidia", ignoreCase = true) == true) return null
        if (maxOf(width, height) < 3_800 || minOf(width, height) < 2_100 || refreshHz < 100.0) return null
        return "4K at ${refreshHz.toInt()} Hz over HDMI reaches the TV in reduced colour on Linux (YCbCr 4:2:0), which shows soft light as bands. " +
            "At 60 Hz the same cable carries full colour; a DisplayPort to HDMI 2.1 adapter keeps 120 Hz with it"
    }
}
