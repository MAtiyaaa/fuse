package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.data.settings.Extension
import io.github.matiyaaa.fuse.data.settings.ExtensionScope
import io.github.matiyaaa.fuse.data.settings.Extensions
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/**
 * What [extension] may do on this device, as a group that opens in place where the extension is set
 * up: a switch for each thing it can ask Fuse for, and what no app is ever given.
 */
internal fun extensionRows(app: AppState, extension: Extension, prefs: UiPrefs): List<MenuAction> {
    val granted = Extensions.granted(prefs.extensions, extension)
    val left = extension.scopes.filter { it !in granted }
    return app.group(
        "ext.${extension.id}", "What ${extension.subject} may do", FuseIcons.ShieldCheck,
        summary = if (left.isEmpty()) "All it asks" else "${granted.size} of ${extension.scopes.size}",
        detail = if (left.isEmpty()) "Only what it asks Fuse for; never passwords, saves or files" else "Not: ${left.joinToString { it.title.replaceFirstChar(Char::lowercase) }}",
    ) {
        extension.scopes.map { scope ->
            toggleRow("ext.${extension.id}.${scope.name.lowercase()}", scope.title, scopeIcon(scope), scope in granted, scope.detail) { allowed ->
                app.store.updatePrefs { it.copy(extensions = Extensions.set(it.extensions, extension, scope, allowed)) }
            }
        } + infoRow("ext.${extension.id}.never", "Never given to any app", detail = Extensions.NEVER, icon = FuseIcons.Lock)
    }
}

private fun scopeIcon(scope: ExtensionScope): ImageVector = when (scope) {
    ExtensionScope.STATUS -> FuseIcons.Activity
    ExtensionScope.LIBRARY -> FuseIcons.Library
    ExtensionScope.PLAY -> FuseIcons.Play
    ExtensionScope.CHANGE_LIBRARY -> FuseIcons.SquarePen
    ExtensionScope.EMULATORS -> FuseIcons.Package
    ExtensionScope.CONTROL -> FuseIcons.Keyboard
    ExtensionScope.CAPTURES -> FuseIcons.Camera
}
