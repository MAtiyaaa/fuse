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
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtPack
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
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import kotlinx.coroutines.launch

/**
 * A licence text shipped inside the app (composeResources/files/licenses), or [inline] text for
 * material Fuse downloads rather than ships.
 */
private data class LicenceDoc(val file: String, val title: String, val covers: String, val inline: String? = null)

private val docs = listOf(
    LicenceDoc("GPL-3.0.txt", "Fuse", "GNU General Public License 3.0 or later"),
    LicenceDoc("Apache-2.0.txt", "Libraries", "Apache License 2.0: Kotlin, Compose Multiplatform, AndroidX, Ktor, Coil, SQLDelight, kotlinx, Media3, Typesafe Config"),
    LicenceDoc("OFL-Sora.txt", "Sora typeface", "SIL Open Font License 1.1"),
    LicenceDoc("OFL-Manrope.txt", "Manrope typeface", "SIL Open Font License 1.1"),
    LicenceDoc("LICENSE-lucide.txt", "Lucide icons", "ISC License (and MIT for icons derived from Feather)"),
    LicenceDoc("LICENSE-qrcodegen.txt", "QR codes", "MIT License: Project Nayuki's QR Code generator library, for Phone Link pairing"),
    LicenceDoc("LICENSE-slf4j.txt", "SLF4J", "MIT License: the logging interface Phone Link's web server (Ktor) is built on"),
    LicenceDoc("LGPL-2.1.txt", "JLayer", "GNU Lesser General Public License 2.1 or later: the MP3 decoder for menu music in the Linux app"),
    LicenceDoc(
        "LICENSE-cartridge.txt", "Cartridge",
        "MIT License: Cartridge by abdu2304 (github.com/abdu2304/cartridge), the RomM companion Fuse pairs with, and the emulator family matching Fuse learned from it",
    ),
    LicenceDoc(
        "music", "Music", "jam channel by ${BundledMusic.ARTIST}. The songs Fuse plays under its menus",
        inline = "${BundledMusic.CREDIT}.\n\n" +
            "Fuse plays these songs under its menus: puddleworld by default and alright apothecary during " +
            "first-time setup. Pick another one, or a song of your own, in Settings, Sound.\n\n" +
            "The songs are the work of ${BundledMusic.ARTIST} and are not covered by Fuse's licence. They ship " +
            "with Fuse so it has music out of the box; all rights stay with the artist.",
    ),
    LicenceDoc(
        "art-book-next", "System art", "Art Book Next, CC BY-NC-SA 2.0. Downloaded when used, not part of Fuse",
        inline = SystemArtPack.ATTRIBUTION + "\n\n" +
            "Source: ${SystemArtPack.REPO_URL}\n" +
            "Licence: ${SystemArtPack.LICENSE} (${SystemArtPack.LICENSE_URL}). You may share and adapt this art " +
            "for non-commercial purposes with credit to the authors above, under the same licence.\n\n" +
            "Fuse never bundles this art. When you turn on system art, Fuse downloads the logos, artwork and " +
            "colours for your systems from the pack's repository and keeps them on your device. Console names " +
            "and logos are trademarks of their owners and are shown only to identify each system.",
    ),
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
        text = current.inline ?: runCatching { Res.readBytes("files/licenses/${current.file}").decodeToString() }
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
