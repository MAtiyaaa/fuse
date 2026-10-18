package io.github.matiyaaa.fuse.ui.shell.addons

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps
import io.github.matiyaaa.fuse.ui.shell.store.CustomPreview
import kotlinx.coroutines.launch

/**
 * Adds an app to the Store by its address: the address, then the shelf it goes on (Other unless
 * another is picked). Fuse checks that it really is an app this device can install (an APK on
 * Android, this computer's build elsewhere) before it joins. On a computer the address can be a
 * Forgejo or Gitea server's too, and what would install (its newest release and the file) is shown
 * first, with the shelves to put it on.
 */
internal fun AppState.addStoreApp() {
    val ops = store.appStore
    val desktop = ops.state.value.desktop
    textInput = TextInputSpec(
        title = "Add an app",
        initial = "",
        placeholder = if (desktop) "github.com/owner/project, or a Forgejo or Gitea address" else "github.com/owner/project",
        capitalize = false,
        doneLabel = "Next",
    ) { url ->
        if (url.isBlank()) return@TextInputSpec
        if (!desktop) {
            choice = shelves(url, message = "Where it goes in the Store. Fuse checks that its releases have an Android app first.", title = "Which shelf?")
            return@TextInputSpec
        }
        toasts.show("Looking at ${url.trim().removePrefix("https://")}", icon = FuseIcons.Search)
        scope.launch {
            ops.previewCustom(url).fold(
                onSuccess = { p -> choice = shelves(p.url, message = previewText(p), title = "Add ${p.name}?") },
                onFailure = { toasts.show(it.message ?: "Fuse couldn't read that project's releases.", ToastKind.ERROR) },
            )
        }
    }
}

/** What adding [p] would bring, said before it is added. */
internal fun previewText(p: CustomPreview): String = buildString {
    // A line each: where from, what installs, the room it needs, then what happens next.
    appendLine("From ${p.source}.")
    append(p.version?.let { "Newest release ${it.removePrefix("v")}" } ?: "Newest release")
    p.publishedAt?.let { append(", ${dateText(it)}") }
    append(": ${p.file.name}")
    p.file.sizeBytes?.takeIf { it > 0 }?.let { append(", ${bytesText(it)}") }
    appendLine(".")
    p.room?.let { appendLine("$it.") }
    append("Choose its shelf to add it. Nothing is downloaded until you install it.")
}

private fun AppState.shelves(url: String, message: String, title: String): ChoiceSpec {
    val ops = store.appStore
    val shelves = (listOf(AppStoreOps.OTHER) + ops.state.value.catalogue?.categories.orEmpty().filterNot { it.trackOnly }.map { it.name }).distinct()
    return ChoiceSpec(
        title = title,
        message = message,
        icon = FuseIcons.Plus,
        options = shelves.map { shelf ->
            MenuAction("shelf.$shelf", shelf, if (shelf == AppStoreOps.OTHER) FuseIcons.Package else FuseIcons.Tag, onSelect = {
                choice = null
                toasts.show("Checking ${url.trim().removePrefix("https://")}", icon = FuseIcons.Search)
                scope.launch {
                    val why = ops.addCustom(url, shelf)
                    if (why == null) toasts.show("Added to $shelf", ToastKind.SUCCESS, icon = FuseIcons.Plus) else toasts.show(why, ToastKind.ERROR)
                }
            })
        },
    )
}
