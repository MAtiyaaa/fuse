package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.PowerShell
import io.github.matiyaaa.fuse.desktop.system.Processes
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
        if (DesktopOs.current != DesktopOs.LINUX) return OtherStatus.read()
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

    fun read(): List<PerformanceMetric> = if (DesktopOs.current == DesktopOs.LINUX) readLinux() else OtherStatus.performance()

    private fun readLinux(): List<PerformanceMetric> = buildList {
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

/** Total RAM in MB from `/proc/meminfo` (elsewhere from the JVM), or null. */
internal fun totalRamMb(): Long? =
    readSys("/proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("MemTotal:") }
        ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.div(1024)
        ?: OtherStatus.osBean?.totalMemorySize?.takeIf { it > 0 }?.div(1024 * 1024)

/**
 * Status and performance on Windows and macOS. Battery: `pmset -g batt` on macOS, the CIM
 * Win32_Battery class through PowerShell on Windows (asked at most once a minute). Network from
 * Java's interface list; on macOS `networksetup` names the Wi-Fi device. CPU and memory from the
 * JVM's operating system bean.
 */
internal object OtherStatus {
    val osBean: com.sun.management.OperatingSystemMXBean? =
        java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    @Volatile private var battery: Pair<Int?, Boolean> = null to false
    @Volatile private var batteryAt = 0L
    private val wifiDevice: String? by lazy { if (DesktopOs.isMac) macWifiDevice() else null }

    fun read(): SystemStatus {
        val (level, charging) = batteryNow()
        val (network, wifi) = network()
        return SystemStatus(batteryPercent = level, charging = charging, wifi = wifi, network = network, bluetooth = ConnectionState.UNKNOWN)
    }

    private fun batteryNow(): Pair<Int?, Boolean> {
        val now = System.currentTimeMillis()
        val ttl = if (DesktopOs.isWindows) 60_000L else 10_000L
        if (now - batteryAt < ttl) return battery
        battery = if (DesktopOs.isWindows) windowsBattery() else macBattery()
        batteryAt = now
        return battery
    }

    private fun macBattery(): Pair<Int?, Boolean> {
        val out = Processes.run(listOf("/usr/bin/pmset", "-g", "batt"), timeoutMs = 3_000)?.takeIf { it.exitCode == 0 } ?: return null to false
        return parsePmset(out.stdout)
    }

    /** "Now drawing from 'AC Power'" and " -InternalBattery-0 (id=1)	85%; charging; 0:42 remaining". */
    internal fun parsePmset(text: String): Pair<Int?, Boolean> {
        val line = text.lineSequence().firstOrNull { "InternalBattery" in it } ?: return null to false
        val level = Regex("""(\d{1,3})%""").find(line)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
        val onAc = "AC Power" in text
        val state = line.substringAfter(';', "").substringBefore(';').trim()
        return level to (state == "charging" || state == "finishing charge" || (onAc && state == "charged"))
    }

    private fun windowsBattery(): Pair<Int?, Boolean> {
        val script = "\$b = Get-CimInstance -ClassName Win32_Battery | Select-Object -First 1; " +
            "if (\$b) { [Console]::Out.Write([string]\$b.EstimatedChargeRemaining + ',' + [string]\$b.BatteryStatus) }"
        val out = PowerShell.run(script, timeoutMs = 8_000)?.takeIf { it.exitCode == 0 } ?: return null to false
        return parseWin32Battery(out.stdout)
    }

    /** "85,2": the charge, then Win32_Battery's BatteryStatus (1, 4 and 5 run on the battery). */
    internal fun parseWin32Battery(text: String): Pair<Int?, Boolean> {
        val parts = text.trim().split(',')
        val level = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: return null to false
        val status = parts.getOrNull(1)?.trim()?.toIntOrNull()
        return level to (status != null && status !in setOf(1, 4, 5))
    }

    private fun network(): Pair<ConnectionState, ConnectionState> {
        val ifaces = try {
            java.net.NetworkInterface.networkInterfaces().toList()
        } catch (e: Exception) {
            return ConnectionState.UNKNOWN to ConnectionState.UNKNOWN
        }
        fun connected(i: java.net.NetworkInterface): Boolean = try {
            i.isUp && !i.isLoopback && !i.isVirtual && i.inetAddresses().anyMatch { a -> !a.isLoopbackAddress && !a.isLinkLocalAddress }
        } catch (e: Exception) {
            false
        }
        val physical = ifaces.filter { i -> runCatching { !i.isLoopback && !i.isVirtual }.getOrDefault(false) }
        val network = when {
            physical.isEmpty() -> ConnectionState.UNKNOWN
            physical.any(::connected) -> ConnectionState.CONNECTED
            else -> ConnectionState.OFF
        }
        val wireless = physical.filter { i ->
            val label = (i.displayName.orEmpty() + " " + i.name).lowercase(Locale.ROOT)
            i.name == wifiDevice || "wi-fi" in label || "wireless" in label || "wlan" in label || "802.11" in label
        }
        val wifi = when {
            wireless.isEmpty() -> ConnectionState.UNKNOWN
            wireless.any(::connected) -> ConnectionState.CONNECTED
            else -> ConnectionState.ON
        }
        return network to wifi
    }

    /** The device behind macOS's "Wi-Fi" hardware port (usually en0). */
    private fun macWifiDevice(): String? {
        val out = Processes.run(listOf("/usr/sbin/networksetup", "-listallhardwareports"), timeoutMs = 3_000)?.takeIf { it.exitCode == 0 } ?: return null
        val lines = out.stdout.lines()
        val port = lines.indexOfFirst { it.trim().equals("Hardware Port: Wi-Fi", ignoreCase = true) || it.trim().equals("Hardware Port: AirPort", ignoreCase = true) }
        if (port < 0) return null
        return lines.getOrNull(port + 1)?.substringAfter("Device:", "")?.trim()?.ifEmpty { null }
    }

    fun performance(): List<PerformanceMetric> = buildList {
        val bean = osBean
        bean?.cpuLoad?.takeIf { it in 0.0..1.0 }?.let { load ->
            add(PerformanceMetric("cpu", "CPU", "${(load * 100).roundToInt()}%", load.toFloat(), "OperatingSystemMXBean"))
        }
        val total = bean?.totalMemorySize ?: 0L
        val free = bean?.freeMemorySize ?: 0L
        if (total > 0) {
            val used = total - free
            add(PerformanceMetric("memory", "Memory", "${gb(used)} / ${gb(total)} GB", (used.toDouble() / total).toFloat(), "OperatingSystemMXBean"))
        }
        val mem = java.lang.management.ManagementFactory.getMemoryMXBean()
        val fuse = mem.heapMemoryUsage.committed + mem.nonHeapMemoryUsage.committed
        add(PerformanceMetric("fuse-memory", "Fuse memory", "${(fuse / 1024.0 / 1024.0).roundToInt()} MB", null, "MemoryMXBean"))
    }

    private fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0)
}
