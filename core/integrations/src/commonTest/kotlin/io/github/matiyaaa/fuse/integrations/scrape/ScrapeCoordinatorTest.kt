package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.igdb.IgdbClient
import io.github.matiyaaa.fuse.integrations.igdb.IgdbCredentials
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.integrations.steamgriddb.SteamGridDbClient
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScrapeCoordinatorTest {

    private val query = ScrapeQuery(
        title = "Super Metroid",
        platform = PlatformId("snes"),
        platformName = "Super Nintendo",
        fileName = "Super Metroid (Japan, USA) (En,Ja).sfc",
    )

    /** A stand-in provider for coordinator logic; provider clients have their own transport tests. */
    private class StubSource(
        override val id: ScrapeProviderId,
        override val providesMetadata: Boolean,
        private val results: ApiResult<List<ProviderGame>>,
        private val art: List<ArtworkOption> = emptyList(),
    ) : ScrapeSource {
        var searches = 0
        override suspend fun search(query: ScrapeQuery): ApiResult<List<ProviderGame>> {
            searches++
            return results
        }
        override suspend fun artwork(game: ProviderGame, query: ScrapeQuery, kinds: Set<MediaKind>) =
            ApiResult.Success(art.filter { it.kind in kinds })
    }

    private fun game(provider: ScrapeProviderId, id: String, title: String, platforms: List<String> = emptyList()) = ProviderGame(
        provider = provider,
        providerGameId = id,
        title = title,
        platformNames = platforms,
        metadata = GameMetadata(description = "About $title", source = MetadataSource.IGDB),
    )

    private fun art(provider: ScrapeProviderId, kind: MediaKind, url: String) =
        ArtworkOption(provider, kind, url, null, null, null)

    private fun request(vararg priority: ScrapeProviderId, strictness: MatchStrictness = MatchStrictness.NORMAL, kinds: Set<MediaKind> = emptySet()) =
        ScrapeRequest(
            query = query,
            priority = priority.toList(),
            configured = priority.toSet(),
            strictness = strictness,
            artworkKinds = kinds,
        )

    @Test
    fun acceptsConfidentMatchAndGathersArtwork() = runTest {
        val igdb = StubSource(
            ScrapeProviderId.IGDB, true,
            ApiResult.Success(listOf(game(ScrapeProviderId.IGDB, "1103", "Super Metroid", listOf("SNES")))),
            listOf(art(ScrapeProviderId.IGDB, MediaKind.BOXART, "https://igdb/cover.jpg")),
        )
        val sgdb = StubSource(
            ScrapeProviderId.STEAMGRIDDB, false,
            ApiResult.Success(listOf(game(ScrapeProviderId.STEAMGRIDDB, "5247", "Super Metroid"))),
            listOf(art(ScrapeProviderId.STEAMGRIDDB, MediaKind.HERO, "https://sgdb/hero.png"), art(ScrapeProviderId.STEAMGRIDDB, MediaKind.LOGO, "https://sgdb/logo.png")),
        )
        val coordinator = ScrapeCoordinator(listOf(igdb, sgdb))
        val outcome = coordinator.scrape(request(ScrapeProviderId.STEAMGRIDDB, ScrapeProviderId.IGDB, kinds = setOf(MediaKind.BOXART, MediaKind.HERO)))
        assertIs<ScrapeOutcome.Accepted>(outcome)
        // Metadata providers are asked first even when an artwork-only provider is higher in the list.
        assertEquals(ScrapeProviderId.IGDB, outcome.candidate.provider)
        assertEquals("About Super Metroid", outcome.metadata?.description)
        assertEquals(setOf("https://igdb/cover.jpg", "https://sgdb/hero.png"), outcome.artwork.map { it.url }.toSet())
        assertTrue(outcome.artwork.none { it.kind == MediaKind.LOGO })
    }

    @Test
    fun ambiguousResultsNeedReview() = runTest {
        val igdb = StubSource(
            ScrapeProviderId.IGDB, true,
            ApiResult.Success(
                listOf(
                    game(ScrapeProviderId.IGDB, "1", "Super Metroid", listOf("SNES")),
                    game(ScrapeProviderId.IGDB, "2", "Super Metroid", listOf("SNES")),
                ),
            ),
        )
        val outcome = ScrapeCoordinator(listOf(igdb)).scrape(request(ScrapeProviderId.IGDB))
        assertIs<ScrapeOutcome.NeedsReview>(outcome)
        assertEquals(2, outcome.candidates.size)
        assertTrue(outcome.candidates.all { it.confidence in 0f..1f && it.reasons.isNotEmpty() })
    }

    @Test
    fun unconfiguredProvidersAreSkippedAndFailuresReported() = runTest {
        val igdb = StubSource(ScrapeProviderId.IGDB, true, ApiResult.Success(emptyList()))
        val tgdb = StubSource(ScrapeProviderId.THEGAMESDB, true, ApiResult.NetworkError("offline"))
        val coordinator = ScrapeCoordinator(listOf(igdb, tgdb))
        val notConfigured = coordinator.scrape(
            ScrapeRequest(query, listOf(ScrapeProviderId.IGDB, ScrapeProviderId.THEGAMESDB), configured = setOf(ScrapeProviderId.THEGAMESDB)),
        )
        assertEquals(0, igdb.searches)
        assertIs<ScrapeOutcome.ProviderErrors>(notConfigured)
        assertEquals(ScrapeProviderId.THEGAMESDB, notConfigured.errors.single().provider)

        val notFound = coordinator.scrape(request(ScrapeProviderId.IGDB, ScrapeProviderId.THEGAMESDB))
        assertIs<ScrapeOutcome.NotFound>(notFound)
        assertEquals(listOf(ScrapeProviderId.IGDB), notFound.searched)
        assertEquals(1, notFound.errors.size)
    }

    @Test
    fun userPickedCandidateIsResolved() = runTest {
        val igdb = StubSource(
            ScrapeProviderId.IGDB, true,
            ApiResult.Success(listOf(game(ScrapeProviderId.IGDB, "1", "Super Metroid", listOf("SNES")), game(ScrapeProviderId.IGDB, "2", "Super Metroid", listOf("SNES")))),
            listOf(art(ScrapeProviderId.IGDB, MediaKind.BOXART, "https://igdb/cover.jpg")),
        )
        val coordinator = ScrapeCoordinator(listOf(igdb))
        val req = request(ScrapeProviderId.IGDB, kinds = setOf(MediaKind.BOXART))
        val review = coordinator.scrape(req) as ScrapeOutcome.NeedsReview
        val picked = review.candidates.first { it.providerGameId == "2" }
        val accepted = coordinator.accept(req, picked)
        assertIs<ScrapeOutcome.Accepted>(accepted)
        assertEquals("2", accepted.candidate.providerGameId)
        assertEquals(listOf("https://igdb/cover.jpg"), accepted.artwork.map { it.url })
    }

    @Test
    fun realClientsThroughAdapters() = runTest {
        val http = TestHttp { request ->
            when (request.url.host) {
                "id.twitch.tv" -> json("""{"access_token":"t","expires_in":3600,"token_type":"bearer"}""")
                "api.igdb.com" -> json(
                    """[{"id":1103,"name":"Super Metroid","first_release_date":764553600,"summary":"Samus.",
                        "cover":{"image_id":"co1tqi"},"platforms":[{"abbreviation":"SNES","name":"Super Nintendo Entertainment System"}],
                        "genres":[{"name":"Platform"}],"involved_companies":[{"company":{"name":"Nintendo"},"developer":true,"publisher":true}]}]""",
                )
                else -> when {
                    "autocomplete" in request.url.encodedPath -> json("""{"success":true,"data":[{"id":5247,"name":"Super Metroid"}]}""")
                    else -> json(
                        """{"success":true,"page":0,"total":1,"limit":20,"data":[{"id":1,"score":5,"style":"alternate",
                            "width":1920,"height":620,"url":"https://cdn2.steamgriddb.com/hero/a.png","thumb":"https://cdn2.steamgriddb.com/thumb/a.jpg"}]}""",
                    )
                }
            }
        }
        val coordinator = ScrapeCoordinator(
            listOf(
                IgdbSource(IgdbClient(http.client, IgdbCredentials("id", "secret"), RateLimiter.unlimited())),
                SteamGridDbSource(SteamGridDbClient(http.client, "key", RateLimiter.unlimited())),
            ),
        )
        val outcome = coordinator.scrape(request(ScrapeProviderId.IGDB, ScrapeProviderId.STEAMGRIDDB, kinds = setOf(MediaKind.BOXART, MediaKind.HERO)))
        assertIs<ScrapeOutcome.Accepted>(outcome)
        assertEquals("1103", outcome.candidate.providerGameId)
        assertEquals(1994, outcome.metadata?.releaseYear)
        assertEquals("Nintendo", outcome.metadata?.developer)
        assertEquals(listOf("Platform"), outcome.metadata?.genres)
        val kinds = outcome.artwork.associate { it.kind to it.url }
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big/co1tqi.jpg", kinds[MediaKind.BOXART])
        assertEquals("https://cdn2.steamgriddb.com/hero/a.png", kinds[MediaKind.HERO])
        assertTrue(http.requests.any { "/heroes/game/5247" in it.url.encodedPath })
    }
}
