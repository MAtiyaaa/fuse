package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.StreamHostSettings
import io.github.matiyaaa.fuse.integrations.stream.GameStream
import io.github.matiyaaa.fuse.integrations.stream.WakeOnLan
import io.github.matiyaaa.fuse.ui.shell.store.StreamHostView
import io.github.matiyaaa.fuse.ui.shell.store.StreamProgress
import io.github.matiyaaa.fuse.ui.shell.store.StreamingOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Streaming (see [StreamingOps]): hosts kept in settings, looked at while Fuse runs, woken on demand. */
internal class DefaultStreamingOps(
    private val ctx: StoreContext,
    private val write: suspend ((AppSettings) -> AppSettings) -> Unit,
) : StreamingOps {
    override val supported = true
    private val stream = GameStream(ctx.services.http)
    private val hostsFlow = MutableStateFlow<List<StreamHostView>>(emptyList())
    override val hosts: StateFlow<List<StreamHostView>> = hostsFlow
    private val progressFlow = MutableStateFlow<StreamProgress?>(null)
    override val progress: StateFlow<StreamProgress?> = progressFlow
    override val client: String? get() = ctx.services.streamClient()
    private var job: Job? = null
    private var looking: Job? = null

    fun start() {
        ctx.scope.launch {
            ctx.settings.map { it.streaming }.distinctUntilChanged().collect { s ->
                // What was seen stays while a fresh look runs.
                val seen = hostsFlow.value.associateBy { it.host.id }
                hostsFlow.value = s.hosts.map { h -> seen[h.id]?.copy(host = h) ?: StreamHostView(h, null) }
                if (s.enabled) refresh()
            }
        }
    }

    override fun refresh() {
        looking?.cancel()
        looking = ctx.scope.launch {
            val hosts = ctx.settings.value.streaming.hosts
            val seen = coroutineScope { hosts.map { h -> async { h to stream.serverInfo(h.address) } }.awaitAll() }
            hostsFlow.value = seen.map { (h, info) -> StreamHostView(h, info != null, info) }
            // A host that told its id or network card for the first time: kept, for Moonlight and for waking it.
            for ((h, info) in seen) {
                if (info == null) continue
                val mac = info.mac ?: h.mac
                if (info.uniqueId != h.uniqueId || mac != h.mac) changeHost(h.id) { it.copy(uniqueId = info.uniqueId, mac = mac) }
            }
        }
    }

    override suspend fun addHost(address: String, name: String?): Result<StreamHostSettings> {
        val clean = address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Type the computer's address, like 192.168.1.20."))
        if (ctx.settings.value.streaming.hosts.any { it.address.equals(clean, ignoreCase = true) }) return Result.failure(IllegalArgumentException("That computer is already here."))
        val info = stream.serverInfo(clean, timeoutMs = 4_000)
        if (info == null && name.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Nothing answered at $clean. Check Sunshine is running there (or wake the computer), then try again."))
        }
        val host = StreamHostSettings(
            id = "pc-" + kotlin.random.Random.nextLong().toULong().toString(16),
            name = name?.takeIf { it.isNotBlank() } ?: info?.name ?: clean,
            address = clean, uniqueId = info?.uniqueId.orEmpty(), mac = info?.mac.orEmpty(),
        )
        write { s -> s.copy(streaming = s.streaming.copy(enabled = true, hosts = s.streaming.hosts + host)) }
        return Result.success(host)
    }

    override suspend fun changeHost(id: String, change: (StreamHostSettings) -> StreamHostSettings) =
        write { s -> s.copy(streaming = s.streaming.copy(hosts = s.streaming.hosts.map { if (it.id == id) change(it) else it })) }

    override suspend fun removeHost(id: String) =
        write { s -> s.copy(streaming = s.streaming.copy(hosts = s.streaming.hosts.filterNot { it.id == id })) }

    override fun stream(hostId: String, app: String) {
        job?.cancel()
        job = ctx.scope.launch {
            val h = ctx.settings.value.streaming.hosts.firstOrNull { it.id == hostId } ?: return@launch
            try {
                progressFlow.value = StreamProgress.Checking(h.name, app)
                var info = stream.serverInfo(h.address)
                if (info == null) {
                    val packet = h.mac.takeIf { h.wake }?.let(WakeOnLan::packet)
                    if (packet == null) {
                        progressFlow.value = StreamProgress.Failed(
                            h.name, app,
                            if (h.wake) "${h.name} isn't answering, and Fuse doesn't know its network card to wake it. Turn it on (and start Sunshine), or add its network card address in Settings, Addons, Streaming."
                            else "${h.name} isn't answering. Turn it on and check Sunshine is running.",
                        )
                        return@launch
                    }
                    val wait = ctx.settings.value.streaming.wakeWaitSeconds.coerceIn(15, 300)
                    val started = ctx.now()
                    var sent = 0L
                    while (info == null) {
                        val left = wait - ((ctx.now() - started) / 1000).toInt()
                        if (left <= 0) break
                        // The packet again every few seconds: a computer just going to sleep can miss the first.
                        if (ctx.now() - sent > WAKE_EVERY_MS) {
                            ctx.services.sendWake(packet, WakeOnLan.PORTS, h.address)
                            sent = ctx.now()
                        }
                        progressFlow.value = StreamProgress.Waking(h.name, app, left)
                        delay(POLL_MS)
                        info = stream.serverInfo(h.address, timeoutMs = 1_500)
                    }
                    if (info == null) {
                        progressFlow.value = StreamProgress.Failed(h.name, app, "${h.name} didn't wake. Check Wake-on-LAN is on in its settings and that it is plugged into the network, or turn it on by hand.")
                        return@launch
                    }
                }
                if (info.uniqueId != h.uniqueId && h.uniqueId.isNotBlank()) {
                    progressFlow.value = StreamProgress.Failed(h.name, app, "Another computer answered at ${h.address}. Check its address in Settings, Addons, Streaming.", canRetry = false)
                    return@launch
                }
                progressFlow.value = StreamProgress.Starting(h.name, app)
                val problem = ctx.services.startStream(info.name, info.uniqueId, h.address, app, ctx.settings.value.streaming.client)
                progressFlow.value = problem?.let { StreamProgress.Failed(h.name, app, it, canRetry = false) }
            } catch (e: CancellationException) {
                progressFlow.value = null
                throw e
            } catch (e: Exception) {
                progressFlow.value = StreamProgress.Failed(h.name, app, e.message ?: "Streaming didn't start.")
            }
        }
    }

    override suspend fun wake(hostId: String): Boolean {
        val h = ctx.settings.value.streaming.hosts.firstOrNull { it.id == hostId } ?: return false
        val packet = WakeOnLan.packet(h.mac) ?: return false
        return ctx.services.sendWake(packet, WakeOnLan.PORTS, h.address)
    }

    override fun cancel() {
        job?.cancel()
        job = null
        progressFlow.value = null
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val WAKE_EVERY_MS = 10_000L
    }
}
