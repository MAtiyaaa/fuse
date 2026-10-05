package io.github.matiyaaa.fuse.ui.shell.quick

/** How a quick menu item draws and answers the controller. */
internal enum class QuickKind {
    /** A button or switch: a press does it. */
    TILE,

    /** Brightness or volume: a bar across the menu, or a tall bar in a tile. */
    SLIDER,

    /** What is playing (the menu music, or Fuse Player), with skip buttons. */
    MUSIC,

    /** A setting with a few choices side by side (the second screen: Off, Fuse, Flipped). */
    CHOICE,
}

/**
 * Everything the quick menu can hold. [spans] are the widths it can take, in thirds of the menu;
 * the first is the smallest. Stored by name, so the order of this list can change freely.
 */
internal enum class QuickId(val title: String, val kind: QuickKind, val spans: List<Int>, val defaultSpan: Int, val blurb: String) {
    WIFI("Wi-Fi", QuickKind.TILE, listOf(1, 2, 3), 1, "Opens the system's Wi-Fi panel"),
    BLUETOOTH("Bluetooth", QuickKind.TILE, listOf(1, 2, 3), 1, "Opens the system's Bluetooth panel"),
    CAPTURE("Screenshot", QuickKind.TILE, listOf(1, 2, 3), 1, "A picture of the screen, or held, a recording"),
    SECOND_SCREEN("Second screen", QuickKind.CHOICE, listOf(1, 2, 3), 3, "Off, Fuse below, or Flipped above"),
    HIDE_SECOND("Hide screen", QuickKind.TILE, listOf(1, 2, 3), 1, "Puts the second screen away and back"),
    DISPLAY("Display", QuickKind.TILE, listOf(1, 2, 3), 1, "Screens, rotation and performance"),
    CONTROLLER("Controller", QuickKind.TILE, listOf(1, 2, 3), 1, "Buttons, mapping and sticks"),
    PERFORMANCE("Performance", QuickKind.TILE, listOf(1, 2, 3), 1, "Automatic, Balanced or Smooth"),
    LOW_POWER("Low Power", QuickKind.TILE, listOf(1, 2, 3), 1, "Calmer motion, longer battery"),
    FIND_GAMES("Find games", QuickKind.TILE, listOf(1, 2, 3), 1, "A quick look for new games"),
    CARTRIDGE("Cartridge", QuickKind.TILE, listOf(1, 2, 3), 1, "Opens Cartridge"),
    HOME_STYLE("Home", QuickKind.TILE, listOf(1, 2, 3), 1, "Flow or Channels, in one press"),
    SOUND("Sound", QuickKind.TILE, listOf(1, 2, 3), 1, "Interface sounds on or off"),
    MUSIC("Now playing", QuickKind.MUSIC, listOf(1, 2, 3), 3, "The menu music or Fuse Player, with skip"),
    BRIGHTNESS("Brightness", QuickKind.SLIDER, listOf(1, 3), 3, "A slider for the screen's brightness"),
    VOLUME("Volume", QuickKind.SLIDER, listOf(1, 3), 3, "A slider for the device's volume"),
    SEARCH("Search", QuickKind.TILE, listOf(1, 2, 3), 1, "Search games, systems and settings"),
    THEMES("Themes", QuickKind.TILE, listOf(1, 2, 3), 1, "Pick or make a theme"),
    PHONE_LINK("Phone Link", QuickKind.TILE, listOf(1, 2, 3), 1, "Your phone as a keyboard and controller"),
    PLAY_TIME("Play time", QuickKind.TILE, listOf(1, 2, 3), 1, "What you played, and for how long"),
    STANDBY("Standby", QuickKind.TILE, listOf(1, 2, 3), 1, "The standby screen, right away"),
    FRAME_TIMES("Frame times", QuickKind.TILE, listOf(1, 2, 3), 1, "Shows how smoothly Fuse draws"),
    FULLSCREEN("Full screen", QuickKind.TILE, listOf(1, 2, 3), 1, "Full screen or a window"),
    ;

    /** The next width, after the widest the smallest. */
    fun nextSpan(span: Int): Int {
        val i = spans.indexOf(span)
        return if (i < 0) defaultSpan else spans[(i + 1) % spans.size]
    }
}

/** One item in the quick menu and how wide it is. */
internal data class QuickSlot(val id: QuickId, val span: Int)

/** Where a slot sits: its row, its first column and how many it takes. */
internal data class QuickPlaced(val index: Int, val row: Int, val column: Int, val span: Int)

/**
 * The quick menu's arrangement: which items, in what order, how wide. Pure, so every rule is tested
 * on its own; the menu only draws what this works out.
 */
internal object QuickLayout {
    const val COLUMNS = 3

    /** As Fuse comes: the switches people reach for most first, then what is playing and the sliders. */
    val default: List<QuickSlot> = listOf(
        QuickId.WIFI, QuickId.BLUETOOTH, QuickId.CAPTURE,
        QuickId.SECOND_SCREEN,
        QuickId.HIDE_SECOND, QuickId.DISPLAY, QuickId.CONTROLLER,
        QuickId.PERFORMANCE, QuickId.LOW_POWER, QuickId.FIND_GAMES,
        QuickId.CARTRIDGE, QuickId.HOME_STYLE, QuickId.SOUND,
        QuickId.MUSIC,
        QuickId.BRIGHTNESS, QuickId.VOLUME,
    ).map { QuickSlot(it, it.defaultSpan) }

    /**
     * The stored arrangement ("WIFI:1"), or [default] when nothing is stored. Unknown names (from a
     * newer Fuse), repeats and widths an item can't take are dropped or mended, never fatal.
     */
    fun decode(stored: List<String>): List<QuickSlot> {
        if (stored.isEmpty()) return default
        val seen = HashSet<QuickId>()
        return stored.mapNotNull { entry ->
            val id = QuickId.entries.firstOrNull { it.name == entry.substringBefore(':') } ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val span = entry.substringAfter(':', "").toIntOrNull()?.takeIf { it in id.spans } ?: id.defaultSpan
            QuickSlot(id, span)
        }
    }

    /** For settings; the [default] arrangement is stored as nothing, so a later default reaches it. */
    fun encode(slots: List<QuickSlot>): List<String> =
        if (slots == default) emptyList() else slots.map { "${it.id.name}:${it.span}" }

    /**
     * Rows, filled in order: an item that doesn't fit in what is left of a row starts the next one.
     * The order is the user's, so nothing jumps ahead to fill a gap.
     */
    fun place(spans: List<Int>): List<QuickPlaced> {
        val out = ArrayList<QuickPlaced>(spans.size)
        var row = 0
        var col = 0
        spans.forEachIndexed { i, raw ->
            val span = raw.coerceIn(1, COLUMNS)
            if (col + span > COLUMNS) { row++; col = 0 }
            out += QuickPlaced(i, row, col, span)
            col += span
            if (col >= COLUMNS) { row++; col = 0 }
        }
        return out
    }

    /** [slots] with the item at [from] taken out and put at [to]. */
    fun <T> move(slots: List<T>, from: Int, to: Int): List<T> {
        if (from !in slots.indices) return slots
        val target = to.coerceIn(0, slots.lastIndex)
        if (from == target) return slots
        val list = slots.toMutableList()
        val item = list.removeAt(from)
        list.add(target, item)
        return list
    }

    /**
     * The item a direction leads to from [index], or null at an edge. Left and right stay in the
     * row; up and down go to the row above or below, to the item under [anchor] (the column the
     * selection came from, so a long way down a column keeps to it), else the nearest one.
     */
    fun neighbour(placed: List<QuickPlaced>, index: Int, dx: Int, dy: Int, anchor: Float? = null): Int? {
        val here = placed.getOrNull(index) ?: return null
        if (dx != 0) {
            val row = placed.filter { it.row == here.row }
            val i = row.indexOfFirst { it.index == index } + dx
            return row.getOrNull(i)?.index
        }
        val target = here.row + dy
        val row = placed.filter { it.row == target }.ifEmpty { return null }
        val x = anchor ?: centre(here)
        return row.firstOrNull { x >= it.column && x < it.column + it.span }?.index
            ?: row.minByOrNull { kotlin.math.abs(centre(it) - x) }?.index
    }

    /** The middle of a placed item, in columns. */
    fun centre(p: QuickPlaced): Float = p.column + p.span / 2f

    /** The slot under a point, in columns and rows; past the end, the last. */
    fun at(placed: List<QuickPlaced>, column: Float, row: Int): Int? {
        val inRow = placed.filter { it.row == row }
        if (inRow.isEmpty()) return if (row > (placed.maxOfOrNull { it.row } ?: -1)) placed.lastOrNull()?.index else null
        return inRow.firstOrNull { column >= it.column && column < it.column + it.span }?.index ?: inRow.last().index
    }
}

/** The second screen in three words: nothing, Fuse below (the menus above), or flipped. */
internal enum class SecondScreenChoice(val label: String, val detail: String) {
    OFF("Off", "Nothing there"),
    FUSE("Fuse", "Games below"),
    FLIPPED("Flipped", "Games above"),
}
