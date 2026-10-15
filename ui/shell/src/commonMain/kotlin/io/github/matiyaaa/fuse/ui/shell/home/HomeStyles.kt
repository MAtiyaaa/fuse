package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.HomeStyle
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.settings.ChoiceAid

internal fun HomeStyle.label(): String = when (this) {
    HomeStyle.FUSED -> "Fused"
    HomeStyle.NETWORK -> "Network"
}

/** What each style is. */
internal fun HomeStyle.detail(): String = when (this) {
    HomeStyle.FUSED -> "A board of tiles you arrange yourself"
    HomeStyle.NETWORK -> "A continuous dashboard of shelves under a big title"
}

/** Switches Home to [next]. */
internal fun AppState.applyHomeStyle(next: HomeStyle) {
    val before = store.prefs.value.home.style
    store.updatePrefs { it.copy(home = it.home.withStyle(next)) }
    if (next != before) toasts.show("Home is now ${next.label()}")
}

/**
 * Asks which Home style to use: each one says what it is, with a picture beside it. Choosing is the
 * answer, so a switch never happens without being asked; the one in use is checked and changes nothing.
 */
fun AppState.chooseHomeStyle() {
    val current = store.prefs.value.home.style
    val styles = HomeStyle.entries
    choice = ChoiceSpec(
        title = "Home style",
        message = "Your board's tiles stay as they are whichever you pick.",
        icon = FuseIcons.Dashboard,
        options = styles.map { s ->
            MenuAction(
                "homestyle.${s.name.lowercase()}", s.label(), null,
                detail = s.detail(),
                trailing = Trailing.Check(s == current),
                onSelect = {
                    choice = null
                    applyHomeStyle(s)
                },
            )
        },
        aids = styles.map { ChoiceAid.HomeStyle(it) },
    )
}

/** Uses the theme [spec], from the gallery. */
fun AppState.useTheme(spec: ThemeSpec) {
    store.updatePrefs { it.withTheme(spec) }
}
