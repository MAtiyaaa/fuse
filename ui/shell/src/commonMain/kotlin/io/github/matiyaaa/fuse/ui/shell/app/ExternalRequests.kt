package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.GameId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Something another program on this device asked Fuse to show (the Fuse Decky plugin, from Steam). */
sealed interface ExternalRequest {
    /** Open [game]'s page. */
    data class OpenGame(val game: GameId) : ExternalRequest
}

/**
 * Requests from other programs on this device, carried out by [FuseApp] on the interface's own
 * thread. Like [RemoteInput], nothing here knows how they arrived: the Decky bridge, a launcher
 * argument, or anything later only post here.
 */
object ExternalRequests {
    private val flow = MutableSharedFlow<ExternalRequest>(extraBufferCapacity = 16)

    val requests: SharedFlow<ExternalRequest> = flow

    fun post(request: ExternalRequest): Boolean = flow.tryEmit(request)
}
