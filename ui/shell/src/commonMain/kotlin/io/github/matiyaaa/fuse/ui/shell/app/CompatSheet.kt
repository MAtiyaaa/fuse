package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.Compat
import io.github.matiyaaa.fuse.ui.shell.store.CompatProblem
import io.github.matiyaaa.fuse.ui.shell.store.CompatVerdict
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How It Runs Here (Fuse 0.4.5, compatibility reports): where a game stands on this device, and the
 * person's say in it. Fuse shows what it saw (the sessions it started here) and never more: only
 * the person can make a game playable. Notes stay on this device; the report is theirs to share.
 */
fun AppState.compatSheet(card: GameCard) {
    contextMenu = null
    scope.launch {
        val c = store.compat.of(card.id).first()
        val note = c.note
        val seen = when (c.sessionsHere) {
            0 -> "Fuse hasn't started it on this device yet."
            1 -> "Fuse started it here once" + played(c.playedHereSeconds) + "."
            else -> "Fuse started it here ${c.sessionsHere} times" + played(c.playedHereSeconds) + "."
        }
        val said = "How it ran for you"
        choice = ChoiceSpec(
            icon = FuseIcons.Gauge,
            title = "How it runs here",
            message = "${card.title}: ${c.status.label.lowercase()}. $seen Only you can say a game plays well; Fuse never decides it from a game starting.",
            options = buildList {
                add(MenuAction(
                    "compat.well", CompatVerdict.PLAYS_WELL.label, FuseIcons.CircleCheck, section = said,
                    detail = "It starts, plays, sounds and saves as it should",
                    trailing = Trailing.Check(note?.verdict == CompatVerdict.PLAYS_WELL),
                    onSelect = { say(card, CompatVerdict.PLAYS_WELL) },
                ))
                add(MenuAction(
                    "compat.problems", CompatVerdict.WITH_PROBLEMS.label, FuseIcons.BadgeAlert, section = said,
                    detail = note?.problems?.takeIf { note.verdict == CompatVerdict.WITH_PROBLEMS && it.isNotEmpty() }
                        ?.joinToString(", ") { it.label } ?: "Then choose what went wrong",
                    trailing = Trailing.Check(note?.verdict == CompatVerdict.WITH_PROBLEMS),
                    onSelect = { problemsSheet(card, note?.problems.orEmpty()) },
                ))
                add(MenuAction(
                    "compat.broken", CompatVerdict.DOES_NOT_WORK.label, FuseIcons.CircleX, section = said,
                    detail = "It doesn't start here, or can't be played",
                    trailing = Trailing.Check(note?.verdict == CompatVerdict.DOES_NOT_WORK),
                    onSelect = { say(card, CompatVerdict.DOES_NOT_WORK) },
                ))
                if (note != null) {
                    add(MenuAction("compat.forget", "Forget What I Said", FuseIcons.Eraser, section = said, onSelect = { say(card, null) }))
                }
                add(MenuAction(
                    "compat.report", "Compatibility Report", FuseIcons.FileText, section = "Share",
                    detail = "Every game you've said something about, as text to copy or save",
                    trailing = Trailing.Chevron,
                    onSelect = { compatReport() },
                ))
            },
        )
    }
}

/** What went wrong: ticked as they're chosen, the list staying open, then Done keeps them. */
private fun AppState.problemsSheet(card: GameCard, chosen: Set<CompatProblem>) {
    choice = ChoiceSpec(
        icon = FuseIcons.BadgeAlert,
        title = "What went wrong?",
        message = "Choose all that did. ${card.title} is kept as playable with these problems, on this device.",
        options = CompatProblem.entries.map { p ->
            MenuAction(
                "compat.p.${p.name}", p.label, problemIcon(p),
                trailing = Trailing.Check(p in chosen),
                onSelect = { problemsSheet(card, if (p in chosen) chosen - p else chosen + p) },
            )
        } + MenuAction(
            "compat.done", "Done", FuseIcons.Check, section = "",
            detail = if (chosen.isEmpty()) "Choose at least one" else null,
            enabled = chosen.isNotEmpty(),
            onSelect = { say(card, CompatVerdict.WITH_PROBLEMS, chosen) },
        ),
        onBack = { compatSheet(card) },
    )
}

private fun problemIcon(p: CompatProblem) = when (p) {
    CompatProblem.GRAPHICS -> FuseIcons.Image
    CompatProblem.SOUND -> FuseIcons.Volume
    CompatProblem.CONTROLS -> FuseIcons.Gamepad
    CompatProblem.SPEED -> FuseIcons.Gauge
    CompatProblem.SAVES -> FuseIcons.Save
    CompatProblem.CRASHES -> FuseIcons.Warning
}

private fun AppState.say(card: GameCard, verdict: CompatVerdict?, problems: Set<CompatProblem> = emptySet()) {
    choice = null
    scope.launch {
        store.compat.set(card.id, verdict, problems)
        val status = Compat.of(store.compat.of(card.id).first().note, 0, 0).status
        toasts.show(if (verdict == null) "Forgot how ${card.title} runs here" else "${card.title}: ${status.label.lowercase()}", ToastKind.SUCCESS, icon = FuseIcons.Gauge)
    }
}

private fun AppState.compatReport() {
    choice = null
    scope.launch {
        val text = store.compat.report()
        textPreview = TextPreviewSpec(
            title = "Compatibility report",
            message = "How your games run on this device, as you said. It names games and systems, never folders or people.",
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
                        val where = platform.saveFile("fuse-compatibility.txt", "text/plain", text.encodeToByteArray())
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

/** ", 2 h 15 min in all" for time played, or nothing for none. */
private fun played(seconds: Long): String {
    if (seconds < 60) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return ", " + (if (h > 0) "$h h " else "") + (if (m > 0 || h == 0L) "$m min" else "").trim() + " in all"
}
