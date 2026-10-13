package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.Tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.drawBrandMark
import io.github.matiyaaa.fuse.ui.shell.platform.DeckyState
import kotlinx.coroutines.launch

/**
 * Setup's Steam part, one journey however it is answered. First a single question (integrate Fuse
 * with Steam?), then, each optional on its own: the Fuse Decky plugin (Linux, where Decky runs) and
 * Fuse in Steam's library. When anything was set up, one short finish shows the parts joining and
 * says, after checking, what is really in place. Nothing restarts without the person agreeing:
 * Steam is only closed when they choose to (never while it runs a game), or Fuse waits for Steam to
 * close by itself; Decky reloads only itself, through the system's own password prompt.
 */
@Stable
internal class SteamFlow(val app: AppState) {
    val steam = app.platform.steam?.takeIf { app.platform.host != io.github.matiyaaa.fuse.model.Host.ANDROID }
    val decky = app.platform.decky

    var inSteam by mutableStateOf<Boolean?>(null)
    /** Steam is on this device; null until Fuse has looked. */
    var installed by mutableStateOf<Boolean?>(null)
    /** Someone signed in to Steam here once, so it has a library. */
    var hasUser by mutableStateOf(false)
    var steamOpen by mutableStateOf(false)
    var deckyState by mutableStateOf<DeckyState?>(null)
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var deckyError by mutableStateOf<String?>(null)
    var zip by mutableStateOf<String?>(null)
    var queued by mutableStateOf(false)
    var added by mutableStateOf(false)

    suspend fun look() {
        installed = steam?.let { runCatching { it.installed() }.getOrDefault(false) }
        hasUser = steam?.let { runCatching { it.hasUser() }.getOrDefault(false) } ?: false
        inSteam = steam?.let { runCatching { it.added() }.getOrDefault(false) }
        steamOpen = steam?.let { runCatching { it.steamRunning() }.getOrDefault(false) } ?: false
        queued = steam?.waitingForSteam == true
        deckyState = decky?.let { runCatching { it.state() }.getOrNull() }
    }
}

@Composable
internal fun rememberSteamFlow(app: AppState): SteamFlow {
    val flow = remember(app) { SteamFlow(app) }
    LaunchedEffect(flow) { flow.look() }
    return flow
}

/** The Steam steps for this device: none on Android, no Decky step off Linux. */
internal fun SteamFlow.steps(next: () -> Unit): List<Step> {
    val steam = steam ?: return emptyList()
    val prefs = app.store.prefs.value
    fun set(change: (io.github.matiyaaa.fuse.data.settings.SteamIntegrationSettings) -> io.github.matiyaaa.fuse.data.settings.SteamIntegrationSettings) =
        app.store.updatePrefs { it.copy(steam = change(it.steam)) }
    // Steam's steps only follow a yes on a device that has Steam: without it there is nothing to join.
    val here = installed == true
    val yes = prefs.steam.integrate == "YES" && here
    return buildList {
        add(Step(
            "steam.integrate", "Steam",
            if (installed == false) "Steam isn't on this device" else "Integrate Fuse with Steam?",
            if (installed == false) "Fuse and Steam can work as one, but Fuse didn't find Steam here. Install Steam and sign in once, then choose Look again. Or carry on: it waits in Settings, Library, Steam."
            else "Fuse and Steam work as one: Fuse starts from your Steam library, and Steam's games can live in Fuse too. Every part is optional, nothing is installed twice, and all of it can be changed later in Settings.",
            optional = true, icon = FuseIcons.Gamepad, chapter = Chapters.START,
            actions = if (here) listOf(
                StepAction("Yes, integrate with Steam", primary = true) { set { it.copy(integrate = "YES") }; next() },
                StepAction("Not now") { set { it.copy(integrate = "NO") }; next() },
            ) else listOf(
                // Not now is the one selected while Fuse looks, and stays so when Steam isn't here.
                StepAction("Not now", primary = true) { set { it.copy(integrate = "NO") }; next() },
                StepAction(if (installed == null) "Looking for Steam" else "Look again", enabled = installed != null) { app.scope.launch { look() } },
            ),
            content = { SteamBenefits(decky != null) },
        ))
        if (!yes) return@buildList
        decky?.let { d -> add(deckyStep(d, next, ::set)) }
        add(addStep(steam, next))
        if (prefs.steam.decky || prefs.steam.fuseInSteam || added || queued || inSteam == true) add(readyStep(next))
    }
}

private fun SteamFlow.deckyStep(
    d: io.github.matiyaaa.fuse.ui.shell.platform.DeckyIntegration,
    next: () -> Unit,
    set: ((io.github.matiyaaa.fuse.data.settings.SteamIntegrationSettings) -> io.github.matiyaaa.fuse.data.settings.SteamIntegrationSettings) -> Unit,
): Step {
    val st = deckyState
    fun install() {
        busy = "decky"
        deckyError = null
        app.scope.launch {
            d.install()
                .onSuccess {
                    set { it.copy(decky = true) }
                    deckyState = d.state()
                    // Installed and checked: on to the next part, rather than a page saying so.
                    if (deckyState?.plugin == true) next()
                }
                .onFailure { deckyError = it.message ?: "The plugin couldn't be installed. Nothing was changed." }
            busy = null
        }
    }
    fun guided() {
        app.scope.launch {
            zip = d.stage()
            if (zip == null) deckyError = "This copy of Fuse doesn't carry the plugin."
        }
    }
    val title = when {
        busy == "decky" -> "Installing the Fuse plugin"
        st == null -> "Looking for Decky"
        !st.loader -> "Decky Loader first"
        st.upToDate -> "The Fuse plugin is in Decky"
        st.plugin -> "Update the Fuse plugin?"
        else -> "Add the Fuse plugin to Decky?"
    }
    val body = when {
        busy == "decky" -> "Answer the system's password prompt to put it in Decky's folder. Decky reloads by itself; Steam and your games aren't touched."
        st != null && !st.loader -> "Decky Loader adds plugins to Steam's Gaming Mode. Install it from decky.xyz in Desktop Mode, then come back here. Fuse never installs it for you without asking."
        st?.upToDate == true -> "In Steam, Fuse's plugin is in the Quick Access menu, and each game's menu can add it to Fuse or open it there."
        else -> "Add or remove Steam games from their own menu, open them in Fuse, and see Fuse One's saves, devices and transfers, and your emulators' updates, from the Quick Access menu. Fuse asks the system for your password to put it in Decky's folder; nothing else changes."
    }
    val actions = when {
        busy == "decky" -> listOf(StepAction("Installing", enabled = false) {})
        st == null -> listOf(StepAction("Not now", run = next))
        !st.loader -> listOf(
            StepAction("Not now", primary = true) { set { it.copy(decky = false) }; next() },
            StepAction("Look again") { app.scope.launch { deckyState = d.state() } },
        )
        st.upToDate -> listOf(StepAction("Continue", primary = true) { set { it.copy(decky = true) }; next() })
        zip != null -> listOf(
            StepAction("It's installed", primary = true) {
                app.scope.launch {
                    deckyState = d.state()
                    if (deckyState?.plugin == true) { set { it.copy(decky = true) }; next() } else deckyError = "Decky doesn't show the plugin yet. Install it from the ZIP, then try again."
                }
            },
            StepAction("Not now") { set { it.copy(decky = false) }; next() },
        )
        else -> listOfNotNull(
            StepAction(if (st.plugin) "Update the plugin" else "Install the plugin", primary = true, enabled = st.canInstall, note = "This system can't ask for a password here") { install() },
            StepAction("Install it from Decky") { guided() },
            StepAction("Not now") { set { it.copy(decky = false) }; next() },
        )
    }
    return Step(
        "steam.decky", "Decky", title, body,
        optional = true, icon = FuseIcons.Puzzle, chapter = Chapters.START,
        footnote = deckyError ?: zip?.let { "In Decky: Settings, Developer, Install Plugin from ZIP, then choose $it" },
        actions = actions,
        content = { if (busy == "decky") Spinner() else DeckyPreview(st) },
    )
}

private fun SteamFlow.addStep(steam: io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration, next: () -> Unit): Step {
    val app = app
    fun done(r: Result<String>) {
        r.onSuccess {
            added = true
            app.store.updatePrefs { p -> p.copy(steam = p.steam.copy(fuseInSteam = true)) }
            // Done and checked: straight on to the parts joining, which says what is in place.
            next()
        }
            .onFailure {
                error = it.message ?: "Steam's library couldn't be updated. Nothing was lost."
                // Said where it can't be missed, not only in the line under the buttons.
                app.toasts.show(error!!, io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.ERROR)
            }
    }
    fun add() {
        busy = "steam"
        error = null
        app.scope.launch {
            look()
            when {
                !steamOpen -> done(steam.addFuse())
                else -> {
                    busy = null
                    // In Game Mode (or started from Steam) Fuse closes with Steam: a helper of Fuse's
                    // restarts Steam and opens Fuse again, here, where setup left off.
                    val withFuse = steam.closesFuse
                    app.confirm = ConfirmSpec(
                        if (withFuse) "Restart Steam and Fuse?" else "Close Steam for a moment?",
                        if (withFuse) "Steam writes its library when it quits, so Steam closes, Fuse adds itself, and Steam comes back. Fuse closes with it and opens again where setup left off. A game running in Steam is never closed. To wait for Steam to close by itself instead, cancel and choose Add when Steam closes."
                        else "Steam writes its library when it quits, so Fuse adds itself while Steam is closed, then opens Steam again. A game running in Steam is never closed. To wait for you to close Steam instead, cancel and choose Add when Steam closes.",
                        if (withFuse) "Restart Steam" else "Close and add",
                    ) {
                        busy = "steam"
                        app.scope.launch {
                            if (steam.gameRunning()) error = "A game is running in Steam. Fuse never closes it: add Fuse when Steam closes instead."
                            else steam.addForSetup().also { r -> if (withFuse) r.onSuccess { app.toasts.show(it) } }.let { done(it) }
                            busy = null
                            look()
                        }
                    }
                    return@launch
                }
            }
            busy = null
            look()
        }
    }
    fun wait() {
        app.scope.launch {
            done(steam.addWhenSteamCloses())
            queued = steam.waitingForSteam
        }
    }
    val inIt = inSteam == true || added
    // What keeps Fuse from being added here, said before anything is pressed.
    val blocked = when {
        inIt || queued || busy == "steam" -> null
        !hasUser -> "Sign in to Steam once, close it, then choose Check again. Fuse adds itself to the library of everyone signed in here."
        !steam.canBeAdded -> "This copy of Fuse isn't running from its AppImage or installed program, so Steam would have nothing to start. Add it later from Settings, Library, Steam."
        else -> null
    }
    return Step(
        "steam.add", "Fuse in Steam",
        when {
            busy == "steam" -> "Adding Fuse to Steam"
            inIt -> "Fuse is in your Steam library"
            queued -> "Fuse joins Steam when it closes"
            blocked != null -> "Fuse can't join Steam's library yet"
            else -> "Add Fuse to your Steam library?"
        },
        when {
            blocked != null -> blocked
            busy == "steam" -> "Fuse writes its entry with its capsule, banner, hero, logo and icon. Your other shortcuts and any art you chose stay as they are."
            inIt -> "Start Fuse from Steam and Gaming Mode, with its own art. Repair it from Settings, Library, Steam whenever you like."
            queued -> "Fuse doesn't close Steam for this. The next time Steam closes while Fuse is open, Fuse puts itself in Steam's library."
            steam.closesFuse && steamOpen -> "Fuse restarts Steam to add itself, with its capsule, banner, hero, logo and icon. Fuse closes with Steam and opens again right here. Or it waits until Steam closes by itself."
            else -> "Fuse appears in Steam with its own capsule, banner, hero, logo and icon, and starts full screen from Gaming Mode. Your other shortcuts and custom art stay exactly as they are."
        },
        optional = true, icon = FuseIcons.Gamepad, chapter = Chapters.START,
        footnote = error,
        actions = when {
            busy == "steam" -> listOf(StepAction("Adding", enabled = false) {})
            inIt || queued -> listOf(StepAction("Continue", primary = true, run = next))
            blocked != null -> listOf(
                StepAction("Continue", primary = true, run = next),
                StepAction("Check again") { app.scope.launch { look() } },
            )
            steamOpen -> listOf(
                StepAction("Yes, add Fuse to Steam", primary = true) { add() },
                StepAction("Add when Steam closes") { wait() },
                StepAction("No, keep Fuse separate", run = next),
            )
            else -> listOf(
                StepAction("Yes, add Fuse to Steam", primary = true) { add() },
                StepAction("No, keep Fuse separate", run = next),
            )
        },
        content = { if (busy == "steam") Spinner() else SteamArtPreview() },
    )
}

private fun SteamFlow.readyStep(next: () -> Unit): Step {
    val prefs = app.store.prefs.value
    val steamPart: Boolean? = when {
        inSteam == true || added -> true
        queued -> null
        else -> false
    }
    val deckyPart: Boolean? = when {
        decky == null || !prefs.steam.decky -> null
        deckyState?.plugin == true -> true
        else -> false
    }
    val rows = buildList {
        add(Triple(steamPart, "Steam", when (steamPart) { true -> "Fuse is in Steam's library"; null -> "Waiting for Steam to close"; false -> "Not added" }))
        if (decky != null) add(Triple(deckyPart, "Decky", when (deckyPart) { true -> "The Fuse plugin is installed"; null -> "Not used"; false -> "The plugin isn't in Decky yet" }))
        if (decky != null && prefs.steam.decky) add(Triple(true, "Bridge", "Fuse answers the plugin on this device only"))
    }
    return Step(
        "steam.ready", "Together", "Fuse and Steam, together",
        "Checked just now, not assumed. Change any of it later in Settings, Library, Steam.",
        icon = FuseIcons.Link, chapter = Chapters.START,
        actions = listOf(StepAction("Continue", primary = true, run = next), StepAction("Check again") { app.scope.launch { look() } }),
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SteamUnion(decky = decky != null && prefs.steam.decky, steam = steamPart != false)
                Spacer(Modifier.height(Space.l))
                StatusList(rows)
            }
        },
    )
}

/** What integrating brings, each with its icon, arriving one after another. */
@Composable
private fun SteamBenefits(decky: Boolean) {
    val c = Fuse.colors
    val items = buildList {
        add(FuseIcons.Gamepad to "Start Fuse from your Steam library and Gaming Mode")
        add(FuseIcons.Library to "Add Steam games to Fuse, without installing anything twice")
        add(FuseIcons.Images to "Fuse's own capsule, banner, hero, logo and icon in Steam")
        if (decky) {
            add(FuseIcons.Puzzle to "Add, open or remove games in Fuse from Steam's game menu")
            add(FuseIcons.RefreshCcw to "Fuse One's saves, devices and transfers in Quick Access")
            add(FuseIcons.Chip to "Update every emulator from Quick Access")
        }
    }
    val reveal = remember { FuselineValue(0f) }
    val motion = Fuse.motion
    LaunchedEffect(Unit) { reveal.animateTo(1f, Tween(if (motion.reduced) 1 else 900, curve = Curves.Enter)) }
    Panel(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.xl, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            items.forEachIndexed { i, (icon, words) ->
                val share = 1f / items.size
                Row(
                    Modifier.graphicsLayer {
                        val p = ((reveal.value - i * share * 0.6f) / 0.4f).coerceIn(0f, 1f)
                        alpha = p
                        translationX = (1f - p) * 16.dp.toPx()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(Size.touch * 0.75f).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        FuseIcon(icon, size = Size.iconM, tint = c.accent)
                    }
                    Spacer(Modifier.width(Space.m))
                    FText(words, Fuse.type.body, maxLines = 2)
                }
            }
        }
    }
}

/** Steam's Quick Access menu with Fuse's panel in it, drawn small: what the plugin adds. */
@Composable
private fun DeckyPreview(state: DeckyState?) {
    val c = Fuse.colors
    Panel(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(22.dp)) { drawBrandMark(Offset.Zero, size.width, c.text, c.accent) }
                Spacer(Modifier.width(Space.s))
                FText("Fuse", Fuse.type.bodyStrong, maxLines = 1)
                Spacer(Modifier.weight(1f))
                FText(state?.pluginVersion?.let { "Plugin $it" } ?: "Quick Access", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            for ((label, detail) in listOf("This game" to "Add to Fuse", "Fuse One" to "Synced everywhere", "Emulators" to "Update all")) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.text.copy(alpha = if (c.isDark) 0.06f else 0.04f)).padding(horizontal = Space.m, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FText(label, Fuse.type.label, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    FText(detail, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** How Fuse looks in Steam: a portrait capsule, the wide banner and the icon, in Fuse's own art. */
@Composable
private fun SteamArtPreview() {
    val c = Fuse.colors
    Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Bottom) {
        ArtCard(Modifier.weight(0.6f).aspectRatio(2f / 3f), "Capsule", markScale = 0.42f)
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            ArtCard(Modifier.fillMaxWidth().aspectRatio(92f / 43f), "Banner", markScale = 0.38f)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                ArtCard(Modifier.size(56.dp), null, markScale = 0.6f)
                Column {
                    FText("Fuse", Fuse.type.bodyStrong, maxLines = 1)
                    FText("Hero, logo and icon too", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ArtCard(modifier: Modifier, label: String?, markScale: Float) {
    val c = Fuse.colors
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF1B1E26), Color(0xFF0E1015)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension * markScale
            drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.35f), Color.Transparent), center = center, radius = size.minDimension * 0.7f), radius = size.minDimension * 0.7f)
            drawBrandMark(Offset(center.x - s / 2, center.y - s / 2), s, Color.White, c.accent)
        }
        if (label != null) FText(label, Fuse.type.overline, color = Color.White.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s))
    }
}

/**
 * The finish: Fuse, Steam and (when chosen) Decky as three lights that draw toward each other, link
 * and settle into one ring around Fuse's mark. About two seconds, played once; reduced motion shows
 * the joined picture at once.
 */
@Composable
private fun SteamUnion(decky: Boolean, steam: Boolean) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val t = remember { FuselineValue(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!motion.reduced) t.animateTo(1f, Tween(2_100, curve = Curves.Standard)) }
    val parts: List<Pair<ImageVector?, Color>> = buildList {
        add(null to c.accent)
        if (steam) add(FuseIcons.Gamepad to Color(0xFF66C0F4))
        if (decky) add(FuseIcons.Puzzle to Color(0xFFB98CFF))
    }
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            val gather = ((p - 0.25f) / 0.55f).coerceIn(0f, 1f)
            val ring = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
            val r = size.minDimension * 0.36f * (1f - 0.6f * gather)
            val n = parts.size
            val points = List(n) { i ->
                val a = (-90f + i * 360f / n) * (kotlin.math.PI.toFloat() / 180f) + p * 1.2f
                center + Offset(kotlin.math.cos(a) * r, kotlin.math.sin(a) * r)
            }
            // Links between the parts, brightening as they draw together.
            for (i in points.indices) for (j in i + 1 until points.size) {
                drawLine(c.accent.copy(alpha = 0.18f + 0.5f * gather), points[i], points[j], strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            for ((i, pt) in points.withIndex()) {
                val color = parts[i].second
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent), center = pt, radius = 34.dp.toPx()), radius = 34.dp.toPx(), center = pt)
                drawCircle(color, radius = 9.dp.toPx() * (1f - 0.5f * ring), center = pt)
            }
            // The one ring they become.
            if (ring > 0f) {
                drawCircle(c.accent.copy(alpha = ring), radius = size.minDimension * 0.42f, center = center, style = Stroke(width = 4.dp.toPx() * ring))
                val m = size.minDimension * 0.34f * ring
                drawBrandMark(Offset(center.x - m / 2, center.y - m / 2), m, c.text.copy(alpha = ring), c.accent)
            }
        }
    }
}
