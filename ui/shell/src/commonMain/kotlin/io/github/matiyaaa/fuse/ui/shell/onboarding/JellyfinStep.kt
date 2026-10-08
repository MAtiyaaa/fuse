package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import kotlinx.coroutines.launch

/**
 * Setup's Jellyfin step, from the button: looks for servers on this network and offers them (or
 * typing an address), tests the address, then asks for the user name and password and signs in.
 * A home address is kept as the home address, anything else as the address from outside. The
 * password goes to the server and nowhere else.
 */
internal fun connectJellyfin(app: AppState, onDone: () -> Unit) {
    val service = app.store.jellyfin
    if (service == null) {
        app.store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = true)) }
        onDone()
        return
    }
    app.store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = true)) }
    app.toasts.show("Looking for Jellyfin on this network")
    app.scope.launch {
        val found = runCatching { service.discover() }.getOrDefault(emptyList())
        app.choice = ChoiceSpec(
            title = "Your Jellyfin server",
            icon = FuseIcons.Server,
            message = if (found.isEmpty()) "None answered on this network. Type its address, like 192.168.1.20:8096 or jellyfin.example.com." else "Found on this network",
            options = found.map { s ->
                MenuAction("found.${s.address}", s.name, FuseIcons.Server, detail = s.address, onSelect = {
                    app.choice = null
                    useAddress(app, s.address, onDone)
                })
            } + MenuAction("type", "Type an address", FuseIcons.Keyboard, onSelect = {
                app.choice = null
                app.textInput = TextInputSpec("Server address", "", "192.168.1.20:8096", capitalize = false, doneLabel = "Next") { typed ->
                    if (typed.isNotBlank()) useAddress(app, typed.trim(), onDone)
                }
            }),
        )
    }
}

/** Tests [address], keeps the form of it that answered, then signs in. */
private fun useAddress(app: AppState, address: String, onDone: () -> Unit) {
    val service = app.store.jellyfin ?: return
    app.scope.launch {
        val tested = service.test(address)
        val base = tested.getOrElse {
            app.toasts.show("Nothing answered at $address. ${it.message ?: ""}".trim())
            return@launch
        }.first
        val home = looksAtHome(base)
        app.store.updatePrefs { p ->
            p.copy(jellyfin = p.jellyfin.copy(enabled = true, localAddress = if (home) base else p.jellyfin.localAddress, remoteAddress = if (home) p.jellyfin.remoteAddress else base))
        }
        // Signing in needs the address in use now, before the settings come round to it.
        val j = app.store.prefs.value.jellyfin
        service.configure(true, io.github.matiyaaa.fuse.jellyfin.JellyfinConnection(io.github.matiyaaa.fuse.jellyfin.ConnectionMode.AUTO, if (home) base else j.localAddress, if (home) j.remoteAddress else base))
        val server = tested.getOrNull()?.second?.name ?: "Jellyfin"
        app.textInput = TextInputSpec("User name on $server", "", "User name", capitalize = false, doneLabel = "Next") { user ->
            val name = user.trim()
            if (name.isEmpty()) return@TextInputSpec
            app.textInput = TextInputSpec("Password for $name", "", "Password", secret = true, capitalize = false, doneLabel = "Sign in") { password ->
                app.scope.launch {
                    service.signIn(name, password)
                        .onSuccess {
                            app.toasts.show("Signed in to ${it.serverName ?: server} as ${it.userName ?: name}")
                            onDone()
                        }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't sign in") }
                }
            }
        }
    }
}

/** A private network address (or a .local name): the server is at home. */
internal fun looksAtHome(address: String): Boolean {
    val host = address.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 100 && parts[1] in 64..127)
}

/**
 * The stage for Jellyfin: a fan of posters, as a film library looks, with Fuse Player's bar in
 * front of them, playing. They drift a little, out of step, so the stage feels alive. Connected,
 * the bar says so.
 */
@Composable
internal fun JellyfinStage(connected: Boolean, server: String?) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val clock = rememberLoopClock("jellyfin stage")
    val drift by clock.animateFloat(-1f, 1f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), "drift")
    val drift2 by clock.animateFloat(1f, -1f, infiniteRepeatable(tween(5100), RepeatMode.Reverse), "drift2")
    val progress by clock.animateFloat(0.18f, 0.82f, infiniteRepeatable(tween(16000), RepeatMode.Restart), "progress")
    val still = motion.reduced
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(300.dp, 230.dp), contentAlignment = Alignment.Center) {
            Poster(
                listOf(Color(0xFF2E3A8C), Color(0xFF7A4BC9)), FuseIcons.Film,
                Modifier.offset((-92).dp, 6.dp).graphicsLayer {
                    rotationZ = -11f + if (still) 0f else drift * 1.2f
                    translationY = if (still) 0f else drift * 3.dp.toPx()
                },
            )
            Poster(
                listOf(Color(0xFF0F5E63), Color(0xFF39B39A)), FuseIcons.Tv,
                Modifier.offset(92.dp, 6.dp).graphicsLayer {
                    rotationZ = 11f + if (still) 0f else drift2 * 1.2f
                    translationY = if (still) 0f else drift2 * 3.dp.toPx()
                },
            )
            Poster(
                listOf(Color(0xFF8C3B2E), Color(0xFFE8873A)), FuseIcons.Clapperboard,
                Modifier.offset(0.dp, (-10).dp).graphicsLayer {
                    translationY = if (still) 0f else drift2 * 2.dp.toPx()
                    shadowElevation = 18.dp.toPx()
                    shape = RoundedCornerShape(14.dp)
                },
                big = true,
            )
            // Fuse Player's bar, in front.
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(260.dp)
                    .graphicsLayer { shadowElevation = 24.dp.toPx(); shape = RoundedCornerShape(18.dp); clip = true }
                    .background(c.surfaceOverlay)
                    .padding(horizontal = Space.m, vertical = Space.s + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    FuseIcon(if (connected) FuseIcons.Check else FuseIcons.Play, size = 16.dp, tint = c.onAccent)
                }
                Spacer(Modifier.width(Space.s + 2.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    FText(if (connected) (server ?: "Your server") else "Tonight's film", Fuse.type.label, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(PillShape).background(c.text.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth(if (connected) 1f else if (still) 0.5f else progress).height(4.dp).clip(PillShape).background(c.accent))
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        FText(if (connected) "Connected" else "Films, shows and music, played by Fuse", Fuse.type.bodyStrong, maxLines = 1)
    }
}

/** One poster: a deep two-colour wash, a soft light from the top and an icon in its corner. */
@Composable
private fun Poster(colors: List<Color>, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, big: Boolean = false) {
    val w = if (big) 118.dp else 100.dp
    Box(
        modifier
            .size(w, w * 1.45f)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(colors))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent, Color.Black.copy(alpha = 0.28f)))),
        contentAlignment = Alignment.TopStart,
    ) {
        FuseIcon(icon, size = if (big) 26.dp else 22.dp, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.padding(12.dp))
    }
}
