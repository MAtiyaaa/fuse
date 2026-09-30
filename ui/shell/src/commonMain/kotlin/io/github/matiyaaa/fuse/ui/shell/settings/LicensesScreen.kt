package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.res.Res
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import kotlinx.coroutines.launch

/** A licence text shipped inside the app (composeResources/files/licenses). */
private data class LicenceDoc(val file: String, val title: String, val covers: String)

private val docs = listOf(
    LicenceDoc("GPL-3.0.txt", "Fuse", "GNU General Public License 3.0 or later"),
    LicenceDoc("Apache-2.0.txt", "Libraries", "Apache License 2.0: Kotlin, Compose Multiplatform, AndroidX, Ktor, Coil, SQLDelight, kotlinx, Media3"),
    LicenceDoc("OFL-Sora.txt", "Sora typeface", "SIL Open Font License 1.1"),
    LicenceDoc("OFL-Manrope.txt", "Manrope typeface", "SIL Open Font License 1.1"),
    LicenceDoc("LICENSE-lucide.txt", "Lucide icons", "ISC License (and MIT for icons derived from Feather)"),
)

/** Every licence text Fuse ships, readable with the controller (L2/R2 page through the text). */
@Composable
fun LicensesScreen(app: AppState) {
    val sel = remember { LinearSelection() }
    var text by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val current = docs[sel.index.coerceIn(0, docs.lastIndex)]
    LaunchedEffect(current) {
        text = null
        scroll.scrollTo(0)
        text = runCatching { Res.readBytes("files/licenses/${current.file}").decodeToString() }
            .getOrElse { "This licence text could not be read." }
    }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.PAGE_PREV, "Scroll up"), Hint(HintButton.PAGE_NEXT, "Scroll down"), Hint(HintButton.BACK, "Back"))
    }
    val actions = docs.map { MenuAction(it.file, it.title, FuseIcons.File, detail = it.covers) }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.PAGE_DOWN -> {
                scope.launch { scroll.animateScrollTo(scroll.value + (scroll.viewportSize * 0.85f).toInt()) }
                NavResult.CONSUMED
            }
            NavAction.PAGE_UP -> {
                scope.launch { scroll.animateScrollTo(scroll.value - (scroll.viewportSize * 0.85f).toInt()) }
                NavResult.CONSUMED
            }
            else -> handleMenuAction(e, actions, sel)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        Spacer(Modifier.height(Size.hudHeight + Space.l))
        FText("Licences", Fuse.type.display, maxLines = 1)
        FText("Fuse is free software, and it is built on the work of others", Fuse.type.body, color = Fuse.colors.textMuted)
        Spacer(Modifier.height(Space.l))
        Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Panel(Modifier.width(380.dp).fillMaxHeight()) {
                MenuList(actions, sel, modifier = Modifier.padding(Space.s))
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                val body = text
                if (body == null) {
                    Box(Modifier.fillMaxSize().padding(Space.xl)) { Spinner() }
                } else {
                    FText(
                        body,
                        Fuse.type.caption,
                        color = Fuse.colors.textMuted,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(Space.l),
                    )
                }
            }
        }
    }
}
