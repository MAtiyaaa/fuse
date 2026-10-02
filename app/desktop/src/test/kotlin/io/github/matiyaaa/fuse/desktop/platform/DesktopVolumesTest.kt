package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.model.VolumeKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVolumesTest {
    // A Steam Deck with its SD card, a USB SSD, and a bind mount of the SSD's ROMs into the home folder.
    private val deck = """
        23 28 0:22 / /proc rw,relatime - proc proc rw
        28 1 259:2 / / rw,relatime - btrfs /dev/nvme0n1p4 rw,subvol=/@
        29 28 259:2 /@home /home rw,relatime - btrfs /dev/nvme0n1p4 rw,subvol=/@home
        30 28 0:40 / /run/user/1000 rw,nosuid - tmpfs tmpfs rw
        31 28 179:1 / /run/media/deck/My\040Games rw,nosuid,nodev - ext4 /dev/mmcblk0p1 rw
        32 28 8:17 / /run/media/deck/SSD ro,nosuid - exfat /dev/sdb1 ro
        33 28 8:17 /ROMs /home/deck/ROMs ro,nosuid - exfat /dev/sdb1 ro
        34 28 259:1 / /boot/efi rw - vfat /dev/nvme0n1p1 rw
        35 28 0:50 / /var/lib/docker/overlay2/x/merged rw - overlay overlay rw
    """.trimIndent()

    @Test
    fun parsesMountsAndKeepsOnlyDrives() {
        val drives = MountInfo.parse(deck).filter(MountInfo::isStorage)
        assertEquals(listOf("/", "/home", "/run/media/deck/My Games", "/run/media/deck/SSD", "/home/deck/ROMs"), drives.map { it.mountPoint })
        val ssd = drives.first { it.mountPoint == "/run/media/deck/SSD" }
        assertTrue(ssd.readOnly)
        assertEquals("exfat", ssd.fsType)
        assertEquals("/dev/sdb1", ssd.source)
    }

    @Test
    fun aBindMountIsTheSameFilesystemFromAnotherFolder() {
        val drives = MountInfo.parse(deck).filter(MountInfo::isStorage)
        val bind = drives.first { it.mountPoint == "/home/deck/ROMs" }
        val ssd = drives.first { it.mountPoint == "/run/media/deck/SSD" }
        assertEquals(ssd.device, bind.device)
        // Not the same root: it is the SSD's ROMs folder, so it can't stand in for the SSD's root.
        assertEquals("/ROMs", bind.root)
        assertEquals("/", ssd.root)
    }

    @Test
    fun kindsComeFromTheBus() {
        val drives = MountInfo.parse(deck).filter(MountInfo::isStorage).associateBy { it.mountPoint }
        assertEquals(VolumeKind.INTERNAL, MountInfo.kind(drives.getValue("/"), "/sys/devices/pci0000:00/nvme/nvme0/nvme0n1"))
        assertEquals(VolumeKind.SD_CARD, MountInfo.kind(drives.getValue("/run/media/deck/My Games"), "/sys/devices/platform/mmc0/block/mmcblk0"))
        assertEquals(VolumeKind.USB, MountInfo.kind(drives.getValue("/run/media/deck/SSD"), "/sys/devices/pci0000:00/usb2/2-1/host0/block/sdb"))
        assertEquals(VolumeKind.FIXED, MountInfo.kind(drives.getValue("/home"), "/sys/devices/pci0000:00/nvme/nvme0/nvme0n1"))
    }

    @Test
    fun namesComeFromTheLabelThenFromWhereItIs() {
        assertEquals("GAMES", MountInfo.label("GAMES", "/run/media/me/GAMES", VolumeKind.USB, "/home/me"))
        assertEquals("System drive", MountInfo.label(null, "/", VolumeKind.INTERNAL, "/home/me"))
        assertEquals("Home", MountInfo.label(null, "/home/me", VolumeKind.FIXED, "/home/me"))
        assertEquals("SD card", MountInfo.label(null, "/run/media/mmcblk0p1", VolumeKind.SD_CARD, "/home/deck"))
        assertEquals("My Games", MountInfo.unescapeUdev("My\\x20Games"))
        assertEquals("a b\\c", MountInfo.unescapeOctal("a\\040b\\134c"))
    }

    @Test
    fun theUsersMediaFolderIsThePathADriveGoesBy() {
        val order = listOf("/home/deck/ROMs", "/mnt/games", "/media/me/G", "/run/media/me/G").sortedBy(MountInfo::preference)
        assertEquals("/run/media/me/G", order.first())
    }

    @Test
    fun readsDiskutilProperties() {
        val xml = """
            <plist version="1.0"><dict>
            <key>BusProtocol</key><string>USB</string>
            <key>Ejectable</key><true/>
            <key>Internal</key><false/>
            <key>TotalSize</key><integer>2000398934016</integer>
            <key>VolumeName</key><string>Games &amp; More</string>
            <key>VolumeUUID</key><string>7C1E2E0B-AAAA-BBBB-CCCC-0123456789AB</string>
            </dict></plist>
        """.trimIndent()
        val d = Plist.dict(xml)
        assertEquals("USB", d["BusProtocol"])
        assertEquals("true", d["Ejectable"])
        assertEquals("false", d["Internal"])
        assertEquals("Games & More", d["VolumeName"])
        assertEquals("7C1E2E0B-AAAA-BBBB-CCCC-0123456789AB", d["VolumeUUID"])
    }

    @Test
    fun thisMachinesDrivesIncludeItsRoot() = runBlocking {
        if (DesktopOs.current != DesktopOs.LINUX) return@runBlocking
        val drives = DesktopVolumes(DesktopOs.LINUX).volumes()
        val root = drives.firstOrNull { "/" in it.mountPaths }
        assertTrue(root != null, "$drives")
        assertEquals(VolumeKind.INTERNAL, root.kind)
        assertFalse(root.removable)
        assertTrue(root.totalBytes > 0)
    }
}
