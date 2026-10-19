package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIn
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIns
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.launch

/**
 * The emulators here and RetroAchievements: who each is signed in as, signing them all in with the
 * account in use (the password asked once and kept nowhere), and signing out what Fuse signed in.
 */
internal fun AppState.emulatorSignInSheet() {
    contextMenu = null
    scope.launch {
        when (val s = store.achievements.emulatorSignIns()) {
            is EmulatorSignIns.Unavailable -> showProblem(Problem("Sign in from each emulator", s.reason, ProblemKind.ACCOUNT, Severity.INFO, reassurance = null))
            is EmulatorSignIns.Ready -> choice = ChoiceSpec(
                icon = FuseIcons.Trophy,
                title = "Emulators and RetroAchievements",
                message = when {
                    s.account == null -> "Connect your RetroAchievements account first. Then Fuse signs every emulator here in with it."
                    s.emulators.isEmpty() -> "No emulator here keeps a RetroAchievements sign-in Fuse can write: RetroArch, PCSX2, DuckStation, PPSSPP and Dolphin do."
                    else -> "Each emulator signs in to RetroAchievements with a token, never your password: Fuse gets it once and writes it where each emulator keeps its own. Unlocks then count as ${s.account}'s, in every emulator."
                },
                options = signInRows(s),
            )
        }
    }
}

private fun AppState.signInRows(s: EmulatorSignIns.Ready): List<MenuAction> = buildList {
    val account = s.account
    if (account != null && s.emulators.any { it.problem == null }) add(MenuAction(
        "signin", "Sign In as $account", FuseIcons.LogIn,
        detail = "Your RetroAchievements password, used once to sign in and kept nowhere",
        section = "RetroAchievements",
        onSelect = { askPassword(account) },
    ))
    for (e in s.emulators) add(MenuAction(
        "emu.${e.family}.${e.emulator}", e.emulator, FuseIcons.Chip,
        detail = describe(e),
        enabled = e.problem == null,
        unavailableReason = e.problem,
        section = "On This Device",
        onSelect = {},
    ))
    if (s.emulators.any { it.byFuse }) add(MenuAction(
        "signout", "Sign Them Out", FuseIcons.LogOut,
        detail = "Puts back what Fuse changed in each emulator; one signed in before is signed in as it was",
        section = "",
        onSelect = {
            choice = null
            confirm = ConfirmSpec("Sign the emulators out?", "Fuse takes out the sign-ins it wrote. An emulator signed in again from its own settings since stays as it is.", "Sign Out") {
                scope.launch {
                    if (store.achievements.signOutEmulators()) toasts.show("The emulators Fuse signed in are signed out", ToastKind.INFO, icon = FuseIcons.LogOut)
                    else toasts.show("Fuse couldn't sign them out. Close any game that's running, then try again.", ToastKind.WARNING)
                }
            }
        },
    ))
}

private fun describe(e: EmulatorSignIn): String = when {
    e.problem != null -> e.problem
    e.user == null -> "Not signed in"
    e.byFuse -> "Signed in as ${e.user}, by Fuse"
    else -> "Signed in as ${e.user}"
}

private fun AppState.askPassword(account: String) {
    choice = null
    textInput = TextInputSpec(
        "RetroAchievements password", "", secret = true, capitalize = false, doneLabel = "Sign In",
        message = "For $account. Sent once to RetroAchievements for a sign-in token, then forgotten",
    ) { password ->
        scope.launch {
            toasts.show("Signing in to RetroAchievements", ToastKind.INFO, icon = FuseIcons.Trophy)
            val r = store.achievements.signInEmulators(password)
            when {
                r.problem != null -> toasts.show(r.problem, ToastKind.WARNING, durationMs = 8_000L)
                r.signedIn.isNotEmpty() -> toasts.show(
                    "${r.signedIn.joinToString(", ")} signed in as $account" + if (r.failed.isEmpty()) "" else ". Not ${r.failed.joinToString(", ") { it.first }}",
                    if (r.failed.isEmpty()) ToastKind.SUCCESS else ToastKind.WARNING, icon = FuseIcons.CircleCheck, durationMs = 8_000L,
                )
                else -> toasts.show(r.failed.firstOrNull()?.let { "${it.first}: ${it.second}" } ?: "No emulator was signed in", ToastKind.WARNING, durationMs = 8_000L)
            }
            emulatorSignInSheet()
        }
    }
}
