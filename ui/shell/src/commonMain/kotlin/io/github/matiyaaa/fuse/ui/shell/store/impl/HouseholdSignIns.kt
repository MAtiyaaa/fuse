package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.jellyfin.JellyfinService
import io.github.matiyaaa.fuse.sync.HouseholdShared
import io.github.matiyaaa.fuse.sync.JellyfinLogin
import io.github.matiyaaa.fuse.sync.ServiceAddress
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * RomM, Jellyfin and Fuse's keys for the whole household, through Fuse One. A device signed in to them shares
 * the addresses and its sign-ins with the host (sealed for it, kept sealed there, sealed again for
 * each device), and a device without them gets them: sign in to Fuse Sync and RomM and Jellyfin
 * are there too. Each person can have their own Jellyfin account on the same server; the one in
 * use follows the profile in use. Keys (SteamGridDB, TheGamesDB, IGDB, ScreenScraper and
 * RetroAchievements) go the same way, so a key added on one device is on every device; a key
 * removed on one device stays off that device only ([HouseholdKeys]).
 *
 * A device that joins on this version shares by itself (Settings, Fuse Sync, Share Sign-ins turns
 * it off). A device that was already set up when it updated asks once, when the household has
 * nothing shared yet: yes shares from it, no leaves it to the next device that updates.
 */
internal class HouseholdSignIns(
    private val scope: CoroutineScope,
    private val svc: SyncService,
    private val config: StateFlow<SyncSettings>,
    private val configure: suspend ((SyncSettings) -> SyncSettings) -> Unit,
    /** Whether first-run setup was finished before this run (a device setting up now is new). */
    private val setupDone: () -> Boolean,
    private val romm: Romm,
    private val jellyfin: JellyfinService,
    private val jellyfinPlace: JellyfinPlace,
    private val keys: Keys,
    /** The question for a device that updated, shown while true. */
    private val ask: MutableStateFlow<Boolean>,
) {
    /** This device's RomM as the household needs it. */
    interface Romm {
        /** Its addresses and sign-in, or null when it has no server. */
        suspend fun mine(): Pair<ServiceAddress, String?>?

        /** Takes the household's where this device has none; true when anything changed. */
        suspend fun take(address: ServiceAddress, credential: String?): Boolean
    }

    /** This device's Jellyfin addresses as the household needs them. */
    interface JellyfinPlace {
        /** The addresses in use, or null when it isn't set up or is off here. */
        fun mine(): ServiceAddress?

        /** Turns Jellyfin on with [address] when this device has no server; true when it did. */
        suspend fun take(address: ServiceAddress): Boolean
    }

    /** This device's keys as the household needs them, by the names Fuse keeps them under. */
    interface Keys {
        /** Every key this device has. */
        suspend fun mine(): Map<String, String>

        /** Keeps the household's [value] for [name] here. */
        suspend fun take(name: String, value: String)

        /** Keys the person removed here, which the household's copy never brings back. */
        fun keptOff(): Set<String>

        /** The value of each key this device and the household last had alike (kept between runs). */
        suspend fun agreed(): Map<String, String>

        /** Notes that this device and the household now have [values] alike. */
        suspend fun agree(values: Map<String, String>)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** What this device had last time, so only what changes here (or what the household lacks) goes up. */
    private var seen: Map<String, String>? = null

    /** Shared Jellyfin sign-ins already tried for a person here, so one the server refuses isn't tried again and again. */
    private val tried = HashSet<String>()

    /** Keys sent and not kept by the host (one from before 0.4.2 keeps none), so they aren't sent each round. */
    private val unkept = HashSet<String>()
    private var sentKeys: Set<String> = emptySet()

    fun start(localChanges: Flow<Any?>) {
        scope.launch {
            // Setting up for the first time: this device is new, so it just shares.
            if (config.value.signInsChoice.isEmpty() && !setupDone()) configure { it.copy(signInsChoice = AUTO) }
            // Joining a host on this version is the same.
            var linked = config.value.hostId.isNotBlank()
            config.map { it.hostId.isNotBlank() }.distinctUntilChanged().collect { now ->
                if (now && !linked && config.value.signInsChoice.isEmpty()) configure { it.copy(signInsChoice = AUTO) }
                linked = now
            }
        }
        // Each person's own Jellyfin account, following the profile in use.
        scope.launch {
            svc.activeProfile.map { it?.id }.distinctUntilChanged().collect { id ->
                runCatching { jellyfin.useProfile(id) }
                kick()
            }
        }
        // Each time the host is reached and a round of syncing ends: what another device shared since comes in.
        scope.launch { svc.status.map { it is SyncStatus.Online }.distinctUntilChanged().collect { if (it) kick() } }
        scope.launch { svc.rounds.collect { kick() } }
        scope.launch { config.map { Triple(it.enabled, it.shareSignIns, it.signInsChoice) }.distinctUntilChanged().collect { kick() } }
        scope.launch { localChanges.collect { kick() } }
        scope.launch { for (k in kicks) runCatching { round() } }
    }

    fun kick() {
        kicks.trySend(Unit)
    }

    /** The answer to the question: [share] shares from here now; no leaves it to the next device that updates. */
    suspend fun answer(share: Boolean) {
        ask.value = false
        if (share) {
            configure { it.copy(signInsChoice = YES, shareSignIns = true) }
        } else {
            configure { it.copy(signInsChoice = NO, shareSignIns = false) }
            runCatching { svc.shareServices(null, null, emptyMap(), declined = true) }
        }
    }

    /** One look: what the household has comes in where this device has nothing, and what this device has goes up. */
    internal suspend fun round() = lock.withLock {
        val c = config.value
        if (!c.enabled || c.hostId.isBlank()) return@withLock
        val household = svc.householdServices() ?: return@withLock
        val h = household.services
        if (c.signInsChoice.isEmpty()) {
            when {
                // Another device already said yes: this one comes along.
                h.shared -> configure { it.copy(signInsChoice = AUTO) }
                c.deviceId.isNotEmpty() && c.deviceId in h.declined -> configure { it.copy(signInsChoice = NO, shareSignIns = false) }
                mine().isNotEmpty() -> ask.value = true
            }
            // The change above starts the next round.
            return@withLock
        }
        ask.value = false
        if (!c.shareSignIns) return@withLock
        take(household)
        give(household)
    }

    private suspend fun take(household: HouseholdShared) {
        val h = household.services
        h.romm?.let { runCatching { romm.take(it, household.signIns[ROMM]) } }
        h.jellyfin?.let { runCatching { jellyfinPlace.take(it) } }
        // Keys: the household's where this device has none, or where it changed elsewhere and not here.
        val theirs = HouseholdKeys.fromSignIns(household.signIns)
        val mineNow = runCatching { keys.mine() }.getOrDefault(emptyMap())
        val agreed = runCatching { keys.agreed() }.getOrDefault(emptyMap())
        val taken = HouseholdKeys.incoming(theirs, mineNow, agreed, keys.keptOff())
            .filter { (name, value) -> runCatching { keys.take(name, value) }.isSuccess }
        // What both have alike now, taken or already the same.
        val alike = taken + theirs.filter { (name, value) -> mineNow[name] == value && agreed[name] != value }
        if (alike.isNotEmpty()) runCatching { keys.agree(alike) }
        // The person in use signs in to Jellyfin as themselves, when they shared it from another device.
        val person = svc.activeProfile.value?.id ?: return
        val login = household.signIns[jellyfinKey(person)]?.let { runCatching { json.decodeFromString(JellyfinLogin.serializer(), it) }.getOrNull() } ?: return
        if (jellyfin.profile != person || !jellyfin.state.value.enabled) return
        if (jellyfin.hasOwnAccount() || jellyfin.signedOutHere()) return
        val mark = "$person\n${login.username}\n${login.password.hashCode()}"
        if (!tried.add(mark)) return
        jellyfin.signIn(login.username, login.password).onFailure { e ->
            // Only a refusal counts: a server that couldn't be reached is tried again next time.
            if ((e as? io.github.matiyaaa.fuse.jellyfin.JellyfinException)?.kind != io.github.matiyaaa.fuse.jellyfin.JellyfinException.Kind.AUTH) tried.remove(mark)
        }
    }

    private suspend fun give(household: HouseholdShared) {
        val now = mine()
        val before = seen
        seen = now
        val h = household.services
        val has = buildMap {
            h.romm?.let { put(ROMM_ADDRESS, it.toString()) }
            h.jellyfin?.let { put(JELLYFIN_ADDRESS, it.toString()) }
            putAll(household.signIns)
        }
        // A key sent last time that the household still lacks: its host doesn't keep keys.
        unkept += sentKeys.filter { it !in has }
        // What the household lacks, and what changed here since the last look. A key still unlike
        // the household's after taking is one changed here (one changed elsewhere was just taken).
        val goes = now.filter { (k, v) ->
            if (k.startsWith(HouseholdKeys.PREFIX)) k !in unkept && has[k] != v
            else k !in has || (before != null && before[k] != v && has[k] != v)
        }
        sentKeys = emptySet()
        if (goes.isEmpty()) return
        val rommAddress = if (ROMM_ADDRESS in goes) romm.mine()?.first else null
        val jellyfinAddress = if (JELLYFIN_ADDRESS in goes) jellyfinPlace.mine() else null
        val signIns = goes.filterKeys { it != ROMM_ADDRESS && it != JELLYFIN_ADDRESS }
        if (!svc.shareServices(rommAddress, jellyfinAddress, signIns)) {
            seen = before
            return
        }
        val sent = HouseholdKeys.fromSignIns(signIns)
        sentKeys = sent.keys.map { HouseholdKeys.PREFIX + it }.toSet()
        if (sent.isNotEmpty()) runCatching { keys.agree(sent) }
    }

    /** Everything this device could share, by key (addresses as text, so changes show). */
    private suspend fun mine(): Map<String, String> = buildMap {
        runCatching { romm.mine() }.getOrNull()?.let { (address, credential) ->
            put(ROMM_ADDRESS, address.toString())
            credential?.let { put(ROMM, it) }
        }
        jellyfinPlace.mine()?.let { put(JELLYFIN_ADDRESS, it.toString()) }
        for (p in svc.profiles.value) {
            val login = runCatching { jellyfin.keptLoginFor(p.id) }.getOrNull() ?: continue
            put(jellyfinKey(p.id), json.encodeToString(JellyfinLogin.serializer(), JellyfinLogin(login.first, login.second)))
        }
        runCatching { this@HouseholdSignIns.keys.mine() }.getOrNull()?.forEach { (name, value) -> put(HouseholdKeys.PREFIX + name, value) }
    }

    companion object {
        const val AUTO = "AUTO"
        const val YES = "YES"
        const val NO = "NO"
        private const val ROMM = "romm"
        private const val ROMM_ADDRESS = "romm.address"
        private const val JELLYFIN_ADDRESS = "jellyfin.address"
        private fun jellyfinKey(profile: String) = "jellyfin:$profile"
    }
}

/**
 * How keys move between this device and the household. A key the household has comes here when
 * this device has none, or when it changed on another device while this one still has the value
 * both last had alike (one changed here goes up instead, even after Fuse restarts). A key the
 * person removed here never comes back by itself: removing is this device's own choice, and the
 * household and the other devices keep theirs.
 */
internal object HouseholdKeys {
    const val PREFIX = "key:"

    /** The keys among the household's sign-ins, by name. */
    fun fromSignIns(signIns: Map<String, String>): Map<String, String> =
        signIns.filterKeys { it.startsWith(PREFIX) }.mapKeys { it.key.removePrefix(PREFIX) }

    /**
     * What this device takes from [household]: by name, given what it has ([mine]), what it and the
     * household last had alike ([agreed]) and what was removed here ([keptOff]).
     */
    fun incoming(household: Map<String, String>, mine: Map<String, String>, agreed: Map<String, String>, keptOff: Set<String>): Map<String, String> =
        household.filter { (name, value) ->
            if (value.isBlank() || name in keptOff) return@filter false
            val here = mine[name]
            when {
                here == null -> true
                here == value -> false
                // Changed elsewhere: taken only while this device still has what it had last time.
                else -> agreed[name] == here
            }
        }
}
