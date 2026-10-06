package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import kotlinx.coroutines.launch

/** What the editor does with a profile's PIN: leave it as it is, take it off, or set a new one. */
sealed interface PinChoice {
    data object Keep : PinChoice
    data object None : PinChoice
    data class Set(val digits: String) : PinChoice
}

/**
 * The parts of the editor the D-pad moves between, in the order they sit on screen: the name and
 * the PIN beside the large picture, the pictures below them, then the buttons at the bottom.
 */
internal enum class EditPart { NAME, PIN, PICTURES, BUTTONS }

/** Where the D-pad goes from [part], for the editor's layout ([columns] pictures a row, [count] in all). */
internal object EditorNav {
    /** The editor's position: which part, which picture, which button (0 Cancel, 1 the main one). */
    data class At(val part: EditPart, val picture: Int, val button: Int)

    fun move(at: At, action: NavAction, columns: Int, count: Int): At? = when (at.part) {
        EditPart.NAME -> when (action) {
            NavAction.DOWN -> at.copy(part = EditPart.PIN)
            else -> null
        }
        EditPart.PIN -> when (action) {
            NavAction.UP -> at.copy(part = EditPart.NAME)
            NavAction.DOWN -> at.copy(part = EditPart.PICTURES)
            else -> null
        }
        EditPart.PICTURES -> when (action) {
            NavAction.LEFT -> if (at.picture % columns > 0) at.copy(picture = at.picture - 1) else null
            NavAction.RIGHT -> if (at.picture % columns < columns - 1 && at.picture < count - 1) at.copy(picture = at.picture + 1) else null
            NavAction.UP -> if (at.picture >= columns) at.copy(picture = at.picture - columns) else at.copy(part = EditPart.PIN)
            NavAction.DOWN -> if (at.picture + columns < count) at.copy(picture = at.picture + columns) else at.copy(part = EditPart.BUTTONS, button = 1)
            else -> null
        }
        EditPart.BUTTONS -> when (action) {
            NavAction.LEFT -> if (at.button > 0) at.copy(button = 0) else null
            NavAction.RIGHT -> if (at.button < 1) at.copy(button = 1) else null
            NavAction.UP -> at.copy(part = EditPart.PICTURES)
            else -> null
        }
    }
}

/**
 * A profile's name, picture and PIN, for making one or changing one. Laid out so it always fits:
 * the buttons stay on screen at the bottom, and the parts above them scroll, the selection kept in
 * view. On short screens (a handheld's, the AYN Thor's two) the pictures are one row that scrolls
 * sideways. The D-pad moves in the order things sit on screen; every part can also be tapped.
 *
 * [onSubmit] gets the name, the picture's id and what to do with the PIN; it answers with an error
 * to show, or null when done.
 */
@Composable
internal fun ProfileEditor(
    app: AppState,
    title: String,
    subtitle: String?,
    submitLabel: String,
    submitIcon: ImageVector,
    initialName: String = "",
    initialAvatar: String? = null,
    hasPin: Boolean = false,
    priority: Int = LayerPriority.DIALOG + 2,
    onSubmit: suspend (name: String, avatar: String, pin: PinChoice) -> String?,
    onBack: () -> Unit,
) {
    val c = Fuse.colors
    val avatars = FuseAvatars.all
    var name by remember { mutableStateOf(initialName) }
    var picture by remember { mutableIntStateOf(avatars.indexOfFirst { it.id == initialAvatar }.takeIf { it >= 0 } ?: avatars.indices.random()) }
    var pin by remember { mutableStateOf<PinChoice>(if (hasPin) PinChoice.Keep else PinChoice.None) }
    var part by remember { mutableStateOf(EditPart.NAME) }
    var button by remember { mutableIntStateOf(1) }
    var working by remember { mutableStateOf(false) }

    fun askName() {
        app.textInput = TextInputSpec("Profile name", name, "Name", doneLabel = "Next") { v ->
            name = v.trim().take(24)
            if (name.isNotEmpty()) part = EditPart.PIN
        }
    }
    fun askPin() {
        // A PIN set (or kept) comes off with one press; none asks for one.
        if (pin != PinChoice.None) {
            pin = PinChoice.None
            return
        }
        app.textInput = TextInputSpec("A PIN for ${name.ifBlank { "this profile" }}", "", "4 to 8 digits", secret = true, capitalize = false, doneLabel = "Set PIN") { v ->
            val digits = v.filter { it.isDigit() }
            if (digits.length in 4..8) pin = PinChoice.Set(digits) else app.toasts.show("A PIN is 4 to 8 digits", ToastKind.WARNING)
        }
    }
    fun submit() {
        if (working) return
        if (name.isBlank()) {
            part = EditPart.NAME
            askName()
            return
        }
        working = true
        app.scope.launch {
            val problem = onSubmit(name.trim(), avatars[picture].id, pin)
            working = false
            problem?.let { app.toasts.show(it, ToastKind.ERROR) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 560.dp || maxWidth < 640.dp
        val short = maxHeight < 440.dp
        val columns = when {
            short -> avatars.size
            compact -> 8
            else -> 10
        }
        // While a keyboard is open it has the D-pad; the moment it closes, the editor has it again.
        InputLayer(priority = priority, enabled = app.textInput == null, modal = true) { e ->
            if (e.action == NavAction.BACK) {
                onBack()
                return@InputLayer NavResult.CONSUMED
            }
            if (e.action == NavAction.SELECT) {
                when (part) {
                    EditPart.NAME -> askName()
                    EditPart.PIN -> askPin()
                    EditPart.PICTURES -> { part = EditPart.BUTTONS; button = 1 }
                    EditPart.BUTTONS -> if (button == 0) onBack() else submit()
                }
                return@InputLayer NavResult.ACTIVATED
            }
            val next = EditorNav.move(EditorNav.At(part, picture, button), e.action, columns, avatars.size)
                ?: return@InputLayer if (e.action in DIRECTIONS) NavResult.BLOCKED else NavResult.CONSUMED
            part = next.part
            picture = next.picture
            button = next.button
            NavResult.MOVED
        }
        val partInView = remember { EditPart.entries.associateWith { BringIntoViewRequester() } }
        val pictureInView = remember(avatars.size) { List(avatars.size) { BringIntoViewRequester() } }
        LaunchedEffect(part, picture) {
            when (part) {
                EditPart.PICTURES -> pictureInView.getOrNull(picture)?.bringIntoView()
                // The name and PIN sit together beside the large picture.
                EditPart.PIN -> partInView.getValue(EditPart.NAME).bringIntoView()
                else -> partInView.getValue(part).bringIntoView()
            }
        }
        Column(Modifier.fillMaxSize().padding(vertical = if (short) Space.s else Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
            // Everything but the buttons: centred when it fits, scrolled when it doesn't.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FText(title, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1, modifier = Modifier.semantics { heading() })
                    if (subtitle != null && !short) {
                        Spacer(Modifier.height(Space.xs))
                        FText(subtitle, Fuse.type.body, color = c.textMuted, maxLines = 2, align = TextAlign.Center)
                    }
                    Spacer(Modifier.height(if (short) Space.s else if (compact) Space.m else Space.xl))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bringIntoViewRequester(partInView.getValue(EditPart.NAME))) {
                        ProfileAvatar(avatars[picture].id, if (short) 64.dp else if (compact) 72.dp else 104.dp)
                        Spacer(Modifier.width(if (short) Space.m else Space.l))
                        Column(Modifier.width(if (compact) 240.dp else 320.dp)) {
                            EditorField(
                                label = "Name", value = name.ifBlank { "Choose to type a name" }, filled = name.isNotBlank(),
                                icon = FuseIcons.Pencil, selected = part == EditPart.NAME, tag = "editor.name",
                            ) { part = EditPart.NAME; askName() }
                            Spacer(Modifier.height(Space.s))
                            EditorField(
                                label = "PIN",
                                value = when (val p = pin) {
                                    PinChoice.Keep -> "Set. Choose to remove"
                                    PinChoice.None -> "Not set"
                                    is PinChoice.Set -> "Set, ${p.digits.length} digits"
                                },
                                filled = pin != PinChoice.None,
                                icon = if (pin != PinChoice.None) FuseIcons.Lock else FuseIcons.LockOpen, selected = part == EditPart.PIN, tag = "editor.pin",
                            ) { part = EditPart.PIN; askPin() }
                        }
                    }
                    Spacer(Modifier.height(if (short) Space.s else if (compact) Space.m else Space.l))
                    val cell = if (short || compact) 40.dp else 44.dp
                    val pictures: @Composable (Int) -> Unit = { i ->
                        val chosen = i == picture
                        Box(
                            Modifier.bringIntoViewRequester(pictureInView[i]).testTag("editor.picture.$i").clip(CircleShape)
                                .clickable(remember { MutableInteractionSource() }, indication = null) { picture = i; part = EditPart.PICTURES },
                        ) {
                            ProfileAvatar(
                                avatars[i].id, cell,
                                ring = if (chosen && part == EditPart.PICTURES) c.focus else if (chosen) c.text else null,
                                dim = !chosen && part == EditPart.PICTURES,
                            )
                        }
                    }
                    if (short) {
                        // One row, sideways: the chosen picture is always the one in view.
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            for (i in avatars.indices) pictures(i)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            for (row in avatars.indices.chunked(columns)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { for (i in row) pictures(i) }
                            }
                        }
                    }
                }
            }
            // The buttons, always on screen.
            Spacer(Modifier.height(if (short) Space.s else Space.m))
            Row(
                Modifier.bringIntoViewRequester(partInView.getValue(EditPart.BUTTONS)).testTag("editor.buttons"),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                FuseButton(
                    "Cancel", selected = part == EditPart.BUTTONS && button == 0, onClick = onBack,
                    icon = FuseIcons.ArrowLeft, kind = ButtonKind.GHOST,
                )
                FuseButton(
                    submitLabel, selected = part == EditPart.BUTTONS && button == 1, onClick = { part = EditPart.BUTTONS; button = 1; submit() },
                    icon = submitIcon, kind = ButtonKind.PRIMARY, loading = working,
                )
            }
        }
    }
}

private val DIRECTIONS = setOf(NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT)

@Composable
private fun EditorField(label: String, value: String, filled: Boolean, icon: ImageVector, selected: Boolean, tag: String, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(if (selected) c.text else c.text.copy(alpha = 0.06f), Fuse.motion.tween(Durations.FAST), label = "field")
    val fg = if (selected) c.ink else c.text
    Row(
        Modifier.fillMaxWidth().testTag(tag).clip(RoundedCornerShape(Fuse.geometry.control)).background(bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FText(label, Fuse.type.caption, color = if (selected) fg.copy(alpha = 0.7f) else c.textMuted, maxLines = 1)
            FText(value, Fuse.type.bodyStrong, color = if (filled || selected) fg else c.textMuted, maxLines = 1)
        }
        FuseIcon(icon, size = Size.iconM, tint = fg.copy(alpha = 0.8f))
    }
}
