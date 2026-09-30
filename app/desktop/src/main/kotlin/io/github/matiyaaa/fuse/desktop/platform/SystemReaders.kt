package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SystemStatus
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun readSys(path: String): String? = try {
    File(path).takeIf { it.isFile }?.readText()?.trim()
} catch (e: Exception) {
    null
}

/**
 * Battery, network and Bluetooth state from sysfs and procfs. Every read is a few tiny files; values
 * that can't be read are left out (null or UNKNOWN), never guessed.
 */
internal object StatusReader {
    fun read(): SystemStatus {
        val (battery, charging) = battery()
        val net = network()
        return SystemStatus(
            batteryPercent = battery,
            charging = charging,
            wifi = net.wifi,
            wifiStrength = net.wifiStrength,
            bluetooth = bluetooth(),
            network = net.network,
        )
    }

    /** System batteries only (`scope=Device` marks controller and mouse batteries). */
    private fun battery(): Pair<Int?, Boolean> {
        val supplies = File("/sys/class/power_supply").listFiles() ?: return null to false
        val batteries = supplies.filter { s ->
            readSys("${s.path}/type") == "Battery" && readSys("${s.path}/scope") != "Device" && readSys("${s.path}/present") != "0"
        }
        val levels = batteries.mapNotNull { readSys("${it.path}/capacity")?.toIntOrNull()?.coerceIn(0, 100) }
        if (levels.isEmpty()) return null to false
        val statuses = batteries.mapNotNull { readSys("${it.path}/status") }
        val acOnline = supplies.any { s -> readSys("${s.path}/type") == "Mains" && readSys("${s.path}/online") == "1" }
        val charging = statuses.any { it == "Charging" } || (acOnline && statuses.all { it == "Full" || it == "Not charging" })
        return levels.average().roundToInt() to charging
    }

    private class Net(val network: ConnectionState, val wifi: ConnectionState, val wifiStrength: Int?)

    private fun network(): Net {
        val ifaces = File("/sys/class/net").listFiles()?.filter { it.name != "lo" } ?: return Net(ConnectionState.UNKNOWN, ConnectionState.UNKNOWN, null)
        // Physical interfaces have a `device` link; bridges, VPNs and containers don't.
        val physical = ifaces.filter { File(it, "device").exists() }
        fun up(i: File): Boolean {
            val state = readSys("${i.path}/operstate")
            return state == "up" || (state == "unknown" && readSys("${i.path}/carrier") == "1")
        }
        val wireless = physical.filter { File(it, "wireless").exists() || File(it, "phy80211").exists() }
        val quality = wirelessQuality()
        val wifi = when {
            wireless.isEmpty() -> ConnectionState.UNKNOWN
            wireless.any(::up) -> ConnectionState.CONNECTED
            rfkillBlocked("wlan") -> ConnectionState.OFF
            else -> ConnectionState.ON
        }
        val strength = if (wifi == ConnectionState.CONNECTED) {
            wireless.filter(::up).firstNotNullOfOrNull { quality[it.name] }?.let { q -> ceil(q / 70.0 * 4).toInt().coerceIn(0, 4) }
        } else {
            null
        }
        val network = when {
            physical.isEmpty() -> ConnectionState.UNKNOWN
            physical.any(::up) -> ConnectionState.CONNECTED
            else -> ConnectionState.OFF
        }
        return Net(network, wifi, strength)
    }

    /** Link quality per interface from `/proc/net/wireless` (0..70 on most drivers). */
    private fun wirelessQuality(): Map<String, Double> {
        val text = readSys("/proc/net/wireless") ?: return emptyMap()
        return text.lineSequence().drop(2).mapNotNull { line ->
            val name = line.substringBefore(':').trim()
            val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
            val q = fields.getOrNull(1)?.trimEnd('.')?.toDoubleOrNull() ?: return@mapNotNull null
            name to q
        }.toMap()
    }

    /** CONNECTED when a device is linked (`hciN:handle` entries), ON/OFF from the adapter and rfkill. */
    private fun bluetooth(): ConnectionState {
        val entries = File("/sys/class/bluetooth").list() ?: return ConnectionState.UNKNOWN
        val adapters = entries.filter { Regex("hci\\d+").matches(it) }
        if (adapters.isEmpty()) return ConnectionState.UNKNOWN
        if (rfkillBlocked("bluetooth")) return ConnectionState.OFF
        if (entries.any { Regex("hci\\d+:\\d+").matches(it) }) return ConnectionState.CONNECTED
        return ConnectionState.ON
    }

    private fun rfkillBlocked(type: String): Boolean {
        val switches = File("/sys/class/rfkill").listFiles()?.filter { readSys("${it.path}/type") == type } ?: return false
        return switches.isNotEmpty() && switches.all { readSys("${it.path}/soft") == "1" || readSys("${it.path}/hard") == "1" }
    }
}

/**
 * Live numbers for the performance panel: CPU load from `/proc/stat` deltas, memory from
 * `/proc/meminfo`, Fuse's own resident memory, and CPU temperature where the kernel exposes a CPU
 * sensor. Metrics that can't be read are absent.
 */
internal class PerformanceReader {
    private var lastIdle = -1L
    private var lastTotal = -1L

    fun read(): List<PerformanceMetric> = buildList {
        cpuLoad()?.let { load ->
            add(PerformanceMetric("cpu", "CPU", "${(load * 100).roundToInt()}%", load.toFloat(), "/proc/stat"))
        }
        val mem = memInfo()
        val total = mem["MemTotal"]
        val available = mem["MemAvailable"]
        if (total != null && available != null && total > 0) {
            val used = total - available
            add(PerformanceMetric("memory", "Memory", "${gb(used)} / ${gb(total)} GB", (used.toDouble() / total).toFloat(), "/proc/meminfo"))
        }
        selfRssKb()?.let { rss ->
            add(PerformanceMetric("fuse-memory", "Fuse memory", "${(rss / 1024.0).roundToInt()} MB", null, "/proc/self/status"))
        }
        cpuTemperature()?.let { (celsius, sensor) ->
            add(PerformanceMetric("cpu-temp", "CPU temperature", "${celsius.roundToInt()} °C", (celsius / 100.0).toFloat().coerceIn(0f, 1f), sensor))
        }
    }

    private fun cpuLoad(): Double? {
        val line = readSys("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val values = line.removePrefix("cpu").trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) return null
        // user nice system idle iowait irq softirq steal (guest time is already counted in user).
        val idle = values[3] + values[4]
        val total = values.take(8).sum()
        val prevIdle = lastIdle
        val prevTotal = lastTotal
        lastIdle = idle
        lastTotal = total
        if (prevTotal < 0 || total <= prevTotal) return null
        return (1.0 - (idle - prevIdle).toDouble() / (total - prevTotal)).coerceIn(0.0, 1.0)
    }

    private fun memInfo(): Map<String, Long> =
        readSys("/proc/meminfo")?.lineSequence()?.mapNotNull { line ->
            val key = line.substringBefore(':')
            val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
            key to kb
        }?.toMap() ?: emptyMap()

    private fun selfRssKb(): Long? =
        readSys("/proc/self/status")?.lineSequence()?.firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()

    private fun gb(kb: Long): String = String.format(Locale.ROOT, "%.1f", kb / 1024.0 / 1024.0)

    /** Package or die temperature: thermal zones typed x86_pkg_temp/cpu, else hwmon k10temp/coretemp/zenpower. */
    private fun cpuTemperature(): Pair<Double, String>? {
        File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.sortedBy { it.name }?.forEach { zone ->
            val type = readSys("${zone.path}/type")?.lowercase(Locale.ROOT) ?: return@forEach
            if (type == "x86_pkg_temp" || "cpu" in type) {
                readSys("${zone.path}/temp")?.toLongOrNull()?.let { milli -> return milli / 1000.0 to "${zone.path}/temp" }
            }
        }
        File("/sys/class/hwmon").listFiles()?.sortedBy { it.name }?.forEach { mon ->
            val name = readSys("${mon.path}/name") ?: return@forEach
            if (name in setOf("k10temp", "coretemp", "zenpower", "cpu_thermal")) {
                readSys("${mon.path}/temp1_input")?.toLongOrNull()?.let { milli -> return milli / 1000.0 to "${mon.path}/temp1_input" }
            }
        }
        return null
    }
}

/** Total RAM in MB from `/proc/meminfo`, or null. */
internal fun totalRamMb(): Long? =
    readSys("/proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("MemTotal:") }
        ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.div(1024)
