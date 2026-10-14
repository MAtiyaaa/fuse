package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.IdentityFact
import io.github.matiyaaa.fuse.data.repo.IdentityRepository
import io.github.matiyaaa.fuse.library.identity.ContentHash
import io.github.matiyaaa.fuse.library.identity.GameEvidence
import io.github.matiyaaa.fuse.library.identity.HashKind
import io.github.matiyaaa.fuse.library.identity.Ids
import io.github.matiyaaa.fuse.library.identity.LinkKind
import io.github.matiyaaa.fuse.library.identity.SerialId
import io.github.matiyaaa.fuse.model.Game

/** A library game as Fuse Identity sees it: its own titles (never a rename), serials, hashes and links. */
internal object GameIdentities {
    fun evidence(game: Game, facts: List<IdentityFact>): GameEvidence {
        val content = facts.filter { it.kind == IdentityRepository.SERIAL_CONTENT }.mapNotNull { Ids.serialOrNull(it.value) }
        val serials = content.map { SerialId(it, fromContent = true) } +
            listOfNotNull(Ids.serialOrNull(game.tags.serial)?.let { SerialId(it) })
        val hashes = facts.mapNotNull { f -> hashKind(f.kind)?.let { ContentHash.of(it, f.value) } }.toSet()
        val links = buildMap {
            game.links.rommRomId?.let { put(LinkKind.ROMM_ROM, it.toString()) }
            game.links.retroAchievementsGameId?.let { put(LinkKind.RA_GAME, it.toString()) }
            game.links.steamAppId?.let { put(LinkKind.STEAM_APP, it.toString()) }
            game.links.igdbId?.let { put(LinkKind.IGDB, it.toString()) }
            game.links.steamGridDbGameId?.let { put(LinkKind.STEAMGRIDDB, it.toString()) }
            game.appId?.let { put(LinkKind.ANDROID_PACKAGE, it) }
            facts.firstOrNull { it.kind == IdentityRepository.HLTB }?.let { put(LinkKind.HLTB, it.value) }
        }
        return GameEvidence(
            id = game.id.value,
            platform = game.platformId.value,
            fileName = io.github.matiyaaa.fuse.library.FsPath.name(game.location.path),
            title = game.titles.original,
            serials = serials.distinctBy { it.value },
            hashes = hashes,
            links = links,
            scannedPlatform = game.scannedPlatformId?.value,
        )
    }

    private fun hashKind(kind: String): HashKind? = when (kind) {
        IdentityRepository.MD5 -> HashKind.MD5
        IdentityRepository.SHA1 -> HashKind.SHA1
        IdentityRepository.CRC32 -> HashKind.CRC32
        IdentityRepository.RA -> HashKind.RA
        else -> null
    }
}
