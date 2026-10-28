package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.emucorex.EmuCoreX
import io.github.matiyaaa.fuse.integrations.emucorex.ExCheat
import io.github.matiyaaa.fuse.integrations.emucorex.ExTexturePack
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.library.disc.DiscIdentity
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.EmuCoreXFinds
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementPreview
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.serializer

/**
 * PS2 texture packs and pnach cheats and patches from EmuCoreX's public catalogues on GitHub. A
 * game is matched by what its own disc says: packs by its serial, pnach files by the CRC PCSX2
 * gives it (both read from the image, as for a person's own files), never by its name. A pack is
 * fetched into Fuse's own folder and checked against the catalogue's size and SHA-256; packs in
 * pieces, or whose makers gave no permission to share them, are left to their own pages. Then the
 * same preview and restoration point as a person's own files apply ([NativeEnhancements]).
 */
internal class EmuCoreXMods(
    private val ctx: StoreContext,
    private val enhancements: NativeEnhancements,
    private val discIdentity: suspend (GameId) -> DiscIdentity?,
) {
    private val data = ctx.data

    private suspend fun fetchText(url: String): String? = try {
        kotlinx.coroutines.withTimeout(60_000) {
            val response = ctx.services.http.get(url)
            if (!response.status.isSuccess()) null else response.bodyAsText()
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** A catalogue, asked of GitHub at most once a day. */
    private suspend fun catalogue(url: String): String? {
        data.cache.get(CACHE, url, String.serializer(), ctx.now())?.let { return it }
        val body = fetchText(url) ?: return null
        data.cache.put(CACHE, url, body, String.serializer(), ctx.now(), DAY_MS)
        return body
    }

    suspend fun finds(id: GameId): EmuCoreXFinds {
        val game = data.games.get(id) ?: return none(null, null, "This game is no longer in the library.")
        if (game.platformId.value != "ps2") return none(null, null, "EmuCoreX's catalogues are for PS2 games.")
        val disc = discIdentity(id)
        val serial = disc?.serial ?: game.tags.serial
        val crc = disc?.crcText
        if (serial == null && crc == null) {
            return none(null, null, "Fuse couldn't read this game's disc for its serial and CRC, which is how EmuCoreX's packs and cheats are matched. It reads ISO, BIN, CHD and CSO images; this one doesn't say them.")
        }
        val kinds = enhancements.list(id).kinds
        val textures = catalogue(EmuCoreX.TEXTURES_URL)?.let(EmuCoreX::parseTextures)
        val cheats = if (crc == null) emptyList() else catalogue(EmuCoreX.CHEATS_URL)?.let(EmuCoreX::parseCheats)
        if (textures == null || cheats == null) return none(serial, crc, "EmuCoreX's catalogue couldn't be reached on GitHub. Check the connection and try again.")
        val packs = serial?.let { EmuCoreX.packsFor(textures, it) }.orEmpty()
            .map { it to EnhancementKind.TEXTURES.takeIf { k -> k in kinds } }
        val pnach = crc?.let { EmuCoreX.cheatsFor(cheats, it) }.orEmpty()
            .map { it to kindOf(it, kinds) }
            .sortedWith(compareBy({ if (it.first.isPatch) 0 else 1 }, { it.first.title.lowercase() }))
        return EmuCoreXFinds(serial, crc, packs, pnach)
    }

    private fun none(serial: String?, crc: String?, problem: String) = EmuCoreXFinds(serial, crc, emptyList(), emptyList(), problem)

    /** A patch goes in as a patch where the emulator keeps them apart, else as a cheat. */
    private fun kindOf(cheat: ExCheat, kinds: List<EnhancementKind>): EnhancementKind? = when {
        cheat.isPatch && EnhancementKind.PATCHES in kinds -> EnhancementKind.PATCHES
        EnhancementKind.CHEATS in kinds -> EnhancementKind.CHEATS
        else -> null
    }

    suspend fun fetch(id: GameId, entryId: String, onProgress: (Float) -> Unit): EnhancementPreview {
        val files = ctx.services.emulatorFiles ?: return EnhancementPreview.Unavailable("Not on this device", "EmuCoreX's packs and cheats can be added from Fuse on a computer.")
        val found = finds(id)
        found.problem?.let { return EnhancementPreview.Unavailable("Couldn't look in EmuCoreX's catalogue", it) }
        val pack = found.packs.firstOrNull { it.first.id == entryId }
        val pnach = found.pnach.firstOrNull { it.first.id == entryId }
        val kind = pack?.second ?: pnach?.second
            ?: return EnhancementPreview.Unavailable("Fuse can't add this here", "It isn't in EmuCoreX's catalogue for this game any more, or this game's emulator doesn't take it.")
        // One fetched file at a time: whatever an earlier preview left behind goes first.
        files.clearFetched()
        if (pack != null) {
            val p: ExTexturePack = pack.first
            p.notFetchable?.let { return EnhancementPreview.Unavailable("Fuse won't fetch this pack", "${p.name}: $it. Its page has it.") }
            val path = files.fetch(p.downloadUrl, fileName(p.downloadUrl), p.sizeBytes, null, EmuCoreX.MAX_PACK_BYTES, onProgress, sha256 = p.sha256)
                ?: return EnhancementPreview.Unavailable(
                    "Couldn't fetch ${p.name}",
                    "It didn't arrive whole, or wasn't what EmuCoreX's catalogue says it is (its size and SHA-256 didn't match), so Fuse kept nothing of it.",
                )
            return enhancements.plan(id, kind, path, p.name, "EmuCoreX", p.sourceUrl ?: EmuCoreX.HOME, p.version)
        }
        val c: ExCheat = pnach!!.first
        c.notFetchable?.let { return EnhancementPreview.Unavailable("Fuse won't fetch this file", "${c.title}: $it.") }
        val path = files.fetch(c.downloadUrl, fileName(c.downloadUrl), null, null, EmuCoreX.MAX_PNACH_BYTES, onProgress)
            ?: return EnhancementPreview.Unavailable("Couldn't fetch ${c.title}", "It didn't arrive from GitHub, so Fuse kept nothing of it. Try again later.")
        return enhancements.plan(id, kind, path, c.description?.takeIf { it.length <= NAME_MAX } ?: c.title, "EmuCoreX", c.sourceUrl ?: EmuCoreX.HOME, null)
    }

    private fun fileName(url: String) = url.substringBefore('?').substringAfterLast('/').ifEmpty { "download" }

    private companion object {
        const val CACHE = "emucorex"
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val NAME_MAX = 80
    }
}
