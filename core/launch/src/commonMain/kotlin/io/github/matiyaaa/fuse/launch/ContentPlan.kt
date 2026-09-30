package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.ContentSupport
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Game
import kotlinx.serialization.Serializable

/** What one emulator does with one kind of a game's child content, in a sentence for the user. */
@Serializable
data class ContentEntry(
    val kind: ContentKind,
    val support: ContentSupport,
    val items: List<ChildContent>,
    val message: String,
)

/** How an emulator handles a game's DLC, updates and other game-data children. Fuse never installs them itself. */
@Serializable
data class ContentPlan(val emulatorId: EmulatorId, val entries: List<ContentEntry>) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** Builds [ContentPlan]s. Never touches emulator configs. */
object ContentPlanner {
    fun plan(game: Game, adapter: EmulatorAdapter): ContentPlan {
        val entries = game.content
            .filter { it.kind.holdsGameData && it.kind != ContentKind.GAME }
            .groupBy { it.kind }
            .toList()
            .sortedBy { it.first.ordinal }
            .map { (kind, items) ->
                val support = supportFor(adapter, kind)
                ContentEntry(kind, support, items, message(adapter, kind, support, items.size))
            }
        return ContentPlan(adapter.id, entries)
    }

    private fun supportFor(adapter: EmulatorAdapter, kind: ContentKind): ContentSupport = when (kind) {
        ContentKind.DLC -> adapter.capabilities.dlc
        ContentKind.UPDATE -> adapter.capabilities.updates
        // Patches, hacks, mods, translations, demos and prototypes: no emulator takes them from Fuse.
        else -> ContentSupport.UNSUPPORTED
    }

    private fun noun(kind: ContentKind, count: Int): String {
        val one = count == 1
        return when (kind) {
            ContentKind.DLC -> "DLC"
            ContentKind.UPDATE -> if (one) "update" else "updates"
            ContentKind.PATCH -> if (one) "patch" else "patches"
            else -> if (one) kind.slug else kind.slug + "s"
        }
    }

    private fun message(adapter: EmulatorAdapter, kind: ContentKind, support: ContentSupport, count: Int): String {
        val name = adapter.name
        val what = noun(kind, count)
        return when (support) {
            ContentSupport.AUTOMATIC -> "$name finds the installed $what on its own."
            ContentSupport.LAUNCH_ARGUMENTS -> "Fuse passes the $what to $name when the game starts."
            ContentSupport.INSTALL_IN_EMULATOR -> {
                val hint = adapter.installHint(kind)?.let { " ($it)" }.orEmpty()
                "$name: install the $what from $name's menu$hint. Fuse shows where it is."
            }
            ContentSupport.UNSUPPORTED -> "$name has no known way to use the $what from outside. Fuse shows where it is."
            ContentSupport.NOT_APPLICABLE -> "$name does not use $what for this platform."
        }
    }
}
