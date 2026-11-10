package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everyone's saves kept on this device, written out for Fuse V2 (github.com/MAtiyaaa/fusev2) to
 * import: its Developer page, Import from Fuse V1, reads this folder, asks which of its profiles
 * each person here is, and keeps every save as that person's versions. Fuse V2 can't read Fuse's
 * own folder (Android keeps an app's folder from every other app), so the saves come out in
 * shared storage, plainly: a manifest ([MANIFEST]) and each file once, named by its SHA-256, in
 * `objects`. Nothing on this device changes.
 */
object FuseV2Export {
    /** The folder it is written to, in shared storage (Android) or the home folder (a computer). */
    const val FOLDER = "Fuse V1 Saves"

    /** The manifest, written last: a folder without it is an export cut short. */
    const val MANIFEST = "fuse-v1-saves.json"

    /** What Fuse V2 checks the manifest is. */
    const val KIND = "fuse-v1-saves"
    const val FORMAT = 1

    private val json = Json { encodeDefaults = true; prettyPrint = true }

    /** A person on this device: a profile of its own, or a host's it knows. */
    @Serializable
    data class Person(val id: String, val name: String, val avatar: String = "")

    /** Whose save is in an emulator's folder now, for one game's save of one kind. */
    @Serializable
    data class Holder(val person: String, val game: String, val kind: String)

    /**
     * One save of one person: its game ([GameKey.id], `platform:s.serial` or `platform:t.title`),
     * its files by their place in the save and SHA-256, and what it is ([from]): "kept-aside" (the
     * person's own while someone else played), "unsent" (made here and not yet on a host) or
     * "before-import" (what an import replaced). [at] is when it was made, in milliseconds (0 when
     * this device can't say: a save kept aside is the person's newest).
     */
    @Serializable
    data class Save(
        val person: String,
        val game: String,
        val title: String = "",
        val kind: String,
        val format: String,
        val at: Long = 0,
        val from: String,
        val files: List<SaveFile>,
    )

    @Serializable
    data class Manifest(
        val kind: String = KIND,
        val format: Int = FORMAT,
        val fuse: String,
        val device: String,
        val exportedAt: Long,
        /** The person playing on this device when it was written. */
        val current: String? = null,
        val people: List<Person>,
        /** The save folders chosen in Settings, Save folders, by emulator (a `tree:` one as picked). */
        val saveFolders: Map<String, String> = emptyMap(),
        val holders: List<Holder> = emptyList(),
        val saves: List<Save> = emptyList(),
        /** Files no save here had any more (counted, never invented). */
        val missing: Int = 0,
    )

    /** Where it goes on a device: shared storage on Android, the home folder on a computer. */
    fun target(platform: String): File {
        val root = if (platform == "ANDROID") System.getenv("EXTERNAL_STORAGE")?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0" else System.getProperty("user.home")
        return File(root, FOLDER)
    }

    /**
     * The saves [kept] holds, as the manifest lists them: everyone's kept aside, unsent and from
     * before an import, the same save once (a save kept aside that was also made here unsent is
     * the unsent one, with its time and title). A removed profile's saves aren't anyone's to take.
     */
    fun saves(kept: SyncDevice.KeptSaves): List<Save> {
        val out = ArrayList<Save>()
        fun add(s: Save) {
            if (s.person == ORPHAN || s.files.isEmpty()) return
            val same = out.indexOfFirst { it.person == s.person && it.game == s.game && it.kind == s.kind && it.format == s.format && it.files.sortedBy { f -> f.path } == s.files.sortedBy { f -> f.path } }
            if (same < 0) out += s else if (out[same].at == 0L && s.at != 0L) out[same] = s.copy(from = out[same].from)
        }
        for (r in kept.unsent) add(Save(r.profile, r.game, r.title, r.kind.name, r.manifest.format, r.at.millis, "unsent", r.manifest.files))
        for ((key, list) in kept.beforeImports) {
            val (person, game, kind) = split(key) ?: continue
            for (b in list) add(Save(person, game, b.title, kind, b.manifest.format, b.at, "before-import", b.manifest.files))
        }
        for ((key, manifest) in kept.parked) {
            val (person, game, kind) = split(key) ?: continue
            add(Save(person, game, "", kind, manifest.format, 0, "kept-aside", manifest.files))
        }
        return out
    }

    /** `person|platform:identity|KIND` as its three parts. */
    private fun split(key: String): Triple<String, String, String>? {
        val person = key.substringBefore('|', "")
        val kind = key.substringAfterLast('|', "")
        val game = key.removePrefix("$person|").removeSuffix("|$kind")
        return if (person.isEmpty() || kind.isEmpty() || game.isEmpty() || game == key) null else Triple(person, game, kind)
    }

    /** Whose save is in each emulator's folder now, from [kept]'s `platform:identity|KIND` keys. */
    fun holders(kept: SyncDevice.KeptSaves): List<Holder> = kept.holders.mapNotNull { (key, person) ->
        val kind = key.substringAfterLast('|', "")
        val game = key.removeSuffix("|$kind")
        if (kind.isEmpty() || game == key || person == ORPHAN) null else Holder(person, game, kind)
    }

    /**
     * Writes [manifest]'s saves into [folder]: every file once from [store] into `objects`, then
     * the manifest. Returns the manifest written (with how many files were missing).
     */
    fun write(folder: File, manifest: Manifest, store: ContentStore?): Manifest {
        val objects = File(folder, "objects").apply { mkdirs() }
        var missing = 0
        for (hash in manifest.saves.flatMap { s -> s.files.map { it.hash } }.distinct()) {
            if (!SavePath.isHash(hash)) continue
            val to = File(objects, hash)
            if (store != null && to.isFile && to.length() == store.size(hash)) continue
            val from = store?.open(hash)
            if (from == null) {
                missing++
                continue
            }
            val tmp = File(objects, ".$hash.tmp")
            from.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(to)) {
                to.delete()
                tmp.renameTo(to)
            }
        }
        val done = manifest.copy(missing = missing)
        writeAtomically(File(folder, MANIFEST), json.encodeToString(Manifest.serializer(), done).toByteArray())
        return done
    }
}
