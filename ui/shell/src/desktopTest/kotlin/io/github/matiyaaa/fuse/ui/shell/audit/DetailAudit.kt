package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val GB = 1_000_000_000L

/**
 * What 0.2.6 added, one quick pass each: searching Settings, marking firmware as set up, a new SD
 * card offered for games, moving a game to it, and adding an app to the Store by its address.
 */
internal fun AuditDriver.detailScreens() {
    scenario("details", "settings search") {
        useLibrary()
        openSettings()
        // Settings opens on Appearance; the search row is just above it.
        tap(PadButton.DPAD_UP)
        settle(400)
        shoot("the search row above Appearance")
        tap(PadButton.A)
        type("con")
        router.textInput?.submit()
        waitFor("places in Settings")
        shoot("what matches \"con\"")
        tap(PadButton.A)
        settle(900)
        shoot("landed on the first one")
    }

    scenario("details", "firmware marked as set up") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("Arrange")
        tap(PadButton.X)
        tapText("System Settings")
        waitFor("Disc playlists")
        focusText("BIOS and firmware")
        tap(PadButton.A)
        waitFor("Fuse's check")
        shoot("what can be done about missing firmware")
        tapText("It's set up")
        settle(900)
        shoot("marked as set up by you")
        // Put back, so later scenarios see the check as it is.
        tap(PadButton.A)
        tapText("Go by Fuse's check")
        settle(600)
    }

    val internal = StorageVolume("android:primary", "Internal storage", listOf(root.parentFile.absolutePath), VolumeKind.INTERNAL, totalBytes = 256 * GB, freeBytes = 118 * GB)
    val cardDir = File(root.parentFile, "audit-new-card").apply { mkdirs() }
    val card = StorageVolume("android:7A11-0C3E", "SanDisk SD card", listOf(cardDir.absolutePath), VolumeKind.SD_CARD, removable = true, totalBytes = 512 * GB, freeBytes = 480 * GB)

    // The question a new card brings waits a moment after it mounts, which this harness's clock
    // doesn't run; Storage offers the same setup for any card without games folders.
    scenario("details", "a new card offered for games") {
        controls.drives = listOf(internal, card)
        libraryStore.sources.refreshDrives()
        try {
            useLibrary { it.copy(drivesAsked = listOf(card.id)) }
            openSettings()
            tap(PadButton.DPAD_DOWN, sectionIndex("storage"))
            tap(PadButton.DPAD_RIGHT)
            tapText("Games and space")
            waitFor("Set up SanDisk SD card for games")
            settle(1_500)
            shoot("Storage offers to set the card up")
            focusText("Set up SanDisk SD card for games") { tap(PadButton.DPAD_UP) }
            tap(PadButton.A)
            waitFor("Set it up for games")
            settle(600)
            shoot("how it is set up")
            tap(PadButton.B)
        } finally {
            controls.drives = emptyList()
            libraryStore.sources.refreshDrives()
        }
    }

    scenario("details", "a game moved to the card") {
        controls.drives = listOf(internal, card)
        libraryStore.sources.refreshDrives()
        try {
            useLibrary { it.copy(drivesAsked = listOf(card.id)) }
            waitFor("Continue playing")
            tap(PadButton.DPAD_LEFT)
            tap(PadButton.X)
            focusText("Move to Another Drive")
            shoot("Move to Another Drive in a game's options")
            tap(PadButton.A)
            waitFor("Move ")
            settle(600)
            shoot("where it can go")
            tap(PadButton.B)
        } finally {
            controls.drives = emptyList()
            libraryStore.sources.refreshDrives()
        }
    }

    scenario("details", "store add an app") {
        androidStore.updatePrefs { it.copy(storeVariant = io.github.matiyaaa.fuse.model.StoreVariant.STANDARD) }
        show(androidStore)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        settle(800)
        focusText("Add an app") { tap(PadButton.DPAD_RIGHT) }
        shoot("Add an app beside the Store's buttons")
        tap(PadButton.A)
        type("github.com/example/handy")
        router.textInput?.submit()
        waitFor("Which shelf?")
        shoot("the shelf it goes on")
        tap(PadButton.B)
    }

    scenario("details", "status on a cable") {
        useLibrary()
        val audit = platform as AuditPlatform
        val before = audit.statusFlow.value
        try {
            home()
            audit.statusFlow.value = before.copy(wifi = io.github.matiyaaa.fuse.model.ConnectionState.OFF, ethernet = true, charging = false, batteryPercent = 52)
            settle(600)
            audit.statusFlow.value = audit.statusFlow.value.copy(charging = true)
            shoot("Ethernet and the battery just plugged in", 500)
            settle(2_500)
            shoot("the battery settled on charging")
            // Up to the tabs, then right past Search and Settings: the status area.
            tap(PadButton.DPAD_UP, 2)
            tap(PadButton.DPAD_RIGHT, 12)
            settle(500)
            shoot("the status area reached from Settings")
            tap(PadButton.A)
            settle(900)
            shoot("the quick menu it opens")
            tap(PadButton.B)
        } finally {
            audit.statusFlow.value = before
        }
    }

    scenario("details", "standby") {
        view = AuditView.Piece { io.github.matiyaaa.fuse.ui.shell.app.StandbyScreen(clock24h = false) {} }
        settle(400)
        shoot("fading in")
        settle(2_000)
        shoot("standby, the clock dim")
        useLibrary()
    }

    scenario("details", "store on a computer") {
        show(windowsStore, windowsPlatform)
        home()
        // Windows has no Apps tab, so the Store's tab is found by name rather than by place.
        repeat(6) { if (!hasText("Emulators for this computer")) tap(PadButton.R1) }
        waitFor("Emulators for this computer")
        settle(1_500)
        shoot("the Store on Windows", 1_200)
        tap(PadButton.DPAD_DOWN)
        settle(900)
        shoot("a shelf focused")
        tap(PadButton.A)
        settle(1_500)
        shoot("a program's page")
        tap(PadButton.B)
        // A processor older than shadPS4 needs (no AVX2): its page says so before anything is installed.
        controls.processor = io.github.matiyaaa.fuse.launch.intelligence.Processor("x86_64", setOf("sse4_1", "sse4_2", "avx"))
        try {
            windowsStore.appStore.open()
            settle(900)
            focusText("Search") { tap(PadButton.DPAD_UP) }
            tap(PadButton.A)
            type("shadPS4")
            router.textInput?.submit()
            waitFor("Search: shadPS4")
            settle(900)
            // The search puts its results first and selects them.
            focusHint("Details") { tap(PadButton.DPAD_DOWN) }
            tap(PadButton.A)
            waitFor("may not run shadPS4")
            settle(1_200)
            shoot("a program this computer can't run")
            tap(PadButton.B)
        } finally {
            controls.processor = null
            windowsStore.appStore.open()
        }
    }

    scenario("details", "ports on a computer") {
        windowsStore.updatePrefs { it.copy(portsEnabled = true, addonsOrder = listOf("PORTS")) }
        try {
            show(windowsStore, windowsPlatform)
            home()
            // Addons is found by name: the Ports tab, moved first, opens with it.
            repeat(6) { if (!hasText("Games rebuilt to run natively")) tap(PadButton.R1) }
            waitFor("Games rebuilt to run natively")
            settle(1_200)
            shoot("Ports: the list inside Fuse, nothing installed")
            tap(PadButton.DPAD_DOWN)
            settle(600)
            shoot("a port focused, Install its next step")
            tap(PadButton.X)
            waitFor("DevilutionX's Project Page")
            settle(600)
            shoot("a port's options: install, its game's files first, its project")
            tap(PadButton.B)
            waitGone("DevilutionX's Project Page")
        } finally {
            windowsStore.updatePrefs { it.copy(portsEnabled = false, addonsOrder = emptyList()) }
        }
    }

    scenario("details", "ports while reading, empty, failing and in every stage") {
        // Fuse's own list, dressed in each state Ports can show: the list being read, a list with no
        // ports, a look for a newer list that failed, and each port somewhere between install and play.
        val base = windowsStore
        val real = runBlocking {
            base.ports.open()
            kotlinx.coroutines.withTimeout(10_000) { base.ports.state.first { it.loaded && it.ports.size >= 3 } }
        }
        val (dx, rct, fo) = real.ports
        val fixed = kotlinx.coroutines.flow.MutableStateFlow(io.github.matiyaaa.fuse.ui.shell.store.PortsState())
        val ops = object : io.github.matiyaaa.fuse.ui.shell.store.PortsOps by base.ports {
            override val state = fixed
            override fun open() = Unit
        }
        val store = object : io.github.matiyaaa.fuse.ui.shell.store.FuseStore by base { override val ports = ops }
        base.updatePrefs { it.copy(portsEnabled = true, addonsOrder = listOf("PORTS")) }
        fun installed(v: io.github.matiyaaa.fuse.ui.shell.store.PortView) = v.copy(installedVersion = v.port.version, installedSha = v.build?.sha256)
        try {
            show(store, windowsPlatform)
            home()
            repeat(6) { if (!hasText("Games rebuilt to run natively")) tap(PadButton.R1) }
            waitFor("Reading the list of ports...")
            settle(900)
            shoot("Ports: the list being read, the shapes its cards will take")
            fixed.value = io.github.matiyaaa.fuse.ui.shell.store.PortsState(loaded = true)
            waitFor("No ports in the list yet")
            settle(600)
            shoot("a list with no ports in it")
            fixed.value = real.copy(
                signed = true, canCheck = true, revision = 4, problem = "GitHub didn't answer.",
                ports = listOf(
                    dx.copy(job = io.github.matiyaaa.fuse.ui.shell.store.PortJob.Downloading(0.42f, (dx.build?.size ?: 0) * 42 / 100, dx.build?.size)),
                    rct.copy(job = io.github.matiyaaa.fuse.ui.shell.store.PortJob.Failed("The download didn't match the checksum its source published, so it was deleted.")),
                    installed(fo).copy(folders = mapOf(fo.port.data.first().id to "D:/Games/Fallout 2"), missing = mapOf(fo.port.data.first().id to "That folder has no master.dat.")),
                ),
            )
            waitFor("GitHub didn't answer. The list Fuse has is shown.")
            tap(PadButton.DPAD_DOWN, 2)
            settle(900)
            shoot("a look for a newer list that failed; downloading, a download refused, a folder lacking a file")
            fixed.value = real.copy(
                signed = true, canCheck = true, revision = 4,
                ports = listOf(
                    installed(dx).copy(verified = "2026-10-17", folders = mapOf(dx.port.data.first().id to "D:/Games/Diablo"), missing = emptyMap(), game = io.github.matiyaaa.fuse.model.GameId(900_001L)),
                    installed(rct).copy(installedVersion = "0.5.5", installedSha = "0".repeat(64), missing = emptyMap(), folders = mapOf(rct.port.data.first().id to "D:/Games/RCT2"), game = io.github.matiyaaa.fuse.model.GameId(900_002L)),
                    fo.copy(build = null, whyNot = "${fo.port.name} isn't published for this computer."),
                ),
            )
            waitFor("signed list, revision 4")
            tap(PadButton.DPAD_UP)
            settle(900)
            shoot("a signed list: verified and ready, an update out, one not made for this computer")
        } finally {
            base.updatePrefs { it.copy(portsEnabled = false, addonsOrder = emptyList()) }
        }
    }

    scenario("details", "store channels, room and a link") {
        show(windowsStore, windowsPlatform)
        home()
        repeat(6) { if (!hasText("Emulators for this computer")) tap(PadButton.R1) }
        waitFor("Emulators for this computer")
        settle(900)
        // Cemu isn't on this PC: its page says the room it needs against what's free, and what it follows.
        focusText("Search") { tap(PadButton.DPAD_UP) }
        tap(PadButton.A)
        type("Cemu")
        router.textInput?.submit()
        waitFor("Search: Cemu")
        settle(900)
        focusHint("Details") { tap(PadButton.DPAD_DOWN) }
        tap(PadButton.A)
        waitFor("Needs about")
        settle(1_200)
        tap(PadButton.DPAD_DOWN, 2)
        settle(900)
        shoot("a program's page: the room it needs, and the releases it follows")
        tap(PadButton.DPAD_UP, 2)
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Cemu's Builds")
        settle(900)
        shoot("stable releases or pre-releases")
        tap(PadButton.B)
        waitGone("Cemu's Builds")
        tap(PadButton.B)
        // A program by its link, on a Forgejo server: what would install is shown before it is added.
        waitFor("Emulators for this computer")
        focusText("Search", substring = true) { tap(PadButton.DPAD_UP) }
        focusText("Add an app") { tap(PadButton.DPAD_RIGHT) }
        tap(PadButton.A)
        type("codeberg.org/lantern/pocket-arcade")
        router.textInput?.submit()
        waitFor("Add pocket-arcade?")
        settle(1_200)
        shoot("what a link would install, and the shelf it goes on")
        tap(PadButton.B)
        waitGone("Add pocket-arcade?")
    }

    scenario("details", "achievements from xenia") {
        // A Windows PC of its own with one Xbox 360 game, tagged with its title id, and Xenia's
        // profile holding that game's achievements (a GPD, as Xenia writes it).
        val dir = File(cache, "xenia-audit").apply { deleteRecursively() }
        File(dir, "games/xbox360").mkdirs()
        File(dir, "games/xbox360/Halo 3 [4D5307E6].iso").writeBytes(ByteArray(512))
        val profile = File(dir, "content/E030000012345678/FFFE07D1/00010000/E030000012345678").apply { mkdirs() }
        File(profile, "4D5307E6.gpd").writeBytes(AuditGpd.halo())
        controls.xeniaContent = File(dir, "content").absolutePath
        val pc = runBlocking {
            io.github.matiyaaa.fuse.ui.shell.store.createFuseStore(AuditServices.create(cache, controls, io.github.matiyaaa.fuse.model.Host.WINDOWS), scope).also { s ->
                s.updatePrefs { it.copy(onboardingDone = true) }
                s.sources.add(File(dir, "games").absolutePath, io.github.matiyaaa.fuse.model.LibrarySourceKind.ROMS_ROOT)
                kotlinx.coroutines.withTimeout(30_000) { s.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.isNotEmpty() } }
            }
        }
        try {
            show(pc, windowsPlatform)
            search("Halo 3")
            tap(PadButton.START)
            tap(PadButton.X)
            tapText("Game Info")
            waitFor("Xenia")
            settle(1_200)
            shoot("its page, with Xenia's achievements below")
            tap(PadButton.DPAD_DOWN, 2)
            settle(900)
            shoot("Xbox 360 achievements from Xenia, gamerscore as points")
            tap(PadButton.DPAD_RIGHT, 2)
            settle(900)
            shoot("a secret one not yet earned, behind a lock")
            tap(PadButton.DPAD_RIGHT)
            settle(900)
            shoot("one not yet earned, dimmed")
            // The Achievements page: Xenia's unlocks beside everything else, with no account at all.
            tab(io.github.matiyaaa.fuse.model.Destination.ACHIEVEMENTS)
            waitFor("Halo 3")
            settle(1_500)
            shoot("every source on one page: Xbox 360 achievements, no RetroAchievements account")
        } finally {
            controls.xeniaContent = null
        }
    }

    scenario("details", "packs and cheats from emucorex") {
        // A computer of its own with one PS2 disc Fuse can read (serial and CRC), PCSX2, and its folders.
        val dir = File(cache, "emucorex-audit").apply { deleteRecursively() }
        File(dir, "games/ps2").mkdirs()
        File(dir, "games/ps2/Sky Racer.iso").writeBytes(AuditEmuCoreX.ps2Iso())
        val pcsx2 = File(dir, "pcsx2").apply { listOf("inis", "patches", "cheats", "gamesettings", "textures").forEach { File(this, it).mkdirs() } }
        File(pcsx2, "inis/PCSX2.ini").writeText("[EmuCore]\nEnableCheats = false\n")
        val home = pcsx2.absolutePath
        controls.pcsx2 = io.github.matiyaaa.fuse.launch.patches.Pcsx2Home(home, "$home/gamesettings", "$home/patches", null, cheats = "$home/cheats", textures = "$home/textures")
        val pc = runBlocking {
            io.github.matiyaaa.fuse.ui.shell.store.createFuseStore(AuditServices.create(cache, controls), scope).also { s ->
                s.updatePrefs { it.copy(onboardingDone = true) }
                s.sources.add(File(dir, "games").absolutePath, io.github.matiyaaa.fuse.model.LibrarySourceKind.ROMS_ROOT)
                kotlinx.coroutines.withTimeout(30_000) { s.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.isNotEmpty() } }
                kotlinx.coroutines.withTimeout(30_000) { s.emulators.installed.first { it.isNotEmpty() } }
            }
        }
        try {
            show(pc, AuditPlatform(size, host = io.github.matiyaaa.fuse.model.Host.LINUX))
            search("Sky Racer")
            tap(PadButton.START)
            tap(PadButton.X)
            tapText("Patches, Cheats and Textures")
            waitFor("Find Packs and Cheats on EmuCoreX")
            focusText("Find Packs and Cheats on EmuCoreX")
            shoot("EmuCoreX beside GameBanana, for a PS2 game", 1_200)
            tap(PadButton.A)
            waitFor("Sky Racer HD Texture Pack")
            settle(900)
            shoot("what EmuCoreX has for this disc: packs, patches, cheats", 1_200)
            tapText("Sky Racer HD Texture Pack")
            waitFor("Add This Pack")
            shoot("a pack: who made it, its licence, its size and checksum", 1_200)
            tapText("Back to the List")
            waitFor("Sky Racer UHD Texture Pack")
            tapText("Sky Racer UHD Texture Pack")
            waitFor("Add This Pack")
            shoot("a pack in pieces, left to its page", 1_200)
            tapText("Back to the List")
            waitFor("Infinite Boost, Max Money, All Tracks")
            tapText("Infinite Boost, Max Money, All Tracks")
            waitFor("Add This File")
            shoot("a cheat file: who made it, the collection it is from", 1_200)
            tap(PadButton.B)
        } finally {
            controls.pcsx2 = null
        }
    }

    /** A PSP game on a computer showing [view] as its Play Together, opened from its options. */
    fun playTogether(view: io.github.matiyaaa.fuse.ui.shell.store.TogetherView, until: String, shot: String) {
        useLibrary()
        val base = libraryStore
        val library = object : io.github.matiyaaa.fuse.ui.shell.store.LibraryOps by base.library {
            override suspend fun together(id: io.github.matiyaaa.fuse.model.GameId) = view
        }
        show(object : io.github.matiyaaa.fuse.ui.shell.store.FuseStore by base { override val library = library }, AuditPlatform(size, host = io.github.matiyaaa.fuse.model.Host.LINUX))
        search("Waystation Nine")
        tap(PadButton.START)
        tap(PadButton.X)
        tapText("Play Together")
        waitFor(until)
        settle(900)
        shoot(shot, 1_200)
    }

    scenario("details", "play together") {
        // Two others in the household hosting it.
        val now = System.currentTimeMillis()
        playTogether(
            io.github.matiyaaa.fuse.ui.shell.store.TogetherView.Ready(
                title = "Waystation Nine", emulator = "PPSSPP", way = io.github.matiyaaa.fuse.launch.online.TogetherWay.AD_HOC,
                missing = listOf("Its wireless on"), canHost = true,
                hosts = listOf(
                    io.github.matiyaaa.fuse.ui.shell.store.TogetherHost("deck", "Steam Deck", "Ana", now - 4 * 60_000, "192.168.1.31", 27312),
                    io.github.matiyaaa.fuse.ui.shell.store.TogetherHost("ally", "ROG Ally", "Mo", now - 22 * 60_000, "192.168.1.44", 27312),
                ),
                household = true, rpcn = null, canPutBack = true,
            ),
            until = "Join Ana's Game", shot = "the household's games to join, hosting here, and what PPSSPP needs",
        )
    }

    scenario("details", "play together online") {
        playTogether(
            io.github.matiyaaa.fuse.ui.shell.store.TogetherView.Ready(
                title = "Waystation Nine", emulator = "RPCS3", way = io.github.matiyaaa.fuse.launch.online.TogetherWay.ONLINE_SERVICE,
                missing = listOf("Its network on, through RPCN"), canHost = false, hosts = emptyList(), household = true, rpcn = "Ana_99", canPutBack = true,
            ),
            until = "Sign Out of RPCN", shot = "RPCS3 online through RPCN, signed in",
        )
    }

    scenario("details", "play together alone and where it can't") {
        // Nobody in the household hosting, and no Fuse One: hosting here and joining by address.
        playTogether(
            io.github.matiyaaa.fuse.ui.shell.store.TogetherView.Ready(
                title = "Waystation Nine", emulator = "PPSSPP", way = io.github.matiyaaa.fuse.launch.online.TogetherWay.AD_HOC,
                missing = emptyList(), canHost = true, hosts = emptyList(), household = false, rpcn = null, canPutBack = false,
            ),
            until = "Join by Address", shot = "nobody hosting yet: host here, or join by an address",
        )
        // An emulator Fuse doesn't know how to play together with: said, with where to look instead.
        playTogether(
            io.github.matiyaaa.fuse.ui.shell.store.TogetherView.Unavailable(
                "Not with Mednafen", "Fuse doesn't know how Mednafen plays with others. Look in its own menus for multiplayer or netplay.",
            ),
            until = "Not with Mednafen", shot = "an emulator Fuse can't play together with",
        )
    }

    scenario("details", "mods from nexus mods") {
        // The same computer and PS2 disc, with the person's own Nexus Mods key (an account that isn't Premium).
        val dir = File(cache, "nexus-audit").apply { deleteRecursively() }
        File(dir, "games/ps2").mkdirs()
        File(dir, "games/ps2/Sky Racer.iso").writeBytes(AuditEmuCoreX.ps2Iso())
        val pcsx2 = File(dir, "pcsx2").apply { listOf("inis", "patches", "cheats", "gamesettings", "textures").forEach { File(this, it).mkdirs() } }
        File(pcsx2, "inis/PCSX2.ini").writeText("[EmuCore]\nEnableCheats = false\n")
        val home = pcsx2.absolutePath
        controls.pcsx2 = io.github.matiyaaa.fuse.launch.patches.Pcsx2Home(home, "$home/gamesettings", "$home/patches", null, cheats = "$home/cheats", textures = "$home/textures")
        val pc = runBlocking {
            io.github.matiyaaa.fuse.ui.shell.store.createFuseStore(AuditServices.create(cache, controls), scope).also { s ->
                s.updatePrefs { it.copy(onboardingDone = true) }
                s.credentials.put(io.github.matiyaaa.fuse.data.settings.SecretKeys.NEXUS_API_KEY, "audit-own-key")
                s.sources.add(File(dir, "games").absolutePath, io.github.matiyaaa.fuse.model.LibrarySourceKind.ROMS_ROOT)
                kotlinx.coroutines.withTimeout(30_000) { s.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.isNotEmpty() } }
                kotlinx.coroutines.withTimeout(30_000) { s.emulators.installed.first { it.isNotEmpty() } }
            }
        }
        try {
            show(pc, AuditPlatform(size, host = io.github.matiyaaa.fuse.model.Host.LINUX))
            search("Sky Racer")
            tap(PadButton.START)
            tap(PadButton.X)
            tapText("Patches, Cheats and Textures")
            waitFor("Find Mods on Nexus Mods")
            focusText("Find Mods on Nexus Mods")
            shoot("Nexus Mods beside GameBanana and EmuCoreX", 1_200)
            tap(PadButton.A)
            waitFor("Which is Sky Racer?")
            settle(900)
            shoot("which game on Nexus Mods, the same name first", 1_200)
            tap(PadButton.A)
            waitFor("Clear Skies HD Textures")
            settle(900)
            shoot("the game's lists: trending, then the newest", 1_200)
            tapText("Clear Skies HD Textures")
            waitFor("Open Its Page")
            settle(900)
            shoot("a mod's files: what Fuse fetches, and why not the rest", 1_200)
            tap(PadButton.A)
            waitFor("Paste Its Mod Manager Download Link")
            shoot("a file for an account that isn't Premium", 1_200)
            tap(PadButton.B)
        } finally {
            controls.pcsx2 = null
        }
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("accounts"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Your Nexus Mods Key")
        focusText("Your Nexus Mods Key")
        settle(900)
        shoot("Settings, Accounts: whose key, and where downloads come from", 1_200)
        // Without a key, Nexus Mods is never asked: Fuse says where the key goes.
        runBlocking { pc.credentials.remove(io.github.matiyaaa.fuse.data.settings.SecretKeys.NEXUS_API_KEY) }
        controls.pcsx2 = io.github.matiyaaa.fuse.launch.patches.Pcsx2Home(home, "$home/gamesettings", "$home/patches", null, cheats = "$home/cheats", textures = "$home/textures")
        try {
            show(pc, AuditPlatform(size, host = io.github.matiyaaa.fuse.model.Host.LINUX))
            search("Sky Racer")
            tap(PadButton.START)
            tap(PadButton.X)
            tapText("Patches, Cheats and Textures")
            waitFor("Find Mods on Nexus Mods")
            tapText("Find Mods on Nexus Mods")
            waitFor("Add your Nexus Mods key")
            shoot("no key yet: where to add it", 1_200)
            tap(PadButton.B)
        } finally {
            controls.pcsx2 = null
        }
    }

    scenario("details", "emulators signed in to retroachievements") {
        // A computer with Ana's account connected and five emulators: some signed in by Fuse, one by
        // someone else, one that hasn't set up its folders yet.
        useLibrary()
        val base = libraryStore
        val achievements = object : io.github.matiyaaa.fuse.ui.shell.store.AchievementOps by base.achievements {
            override val configured = kotlinx.coroutines.flow.MutableStateFlow(true)
            override suspend fun emulatorSignIns() = io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIns.Ready(
                "Ana",
                listOf(
                    io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIn("RetroArch", "retroarch", "Ana", byFuse = true),
                    io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIn("PCSX2", "pcsx2", "Mo", byFuse = false),
                    io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIn("DuckStation", "duckstation", "Ana", byFuse = true),
                    io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIn("PPSSPP", "ppsspp", null, byFuse = false),
                    io.github.matiyaaa.fuse.ui.shell.store.EmulatorSignIn("Dolphin", "dolphin", null, byFuse = false, problem = "Start Dolphin once so it sets up its folders."),
                ),
            )
        }
        show(object : io.github.matiyaaa.fuse.ui.shell.store.FuseStore by base { override val achievements = achievements }, AuditPlatform(size, host = io.github.matiyaaa.fuse.model.Host.LINUX))
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("accounts"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Sign Emulators In")
        focusText("Sign Emulators In")
        settle(900)
        shoot("Settings, Accounts: signing every emulator in at once", 1_200)
        tap(PadButton.A)
        waitFor("Emulators and RetroAchievements")
        settle(900)
        shoot("who each emulator is signed in as, and one sign-in for all", 1_200)
        tap(PadButton.A)
        waitFor("RetroAchievements password")
        settle(900)
        shoot("the password, asked once and kept nowhere", 1_200)
        tap(PadButton.B)
    }

    scenario("details", "a game with many facts") {
        useLibrary()
        val card = runBlocking { libraryStore.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first().first { it.title == "Emberline Saga" } }
        val owner = io.github.matiyaaa.fuse.model.MediaOwner.OfGame(card.id)
        val shot = File(cache, "detail-screenshot.png").also { AuditSystemArt.panel(it, 0xFF3A5A8CL) }
        runBlocking {
            libraryData.games.replaceMetadata(
                card.id,
                io.github.matiyaaa.fuse.model.GameMetadata(
                    description = "A long journey across a burning coast.",
                    releaseYear = 1997, developer = "Lantern Works", publisher = "Northlight", players = "1",
                    genres = listOf("Role-playing", "Adventure", "Fantasy"), franchise = "Emberline", rating = 86,
                ),
                null,
            )
            libraryStore.media.setFromFile(owner, io.github.matiyaaa.fuse.model.MediaKind.SCREENSHOT, shot.absolutePath)
        }
        try {
            openGame("Emberline Saga")
            settle(900)
            shoot("the first facts and a + for the rest")
            tap(PadButton.DPAD_UP)
            settle(400)
            shoot("the + chip selected")
            tap(PadButton.A)
            settle(900)
            shoot("every fact")
            tap(PadButton.B)
            settle(400)
            // From the + chip: the buttons, the description, then the screenshots.
            tap(PadButton.DPAD_DOWN, 3)
            settle(600)
            shoot("the screenshots row")
            tap(PadButton.A)
            settle(1_200)
            shoot("a screenshot full screen")
            tap(PadButton.B)
        } finally {
            runBlocking {
                libraryData.games.replaceMetadata(card.id, null, null)
                libraryStore.media.reset(owner, io.github.matiyaaa.fuse.model.MediaKind.SCREENSHOT)
            }
        }
    }
}

/**
 * An Xbox 360 title's GPD as Xenia writes it (xdbf_io.h, gpd_info.h), for the audit: the XDBF
 * header, 18-byte entries, one free slot, then achievements (section 1) with their pictures (2) and
 * the game's name (5).
 */
internal object AuditGpd {
    private fun picture(color: Int): List<Byte> {
        val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color(color)
        g.fillRect(0, 0, 64, 64)
        g.color = java.awt.Color(255, 255, 255, 210)
        g.fillOval(18, 14, 28, 28)
        g.fillRect(28, 40, 8, 10)
        g.fillRect(20, 48, 24, 5)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray().toList()
    }

    fun halo(): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        fun u32(v: Long) { u16((v ushr 16).toInt() and 0xFFFF); u16((v and 0xFFFF).toInt()) }
        fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun utf16(s: String): List<Byte> = (s + "\u0000").flatMap { listOf((it.code shr 8).toByte(), it.code.toByte()) }
        fun achievement(id: Long, image: Long, score: Long, flags: Long, earnedSeconds: Long, vararg texts: String): List<Byte> {
            val b = ArrayList<Byte>()
            fun b32(v: Long) { for (i in 3 downTo 0) b += (v ushr (8 * i)).toByte() }
            val fileTime = if (earnedSeconds == 0L) 0L else (earnedSeconds * 1000 + 11_644_473_600_000L) * 10_000
            b32(0x1C); b32(id); b32(image); b32(score); b32(flags); b32(fileTime ushr 32); b32(fileTime and 0xFFFFFFFFL)
            texts.forEach { b += utf16(it) }
            return b
        }
        val shown = 0x8L or 3
        val earned = 0x20000L
        val blocks = listOf(
            Triple(1, 1L, achievement(1, 101, 10, shown or earned, 1_759_000_000, "Finish the Fight", "Completed the campaign on any difficulty.", "Complete the campaign on any difficulty.")),
            Triple(1, 2L, achievement(2, 102, 50, 3, 0, "Annual", "Found every skull.", "Keep playing.")),
            Triple(1, 3L, achievement(3, 103, 25, shown or earned, 1_759_400_000, "Lost Platoon", "Found the lost platoon.", "Find the lost platoon.")),
            Triple(1, 4L, achievement(4, 104, 30, shown, 0, "Tourist", "Found every terminal.", "Find every terminal in the campaign.")),
            Triple(2, 101L, picture(0xFF3A6EA5.toInt())),
            Triple(2, 102L, picture(0xFF6B4FA0.toInt())),
            Triple(2, 103L, picture(0xFF3C8D5A.toInt())),
            Triple(2, 104L, picture(0xFFA5703A.toInt())),
            Triple(5, 0x8000L, utf16("Halo 3")),
        )
        out += "XDBF".encodeToByteArray().toList(); u32(0x10000); u32(blocks.size.toLong()); u32(blocks.size.toLong()); u32(1); u32(1)
        var offset = 0L
        for ((section, id, data) in blocks) { u16(section); u64(id); u32(offset); u32(data.size.toLong()); offset += data.size }
        u32(offset); u32(0)
        blocks.forEach { out += it.third }
        return out.toByteArray()
    }
}
