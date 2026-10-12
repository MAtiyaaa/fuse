package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Switch game that keeps both an account save and a device save: one save of two parts, captured
 * together on one device and put back together on another whose Eden user has a different id and
 * whose folders are laid out the other way (legacy against newer).
 */
class SwitchPartsTest {
    private lateinit var root: File
    private val game = GameKey.of("switch", "0100000000010000", null, "Game")

    @BeforeTest fun setUp() { root = Files.createTempDirectory("parts").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun spot(account: File, device: File) =
        SaveSpot(SaveKind.SAVE, "switch.savedata", root = account.path, mounts = mapOf(SwitchNand.DEVICE to device.path))

    @Test
    fun bothPartsTravelAsOneSave(): Unit = runBlocking {
        // The phone: newer layout.
        val phoneAccount = File(root, "phone/user/save/account/1032547698badcfeefcdab8967452301/0100000000010000/0").apply { mkdirs() }
        val phoneDevice = File(root, "phone/user/save/device/0100000000010000/0").apply { mkdirs() }
        File(phoneAccount, "slot1.dat").writeText("progress")
        File(phoneDevice, "shared/settings.bin").apply { parentFile.mkdirs() }.writeText("options")
        val phone = SyncDevice(File(root, "phone-state"), "phone", "Thor")
        val phoneSlot = Slots.of(game, spot(phoneAccount, phoneDevice))
        assertEquals(listOf("@device/shared/settings.bin", "slot1.dat"), phoneSlot.files.map { it.path })
        phone.handover("mo", phoneSlot)
        val revision = phone.capture("mo", phoneSlot, 60)!!
        assertEquals(setOf("slot1.dat", "@device/shared/settings.bin"), revision.manifest.files.map { it.path }.toSet())

        // The PC: legacy layout, another user id, nothing saved yet.
        val pcAccount = File(root, "pc/nand/user/save/0000000000000000/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/0100000000010000")
        val pcDevice = File(root, "pc/nand/user/save/0000000000000000/00000000000000000000000000000000/0100000000010000")
        val pcSlot = Slots.of(game, spot(pcAccount, pcDevice))
        for (f in revision.manifest.files) {
            val target = pcSlot.target(f.path)!!
            target.parentFile.mkdirs()
            phone.store.fileOf(f.hash).copyTo(target)
        }
        assertEquals("progress", File(pcAccount, "slot1.dat").readText())
        assertEquals("options", File(pcDevice, "shared/settings.bin").readText())
        assertTrue(!File(pcAccount, "@device").exists(), "the device part never lands inside the account folder")
    }

    @Test
    fun aSwapBetweenPeopleMovesBothPartsTogether(): Unit = runBlocking {
        val account = File(root, "user/save/account/u/0100000000010000/0").apply { mkdirs() }
        val device = File(root, "user/save/device/0100000000010000/0").apply { mkdirs() }
        val d = SyncDevice(File(root, "state"), "deck", "Deck")
        d.handover("john", Slots.of(game, spot(account, device)))
        File(account, "a").writeText("john a")
        File(device, "d").writeText("john d")
        d.handover("mark", Slots.of(game, spot(account, device)))
        assertEquals(emptyList(), account.list()!!.toList() + device.list()!!.toList())
        File(account, "a").writeText("mark a")
        d.handover("john", Slots.of(game, spot(account, device)))
        assertEquals("john a", File(account, "a").readText())
        assertEquals("john d", File(device, "d").readText())
    }
}
