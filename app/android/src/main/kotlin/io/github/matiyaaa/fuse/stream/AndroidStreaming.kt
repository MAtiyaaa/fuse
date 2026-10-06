package io.github.matiyaaa.fuse.stream

import android.content.Context
import android.content.Intent
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Streaming on Android: Moonlight's own shortcut entry (ShortcutTrampoline), which takes the
 * computer by its id or name and the app by name, exactly as its home-screen shortcuts do. Fuse
 * never pairs or changes Moonlight. A computer Moonlight hasn't paired with is paired in Moonlight.
 */
internal object AndroidStreaming {
    /** Moonlight's builds: the Play Store and F-Droid one, its root build, and unofficial builds. */
    private val PACKAGES = listOf("com.limelight", "com.limelight.root", "com.limelight.unofficial")

    fun installed(context: Context, chosen: String = ""): String? =
        (listOf(chosen).filter { it.isNotBlank() } + PACKAGES).firstOrNull { p ->
            runCatching { context.packageManager.getLaunchIntentForPackage(p) != null }.getOrDefault(false)
        }

    fun start(context: Context, hostName: String, uniqueId: String?, app: String, client: String): String? {
        val pkg = installed(context, client)
            ?: return "Moonlight isn't installed. Get it from the Play Store or F-Droid, pair it with your computer once, then stream from Fuse."
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(pkg, "com.limelight.ShortcutTrampoline")
            .putExtra("Name", hostName)
            .putExtra("AppName", app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uniqueId?.takeIf { it.isNotBlank() }?.let { intent.putExtra("UUID", it) }
        return runCatching {
            context.startActivity(intent)
            null
        }.getOrElse {
            // An older Moonlight without the shortcut entry: open it, and the person picks the app there.
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            "This Moonlight can't be told which app to start, so it opened on its own page. Choose $app there."
        }
    }

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
