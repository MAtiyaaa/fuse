package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.AchievementSource
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A Vita game's trophies on its page: found through its title id (an ES-DE `.psvita` file holds
 * it) in Vita3K's own folders, labelled as Vita3K's, with a secret one kept a secret.
 */
class VitaTrophyStoreTest {
    private val root: File = Files.createTempDirectory("fuse-vita").toFile()
    private val cache: File = Files.createTempDirectory("fuse-vita-cache").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun aVitaGamesTrophiesComeFromVita3kByItsTitleId(): Unit = runBlocking {
        File(root, "games/psvita").mkdirs()
        File(root, "games/psvita/Gravity Rush.psvita").writeText("PCSA00011\n")
        val pref = File(root, "vita3k")
        File(pref, "ux0/app/PCSA00011/sce_sys/trophy/NPWR01234_00").mkdirs()
        File(pref, "ux0/app/PCSA00011/sce_sys/trophy/NPWR01234_00/TROPHY.TRP").writeBytes(ByteArray(16))
        val conf = File(pref, "ux0/user/00/trophy/conf/NPWR01234_00").apply { mkdirs() }
        File(conf, "TROP.SFM").writeText(
            """<trophyconf version="1.1"><title-name>Gravity Rush</title-name>
<trophy id="000" hidden="no" ttype="P" pid="-1"><name>Queen of Gravity</name><detail>All trophies.</detail></trophy>
<trophy id="001" hidden="no" ttype="B" pid="000"><name>First Fall</name><detail>Fall and land.</detail></trophy>
<trophy id="002" hidden="yes" ttype="S" pid="000"><name>Secret</name><detail>Hidden.</detail></trophy></trophyconf>""",
        )
        // Vita3K's TROPUSR.DAT: trophy 1 unlocked at 1 700 000 000 s.
        val usr = ByteArray(1648)
        fun put(at: Int, v: Long, bytes: Int) { for (i in 0 until bytes) usr[at + i] = (v ushr (8 * i)).toByte() }
        put(0, 0x12D5819AL, 4)
        usr[4] = 0b10
        put(112 + 8, 1_700_000_000L, 8)
        File(pref, "ux0/user/00/trophy/data/NPWR01234_00").mkdirs()
        File(pref, "ux0/user/00/trophy/data/NPWR01234_00/TROPUSR.DAT").writeBytes(usr)

        val vita3k = InstalledEmulator(EmulatorId("linux.vita3k"), "Vita3K", Host.LINUX, "/usr/bin/Vita3K", platforms = setOf(PlatformId("psvita")), detectedVia = "PATH")
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache, installedEmulators = listOf(vita3k))
        services.vitaPref = pref.absolutePath
        val store = createFuseStore(services, scope)
        withTimeout(20_000) { store.emulators.installed.first { it.isNotEmpty() } }
        store.sources.add(File(root, "games").absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }.single()

        val trophies = withTimeout(20_000) { store.library.game(game.id).first { it?.achievements != null } }!!.achievements!!
        assertEquals(AchievementSource.VITA3K, trophies.source)
        assertEquals("Trophies", trophies.source.noun)
        assertEquals(3, trophies.total)
        assertEquals(1, trophies.earned)
        assertEquals(1_700_000_000_000L, trophies.achievements.single { it.title == "First Fall" }.earnedAt)
        // The secret one stays one: no name, no picture, until it is earned.
        val secret = trophies.achievements.single { it.title == "Hidden trophy" }
        assertEquals("", secret.badgeUrl)
        assertEquals("", secret.badgeLockedUrl)
    }
}
