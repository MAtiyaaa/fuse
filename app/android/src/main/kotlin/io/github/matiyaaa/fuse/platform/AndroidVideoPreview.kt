package io.github.matiyaaa.fuse.platform

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.matiyaaa.fuse.R
import io.github.matiyaaa.fuse.ui.shell.platform.VideoPreview
import java.io.File

/**
 * Muted, looping gameplay previews with Media3. Audio tracks are disabled (not just muted), so a
 * preview never takes audio focus. The player is released when the preview leaves the screen.
 */
class AndroidVideoPreview : VideoPreview {
    @OptIn(UnstableApi::class)
    @Composable
    override fun Player(source: String, playing: Boolean, modifier: Modifier, onFirstFrame: () -> Unit) {
        val context = LocalContext.current
        val firstFrame = rememberUpdatedState(onFirstFrame)
        val player = remember(source) {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                setMediaItem(MediaItem.fromUri(uriOf(source)))
                prepare()
            }
        }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() = firstFrame.value()
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
        LaunchedEffect(player, playing) { player.playWhenReady = playing }
        AndroidView(
            factory = { ctx ->
                // No parent: AndroidView sizes the view itself.
                @SuppressLint("InflateParams")
                val view = LayoutInflater.from(ctx).inflate(R.layout.fuse_video_preview, null, false) as PlayerView
                view.also {
                    it.player = player
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null },
            modifier = modifier.clipToBounds(),
        )
    }

    private fun uriOf(source: String): Uri =
        if (source.startsWith("/")) Uri.fromFile(File(source)) else source.toUri()
}
