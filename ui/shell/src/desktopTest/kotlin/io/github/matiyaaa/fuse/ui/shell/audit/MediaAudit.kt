package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.PadButton

/**
 * What a switch between tabs costs: every tab in turn, twice (the second round shows a tab already
 * visited), with the slowest frames after each switch. Written to tab-switch.txt in the audit folder.
 */
internal fun AuditDriver.tabSwitchCost(out: java.io.File) {
    scenario("media", "tab switch cost") {
        useLibrary()
        home()
        settle(1_500)
        val lines = ArrayList<String>()
        for (round in 1..2) {
            for (i in 1..5) {
                val worst = frameCost { tap(PadButton.R1) }
                settle(800)
                lines += "round $round, tab ${i + 1}: slowest frames ${worst.take(3).joinToString { "${it / 1000.0} ms" }}"
            }
            tap(PadButton.L1, 5)
            settle(800)
        }
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n") + "\n")
    }
}

/** What 0.2.8 changed, one quick pass each. */
internal fun AuditDriver.mediaScreens() {
    scenario("media", "tab marker") {
        useLibrary()
        home()
        settle(600)
        tap(PadButton.R1)
        shoot("travelling to the next tab", 60)
        shoot("arrived under it", 900)
        tap(PadButton.R1, 3)
        shoot("several tabs on, the carousel scrolling", 90)
        shoot("settled under the tab", 1_000)
        tap(PadButton.L1, 4)
        shoot("back to Home", 1_000)
    }
}
