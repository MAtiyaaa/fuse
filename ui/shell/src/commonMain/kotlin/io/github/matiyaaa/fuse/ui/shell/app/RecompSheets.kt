package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.launch.ports.PortAdapter
import io.github.matiyaaa.fuse.launch.ports.Recomps
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.RecompPlace
import kotlinx.coroutines.launch

/**
 * Whether [card] plays with a recomp here: one chosen in Play With (Fuse's copy or the person's
 * own), or the entry of Fuse's own copy of one. Read without waiting, for the game's options.
 */
internal fun AppState.recompPlays(card: GameCard): Boolean {
    val with = store.ports.playWith(card.id)?.removePrefix("yours:")
    if (with != null && Recomps.byId(with) != null) return true
    if (card.platformId != PortAdapter.PLATFORM) return false
    val name = TitleText.normalize(card.title)
    return Recomps.all.any { TitleText.normalize(it.game) == name }
}

/**
 * A recomp's own extras for the game it plays, each in the recomp's own files: its mods and
 * texture packs, its graphics settings, controls profiles Fuse keeps to swap between, and updates.
 */
fun AppState.recompSheet(card: GameCard) {
    contextMenu = null
    scope.launch {
        val place = store.ports.recompFor(card.id)
        if (place == null) {
            toasts.show("${card.title} doesn't play with a recomp on this computer", ToastKind.WARNING)
            return@launch
        }
        val r = place.recomp
        val mods = store.ports.recompMods(place)
        val profiles = store.ports.recompProfiles(place)
        val whose = place.yours?.let { "Your ${it.how}" } ?: "Fuse's copy"
        choice = ChoiceSpec(
            icon = FuseIcons.Boxes,
            title = r.name,
            message = "$whose of ${r.name}${place.version?.let { ", version $it" }.orEmpty()}. Changes go in its own files and apply the next time it starts. " +
                "Its saves are kept in step with every device that plays ${r.game} with ${r.name}.",
            options = listOf(
                MenuAction(
                    "mods", "Mods and Texture Packs", FuseIcons.Puzzle,
                    detail = if (mods.isEmpty()) "None yet. Add .nrm mods and .rtz texture packs" else "${mods.size} in its mods folder",
                    trailing = Trailing.Chevron, onSelect = { recompModsSheet(card, place) },
                ),
                MenuAction(
                    "graphics", "Graphics", FuseIcons.Monitor,
                    detail = "Resolution, aspect ratio, antialiasing and frame rate",
                    trailing = Trailing.Chevron, onSelect = { recompGraphicsSheet(card, place) },
                ),
                MenuAction(
                    "controls", "Controls Profiles", FuseIcons.Gamepad,
                    detail = if (profiles.isEmpty()) "Keep its controls as they are to swap back to" else "${profiles.size} kept",
                    trailing = Trailing.Chevron, onSelect = { recompControlsSheet(card, place) },
                ),
                MenuAction(
                    "updates", "Check for Updates", FuseIcons.Download,
                    detail = "Asks ${r.name}'s project for its newest release",
                    onSelect = { recompUpdates(card, place) },
                ),
            ),
            onBack = { choice = null },
        )
    }
}

private fun AppState.recompModsSheet(card: GameCard, place: RecompPlace) {
    scope.launch {
        val r = place.recomp
        val mods = store.ports.recompMods(place)
        choice = ChoiceSpec(
            icon = FuseIcons.Puzzle,
            title = "${r.name} Mods",
            message = "Mods are .nrm files and texture packs .rtz files, in its own mods folder. A new one is turned on if its maker says so; turn mods on and off in its Mods menu.",
            options = listOf(
                MenuAction("add", "Add a Mod", FuseIcons.Plus, detail = "A .nrm mod, an .rtz texture pack, or a zip with them inside", onSelect = {
                    choice = null
                    scope.launch {
                        val file = platform.storage.pickFile("Add a mod to ${r.name}", "Mods and texture packs", listOf("nrm", "rtz", "zip"))
                        if (file == null) { recompModsSheet(card, place); return@launch }
                        val problem = store.ports.addRecompMod(place, file)
                        if (problem == null) toasts.show("Added to ${r.name}. It shows in its Mods menu next time it starts", ToastKind.SUCCESS, icon = FuseIcons.Puzzle)
                        else toasts.show(problem, ToastKind.WARNING)
                        recompModsSheet(card, place)
                    }
                }),
            ) + listOfNotNull(
                r.modSite?.let { site ->
                    MenuAction("find", "Find Mods", FuseIcons.Globe, detail = "Its mods page. Download a mod's file, then add it here", onSelect = { platform.openUrl(site) })
                },
            ) + mods.map { m ->
                MenuAction(
                    "mod.${m.name}", m.name.substringBeforeLast('.'), if (m.texturePack) FuseIcons.Image else FuseIcons.Puzzle,
                    detail = "${if (m.texturePack) "Texture pack" else "Mod"}  ·  ${sizeText(m.size)}",
                    section = "In its mods folder",
                    onSelect = {
                        choice = ChoiceSpec(
                            icon = FuseIcons.Trash,
                            title = "Take Out ${m.name.substringBeforeLast('.')}?",
                            message = "It comes out of ${r.name}'s mods folder. Your saves stay as they are.",
                            options = listOf(
                                MenuAction("remove", "Take It Out", FuseIcons.Trash, destructive = true, onSelect = {
                                    scope.launch {
                                        if (!store.ports.removeRecompMod(place, m.name)) toasts.show("Fuse couldn't take it out", ToastKind.WARNING)
                                        recompModsSheet(card, place)
                                    }
                                }),
                                MenuAction("keep", "Keep It", FuseIcons.Check, onSelect = { recompModsSheet(card, place) }),
                            ),
                            onBack = { recompModsSheet(card, place) },
                        )
                    },
                )
            },
            onBack = { recompSheet(card) },
        )
    }
}

private fun AppState.recompGraphicsSheet(card: GameCard, place: RecompPlace) {
    scope.launch {
        val r = place.recomp
        val settings = store.ports.recompGraphics(place)
        choice = ChoiceSpec(
            icon = FuseIcons.Monitor,
            title = "${r.name} Graphics",
            message = "As its own Graphics menu has them, in its graphics file. They apply the next time it starts.",
            options = settings.map { (s, now) ->
                MenuAction(
                    "g.${s.key}", s.label, FuseIcons.Sliders,
                    trailing = Trailing.Value(now?.label ?: "Its default"),
                    onSelect = {
                        choice = ChoiceSpec(
                            icon = FuseIcons.Sliders,
                            title = s.label,
                            options = s.choices.map { c ->
                                MenuAction("v.${c.value.content}", c.label, trailing = Trailing.Check(c == now), onSelect = {
                                    scope.launch {
                                        if (!store.ports.setRecompGraphic(place, s.key, c)) toasts.show("Fuse couldn't change ${r.name}'s graphics file", ToastKind.WARNING)
                                        recompGraphicsSheet(card, place)
                                    }
                                })
                            },
                            onBack = { recompGraphicsSheet(card, place) },
                        )
                    },
                )
            },
            onBack = { recompSheet(card) },
        )
    }
}

private fun AppState.recompControlsSheet(card: GameCard, place: RecompPlace) {
    scope.launch {
        val r = place.recomp
        val profiles = store.ports.recompProfiles(place)
        choice = ChoiceSpec(
            icon = FuseIcons.Gamepad,
            title = "${r.name} Controls",
            message = "Keep its controls as they are now as a profile, then swap between profiles here: one for a controller, another for a handheld. They apply the next time it starts.",
            options = listOf(
                MenuAction("keep", "Keep These Controls", FuseIcons.Save, detail = "As they are in ${r.name} now", onSelect = {
                    textInput = TextInputSpec("Profile name", "", placeholder = "Handheld", doneLabel = "Keep", message = "A name for ${r.name}'s controls as they are now", onCancel = { recompControlsSheet(card, place) }) { name ->
                        scope.launch {
                            val problem = store.ports.saveRecompProfile(place, name)
                            if (problem == null) toasts.show("Kept as ${name.trim()}", ToastKind.SUCCESS, icon = FuseIcons.Gamepad) else toasts.show(problem, ToastKind.WARNING)
                            recompControlsSheet(card, place)
                        }
                    }
                }),
            ) + profiles.map { name ->
                MenuAction(
                    "p.$name", name, FuseIcons.Gamepad, section = "Profiles", trailing = Trailing.Chevron,
                    onSelect = {
                        choice = ChoiceSpec(
                            icon = FuseIcons.Gamepad,
                            title = name,
                            options = listOf(
                                MenuAction("use", "Use These Controls", FuseIcons.Check, detail = "In place of the ones ${r.name} has now", onSelect = {
                                    scope.launch {
                                        val problem = store.ports.useRecompProfile(place, name)
                                        if (problem == null) toasts.show("${r.name} uses $name next time it starts", ToastKind.SUCCESS, icon = FuseIcons.Gamepad) else toasts.show(problem, ToastKind.WARNING)
                                        recompControlsSheet(card, place)
                                    }
                                }),
                                MenuAction("forget", "Forget This Profile", FuseIcons.Trash, destructive = true, onSelect = {
                                    scope.launch {
                                        store.ports.removeRecompProfile(place, name)
                                        recompControlsSheet(card, place)
                                    }
                                }),
                            ),
                            onBack = { recompControlsSheet(card, place) },
                        )
                    },
                )
            },
            onBack = { recompSheet(card) },
        )
    }
}

private fun AppState.recompUpdates(card: GameCard, place: RecompPlace) {
    choice = null
    scope.launch {
        val r = place.recomp
        val release = store.ports.recompRelease(place)
        if (release == null) {
            toasts.show("Couldn't ask ${r.name}'s project. Check this computer is online", ToastKind.WARNING)
            return@launch
        }
        val flatpak = place.yours?.how == "Flatpak"
        choice = ChoiceSpec(
            icon = FuseIcons.Download,
            title = when (release.newer) {
                true -> "${r.name} ${release.tag} Is Out"
                false -> "${r.name} Is Up to Date"
                null -> "${r.name}'s Newest Is ${release.tag}"
            },
            message = when {
                release.newer == false -> "You have ${place.version}, its newest release."
                place.yours == null -> "Fuse's copy updates in Addons, Ports once the ports list carries the new version."
                flatpak -> "Your Flatpak updates through Flatpak, as your software centre would."
                release.newer == null -> "Fuse can't tell which version your ${place.yours.how} is. Its releases page has the newest."
                else -> "You have ${place.version}. Get the new ${place.yours.how.lowercase()} from its releases page; your saves, mods and settings stay in its folder."
            },
            options = listOfNotNull(
                if (flatpak && release.newer != false) MenuAction("update", "Update With Flatpak", FuseIcons.Download, onSelect = {
                    choice = null
                    scope.launch {
                        toasts.show("Updating ${r.name}", icon = FuseIcons.Download)
                        val problem = store.ports.updateYourRecomp(place)
                        if (problem == null) toasts.show("${r.name} is up to date", ToastKind.SUCCESS, icon = FuseIcons.Download) else toasts.show(problem, ToastKind.WARNING)
                    }
                }) else null,
                MenuAction("page", "Open Its Releases Page", FuseIcons.Globe, onSelect = { platform.openUrl(release.url) }),
            ),
            onBack = { recompSheet(card) },
        )
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${(bytes / 100_000_000) / 10.0} GB"
    bytes >= 1_000_000 -> "${(bytes / 100_000) / 10.0} MB"
    else -> "${maxOf(1, bytes / 1000)} KB"
}
