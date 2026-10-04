package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.ui.shell.store.VolumeMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The drives a desktop has mounted, each with an identity that survives a new mount path:
 * - **Linux**: `/proc/self/mountinfo` for what is mounted where, `/dev/disk/by-uuid` and
 *   `/dev/disk/by-label` for the filesystem's UUID and name, and sysfs for whether the device is a
 *   USB drive, an SD card or a fixed disk. One filesystem seen through several paths (a bind mount)
 *   is one drive with several mount paths.
 * - **Windows**: every drive letter, identified by its volume serial number, so a USB drive that
 *   comes back as `F:` instead of `E:` is still the same drive.
 * - **macOS**: `/` and `/Volumes`, identified by the volume UUID `diskutil` reports.
 *
 * Nothing here changes anything on a drive. The system offers no reliable mount events to a JVM, so
 * [watch] compares a cheap signature of the mount table every few seconds.
 */
internal class DesktopVolumes(private val os: DesktopOs = DesktopOs.current) : VolumeMonitor {
    /** diskutil answers per mount point, kept while the mount point stays mounted. */
    private val macInfo = ConcurrentHashMap<String, Map<String, String>>()

    override suspend fun volumes(): List<StorageVolume> = withContext(Dispatchers.IO) {
        try {
            when (os) {
                DesktopOs.LINUX -> linux()
                DesktopOs.WINDOWS -> windows()
                DesktopOs.MACOS -> mac()
            }
        } catch (e: Exception) {
            Log.warn("could not list drives", e)
            emptyList()
        }
    }

    override fun watch(onChange: () -> Unit): AutoCloseable {
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fuse-drives").apply { isDaemon = true } }
        var last = signature()
        timer.scheduleWithFixedDelay({
            val now = signature()
            if (now != last) {
                last = now
                runCatching(onChange)
            }
        }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
        return AutoCloseable { timer.shutdownNow() }
    }

    /** What changes when anything is mounted or unmounted, read without touching any drive. */
    private fun signature(): String = try {
        when (os) {
            DesktopOs.LINUX -> File(MOUNTINFO).readText().hashCode().toString()
            DesktopOs.WINDOWS -> File.listRoots().orEmpty().joinToString { it.path }
            DesktopOs.MACOS -> File("/Volumes").list().orEmpty().sorted().joinToString("\u0000")
        }
    } catch (e: Exception) {
        ""
    }

    // Linux ------------------------------------------------------------------------------------------

    private fun linux(): List<StorageVolume> {
        val mounts = MountInfo.parse(File(MOUNTINFO).readText()).filter(MountInfo::isStorage)
        val uuids = byLink("/dev/disk/by-uuid")
        val labels = byLink("/dev/disk/by-label")
        val home = System.getProperty("user.home").orEmpty()
        return mounts.groupBy { it.device to it.root }.map { (key, group) ->
            val (device, root) = key
            val first = group.minBy { MountInfo.preference(it.mountPoint) }
            val source = first.source
            val realSource = source.takeIf { it.startsWith("/dev/") }?.let(::realPath)
            val uuid = realSource?.let { uuids[it] }
            val label = realSource?.let { labels[it] }?.let(MountInfo::unescapeUdev)
            val block = sysBlock(device)
            val kind = MountInfo.kind(first, block)
            val paths = group.sortedBy { MountInfo.preference(it.mountPoint) }.map { it.mountPoint }
            val file = File(first.mountPoint)
            StorageVolume(
                id = uuid?.let { "uuid:$it" + if (root != "/") "#$root" else "" } ?: StorageVolume.weakId(first.mountPoint),
                label = MountInfo.label(label, first.mountPoint, kind, home),
                mountPaths = paths,
                kind = kind,
                removable = kind == VolumeKind.USB || kind == VolumeKind.SD_CARD || kind == VolumeKind.OPTICAL ||
                    block?.let { readSmall("$it/removable") == "1" } == true,
                readOnly = first.readOnly,
                totalBytes = file.totalSpace,
                freeBytes = file.usableSpace,
                fsType = first.fsType,
            )
        }
    }

    /** Device link targets (`/dev/sda1`) to the link names in [dir] (UUIDs or labels). */
    private fun byLink(dir: String): Map<String, String> =
        File(dir).listFiles().orEmpty().mapNotNull { link -> realPath(link.path)?.let { it to link.name } }.toMap()

    private fun realPath(path: String): String? = try {
        Path.of(path).toRealPath().toString()
    } catch (e: Exception) {
        null
    }

    /** The sysfs folder of the block device `major:minor` (`/sys/devices/.../usb2/.../block/sdb/sdb1`). */
    private fun sysBlock(device: String): String? {
        val dev = realPath("/sys/dev/block/$device") ?: return null
        // A partition's own folder has no "removable"; its disk (the parent) does.
        return if (File(dev, "removable").exists()) dev else File(dev).parent
    }

    private fun readSmall(path: String): String? = try {
        File(path).takeIf { it.isFile }?.readText()?.trim()
    } catch (e: Exception) {
        null
    }

    // Windows ----------------------------------------------------------------------------------------

    private fun windows(): List<StorageVolume> {
        val systemDrive = (System.getenv("SystemDrive") ?: "C:").trimEnd('\\', '/').uppercase(Locale.ROOT)
        return File.listRoots().orEmpty().mapNotNull { root ->
            val letter = root.path.trimEnd('\\', '/').uppercase(Locale.ROOT)
            val store = try {
                Files.getFileStore(root.toPath())
            } catch (e: Exception) {
                return@mapNotNull null // An empty card reader or disc drive.
            }
            fun attr(name: String): Any? = runCatching { store.getAttribute(name) }.getOrNull()
            val serial = (attr("volume:vsn") as? Int)?.let { "%08X".format(it) }
            val removable = attr("volume:isRemovable") == true
            val optical = attr("volume:isCdrom") == true
            val kind = when {
                optical -> VolumeKind.OPTICAL
                letter == systemDrive -> VolumeKind.INTERNAL
                removable -> VolumeKind.USB
                else -> VolumeKind.FIXED
            }
            val name = store.name().orEmpty().ifBlank { if (kind == VolumeKind.INTERNAL) "Local Disk" else "Drive" }
            StorageVolume(
                id = serial?.let { "vsn:$it" } ?: StorageVolume.weakId(letter + "/"),
                label = "$name ($letter)",
                mountPaths = listOf("$letter/"),
                kind = kind,
                removable = removable || optical,
                readOnly = store.isReadOnly,
                totalBytes = runCatching { store.totalSpace }.getOrDefault(0),
                freeBytes = runCatching { store.usableSpace }.getOrDefault(0),
                fsType = store.type(),
            )
        }
    }

    // macOS ------------------------------------------------------------------------------------------

    private fun mac(): List<StorageVolume> {
        val mounts = listOf(File("/")) + File("/Volumes").listFiles().orEmpty()
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith(".") }
        macInfo.keys.retainAll(mounts.map { it.path }.toSet())
        return mounts.mapNotNull { dir ->
            val info = macInfo.getOrPut(dir.path) {
                // "/" is the sealed system volume, whose id changes with every macOS update; the
                // user's files are on the Data volume, whose id stays. That one names the Mac's disk.
                val asked = if (dir.path == "/" && File(MAC_DATA).isDirectory) MAC_DATA else dir.path
                Processes.run(listOf("/usr/sbin/diskutil", "info", "-plist", asked), timeoutMs = 4_000)
                    ?.takeIf { it.exitCode == 0 }?.stdout?.let(Plist::dict).orEmpty()
            }
            val internal = info["Internal"] == "true"
            val removable = info["RemovableMedia"] == "true" || info["Removable"] == "true" || info["Ejectable"] == "true"
            val kind = when {
                dir.path == "/" -> VolumeKind.INTERNAL
                info["BusProtocol"].equals("USB", ignoreCase = true) -> VolumeKind.USB
                info["BusProtocol"]?.contains("Secure Digital", ignoreCase = true) == true -> VolumeKind.SD_CARD
                info["OpticalMediaType"] != null -> VolumeKind.OPTICAL
                internal -> VolumeKind.FIXED
                else -> VolumeKind.EXTERNAL
            }
            StorageVolume(
                id = info["VolumeUUID"]?.let { "uuid:$it" } ?: StorageVolume.weakId(dir.fusePath),
                label = info["VolumeName"]?.takeIf { it.isNotBlank() } ?: if (dir.path == "/") "Mac" else dir.name,
                mountPaths = listOf(dir.absoluteFile.fusePath),
                kind = kind,
                removable = removable && dir.path != "/",
                readOnly = info["WritableVolume"] == "false",
                totalBytes = dir.totalSpace,
                freeBytes = dir.usableSpace,
                fsType = info["FilesystemType"] ?: info["FilesystemName"],
            )
        }
    }

    private companion object {
        const val MOUNTINFO = "/proc/self/mountinfo"

        /** Where macOS mounts the volume that holds the user's files (Catalina and later). */
        const val MAC_DATA = "/System/Volumes/Data"
        const val POLL_SECONDS = 3L
    }
}

/** One line of `/proc/self/mountinfo`. */
internal data class MountInfo(
    val device: String,
    /** The folder of the filesystem that is mounted (`/` for the whole filesystem, a subvolume or bind source otherwise). */
    val root: String,
    val mountPoint: String,
    val readOnly: Boolean,
    val fsType: String,
    val source: String,
) {
    companion object {
        /** Filesystems games can live on. Pseudo filesystems, overlays of containers and RAM disks are left out. */
        private val STORAGE_TYPES = setOf(
            "ext2", "ext3", "ext4", "btrfs", "xfs", "f2fs", "vfat", "exfat", "ntfs", "ntfs3", "fuseblk", "hfsplus",
            "apfs", "zfs", "bcachefs", "jfs", "reiserfs", "udf", "iso9660", "nfs", "nfs4", "cifs", "smb3", "fuse.sshfs",
        )
        private val NETWORK_TYPES = setOf("nfs", "nfs4", "cifs", "smb3", "fuse.sshfs")

        /** Mount points that never hold a library (boot partitions, snaps, container storage). */
        private val SYSTEM_PREFIXES = listOf("/proc", "/sys", "/dev", "/boot", "/efi", "/snap", "/var/lib/docker", "/var/lib/containers", "/var/snap")

        fun parse(text: String): List<MountInfo> = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(' ')
            val dash = parts.indexOf("-")
            if (dash < 6 || parts.size < dash + 3) return@mapNotNull null
            MountInfo(
                device = parts[2],
                root = unescapeOctal(parts[3]),
                mountPoint = unescapeOctal(parts[4]),
                readOnly = parts[5].split(',').firstOrNull() == "ro",
                fsType = parts[dash + 1],
                source = unescapeOctal(parts[dash + 2]),
            )
        }.toList()

        fun isStorage(m: MountInfo): Boolean {
            if (m.fsType !in STORAGE_TYPES) return false
            if (m.mountPoint == "/") return true
            if (SYSTEM_PREFIXES.any { m.mountPoint == it || m.mountPoint.startsWith("$it/") }) return false
            // /run holds runtime state; only the desktop's removable mounts under it are drives.
            if (m.mountPoint.startsWith("/run/") && !m.mountPoint.startsWith("/run/media/")) return false
            return true
        }

        /** Lower is preferred as the path a drive is shown by: the user's media folders first. */
        fun preference(mountPoint: String): Int = when {
            mountPoint.startsWith("/run/media/") -> 0
            mountPoint.startsWith("/media/") -> 1
            mountPoint.startsWith("/mnt/") -> 2
            else -> 3 + mountPoint.count { it == '/' }
        }

        fun kind(m: MountInfo, sysBlock: String?): VolumeKind {
            if (m.fsType in NETWORK_TYPES) return VolumeKind.NETWORK
            if (m.mountPoint == "/") return VolumeKind.INTERNAL
            if (m.fsType == "iso9660" || m.fsType == "udf") return VolumeKind.OPTICAL
            val sys = sysBlock.orEmpty()
            return when {
                "/usb" in sys -> VolumeKind.USB
                sys.substringAfterLast('/').startsWith("mmcblk") || "/mmc" in sys -> VolumeKind.SD_CARD
                m.mountPoint.startsWith("/run/media/") || m.mountPoint.startsWith("/media/") -> VolumeKind.EXTERNAL
                else -> VolumeKind.FIXED
            }
        }

        /** A drive's name: its filesystem label, else words for where it is. */
        fun label(fsLabel: String?, mountPoint: String, kind: VolumeKind, home: String): String = when {
            !fsLabel.isNullOrBlank() -> fsLabel
            mountPoint == "/" -> "System drive"
            home.isNotEmpty() && mountPoint == home.trimEnd('/') -> "Home"
            kind == VolumeKind.SD_CARD -> "SD card"
            kind == VolumeKind.USB -> "USB drive"
            else -> mountPoint.trimEnd('/').substringAfterLast('/').ifEmpty { mountPoint }
        }

        /** mountinfo writes space, tab, newline and backslash as `\040`-style octal. */
        fun unescapeOctal(s: String): String {
            if ('\\' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val octal = s[i] == '\\' && i + 4 <= s.length && s.substring(i + 1, i + 4).all { it in '0'..'7' }
                if (octal) {
                    out.append(s.substring(i + 1, i + 4).toInt(8).toChar())
                    i += 4
                } else {
                    out.append(s[i])
                    i++
                }
            }
            return out.toString()
        }

        /** udev writes characters in link names as `\x20`-style hex. */
        fun unescapeUdev(s: String): String = Regex("""\\x([0-9a-fA-F]{2})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
    }
}

/** Just enough of Apple's XML property lists for `diskutil info -plist`: a flat dictionary as text. */
internal object Plist {
    private val ENTRY = Regex("""<key>([^<]*)</key>\s*(?:<(string|integer|real)>([^<]*)</\2>|<(true|false)/>)""")

    fun dict(xml: String): Map<String, String> = ENTRY.findAll(xml).associate { m ->
        val key = m.groupValues[1]
        val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
        key to value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }
}
