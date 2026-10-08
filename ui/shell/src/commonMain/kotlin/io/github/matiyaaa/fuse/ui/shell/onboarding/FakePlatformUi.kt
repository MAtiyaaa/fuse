package io.github.matiyaaa.fuse.ui.shell.onboarding

import io.github.matiyaaa.fuse.model.*
import io.github.matiyaaa.fuse.ui.shell.platform.*
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds
import kotlinx.coroutines.flow.MutableStateFlow

/** A pretend device with pretend pickers and Steam; never delegates to an operating system. */
class FakePlatformUi(override val host: Host, private val leave: () -> Unit = {}) : PlatformUi {
    override val features = PlatformFeatures(homeRole = host == Host.ANDROID, androidApps = host == Host.ANDROID)
    override val device = CapabilityProfile(8, 8192, false, 60f, 1280, 800, 160, 1)
    override val status = MutableStateFlow(SystemStatus(network = ConnectionState.CONNECTED))
    override val displays = MutableStateFlow(listOf(DisplayInfo(0, "Rehearsal screen", 1280, 800, 60f, true, false, true)))
    override val performance = MutableStateFlow<List<PerformanceMetric>>(emptyList())
    override val sounds = UiSounds.Silent
    override val haptics = Haptics.None
    override val homeRole = if (host == Host.ANDROID) object : HomeRole {
        override val isHome = MutableStateFlow(false)
        override fun request() { isHome.value = true }
        override fun openHomeSettings() = Unit
        override fun disable() { isHome.value = false }
    } else null
    override val storage = object : StorageAccess {
        override val state = MutableStateFlow(if (host == Host.ANDROID) StorageState.DENIED else StorageState.NOT_NEEDED)
        override fun request() { state.value = StorageState.GRANTED }
        override suspend fun pickFolder(title: String) = "/rehearsal/picked-folder"
        override suspend fun pickImage(title: String) = "/rehearsal/picked-image.png"
        override suspend fun pickAudio(title: String) = PickedFile("/rehearsal/picked-song.ogg", "Rehearsal song")
        override suspend fun pickSave(title: String) = "/rehearsal/picked-save.zip"
        override fun refresh() = Unit
    }
    override val quick = object : QuickControls {
        override val brightness = MutableStateFlow<Float?>(1f)
        override val volume = MutableStateFlow<Float?>(0f)
        override fun setBrightness(value: Float) { brightness.value = value }
        override fun setVolume(value: Float) { volume.value = value }
        override fun openWifi() = Unit
        override fun openBluetooth() = Unit
        override fun openDisplaySettings() = Unit
        override fun openSoundSettings() = Unit
        override fun openSystemSettings() = Unit
        override fun openControllerSettings() = Unit
    }
    override val video: VideoPreview? = null
    override val appVersion = "0.4.1 rehearsal"
    override val steam = if (host == Host.LINUX) object : SteamIntegration {
        private var present = false
        override val gameMode = false
        override suspend fun added() = present
        override suspend fun addFuse(): Result<String> { present = true; return Result.success("Rehearsal Steam entry added") }
    } else null
    override fun openUrl(url: String) = Unit
    override fun restart() = Unit
    override fun exit() = leave()
}
