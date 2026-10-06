package io.github.matiyaaa.fuse.desktop.stream

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streaming on a computer: Moonlight (moonlight-qt) started with its own `stream` command, and
 * Wake-on-LAN sent from here. Fuse never pairs or changes Moonlight; it hands it the computer and
 * the app, as typing `moonlight stream <host> "<app>"` would.
 */
internal object DesktopStreaming {
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    /** The Moonlight to start: the one the person chose, else the first found here. */
    fun client(chosen: String = ""): List<String>? {
        if (chosen.isNotBlank() && File(chosen).canExecute()) return listOf(chosen)
        val home = System.getProperty("user.home").orEmpty()
        val candidates = when {
            "win" in os -> listOf(
                "${System.getenv("ProgramFiles") ?: "C:/Program Files"}/Moonlight Game Streaming/Moonlight.exe",
                "${System.getenv("ProgramFiles(x86)") ?: "C:/Program Files (x86)"}/Moonlight Game Streaming/Moonlight.exe",
                "${System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"}/Programs/Moonlight Game Streaming/Moonlight.exe",
            )
            "mac" in os -> listOf("/Applications/Moonlight.app/Contents/MacOS/Moonlight", "$home/Applications/Moonlight.app/Contents/MacOS/Moonlight")
            else -> (System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/moonlight" } + listOf("/usr/bin/moonlight-qt"))
        }
        candidates.firstOrNull { File(it).canExecute() }?.let { return listOf(it) }
        // The Flatpak, on Linux.
        if ("linux" in os) {
            val installed = listOf("/var/lib/flatpak/app/com.moonlight_stream.Moonlight", "$home/.local/share/flatpak/app/com.moonlight_stream.Moonlight").any { File(it).isDirectory }
            if (installed) return listOf("flatpak", "run", "com.moonlight_stream.Moonlight")
        }
        return null
    }

    suspend fun start(host: String, app: String, chosen: String): String? = withContext(Dispatchers.IO) {
        val program = client(chosen) ?: return@withContext "Moonlight isn't installed here. Get it from moonlight-stream.org, pair it with your computer once, then stream from Fuse."
        runCatching {
            ProcessBuilder(program + listOf("stream", host, app)).also { io.github.matiyaaa.fuse.desktop.system.Processes.hostEnvironment(it.environment()) }.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            null
        }.getOrElse { "Moonlight didn't start (${it.message ?: "no reason given"})." }
    }

    suspend fun wake(packet: ByteArray, ports: List<Int>, address: String?): Boolean = withContext(Dispatchers.IO) {
        sendWake(packet, ports, address)
    }

    /** Broadcasts [packet] on every port, and sends it to [address] directly too (a computer that sleeps keeps its address). */
    fun sendWake(packet: ByteArray, ports: List<Int>, address: String?): Boolean = runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val targets = listOfNotNull(
                InetAddress.getByName("255.255.255.255"),
                address?.substringBefore(':')?.takeIf { it.isNotBlank() }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() },
            )
            for (t in targets) for (p in ports) socket.send(DatagramPacket(packet, packet.size, t, p))
        }
        true
    }.getOrDefault(false)
}
