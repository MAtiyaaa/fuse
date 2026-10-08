package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.romm.BiosPick
import io.github.matiyaaa.fuse.romm.FoundRomm
import io.github.matiyaaa.fuse.romm.RommDiscovery
import io.github.matiyaaa.fuse.romm.MirrorProgress
import io.github.matiyaaa.fuse.romm.RommDeviceCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

import io.github.matiyaaa.fuse.ui.shell.store.*

/** No network, transfers or production mirror is opened during setup rehearsal. */
class FakeRommOps : RommOps {
    override val supported = true
    override val state = MutableStateFlow(RommState())
    override suspend fun test(local: String, remote: String, mode: RouteMode) = RommTest(true, true, "Rehearsal", null)
    override suspend fun setAddresses(local: String, remote: String, mode: RouteMode) { state.value = state.value.copy(link = RommLink.NOT_SET_UP) }
    override suspend fun startPairing(upload: Boolean): Result<RommDeviceCode> = Result.success(RommDeviceCode("rehearsal", "123456", "https://rehearsal.invalid", 600, 1))
    override suspend fun awaitPairing(code: RommDeviceCode): Result<String> = usePairCode(code.userCode)
    override suspend fun usePairCode(code: String): Result<String> { state.value = state.value.copy(link = RommLink.ONLINE, account = "Rehearsal account"); return Result.success("Rehearsal account") }
    override suspend fun usePassword(username: String, password: String): Result<String> { state.value = state.value.copy(link = RommLink.ONLINE, account = "Rehearsal account"); return Result.success("Rehearsal account") }
    override suspend fun useToken(token: String): Result<String> { state.value = state.value.copy(link = RommLink.ONLINE, account = "Rehearsal account"); return Result.success("Rehearsal account") }
    override suspend fun setEnabled(enabled: Boolean) { state.value = state.value.copy(link = if (enabled) RommLink.NOT_SET_UP else RommLink.OFF) }
    override suspend fun signOut() = Unit
    override fun refresh(full: Boolean) = Unit
    override val systems: StateFlow<List<RommSystem>> = MutableStateFlow(emptyList())
    override val recent: StateFlow<List<RommGame>> = MutableStateFlow(emptyList())
    override val newGames: StateFlow<List<RommGame>> = MutableStateFlow(emptyList())
    override val collections: StateFlow<List<RommCollectionCard>> = MutableStateFlow(emptyList())
    override fun games(slug: String?): Flow<List<RommGame>> = flowOf(emptyList())
    override fun collection(id: String): Flow<List<RommGame>> = flowOf(emptyList())
    override fun search(text: String): Flow<List<RommGame>> = flowOf(emptyList())
    override fun markNewSeen() = Unit
    override fun detail(romId: Long): Flow<GameDetail?> = flowOf(null)
    override fun forGame(game: GameId): Flow<RommGameView?> = flowOf(null)
    override fun forRom(romId: Long): Flow<RommGameView?> = flowOf(null)
    override suspend fun download(romId: Long, what: RommDownloadWhat): String? = "Fuse RomM isn't available here."
    override suspend fun uploadPlan(game: GameId): RommUploadPlan? = null
    override suspend fun upload(game: GameId, plan: RommUploadPlan): String? = "Fuse RomM isn't available here."
    override suspend fun biosPlan(all: Boolean): List<BiosPick> = emptyList()
    override suspend fun downloadBios(picks: List<BiosPick>): Int = 0
}
