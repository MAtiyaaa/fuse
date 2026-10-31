package io.github.matiyaaa.fuse.data.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.Chain
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.Resolved
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedKey
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SettingScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Settings that inherit Game -> Platform -> Global -> default ([ScopedSettings]).
 *
 * A key can only be set at the scopes in [ScopedKey.scopes]; values stored at other scopes are
 * ignored when resolving. For keys whose global value is part of [AppSettings] (video preview,
 * preview delay, matching strictness) the global level reads and writes [SettingsStore], so there
 * is one source of truth.
 *
 * A system's emulator is also the person's own ([LibraryPreferences.systemEmulators], one of their
 * profile's settings): the one they chose for a system on this kind of device comes before this
 * device's, so two people sharing a handheld each start a system's games in the emulator they
 * picked. Choosing one sets both: the device's is what everyone who never chose gets.
 */
class ScopedSettingsRepository(
    private val db: FuseDatabase,
    private val settingsStore: SettingsStore,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val q get() = db.settingQueries
    private val json get() = AppSettingsCodec.json

    /**
     * Stores [value] for [key] at [scope].
     * @throws IllegalArgumentException when [key] cannot be set at that scope, or a platform/game
     *   scope has no id.
     */
    suspend fun <T> set(key: ScopedKey<T>, scope: ScopeRef, value: T) {
        val scopeId = checkScope(key, scope)
        val binding = bindingOf(key)
        if (scope.scope == SettingScope.GLOBAL && binding != null) {
            settingsStore.update { binding.write(it, value) }
            return
        }
        withContext(dispatcher) {
            q.put(scope.scope.name, scopeId, key.id, json.encodeToString(key.serializer, value), clock())
        }
        personalKey(key, scope)?.let { k -> settingsStore.update { it.withSystemEmulator(k, value as String) } }
    }

    /** Removes the value at [scope] so it inherits again (a bound global key returns to its default). */
    suspend fun <T> clear(key: ScopedKey<T>, scope: ScopeRef) {
        val scopeId = checkScope(key, scope)
        val binding = bindingOf(key)
        if (scope.scope == SettingScope.GLOBAL && binding != null) {
            settingsStore.update { binding.write(it, key.default) }
            return
        }
        withContext(dispatcher) { q.delete(scope.scope.name, scopeId, key.id) }
        // The person chose the automatic one: theirs, even where the device keeps another.
        personalKey(key, scope)?.let { k -> settingsStore.update { it.withSystemEmulator(k, "") } }
    }

    /** Removes every override stored for one platform or game ("Reset to inherited"). */
    suspend fun clearAll(scope: ScopeRef) {
        require(scope.scope != SettingScope.GLOBAL) { "Use SettingsStore.reset for global settings" }
        val scopeId = scopeIdOf(scope)
        withContext(dispatcher) { q.deleteScope(scope.scope.name, scopeId) }
        personalKey(ScopedSettings.Emulator, scope)?.let { k ->
            settingsStore.update { s -> s.copy(library = s.library.copy(systemEmulators = s.library.systemEmulators - k)) }
        }
    }

    /** Key ids overridden at [scope], for "2 settings changed for this game" hints. */
    fun observeOverriddenKeys(scope: ScopeRef): Flow<Set<String>> =
        q.keysInScope(scope.scope.name, scopeIdOf(scope)).asFlow().mapToList(dispatcher)
            .map { it.toSet() - APP_KEY }
            .flowOn(dispatcher)

    /** The effective value for a game (or a platform when [gameId] is null, or global when both are). */
    suspend fun <T> resolve(key: ScopedKey<T>, platformId: PlatformId?, gameId: GameId?): Resolved<T> {
        val rows = withContext(dispatcher) { chain(key, platformId, gameId).executeAsList() }
        val global = if (bindingOf(key) != null || isPersonal(key)) settingsStore.current() else null
        return resolveFrom(key, rows, platformId, gameId, global)
    }

    fun <T> observeResolved(key: ScopedKey<T>, platformId: PlatformId?, gameId: GameId?): Flow<Resolved<T>> {
        val rows = chain(key, platformId, gameId).asFlow().mapToList(dispatcher)
        val resolved = if (bindingOf(key) == null && !isPersonal(key)) {
            rows.map { resolveFrom(key, it, platformId, gameId, null) }
        } else {
            combine(rows, settingsStore.settings) { r, s -> resolveFrom(key, r, platformId, gameId, s) }
        }
        return resolved.distinctUntilChanged().flowOn(dispatcher)
    }

    private fun <T> chain(key: ScopedKey<T>, platformId: PlatformId?, gameId: GameId?) =
        // Empty strings never match a stored platform or game id.
        q.chain(key.id, platformId?.value ?: "", gameId?.value?.toString() ?: "")

    private fun <T> resolveFrom(
        key: ScopedKey<T>,
        rows: List<Chain>,
        platformId: PlatformId?,
        gameId: GameId?,
        settings: AppSettings?,
    ): Resolved<T> {
        fun at(scope: SettingScope, id: String): T? {
            if (scope !in key.scopes) return null
            val row = rows.firstOrNull { it.scope == scope.name && it.scope_id == id } ?: return null
            return runCatching { json.decodeFromString(key.serializer, row.value_json) }.getOrNull()
        }
        if (gameId != null) at(SettingScope.GAME, gameId.value.toString())?.let { return Resolved(it, SettingScope.GAME, false) }
        // The person's own choice for the system comes before the device's (empty: the automatic one).
        if (platformId != null && settings != null && isPersonal(key)) {
            personalKey(key, ScopeRef.platform(platformId))?.let { k -> settings.library.systemEmulators[k] }?.let { mine ->
                @Suppress("UNCHECKED_CAST")
                return if (mine.isEmpty()) Resolved(key.default, SettingScope.GLOBAL, true) else Resolved(mine as T, SettingScope.PLATFORM, false)
            }
        }
        if (platformId != null) at(SettingScope.PLATFORM, platformId.value)?.let { return Resolved(it, SettingScope.PLATFORM, false) }
        val binding = bindingOf(key)
        if (binding != null && settings != null) {
            val value = binding.read(settings)
            return Resolved(value, SettingScope.GLOBAL, value == key.default)
        }
        at(SettingScope.GLOBAL, "")?.let { return Resolved(it, SettingScope.GLOBAL, false) }
        return Resolved(key.default, SettingScope.GLOBAL, true)
    }

    private fun checkScope(key: ScopedKey<*>, scope: ScopeRef): String {
        require(key.id != APP_KEY) { "\"$APP_KEY\" is reserved for AppSettings" }
        require(scope.scope in key.scopes) { "${key.id} cannot be set at ${scope.scope} (allowed: ${key.scopes})" }
        return scopeIdOf(scope)
    }

    private fun scopeIdOf(scope: ScopeRef): String = when (scope.scope) {
        SettingScope.GLOBAL -> ""
        else -> requireNotNull(scope.id?.takeIf(String::isNotBlank)) { "${scope.scope} scope needs an id" }
    }

    /**
     * This device's kind ("android", "linux", the start of its emulators' ids), which picks the
     * person's choices made on devices like it. Until it is known only the device's are used.
     */
    @kotlin.concurrent.Volatile var personalKind: String? = null

    private fun isPersonal(key: ScopedKey<*>): Boolean = key.id == ScopedSettings.Emulator.id && personalKind != null

    /** Where the person's choice for [key] at [scope] is kept ("android:switch"), or null when it isn't the person's. */
    private fun personalKey(key: ScopedKey<*>, scope: ScopeRef): String? {
        if (!isPersonal(key) || scope.scope != SettingScope.PLATFORM) return null
        val platform = scope.id?.takeIf { it.isNotBlank() } ?: return null
        return "$personalKind:$platform"
    }

    private fun AppSettings.withSystemEmulator(key: String, emulator: String): AppSettings =
        copy(library = library.copy(systemEmulators = library.systemEmulators + (key to emulator)))

    /** A scoped key whose global level is a field of [AppSettings]. */
    private class GlobalBinding<T>(val read: (AppSettings) -> T, val write: (AppSettings, T) -> AppSettings)

    @Suppress("UNCHECKED_CAST")
    private fun <T> bindingOf(key: ScopedKey<T>): GlobalBinding<T>? = bindings[key.id] as GlobalBinding<T>?

    private companion object {
        const val APP_KEY = "app"

        val bindings: Map<String, GlobalBinding<*>> = mapOf(
            ScopedSettings.VideoPreview.id to GlobalBinding(
                read = { it.videoPreview.enabled },
                write = { s, v: Boolean -> s.copy(videoPreview = s.videoPreview.copy(enabled = v)) },
            ),
            ScopedSettings.VideoDelaySeconds.id to GlobalBinding(
                read = { it.videoPreview.delaySeconds },
                write = { s, v: Int -> s.copy(videoPreview = s.videoPreview.copy(delaySeconds = v)) },
            ),
            ScopedSettings.Matching.id to GlobalBinding(
                read = { it.scraping.matching },
                write = { s, v -> s.copy(scraping = s.scraping.copy(matching = v)) },
            ),
        )
    }
}
