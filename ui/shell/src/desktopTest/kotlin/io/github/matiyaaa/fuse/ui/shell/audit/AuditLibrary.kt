package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.LocationHint
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The README sample library plus what the audit needs on top: a system with no emulator installed
 * (PSP), firmware folders that make PS1 missing, PS2 ready and Dreamcast partial (Switch keeps its
 * firmware inside the emulator, so it is unknown), a game that only opens its emulator, an empty
 * collection, pinned games and pinned apps.
 */
internal object AuditLibrary {
    /** PSP games; no PSP emulator is installed. */
    val pspGames = listOf("Waystation Nine", "Driftwood Courier", "Neon Almanac")

    val gameCount: Int get() = AuditSamples.games.size + pspGames.size
    val platformCount: Int get() = AuditSamples.platformCount + 1

    /** A game set to Citron, which Fuse can only open, not start a game in. */
    const val OPENS_APP_ONLY = "Tanuki Trails"
    const val EMPTY_COLLECTION = "Weekend Queue"
    const val STATION_COLLECTION = "Station Stops"

    fun writeTo(root: File) {
        AuditSamples.writeTo(root)
        val psp = File(root, "psp").apply { mkdirs() }
        pspGames.forEachIndexed { i, title -> File(psp, "$title.${if (i == 1) "cso" else "iso"}").writeBytes(ByteArray(512)) }
        // Firmware: an empty PS1 folder (missing), a full-size PS2 dump name (ready), half of Dreamcast's pair (partial).
        File(root, "bios/psx").mkdirs()
        File(root, "bios/ps2").mkdirs()
        File(root, "bios/ps2/SCPH-70012.bin").writeBytes(ByteArray(4 * 1024 * 1024))
        File(root, "bios/dc").mkdirs()
        File(root, "bios/dc/dc_boot.bin").writeBytes(ByteArray(2 * 1024 * 1024))
    }

    /** A second, smaller library in RomM's layout (roms/<system>), offered during onboarding. */
    fun writeSdCard(dir: File) {
        val gba = File(dir, "roms/gba").apply { mkdirs() }
        listOf("Paper Lanterns", "Coral Courier").forEach { File(gba, "$it.gba").writeBytes(ByteArray(512)) }
    }

    fun candidates(root: File, sd: File) = listOf(
        LocationHint(root.absolutePath, "Games"),
        LocationHint(File(sd, "roms").absolutePath, "SD card (RomM)"),
    )

    /** The full library store with history, collections, pins and overrides; setup is done. */
    suspend fun store(root: File, cache: File, controls: AuditControls, scope: CoroutineScope): Pair<FuseStore, FuseData> {
        writeTo(root)
        val services = AuditServices.create(cache, controls)
        val store = createFuseStore(services, scope)
        store.updatePrefs { it.copy(onboardingDone = true) }
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(90_000) {
            store.sources.scan.first { it.phase == ScanPhase.DONE }
            store.library.platforms.first { it.size == platformCount }
            store.emulators.installed.first { it.isNotEmpty() }
        }
        val cards = withTimeout(30_000) { store.library.games(GameQuery()).first { it.size == gameCount } }
        AuditSamples.applyHistory(store, services.data, cards, Clock.System.now().toEpochMilliseconds())
        val byTitle = cards.associateBy { it.title }
        fun card(title: String): GameCard = byTitle[title] ?: error("Audit game not found: $title")

        store.library.setEmulator(card(OPENS_APP_ONLY).id, AuditServices.Citron.id)
        listOf("Kestrel Nine", "Starfold Academy", "Folded Maps").forEach { store.library.setPinned(card(it).id, true) }
        store.collections.create(EMPTY_COLLECTION)
        val station = store.collections.create(STATION_COLLECTION)
        listOf("Waystation Nine", "Signal Garden").forEach { store.collections.add(station, card(it).id) }

        val apps = withTimeout(30_000) { store.apps.apps(AppFilter.ALL).first { it.isNotEmpty() } }
        apps.filter { it.entry.label in pinnedApps }.forEach { store.apps.setPinned(it, true) }

        withTimeout(60_000) {
            store.library.home.first { feed ->
                feed.continuePlaying.size >= 8 &&
                    feed.collections.size == AuditSamples.collections.size + 2 &&
                    feed.pinnedGames.size == 3 &&
                    feed.pinnedApps.size == pinnedApps.size &&
                    // Not this week's: early on a Monday every sample session ended last week.
                    feed.playtime.totalSeconds > 0
            }
            store.library.platforms.first { list ->
                list.firstOrNull { it.platform.id == PlatformId("psx") }?.bios?.state == BiosState.MISSING &&
                    list.firstOrNull { it.platform.id == PlatformId("dc") }?.bios?.state == BiosState.PARTIAL
            }
        }
        return store to services.data
    }

    /** A store that finished setup but has no library folder yet (Home shows its empty state). */
    suspend fun emptyStore(cache: File, controls: AuditControls, scope: CoroutineScope): FuseStore {
        val store = createFuseStore(AuditServices.create(cache, controls), scope)
        store.updatePrefs { it.copy(onboardingDone = true) }
        return store
    }

    /** An Android device with no library folder: the audit's apps, the games among them in the Android system. */
    suspend fun androidStore(cache: File, controls: AuditControls, scope: CoroutineScope): FuseStore {
        val store = createFuseStore(AuditServices.create(cache, controls, io.github.matiyaaa.fuse.model.Host.ANDROID), scope)
        store.updatePrefs { it.copy(onboardingDone = true) }
        withTimeout(30_000) { store.library.platforms.first { list -> list.any { it.platform.id.value == "android" } } }
        return store
    }

    /** A Windows PC: no Apps, no Cartridge, emulators found in folders and one to locate. */
    suspend fun windowsStore(cache: File, controls: AuditControls, scope: CoroutineScope): FuseStore {
        val store = createFuseStore(AuditServices.create(cache, controls, io.github.matiyaaa.fuse.model.Host.WINDOWS), scope)
        store.updatePrefs { it.copy(onboardingDone = true) }
        withTimeout(30_000) { store.emulators.installed.first { it.isNotEmpty() } }
        return store
    }

    /** A first run: nothing set up, two library folders to suggest. */
    suspend fun firstRunStore(root: File, sd: File, cache: File, controls: AuditControls, scope: CoroutineScope): FuseStore {
        writeTo(root)
        writeSdCard(sd)
        controls.libraryCandidates = candidates(root, sd)
        return createFuseStore(AuditServices.create(cache, controls), scope)
    }

    val pinnedApps = listOf("Starfall Arena", "Lumen Browser", "Station Radio", "Pebble Weather")

    val citronId: EmulatorId get() = AuditServices.Citron.id
}

/** App icons drawn for the audit: a rounded square in the app's colour with a simple mark. */
/** Stand-ins for system art pack images: a tall artwork panel and a white logo on transparency. */
internal object AuditSystemArt {
    fun panel(file: File, color: Long) {
        val w = 454
        val h = 1080
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val base = Color(color.toInt(), true)
        g.paint = GradientPaint(0f, 0f, base.brighter(), w.toFloat(), h.toFloat(), base.darker().darker())
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 255, 255, 60)
        g.stroke = BasicStroke(18f)
        g.draw(Ellipse2D.Float(60f, 220f, 340f, 340f))
        g.color = Color(255, 255, 255, 36)
        g.fill(RoundRectangle2D.Float(110f, 640f, 240f, 150f, 40f, 40f))
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    fun logo(file: File, text: String) {
        // Cropped to the text, as logo art is.
        val font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD or java.awt.Font.ITALIC, 112)
        val width = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().run { getFontMetrics(font).stringWidth(text).also { dispose() } }
        val img = BufferedImage(width + 24, 160, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.font = font
        g.drawString(text, 8, 124)
        g.dispose()
        ImageIO.write(img, "png", file)
    }
}

/** Stand-ins for game art: a portrait cover with its title at the top, and a square box art. */
internal object AuditCovers {
    fun cover(file: File, title: String, color: Long) = draw(file, 600, 900, title, color)

    fun square(file: File, title: String, color: Long) = draw(file, 512, 512, title, color)

    /** A wide scene for a game's hero art: light, horizon and shapes, no words. */
    fun hero(file: File, color: Long) {
        val w = 1920
        val h = 620
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val base = Color(color.toInt(), true)
        g.paint = GradientPaint(0f, 0f, base.brighter().brighter(), 0f, h.toFloat(), base.darker().darker())
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 240, 210, 120)
        g.fill(Ellipse2D.Float(w * 0.62f, h * 0.12f, 220f, 220f))
        g.color = Color(0, 0, 0, 90)
        g.fill(java.awt.Polygon(intArrayOf(0, 380, 720, 1100, 1500, 1920, 1920, 0), intArrayOf(470, 300, 420, 260, 400, 330, h, h), 8))
        g.color = Color(0, 0, 0, 140)
        g.fill(java.awt.Polygon(intArrayOf(0, 520, 980, 1400, 1920, 1920, 0), intArrayOf(560, 430, 520, 450, 540, h, h), 7))
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    private fun draw(file: File, w: Int, h: Int, title: String, color: Long) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val base = Color(color.toInt(), true)
        g.paint = GradientPaint(0f, 0f, base.brighter(), w.toFloat(), h.toFloat(), base.darker().darker())
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 255, 255, 50)
        g.fill(Ellipse2D.Float(w * 0.2f, h * 0.38f, w * 0.6f, w * 0.6f))
        // A title band at the top, where covers keep their logo: cropping would cut it off.
        g.color = Color(0, 0, 0, 110)
        g.fillRect(0, 0, w, h / 7)
        g.color = Color.WHITE
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, h / 14)
        val metrics = g.fontMetrics
        g.drawString(title, ((w - metrics.stringWidth(title)) / 2).coerceAtLeast(8), h / 7 - h / 28)
        g.dispose()
        ImageIO.write(img, "png", file)
    }
}

internal object AuditIcons {
    fun write(file: File, label: String, color: Long, variant: Int) {
        val size = 192
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val base = Color(color.toInt(), true)
        val light = base.brighter()
        g.paint = GradientPaint(0f, 0f, light, size.toFloat(), size.toFloat(), base.darker())
        g.fill(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 84f, 84f))
        g.color = Color(255, 255, 255, 235)
        g.stroke = BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (variant % 4) {
            0 -> g.draw(Ellipse2D.Float(52f, 52f, 88f, 88f))
            1 -> g.draw(RoundRectangle2D.Float(52f, 52f, 88f, 88f, 28f, 28f))
            2 -> { g.drawLine(56, 136, 96, 56); g.drawLine(96, 56, 136, 136) }
            else -> { g.drawLine(56, 96, 136, 96); g.drawLine(96, 56, 96, 136) }
        }
        // A dot whose place depends on the name, so icons of the same shape still differ.
        val x = 40 + (label.hashCode().mod(5)) * 26
        g.fill(Ellipse2D.Float(x.toFloat(), 150f, 16f, 16f))
        g.dispose()
        ImageIO.write(img, "png", file)
    }
}
