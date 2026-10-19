package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.PreviewAction
import io.github.matiyaaa.fuse.ui.shell.app.TextPreviewSpec
import io.github.matiyaaa.fuse.ui.shell.app.icon
import io.github.matiyaaa.fuse.ui.shell.app.showProblem
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.store.HealthIssue
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemAction
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.launch

/**
 * Everything System health found, the store's findings and the device's own (file access, Phone
 * Link), most serious first. Screens that show health read it from here so they all agree.
 */
@Composable
fun rememberHealthIssues(app: AppState): List<HealthIssue> {
    val report by app.store.health.report.collectAsState()
    val access by app.platform.storage.state.collectAsState()
    val link = app.phoneLink?.state?.collectAsState()?.value
    val displays by app.platform.displays.collectAsState()
    val device = buildList {
        if (access == StorageState.DENIED) {
            add(HealthIssue("device.access", Problem(
                title = "Fuse can't read your games",
                message = "Without file access Fuse can't scan your folders or hand games to emulators. Allow it, and your library fills in.",
                kind = ProblemKind.ACCESS,
                severity = Severity.BROKEN,
                reassurance = null,
                actions = listOf(ProblemAction.GrantAccess()),
            )))
        }
        // Game Mode drawing Fuse at the handheld's size and enlarging it to a TV: soft text and art,
        // and nothing in Fuse can undo it. Said here (with Health's badge), with Steam's own fix.
        displays.firstOrNull { it.isPrimary && it.outputWidthPx != null }?.let { s ->
            add(HealthIssue("device.enlarged", Problem(
                title = "Game Mode is enlarging Fuse",
                message = "Fuse is drawn at ${s.widthPx} by ${s.heightPx} and enlarged to this screen's ${s.outputWidthPx} by ${s.outputHeightPx}, which softens text and art. " +
                    "In Steam, open Fuse's Properties, set Game Resolution to Native, and start Fuse again.",
                kind = ProblemKind.DISPLAY,
                severity = Severity.ATTENTION,
                reassurance = null,
                actions = listOf(ProblemAction.OpenSettings("displays", "Open Display")),
            )))
        }
        link?.error?.let { error ->
            add(HealthIssue("device.phonelink", Problem(
                title = "Phone Link couldn't start",
                message = "Phones can't connect right now. Another app may be using its network port; restarting Fuse usually frees it.",
                kind = ProblemKind.NETWORK,
                reassurance = null,
                actions = listOf(ProblemAction.OpenSettings("accounts", "Phone Link")),
                details = error,
            )))
        }
    }
    return (device + report.issues).sortedByDescending { it.problem.severity }
}

/** Settings, System health: how the setup is, each finding (A opens it), checking again, power and heat, and the diagnostics report. */
@Composable
fun healthRows(app: AppState): List<MenuAction> {
    val report by app.store.health.report.collectAsState()
    val issues = rememberHealthIssues(app)
    // Opening the section looks again, game files included.
    LaunchedEffect(Unit) { app.store.health.check() }
    val attention = issues.count { it.problem.severity >= Severity.ATTENTION }
    val notes = issues.size - attention
    val drawing by app.platform.drawing.collectAsState()
    val power = powerRows(app)
    return buildList {
        add(MenuAction(
            "status",
            when {
                attention > 0 -> if (attention == 1) "1 thing needs attention" else "$attention things need attention"
                notes > 0 -> "Everything works"
                else -> "Everything looks good"
            },
            if (attention > 0) FuseIcons.BadgeAlert else FuseIcons.HeartPulse,
            detail = when {
                report.checking -> "Checking your games' files"
                report.checkedAt != null -> "Checked ${agoText(report.checkedAt!!)}. Library folders, drives, emulators, firmware, playlists and keys"
                else -> "Library folders, drives, emulators, firmware, playlists and keys"
            },
            trailing = Trailing.Value(if (report.checking) "Checking" else "Check again"),
            onSelect = {
                app.store.health.check()
                app.toasts.show("Checking everything again")
            },
        ))
        fun section(severity: Severity) = when (severity) {
            Severity.BROKEN -> "Needs fixing"
            Severity.ATTENTION -> "Needs attention"
            else -> "Good to know"
        }
        issues.forEach { issue ->
            add(MenuAction(
                "issue.${issue.id}", issue.problem.title, issue.problem.kind.icon(),
                detail = issue.problem.message,
                trailing = Trailing.Chevron,
                section = section(issue.problem.severity),
                onSelect = { app.showProblem(issue.problem) },
            ))
        }
        // Android doesn't say how it draws: there the section is the device's, not a computer's.
        val here = if (drawing != null) "This computer" else "This device"
        app.graphicsRow(drawing, section = here)?.let(::add)
        // Power and heat folds open in place, its facts read only while shown.
        addAll(app.group("health.power", "Power and heat", FuseIcons.BatteryMedium, summary = power.firstOrNull { it.id == "power.heat" }?.let { (it.trailing as? Trailing.Value)?.text }, detail = "The battery, how warm the device is, the screen's rate and what keeps it awake") { power }.map { it.copy(section = it.section ?: here) })
        add(MenuAction(
            "report", "Diagnostics report", FuseIcons.ClipboardList,
            detail = "For a bug report: versions, systems, emulators, drives and what went wrong. You see it before it's saved",
            trailing = Trailing.Chevron,
            section = "Diagnostics",
            onSelect = { app.showDiagnosticsReport() },
        ))
    }
}

/** Builds the diagnostics report and shows it to read before it is saved or copied. */
fun AppState.showDiagnosticsReport() {
    scope.launch {
        val device = listOf(
            "Device: ${platform.device.tier} tier, ${platform.device.cpuCores} cores, ${platform.device.totalRamMb} MB memory, ${platform.device.screenWidthPx} x ${platform.device.screenHeightPx}" + (platform.device.gpuRenderer?.let { ", $it" } ?: ""),
            "File access: ${platform.storage.state.value}",
            "Screens: ${platform.displays.value.size}",
            "Safe mode: ${safeMode?.reason ?: "off"}",
        ) + (platform.drawing.value?.let { d -> listOf("Graphics: ${d.name}") + d.details.map { (label, value) -> "Graphics, $label: $value" } } ?: emptyList())
        val text = store.health.diagnostics(device, crash = platform.lastCrashReport())
        val name = "fuse-diagnostics-${store.updates.currentVersion}.txt"
        textPreview = TextPreviewSpec(
            title = "Diagnostics report",
            message = "Check it before you share it. Personal folder names, keys and passwords are left out, and nothing is sent anywhere.",
            text = text,
            icon = FuseIcons.ClipboardList,
            actions = listOf(
                PreviewAction("Copy", FuseIcons.Copy) {
                    scope.launch {
                        val ok = platform.writeClipboardText(text)
                        toasts.show(if (ok) "Copied the report" else "Copying isn't possible here", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
                    }
                },
                PreviewAction("Save", FuseIcons.Save, primary = true) {
                    scope.launch {
                        val where = platform.saveFile(name, "text/plain", text.encodeToByteArray())
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

/**
 * Whether Fuse draws on the graphics card or on the processor, in one row, and everything known
 * about it (the driver, the display, the window against the screen, and when on the processor, why
 * and what to try) a press away. Null where the platform doesn't say (Android).
 */
fun AppState.graphicsRow(drawing: io.github.matiyaaa.fuse.ui.shell.platform.DrawingInfo?, section: String? = null): MenuAction? {
    drawing ?: return null
    val facts = drawing.details.joinToString("\n") { (label, value) -> "$label: $value" }.ifEmpty { drawing.name }
    return MenuAction(
        "graphics", "Graphics", if (drawing.gpu) FuseIcons.Monitor else FuseIcons.Chip,
        detail = if (drawing.gpu) drawing.name else "Fuse is drawing on the processor, so motion is kept light. Select to see why",
        trailing = Trailing.Value(if (drawing.gpu) "Graphics card" else "Processor"),
        section = section,
        onSelect = {
            textPreview = TextPreviewSpec(
                title = "Graphics",
                message = if (drawing.gpu) "Fuse is drawing on the graphics card." else "Fuse is drawing on the processor. Here is what it knows about why.",
                text = facts,
                icon = if (drawing.gpu) FuseIcons.Monitor else FuseIcons.Chip,
                actions = listOf(
                    PreviewAction("Copy", FuseIcons.Copy, primary = true) {
                        scope.launch {
                            val ok = platform.writeClipboardText(facts)
                            toasts.show(if (ok) "Copied" else "Copying isn't possible here", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
                        }
                    },
                ),
            )
        },
    )
}
