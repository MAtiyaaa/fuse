package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.Deflater
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every disc known: a PlayStation 2 game kept as a CSO is known by the serial on its disc, as one kept as an ISO is. */
class CompressedDiscTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-cso").toFile()
        cache = Files.createTempDirectory("fuse-cso-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    /** A minimal ISO 9660 image with SYSTEM.CNF and the boot program in its root. */
    private fun iso(): ByteArray {
        val sector = 2048
        val image = ByteArray(sector * 24)
        fun le32(at: Int, v: Int) { for (i in 0..3) image[at + i] = (v ushr (8 * i)).toByte() }
        fun record(at: Int, lba: Int, size: Int, dir: Boolean, name: String): Int {
            val n = name.encodeToByteArray()
            val len = 33 + n.size + (if (n.size % 2 == 0) 1 else 0)
            image[at] = len.toByte()
            le32(at + 2, lba)
            le32(at + 10, size)
            image[at + 25] = if (dir) 2 else 0
            image[at + 32] = n.size.toByte()
            n.copyInto(image, at + 33)
            return len
        }
        val pvd = 16 * sector
        image[pvd] = 1
        "CD001".encodeToByteArray().copyInto(image, pvd + 1)
        record(pvd + 156, 18, sector, true, "\u0000")
        var at = 18 * sector
        at += record(at, 18, sector, true, "\u0000")
        at += record(at, 18, sector, true, "\u0001")
        val cnf = "BOOT2 = cdrom0:\\SLUS_209.46;1\r\nVER = 1.01\r\n".encodeToByteArray()
        at += record(at, 20, cnf.size, false, "SYSTEM.CNF;1")
        record(at, 21, 8, false, "SLUS_209.46;1")
        cnf.copyInto(image, 20 * sector)
        return image
    }

    /** [data] as a CSO, as PPSSPP's and maxcso's writers lay one out: deflated blocks behind an index. */
    private fun cso(data: ByteArray, block: Int = 2048): ByteArray {
        val blocks = (data.size + block - 1) / block
        val header = ByteBuffer.allocate(24 + (blocks + 1) * 4).order(ByteOrder.LITTLE_ENDIAN)
        header.put("CISO".encodeToByteArray()).putInt(24).putLong(data.size.toLong()).putInt(block).put(1).put(0).putShort(0)
        val body = ByteArrayOutputStream()
        var at = header.capacity()
        for (i in 0 until blocks) {
            val d = Deflater(9, true)
            d.setInput(data.copyOfRange(i * block, minOf(data.size, (i + 1) * block)))
            d.finish()
            val out = ByteArray(block * 2 + 64)
            val n = d.deflate(out)
            d.end()
            header.putInt(at)
            body.write(out, 0, n)
            at += n
        }
        header.putInt(at)
        return header.array() + body.toByteArray()
    }

    @Test
    fun aCsoIsKnownByTheSerialOnItsDisc() = runBlocking {
        File(root, "roms/ps2").mkdirs()
        File(root, "roms/ps2/Shadow of the Colossus.cso").writeBytes(cso(iso()))
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(File(root, "roms").absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val disc = store.library.discIdentity(game.id)
        assertEquals("SLUS-20946", disc?.serial)
        assertEquals(true, disc?.ps2)
    }
}
