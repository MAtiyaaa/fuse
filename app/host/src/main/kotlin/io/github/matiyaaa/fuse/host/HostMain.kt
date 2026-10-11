package io.github.matiyaaa.fuse.host

import io.github.matiyaaa.fuse.sync.HeadlessHost
import io.github.matiyaaa.fuse.sync.HostAdmin
import io.github.matiyaaa.fuse.sync.LanAddresses
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Fuse One Host, on its own. Commands:
 *
 * - `serve` (the default): serves the household kept in the data folder until stopped.
 * - `pair`: asks the running host for a pairing code, for a device joining from Fuse.
 * - `status`: what the running host keeps and who is connected.
 * - `account <username>`: sets the host's account (the password is read from `FUSE_ONE_PASSWORD`
 *   or standard input), for joining by account and for the Hub from another computer.
 * - `backups`: the household's hosts, and computers offering to be backups.
 * - `accept <name>` / `remove-backup <name>`: makes an offering computer a backup, or takes one out.
 * - `backup-of <address> <code>`: makes this host a backup of the household at that address (with a
 *   pairing code from it); started next, it offers itself and keeps a copy once accepted.
 * - `take-over`: on a backup whose host is gone for good, makes this host the household's host.
 * - `version`.
 *
 * The data folder, port and name come from [HostConfig] (environment variables, so a container sets
 * them in its compose file). Nothing here ever deletes household data: stopping, updating or removing
 * the container leaves the data folder as it is.
 */
fun main(args: Array<String>) {
    val config = HostConfig.from(System.getenv())
    val code = when (args.firstOrNull() ?: "serve") {
        "serve" -> serve(config)
        "pair" -> pair(config)
        "status" -> status(config)
        "account" -> account(config, args.getOrNull(1))
        "backups" -> backups(config)
        "accept" -> accept(config, args.getOrNull(1))
        "remove-backup" -> removeBackup(config, args.getOrNull(1))
        "backup-of" -> backupOf(config, args.getOrNull(1), args.getOrNull(2))
        "take-over" -> takeOver(config)
        "version", "--version" -> { println(HostConfig.version()); 0 }
        "help", "--help", "-h" -> { println(HostConfig.USAGE); 0 }
        else -> { System.err.println(HostConfig.USAGE); 64 }
    }
    // Serving only returns when it couldn't start; every other command is done.
    exitProcess(code)
}

private fun serve(config: HostConfig): Int {
    val dir = config.dataDir.apply { mkdirs() }
    if (!dir.isDirectory || !dir.canWrite()) {
        System.err.println("Fuse One Host can't write to ${dir.path}. Give the host's user that folder, then start it again.")
        return 73
    }
    val host = HeadlessHost.serve(dir, config.port, config.name, HostConfig.version())
    if (host == null) {
        System.err.println("Port ${config.port} is already in use. Stop whatever uses it (another Fuse One Host, or Fuse hosting on this computer), or choose another port with FUSE_ONE_PORT.")
        return 75
    }
    Runtime.getRuntime().addShutdownHook(Thread { host.close() })
    println(HostConfig.banner(config, LanAddresses.list()))
    // Serves on its own threads; this one only waits.
    Thread.currentThread().join()
    return 0
}

private fun admin(config: HostConfig): HostAdmin? = HostAdmin.of(config.dataDir, config.port).also {
    if (it == null) System.err.println("No Fuse One Host is running from ${config.dataDir.path} on port ${config.port}.")
}

private fun pair(config: HostConfig): Int {
    val admin = admin(config) ?: return 69
    return runCatching { runBlocking { admin.pairingCode() } }.fold(
        onSuccess = { code ->
            println("Pairing code: $code")
            println("In Fuse on your device: Settings, Fuse One, Connect to a Host, then enter this code. It works once, for a few minutes.")
            0
        },
        onFailure = { System.err.println(it.message ?: "The host didn't give a code."); 70 },
    )
}

private fun status(config: HostConfig): Int {
    val admin = admin(config) ?: return 69
    return runCatching { runBlocking { admin.status() } }.fold(
        onSuccess = { s ->
            println(HostConfig.describe(s))
            0
        },
        onFailure = { System.err.println(it.message ?: "The host didn't answer."); 70 },
    )
}

private fun account(config: HostConfig, username: String?): Int {
    if (username.isNullOrBlank()) {
        System.err.println("Usage: fuse-one-host account <username>  (password from FUSE_ONE_PASSWORD or standard input)")
        return 64
    }
    val password = System.getenv("FUSE_ONE_PASSWORD")?.takeIf { it.isNotEmpty() }
        ?: System.console()?.readPassword("Password for $username: ")?.let(::String)
        ?: readlnOrNull()
    if (password.isNullOrEmpty() || password.length < HostConfig.MIN_PASSWORD) {
        System.err.println("Choose a password of at least ${HostConfig.MIN_PASSWORD} characters.")
        return 64
    }
    val admin = admin(config) ?: return 69
    return runCatching { runBlocking { admin.setAccount(username.trim(), password) } }.fold(
        onSuccess = { println("The host's account is $username."); 0 },
        onFailure = { System.err.println(it.message ?: "The host didn't take the account."); 70 },
    )
}

private fun backups(config: HostConfig): Int {
    val admin = admin(config) ?: return 69
    return runCatching { runBlocking { admin.status() } }.fold(
        onSuccess = { s -> println(HostConfig.describeHosts(s)); 0 },
        onFailure = { System.err.println(it.message ?: "The host didn't answer."); 70 },
    )
}

private fun accept(config: HostConfig, name: String?): Int {
    if (name.isNullOrBlank()) {
        System.err.println("Usage: fuse-one-host accept <name>  (see fuse-one-host backups)")
        return 64
    }
    val admin = admin(config) ?: return 69
    return runCatching {
        runBlocking {
            val offer = admin.status().backupOffers.firstOrNull { it.name.equals(name, ignoreCase = true) || it.deviceId == name }
                ?: error("No computer called $name is offering to be a backup. See fuse-one-host backups.")
            admin.acceptBackup(offer.deviceId.orEmpty())
            offer.name
        }
    }.fold(
        onSuccess = { println("$it is a backup host now. It keeps a copy of the household and can take over if this host is gone."); 0 },
        onFailure = { System.err.println(it.message ?: "The host didn't accept it."); 70 },
    )
}

private fun removeBackup(config: HostConfig, name: String?): Int {
    if (name.isNullOrBlank()) {
        System.err.println("Usage: fuse-one-host remove-backup <name>")
        return 64
    }
    val admin = admin(config) ?: return 69
    return runCatching {
        runBlocking {
            val l = admin.status().leadership ?: error("This host has no backups.")
            val node = l.backups.firstOrNull { it.name.equals(name, ignoreCase = true) || it.nodeId == name } ?: error("No backup called $name.")
            admin.removeBackup(node.nodeId)
            node.name
        }
    }.fold(
        onSuccess = { println("$it is no longer a backup host."); 0 },
        onFailure = { System.err.println(it.message ?: "The host didn't take it out."); 70 },
    )
}

private fun backupOf(config: HostConfig, address: String?, code: String?): Int {
    if (address.isNullOrBlank() || code.isNullOrBlank()) {
        System.err.println("Usage: fuse-one-host backup-of <address> <pairing code>  (the code from the household's host: fuse-one-host pair, or Settings, Fuse One, Add a Device)")
        return 64
    }
    if (HostAdmin.of(config.dataDir, config.port) != null) {
        System.err.println("Stop this host first (docker stop fuse-one-host), then run backup-of, then start it again.")
        return 75
    }
    return runCatching { runBlocking { io.github.matiyaaa.fuse.sync.HeadlessHost.becomeBackupOf(config.dataDir, address, code, config.name) } }.fold(
        onSuccess = { link ->
            println("Paired with ${link.hostName}. Start this host again: it offers itself as a backup.")
            println("Then accept it on ${link.hostName}: in Fuse, Settings, Fuse One, Backup Hosts (or fuse-one-host accept ${config.name} there).")
            0
        },
        onFailure = { System.err.println(it.message ?: "Couldn't pair with that host."); 70 },
    )
}

private fun takeOver(config: HostConfig): Int {
    val admin = admin(config) ?: return 69
    return runCatching { runBlocking { admin.takeOver() } }.fold(
        onSuccess = { println("This host keeps the household now (generation ${it.generation}). If the old host comes back, it hands over what it kept and becomes a backup."); 0 },
        onFailure = { System.err.println(it.message ?: "This host couldn't take over."); 70 },
    )
}

/** Where the host keeps the household, which port it serves and what it calls itself. */
data class HostConfig(val dataDir: File, val port: Int, val name: String) {
    companion object {
        const val DEFAULT_PORT = 47311
        const val MIN_PASSWORD = 8

        val USAGE = """
            |Fuse One Host ${version()}
            |Usage: fuse-one-host [serve|pair|status|account <username>|backups|accept <name>|remove-backup <name>|backup-of <address> <code>|take-over|version]
            |  serve          serve the household (the default)
            |  pair           show a pairing code for a device joining from Fuse
            |  status         what the host keeps and which devices are connected
            |  account        set the host's account (password from FUSE_ONE_PASSWORD or standard input)
            |  backups        the household's hosts, and computers offering to be backups
            |  accept         make a computer that offers itself a backup host
            |  remove-backup  take a backup host out
            |  backup-of      make this host a backup of the household at an address (with its pairing code)
            |  take-over      on a backup whose host is gone for good, make this the household's host
            |Environment: FUSE_ONE_DATA (default /data), FUSE_ONE_PORT (default $DEFAULT_PORT), FUSE_ONE_NAME (default the computer's name)
        """.trimMargin()

        fun from(env: Map<String, String>): HostConfig {
            val dir = env["FUSE_ONE_DATA"]?.takeIf { it.isNotBlank() } ?: "/data"
            val port = env["FUSE_ONE_PORT"]?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT
            val name = env["FUSE_ONE_NAME"]?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Fuse One Host"
            return HostConfig(File(dir), port, name)
        }

        fun version(): String = System.getProperty("fuse.version")?.takeIf { it.isNotBlank() } ?: "dev"

        /** What the host prints once it serves: where to reach it and what to do next. No secrets. */
        fun banner(config: HostConfig, addresses: List<String>): String = buildString {
            appendLine("Fuse One Host is ready")
            appendLine("  Name:     ${config.name}")
            if (addresses.isEmpty()) appendLine("  Address:  this computer's address, port ${config.port}")
            else addresses.forEachIndexed { i, a -> appendLine("  ${if (i == 0) "Address: " else "          "} $a:${config.port}") }
            appendLine("  Status:   running")
            appendLine("  Data:     ${config.dataDir.path}")
            appendLine("Devices on this network find it by themselves: in Fuse, Settings, Fuse One, Connect to a Host.")
            append("For a pairing code: fuse-one-host pair (in Docker: docker exec fuse-one-host fuse-one-host pair)")
        }

        fun describe(s: io.github.matiyaaa.fuse.sync.HostStatus): String = buildString {
            appendLine("${s.hello.name}: running, Fuse ${s.hello.fuseVersion.ifBlank { "?" }}, protocol ${s.hello.version}")
            appendLine("Profiles: ${s.profiles.size}${if (s.profiles.isNotEmpty()) " (" + s.profiles.joinToString(", ") { it.name } + ")" else ""}")
            appendLine("Devices: ${s.devices.size}${if (s.devices.isNotEmpty()) " (" + s.devices.joinToString(", ") { it.name } + ")" else ""}")
            appendLine("Kept: ${bytes(s.storageBytes)} in ${s.objectCount} files, ${s.revisionCount} save versions")
            if (s.freeBytes >= 0) append("Free space: ${bytes(s.freeBytes)}")
        }.trimEnd()

        /** The household's hosts and the offers waiting, as `backups` prints them. */
        fun describeHosts(s: io.github.matiyaaa.fuse.sync.HostStatus): String = buildString {
            val l = s.leadership
            if (l == null || l.hosts.size <= 1) appendLine("No backup hosts.")
            else {
                appendLine("Hosts (generation ${l.generation}):")
                for (h in l.hosts) appendLine("  ${h.name}${if (h.nodeId == l.leader) " (the host)" else " (backup)"}${h.local?.let { " at $it" } ?: ""}")
            }
            if (s.backupOffers.isEmpty()) append("No computer is offering to be a backup.")
            else {
                appendLine("Offering to be a backup (accept with: fuse-one-host accept <name>):")
                append(s.backupOffers.joinToString("\n") { "  ${it.name}${it.local?.let { a -> " at $a" } ?: ""}" })
            }
        }.trimEnd()

        internal fun bytes(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024L * 1024 -> "${n / 1024} KB"
            n < 1024L * 1024 * 1024 -> "${n / (1024 * 1024)} MB"
            else -> "%.1f GB".format(n / (1024.0 * 1024 * 1024))
        }
    }
}
