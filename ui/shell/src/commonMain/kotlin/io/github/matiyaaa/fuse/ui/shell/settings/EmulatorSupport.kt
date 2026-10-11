package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.PreviewAction
import io.github.matiyaaa.fuse.ui.shell.app.TextPreviewSpec
import io.github.matiyaaa.fuse.ui.shell.app.showEmulator
import kotlinx.coroutines.launch

/**
 * Developer options, Emulator support: every emulator Fuse knows for this system, installed ones
 * first, each with its version, how Fuse knows to start it, the furthest step Fuse saw for itself
 * here and what Fuse does with it (games, saves, patches, cheats, settings). One opens its page;
 * the whole of it can be copied or saved as a report without folder or user names.
 */
fun AppState.showEmulatorSupport() {
    scope.launch {
        val rows = store.emulators.support()
        val installed = rows.count { it.installed }
        val report = MenuAction(
            "support.report", "Compatibility Report", FuseIcons.FileText,
            detail = "All of this as text to copy or save, without your folder or user names",
            trailing = Trailing.Chevron, section = "Share",
            onSelect = { showSupportReport() },
        )
        choice = ChoiceSpec(
            icon = FuseIcons.Chip,
            title = "Emulator support",
            message = "$installed installed, of ${rows.size} Fuse knows for this system. Checked means what Fuse saw for itself on this device, nothing more.",
            options = listOf(report) + rows.map { r ->
                MenuAction(
                    "support.${r.id.value}", r.name, FuseIcons.Joystick,
                    detail = listOfNotNull(
                        r.version?.let { "Version $it" },
                        r.checked,
                        r.features.joinToString(", ").replaceFirstChar { it.uppercase() },
                        "launch from ${r.evidence.lowercase()}",
                    ).joinToString("  ·  "),
                    trailing = Trailing.Value(if (r.systems == 1) "1 system" else "${r.systems} systems"),
                    section = if (r.installed) "Installed" else "Known, not installed",
                    onSelect = { choice = null; showEmulator(r.id) },
                )
            },
        )
    }
}

private fun AppState.showSupportReport() {
    choice = null
    scope.launch {
        val text = store.emulators.supportReport()
        textPreview = TextPreviewSpec(
            title = "Emulator support report",
            message = "Every emulator Fuse knows here and what it found, with your home folder and user names taken out. Check it before you share it.",
            text = text,
            icon = FuseIcons.FileText,
            actions = listOf(
                PreviewAction("Copy", FuseIcons.Copy) {
                    scope.launch {
                        val ok = platform.writeClipboardText(text)
                        toasts.show(if (ok) "Copied the report" else "Copying isn't possible here", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
                    }
                },
                PreviewAction("Save", FuseIcons.Save, primary = true) {
                    scope.launch {
                        val where = platform.saveFile("fuse-emulators-${store.updates.currentVersion}.txt", "text/plain", text.encodeToByteArray())
                        if (where != null) {
                            textPreview = null
                            toasts.show("Saved $where", ToastKind.SUCCESS)
                        }
                    }
                },
            ),
        )
    }
}
