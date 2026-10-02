package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.data.DataJson
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.decodeOrNull
import io.github.matiyaaa.fuse.data.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * What Fuse itself changed in an emulator's own files (the PCSX2 patches it turned on), by file, so
 * it only ever undoes its own changes. Kept on this device: backups leave it out, since on another
 * computer the same setting may be the user's own.
 */
class OwnedChangesRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), SetSerializer(String.serializer()))

    /** What Fuse changed in [file], under [kind] ("pcsx2.patches"). */
    suspend fun get(kind: String, file: String): Set<String> = withContext(dispatcher) { read(kind)[file].orEmpty() }

    suspend fun set(kind: String, file: String, values: Set<String>) = mutex.withLock {
        withContext(dispatcher) {
            db.transaction {
                val all = read(kind).toMutableMap()
                if (values.isEmpty()) all.remove(file) else all[file] = values
                db.settingQueries.put("GLOBAL", "", key(kind), DataJson.encodeToString(serializer, all), clock())
            }
        }
    }

    private fun read(kind: String): Map<String, Set<String>> =
        decodeOrNull(serializer, db.settingQueries.get("GLOBAL", "", key(kind)).executeAsOneOrNull()).orEmpty()

    private fun key(kind: String) = "owned.$kind"
}
