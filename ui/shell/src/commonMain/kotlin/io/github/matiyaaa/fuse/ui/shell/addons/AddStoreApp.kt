package io.github.matiyaaa.fuse.ui.shell.addons

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps
import kotlinx.coroutines.launch

/**
 * Adds an app to the Store by its GitHub address: the address, then the shelf it goes on (Other
 * unless another is picked). Fuse checks that it really is an app this device can install (an APK
 * on Android, this computer's build elsewhere) before it joins.
 */
internal fun AppState.addStoreApp() {
    val ops = store.appStore
    val desktop = ops.state.value.desktop
    textInput = TextInputSpec(
        title = "Add an app",
        initial = "",
        placeholder = "github.com/owner/project",
        capitalize = false,
        doneLabel = "Next",
    ) { url ->
        if (url.isBlank()) return@TextInputSpec
        val shelves = (listOf(AppStoreOps.OTHER) + ops.state.value.catalogue?.categories.orEmpty().filterNot { it.trackOnly }.map { it.name }).distinct()
        choice = ChoiceSpec(
            title = "Which shelf?",
            message = "Where it goes in the Store. Fuse checks that its releases have ${if (desktop) "a build for this computer" else "an Android app"} first.",
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
}
