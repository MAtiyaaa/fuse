package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.settings.StreamHostSettings
import io.github.matiyaaa.fuse.integrations.stream.StreamServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One computer to stream from, as last seen: answering or not, and what it said. */
data class StreamHostView(val host: StreamHostSettings, val online: Boolean?, val info: StreamServerInfo? = null) {
    val canWake: Boolean get() = host.wake && host.mac.isNotBlank()
}

/** Where starting a stream stands, for the sheet that shows it. */
sealed interface StreamProgress {
    val host: String
    val app: String

    data class Checking(override val host: String, override val app: String) : StreamProgress

    /** Woken and waited for: [secondsLeft] until Fuse says it didn't come up. */
    data class Waking(override val host: String, override val app: String, val secondsLeft: Int) : StreamProgress

    data class Starting(override val host: String, override val app: String) : StreamProgress

    data class Failed(override val host: String, override val app: String, val message: String, val canRetry: Boolean = true) : StreamProgress
}

/**
 * Streaming games from a computer at home: the computers running Sunshine (or Apollo, or GeForce
 * Experience), woken when they sleep, and Moonlight started on the app chosen. Fuse reads only the
 * open GameStream port and never changes a host's configuration; Moonlight pairs and streams.
 */
interface StreamingOps {
    val supported: Boolean
    val hosts: StateFlow<List<StreamHostView>>
    val progress: StateFlow<StreamProgress?>

    /** The Moonlight installed here, or null. */
    val client: String?

    fun refresh()

    /** Adds the computer at [address] after asking it who it is; a computer that doesn't answer is added by [name] alone. */
    suspend fun addHost(address: String, name: String? = null): Result<StreamHostSettings>
    suspend fun changeHost(id: String, change: (StreamHostSettings) -> StreamHostSettings)
    suspend fun removeHost(id: String)

    /** Wakes [hostId] when it sleeps, waits for it, then starts Moonlight on [app]. */
    fun stream(hostId: String, app: String)
    fun cancel()

    /** Sends Wake-on-LAN to [hostId] now; false when its network card isn't known. */
    suspend fun wake(hostId: String): Boolean = false

    object None : StreamingOps {
        override val supported = false
        override val hosts: StateFlow<List<StreamHostView>> = MutableStateFlow(emptyList())
        override val progress: StateFlow<StreamProgress?> = MutableStateFlow(null)
        override val client: String? = null
        override fun refresh() = Unit
        override suspend fun addHost(address: String, name: String?) = Result.failure<StreamHostSettings>(UnsupportedOperationException("Streaming isn't available here."))
        override suspend fun changeHost(id: String, change: (StreamHostSettings) -> StreamHostSettings) = Unit
        override suspend fun removeHost(id: String) = Unit
        override fun stream(hostId: String, app: String) = Unit
        override fun cancel() = Unit
    }
}
