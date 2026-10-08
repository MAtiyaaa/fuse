package io.github.matiyaaa.fuse.data.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.model.SettingScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * The global [AppSettings] document, stored in the setting table at (GLOBAL, '', "app").
 * [update] is an atomic read-modify-write: concurrent updates never lose each other's changes.
 */
class SettingsStore(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val q get() = db.settingQueries

    /** Current settings, re-emitted on every change (defaults when never saved). */
    val settings: Flow<AppSettings> = q.get(GLOBAL, "", KEY).asFlow().mapToOneOrNull(dispatcher)
        .map(AppSettingsCodec::decode)
        .distinctUntilChanged()
        .flowOn(dispatcher)

    suspend fun current(): AppSettings = withContext(dispatcher) { AppSettingsCodec.decode(read()) }

    /** Applies [transform] to the stored settings and saves the result, which it returns. */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        withContext(dispatcher) {
            db.transactionWithResult {
                val stored = read()
                val before = AppSettingsCodec.decode(stored)
                var next = transform(before).copy(version = AppSettings.CURRENT_VERSION)
                val switched = next.sync.activeProfile != before.sync.activeProfile
                if (switched) {
                    // Even untouched inherited preferences belong to this person once selected.
                    // Keep them before another profile hydrates the shared settings document.
                    rememberPreferences(before)
                    // A profile transition is hydration, not an edit of the next person's preferences.
                    val remembered = document(next.sync.activeProfile).values
                    next = ProfileSettings.apply(next, remembered.filterKeys { it != "home.layout" || next.sync.homeScope == "PROFILE" }.mapValues { it.value.value })
                    rememberPreferences(next)
                } else recordEdits(before, next)
                q.put(GLOBAL, "", KEY, AppSettingsCodec.encode(next, stored), clock())
                next
            }
        }
    }

    /** The current person's preferences with their durable revisions, including untouched legacy values. */
    suspend fun profileValues(): Map<String, ProfileSettingValue> = mutex.withLock {
        withContext(dispatcher) {
            db.transactionWithResult {
                val settings = AppSettingsCodec.decode(read())
                val known = document(settings.sync.activeProfile).values
                ProfileSettings.extract(settings).mapValues { (path, value) ->
                    known[path]?.takeIf { it.value == value } ?: ProfileSettingValue(value)
                }
            }
        }
    }

    /**
     * Applies a host observation only when its revision is newer than this person's durable value.
     * A reply for a profile that was switched away from cannot change the currently displayed person.
     * Remote values keep their original revision; receiving a value is never a new user edit.
     */
    suspend fun mergeProfileValues(profile: String, incoming: Map<String, ProfileSettingValue>): AppSettings = mutex.withLock {
        withContext(dispatcher) {
            db.transactionWithResult {
                val stored = read()
                val before = AppSettingsCodec.decode(stored)
                if (before.sync.activeProfile != profile) return@transactionWithResult before
                val known = document(profile).values.toMutableMap()
                val accepted = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
                var observed = revisionClock()
                for ((path, value) in incoming) {
                    if (path !in ProfileSettings.paths) continue
                    if (value.revision > observed.revision) observed = observed.copy(revision = value.revision)
                    val local = known[path]
                    if (local == null || value.revision > local.revision || (value.revision == local.revision && value.value == local.value)) {
                        known[path] = value
                        accepted[path] = value.value
                    }
                }
                val next = ProfileSettings.apply(before, accepted)
                // Invalid/unknown-format values must not claim a revision over data we did not apply.
                val applied = ProfileSettings.extract(next)
                for (path in accepted.keys) if (applied[path] != accepted[path]) {
                    val old = document(profile).values[path]
                    if (old == null) known.remove(path) else known[path] = old
                }
                saveDocument(profile, ProfileSettingDocument(known))
                q.put(GLOBAL, "", CLOCK_KEY, json.encodeToString(ProfileSettingClock.serializer(), observed), clock())
                q.put(GLOBAL, "", KEY, AppSettingsCodec.encode(next, stored), clock())
                next
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun document(profile: String): ProfileSettingDocument = q.get(GLOBAL, profile, REVISION_KEY).executeAsOneOrNull()
        ?.let { runCatching { json.decodeFromString(ProfileSettingDocument.serializer(), it) }.getOrNull() } ?: ProfileSettingDocument()

    private fun revisionClock(): ProfileSettingClock = q.get(GLOBAL, "", CLOCK_KEY).executeAsOneOrNull()
        ?.let { runCatching { json.decodeFromString(ProfileSettingClock.serializer(), it) }.getOrNull() } ?: ProfileSettingClock()

    private fun saveDocument(profile: String, value: ProfileSettingDocument) {
        q.put(GLOBAL, profile, REVISION_KEY, json.encodeToString(ProfileSettingDocument.serializer(), value), clock())
    }

    private fun rememberPreferences(settings: AppSettings) {
        val known = document(settings.sync.activeProfile).values
        val values = ProfileSettings.extract(settings).filterKeys {
            it != "home.layout" || settings.sync.homeScope == "PROFILE"
        }.mapValues { (path, value) -> known[path] ?: ProfileSettingValue(value) }
        saveDocument(settings.sync.activeProfile, ProfileSettingDocument(known + values))
    }

    private fun recordEdits(before: AppSettings, after: AppSettings) {
        val old = ProfileSettings.extract(before)
        val current = ProfileSettings.extract(after)
        val changed = current.filter { (path, value) -> old[path] != value &&
            (path != "home.layout" || after.sync.homeScope == "PROFILE") }
        if (changed.isEmpty()) return
        val persisted = revisionClock()
        val origin = after.sync.deviceId.ifBlank { persisted.origin.ifBlank {
            Random.nextLong().toString(16) + Random.nextLong().toString(16)
        } }
        val wall = clock()
        val revision = if (wall > persisted.revision.millis) ProfileSettingRevision(wall, 0, origin)
            else ProfileSettingRevision(persisted.revision.millis, persisted.revision.counter + 1, origin)
        val known = document(after.sync.activeProfile).values
        // Seed legacy preferences without claiming that defaults were explicitly chosen now.
        val values = current.mapValues { (path, value) -> known[path] ?: ProfileSettingValue(value) }.toMutableMap()
        for ((path, value) in changed) values[path] = ProfileSettingValue(value, revision)
        saveDocument(after.sync.activeProfile, ProfileSettingDocument(values))
        q.put(GLOBAL, "", CLOCK_KEY, json.encodeToString(ProfileSettingClock.serializer(), ProfileSettingClock(revision, origin)), wall)
    }

    /** Restores every default (a user action in Settings -> Reset). */
    suspend fun reset(): AppSettings = update { AppSettings() }

    private fun read(): String? = q.get(GLOBAL, "", KEY).executeAsOneOrNull()

    private companion object {
        val GLOBAL = SettingScope.GLOBAL.name
        const val KEY = "app"
        const val REVISION_KEY = "profile-preference-revisions"
        const val CLOCK_KEY = "profile-preference-clock"
    }
}
