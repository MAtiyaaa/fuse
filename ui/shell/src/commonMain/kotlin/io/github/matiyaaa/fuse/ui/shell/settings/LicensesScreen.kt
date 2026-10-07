package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuHeader
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.res.Res
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
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
    LicenceDoc("Apache-2.0.txt", "Libraries", "Apache License 2.0: Kotlin, Compose Multiplatform, AndroidX, Ktor, Coil, SQLDelight, kotlinx, Media3, Typesafe Config, libGDX Jamepad"),
    LicenceDoc("OFL-Sora.txt", "Sora typeface", "SIL Open Font License 1.1"),
    LicenceDoc("OFL-Manrope.txt", "Manrope typeface", "SIL Open Font License 1.1"),
    LicenceDoc("LICENSE-lucide.txt", "Lucide icons", "ISC License (and MIT for icons derived from Feather)"),
    LicenceDoc("LICENSE-qrcodegen.txt", "QR codes", "MIT License: Project Nayuki's QR Code generator library, for Phone Link pairing"),
    LicenceDoc("LICENSE-slf4j.txt", "SLF4J", "MIT License: the logging interface Phone Link's web server (Ktor) is built on"),
    LicenceDoc("LGPL-2.1.txt", "JLayer", "GNU Lesser General Public License 2.1 or later: the MP3 decoder for menu music in the Linux app"),
    LicenceDoc("LICENSE-sdl.txt", "SDL", "zlib License: SDL 2, which reads controllers in the Windows and macOS apps (through libGDX Jamepad)"),
    LicenceDoc(
        "LICENSE-cartridge.txt", "Cartridge",
        "MIT License: Cartridge by abdu2304 (github.com/abdu2304/cartridge), the RomM companion Fuse pairs with, and the emulator family matching Fuse learned from it",
    ),
    LicenceDoc(
        "music", "Music", "${BundledMusic.ALBUM} and ${BundledMusic.ALBUM_TWO} by ${BundledMusic.ARTIST}. The songs Fuse plays under its menus",
        inline = "${BundledMusic.CREDIT}.\n\n" +
            "Fuse plays these songs under its menus: puddleworld by default and alright apothecary during " +
            "first-time setup. Pick another one, shuffle them all, or play a song of your own, in Settings, " +
            "Sound.\n\n" +
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
    LicenceDoc(
        "systematic", "System pictures", "RetroArch's Systematic icons, CC BY 4.0. Downloaded when used, not part of Fuse",
        inline = io.github.matiyaaa.fuse.integrations.systemart.SystemIcons.ATTRIBUTION + "\n\n" +
            "Source: ${io.github.matiyaaa.fuse.integrations.systemart.SystemIcons.REPO_URL}\n" +
            "Licence: ${io.github.matiyaaa.fuse.integrations.systemart.SystemIcons.LICENSE} (${io.github.matiyaaa.fuse.integrations.systemart.SystemIcons.LICENSE_URL}). " +
            "You may share and adapt these pictures for any purpose with credit to the libretro team.\n\n" +
            "Fuse never bundles these pictures. A system shown as a small tile on the Systems page has its " +
            "picture downloaded from the repository and kept on your device. Console names and designs are " +
            "trademarks of their owners and are shown only to identify each system.",
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
    val actions = docs.map { MenuAction(it.file, it.title, if (it.inline != null) FuseIcons.FileText else FuseIcons.File, detail = it.covers) }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.PAGE_DOWN -> {
                scope.launch { scroll.fuselineScrollTo(scroll.value + (scroll.viewportSize * 0.85f).toInt()) }
                NavResult.CONSUMED
            }
            NavAction.PAGE_UP -> {
                scope.launch { scroll.fuselineScrollTo(scroll.value - (scroll.viewportSize * 0.85f).toInt()) }
                NavResult.CONSUMED
            }
            else -> handleMenuAction(e, actions, sel)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW_BELOW
        val short = maxHeight < SHORT_BELOW
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) Space.gutterCompact else Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            SettingsPageHeading("Licences", "Fuse is free software, and it is built on the work of others", short, Modifier.reveal(0))
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            val list = @Composable { m: Modifier ->
                Panel(m) {
                    MenuList(actions, sel, modifier = Modifier.padding(Space.s), fadeEdges = true)
                }
            }
            val reader = @Composable { m: Modifier ->
                Panel(m) {
                    Column(Modifier.fillMaxSize()) {
                        // What is open, above its text, so the reader never loses track of it.
                        MenuHeader(
                            current.title, subtitle = current.covers, icon = FuseIcons.FileText,
                            modifier = Modifier.padding(start = Space.s, end = Space.s, top = Space.l),
                        )
                        val body = text
                        if (body == null) {
                            Column(Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                                repeat(SKELETON_PARAGRAPHS) { SkeletonText(lines = 4, style = Fuse.type.caption, lastLineFraction = 0.45f) }
                            }
                        } else {
                            FText(
                                body,
                                Fuse.type.caption,
                                color = Fuse.colors.textMuted,
                                modifier = Modifier.fillMaxSize().fadingEdges(scroll, top = Space.l, bottom = Space.xl).verticalScroll(scroll)
                                    .padding(horizontal = Space.xl, vertical = Space.l),
                            )
                        }
                    }
                }
            }
            val bottom = Modifier.padding(bottom = Size.hintHeight + Space.s)
            if (narrow) {
                // One above the other: the list short, the text taking the rest.
                Column(Modifier.weight(1f).then(bottom), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    list(Modifier.fillMaxWidth().weight(0.38f).reveal(1))
                    reader(Modifier.fillMaxWidth().weight(0.62f).reveal(2))
                }
            } else {
                Row(Modifier.weight(1f).then(bottom), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    list(Modifier.width(LIST_WIDTH).fillMaxHeight().reveal(1))
                    reader(Modifier.weight(1f).fillMaxHeight().reveal(2))
                }
            }
        }
    }
}

private val NARROW_BELOW = 640.dp
private val SHORT_BELOW = 560.dp

/** The list of licences beside the text; placeholder paragraphs while a text loads. */
private val LIST_WIDTH = 360.dp
private const val SKELETON_PARAGRAPHS = 4
