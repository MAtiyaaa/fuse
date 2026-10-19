package io.github.matiyaaa.fuse.sync

/** A way a game's saves travel between devices. */
enum class SaveWay(val id: String, val label: String) {
    FUSE_ONE("fuse-one", "Fuse One"),
    ROMM("romm", "RomM"),
    SYNCTHING("syncthing", "Syncthing"),
}

/**
 * Which way moves a game's save: only ever one, so two never move the same save past each other
 * (Fuse One putting one save in place while RomM puts another). Of the ways that can move this
 * game's save here, the first in the person's order wins; Fuse One, then RomM, then Syncthing,
 * unless they put them in another order.
 */
object SaveOwners {
    val DEFAULT: List<SaveWay> = listOf(SaveWay.FUSE_ONE, SaveWay.ROMM, SaveWay.SYNCTHING)

    /** The person's order, by [SaveWay.id] (as Settings keeps it), with any way it leaves out after it in the usual order. */
    fun order(ids: List<String>): List<SaveWay> =
        (ids.mapNotNull { id -> SaveWay.entries.firstOrNull { it.id == id } } + DEFAULT).distinct()

    /** The way that moves the save, of those [available] for this game here; null when none is. */
    fun owner(available: Set<SaveWay>, order: List<String>): SaveWay? = order(order).firstOrNull { it in available }
}
