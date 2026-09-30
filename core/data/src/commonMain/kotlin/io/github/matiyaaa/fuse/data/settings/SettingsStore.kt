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
                val next = transform(AppSettingsCodec.decode(stored)).copy(version = AppSettings.CURRENT_VERSION)
                q.put(GLOBAL, "", KEY, AppSettingsCodec.encode(next, stored), clock())
                next
            }
        }
    }

    /** Restores every default (a user action in Settings -> Reset). */
    suspend fun reset(): AppSettings = update { AppSettings() }

    private fun read(): String? = q.get(GLOBAL, "", KEY).executeAsOneOrNull()

    private companion object {
        val GLOBAL = SettingScope.GLOBAL.name
        const val KEY = "app"
    }
}
