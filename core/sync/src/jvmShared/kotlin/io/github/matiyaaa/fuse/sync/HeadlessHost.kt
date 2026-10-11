package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.launch
import java.io.File

/**
 * The host on its own, with no window: what the background service runs (`Fuse --sync-host`), so
 * Fuse Sync keeps serving when Fuse is closed and after the computer restarts. Fuse itself then
 * manages it through its admin calls ([HostAdmin]); it never opens the host's files a second time.
 */
object HeadlessHost {
    /**
     * Serves the host kept in [hostDir] on [port] and answers discovery, until closed. Null when
     * something already serves that port (Fuse itself, still hosting): the service tries again later.
     */
    fun serve(
        hostDir: File,
        port: Int,
        name: String,
        version: String,
        clock: () -> Long = System::currentTimeMillis,
        keepEveryMs: Long = KEEP_EVERY_MS,
        /** Where whole copies of the household go, every day, week and month; null for none. */
        backupsDir: File? = File(hostDir, "household-backups"),
    ): AutoCloseable? {
        if (!HostAdmin.portFree(port)) return null
        if (fuseAppVersion.isEmpty()) fuseAppVersion = version
        val link = readLink(hostDir)
        val store = HostStore(hostDir, clock, hostName = name)
        // A host set up as another's backup waits for its copy; it never makes itself the host.
        if (link != null && !store.leadership.chosenAsHost) store.leadership.standBy()
        // Fuse reads it to manage this process.
        store.adminToken
        val server = runCatching { SyncHost(store, port, version, clock).start() }.getOrNull() ?: return null
        val own = LanAddresses.list().firstOrNull()?.let { "$it:$port" }
        store.leadership.noteOwnAddress(own, store.outsideAddress().ifBlank { null })
        val responder = runCatching { Discovery.answer({ store.hello(port, version) }) }.getOrNull()
        val keeper = link?.let { keep(hostDir, store, server, it, name, own, clock, keepEveryMs) }
        server.backups = backupsDir?.let { HouseholdBackups(it, clock) }
        val schedule = backupsDir?.let {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                while (true) {
                    runCatching { server.backUpIfDue() }
                    kotlinx.coroutines.delay(BACKUP_CHECK_MS)
                }
            }
        }
        return AutoCloseable {
            schedule?.cancel()
            keeper?.cancel()
            responder?.stop()
            server.stop()
        }
    }

    /**
     * A host that is one of the household's backups ([link]: it as a device of the household):
     * offers itself until the household's host accepts it, then keeps its copy up to date and
     * takes part in electing a new host; as the host, brings an older host back.
     */
    private fun keep(hostDir: File, store: HostStore, server: SyncHost, link: HostLink, name: String, own: String?, clock: () -> Long, everyMs: Long): kotlinx.coroutines.Job {
        val client = SyncClient(link).also { c -> c.onLinkChange = { next -> runCatching { writeLink(hostDir, next) } } }
        val node = BackupNode(store, client, clock = clock)
        server.onTakeOver = { node.takeOver() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        return scope.launch {
            while (true) {
                runCatching {
                    if (!store.leadership.isLeader) {
                        val state = client.backupState()
                        if (!state.backup) {
                            if (!state.offered) client.offerBackup(HostNode(store.leadership.nodeId, name, client.link.deviceId, local = own))
                            return@runCatching
                        }
                    }
                    node.watch()
                }
                kotlinx.coroutines.delay(everyMs)
            }
        }
    }

    /** This host as a device of the household it backs up: kept beside the household, readable by its user only. */
    private const val LINK_FILE = "backup-link.json"
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun readLink(hostDir: File): HostLink? = File(hostDir, LINK_FILE).takeIf { it.isFile }?.let {
        runCatching { json.decodeFromString(HostLink.serializer(), it.readText()) }.getOrNull()
    }

    fun writeLink(hostDir: File, link: HostLink) {
        val f = File(hostDir, LINK_FILE)
        hostDir.mkdirs()
        writeAtomically(f, json.encodeToString(HostLink.serializer(), link).toByteArray())
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }

    /**
     * Makes the host kept in [hostDir] a backup of the household at [address]: pairs with it using
     * [code] (shown on that host) as a device named [name], and keeps the link. Refused for a folder
     * that already keeps a household with devices or profiles of its own.
     */
    suspend fun becomeBackupOf(hostDir: File, address: String, code: String, name: String): HostLink {
        val existing = File(hostDir, "devices.json").takeIf { it.isFile }?.readText()?.trim()
        require(existing == null || existing == "[]") { "This folder keeps a household of its own. Use an empty data folder for a backup host." }
        require(readLink(hostDir) == null) { "This host is a backup already." }
        val id = "dev-" + SyncCrypto.token(12).filter { it.isLetterOrDigit() }.take(16)
        val link = SyncClient.pair(address, code, id, name, "LINUX")
        writeLink(hostDir, link)
        return link
    }

    const val KEEP_EVERY_MS = 60_000L

    /** How often a host looks whether the household's backup is due. */
    const val BACKUP_CHECK_MS = 60 * 60_000L
}
