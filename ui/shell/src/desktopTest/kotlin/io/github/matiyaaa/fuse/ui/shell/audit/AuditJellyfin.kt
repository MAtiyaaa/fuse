package io.github.matiyaaa.fuse.ui.shell.audit

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A made-up Jellyfin server for the audit, at [HOST]: a few films, shows and albums with invented
 * names and art drawn here, so renders never show a real library. It answers what Fuse asks
 * (sign-in, views, shelves, pages, search, pictures) and accepts reports.
 */
internal object AuditJellyfin {
    const val HOST = "media.example.com"
    const val USER = "Pat"

    private class Fx(
        val id: String,
        val name: String,
        val type: String,
        val year: Int? = null,
        val minutes: Int? = null,
        val parent: String? = null,
        val series: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val resumeMinutes: Int = 0,
        val played: Boolean = false,
        val favorite: Boolean = false,
        val overview: String? = null,
        val genres: List<String> = emptyList(),
        val rating: Double? = null,
        val official: String? = null,
        val artist: String? = null,
        val logo: Boolean = false,
        val children: Int? = null,
        val unplayed: Int? = null,
    )

    private val films = listOf(
        Fx("m1", "The Long Meridian", "Movie", 2024, 132, "lib-films", resumeMinutes = 48, favorite = true, logo = true, rating = 7.9, official = "12A", genres = listOf("Drama", "Adventure"),
            overview = "A surveyor walking the length of a forgotten line of longitude finds the towns along it remember her before she arrives."),
        Fx("m2", "Saltwater Hymn", "Movie", 2023, 104, "lib-films", rating = 7.2, official = "15", genres = listOf("Drama"), overview = "Two sisters reopen their late father's ferry for one last season."),
        Fx("m3", "Paper Comets", "Movie", 2022, 98, "lib-films", played = true, rating = 6.8, official = "PG", genres = listOf("Family", "Comedy"), overview = "A school science fair gets out of hand."),
        Fx("m4", "Northbound Static", "Movie", 2021, 117, "lib-films", logo = true, rating = 7.5, official = "15", genres = listOf("Thriller"), overview = "A night radio host takes a call from a station that closed thirty years ago."),
        Fx("m5", "Glasshouse Summer", "Movie", 2024, 109, "lib-films", rating = 7.0, official = "12A", genres = listOf("Romance"), overview = "A botanist and a glazier spend one summer restoring a ruined greenhouse."),
        Fx("m6", "Orchard of Lanterns", "Movie", 2020, 121, "lib-films", favorite = true, rating = 8.1, official = "PG", genres = listOf("Animation", "Fantasy"), overview = "Every autumn the orchard lights itself. This year it doesn't."),
        Fx("m7", "The Cartographer's Daughter", "Movie", 2019, 126, "lib-films", resumeMinutes = 21, rating = 7.4, official = "12A", genres = listOf("Mystery"), overview = "Her father's last map shows a coastline that isn't there."),
        Fx("m8", "Ninefold", "Movie", 2025, 141, "lib-films", logo = true, rating = 8.3, official = "15", genres = listOf("Science Fiction"), overview = "Nine copies of a starship wake at once, each certain it is the original."),
    ) + listOf(
        "Quiet Harbour", "Copper Sky", "The Ninth Bell", "Winter Arcade", "Lowland", "Signal Fires", "Midnight Orchard", "The Ferryman",
        "Kites over Kerala", "Second Sunrise", "Glass Rivers", "A Field Guide to Leaving", "Tin Soldiers", "The Last Lighthouse", "Fathom", "Overland",
    ).mapIndexed { i, n -> Fx("m${i + 9}", n, "Movie", 2008 + (i * 7) % 17, 88 + (i * 13) % 50, "lib-films", played = i % 5 == 0, rating = 6.0 + (i % 9) / 3.0, genres = listOf("Drama")) }

    private val shows = listOf(
        Fx("s1", "Harbour Lights", "Series", 2021, parent = "lib-shows", logo = true, children = 3, unplayed = 14, rating = 8.4, official = "15", genres = listOf("Drama", "Crime"),
            overview = "A coastal town's harbourmaster keeps its secrets, until a ship comes in that no one sent."),
        Fx("s2", "Quiet Engines", "Series", 2023, parent = "lib-shows", children = 2, unplayed = 6, rating = 7.8, official = "12", genres = listOf("Science Fiction"), overview = "Engineers keep a generation ship running long after anyone remembers where it's going."),
        Fx("s3", "The Understudy", "Series", 2024, parent = "lib-shows", children = 1, unplayed = 8, rating = 7.1, official = "15", genres = listOf("Comedy"), overview = "A theatre's understudy finally gets her chance, every night."),
        Fx("s4", "Low Orbit", "Series", 2020, parent = "lib-shows", children = 2, played = true, rating = 8.0, official = "PG", genres = listOf("Documentary"), overview = "The people who keep satellites in the sky."),
        Fx("s5", "Copperline", "Series", 2022, parent = "lib-shows", children = 1, unplayed = 10, rating = 7.6, official = "15", genres = listOf("Western"), overview = "A railway town on the edge of a copper boom."),
        Fx("s6", "Field Notes", "Series", 2018, parent = "lib-shows", children = 4, unplayed = 3, favorite = true, rating = 8.7, official = "U", genres = listOf("Nature"), overview = "A year in a single meadow, one season per series."),
    )

    private val episodeTitles = listOf("Arrivals", "The Tide Table", "Lantern Watch", "Salt and Iron", "A Quiet Berth", "Fog Signal", "The Manifest", "Slack Water")

    private fun seasonsOf(show: Fx) = (1..(show.children ?: 1)).map { n -> Fx("${show.id}s$n", "Season $n", "Season", series = show.id, season = n, parent = show.id, children = 8) }

    private fun episodesOf(show: Fx, season: Int) = episodeTitles.mapIndexed { i, t ->
        Fx(
            "${show.id}s${season}e${i + 1}", t, "Episode", show.year, 44 + i % 3 * 6, series = show.id, season = season, episode = i + 1,
            played = season == 1 && i < 2 && show.id == "s1",
            resumeMinutes = if (show.id == "s1" && season == 1 && i == 2) 19 else 0,
            overview = "${show.name} continues: ${t.lowercase()}.",
        )
    }

    private val albums = listOf(
        Fx("a1", "Night Ferry", "MusicAlbum", 2023, parent = "lib-music", artist = "Marlowe Vane", children = 9),
        Fx("a2", "Weather Systems", "MusicAlbum", 2021, parent = "lib-music", artist = "The Tidal Choir", children = 11),
        Fx("a3", "Paper Moons", "MusicAlbum", 2024, parent = "lib-music", artist = "Ada Lumen", children = 8),
        Fx("a4", "Coastal Radio", "MusicAlbum", 2019, parent = "lib-music", artist = "Marlowe Vane", children = 10),
        Fx("a5", "Lowlight", "MusicAlbum", 2022, parent = "lib-music", artist = "Kit Harrow", children = 7),
        Fx("a6", "Greenhouse Sessions", "MusicAlbum", 2020, parent = "lib-music", artist = "Ada Lumen", children = 6),
    )

    private val artists = listOf("Marlowe Vane", "The Tidal Choir", "Ada Lumen", "Kit Harrow").mapIndexed { i, n -> Fx("ar${i + 1}", n, "MusicArtist") }

    private fun tracksOf(album: Fx) = listOf("Departure Board", "Harbour Wall", "Night Ferry", "Signal Lamp", "Undertow", "Port Light", "Last Crossing", "Morning Pier", "Gulls", "Wake", "Coda")
        .take(album.children ?: 8).mapIndexed { i, t -> Fx("${album.id}t${i + 1}", t, "Audio", album.year, 3 + i % 3, parent = album.id, episode = i + 1, artist = album.artist) }

    private val people = listOf("Ines Calder" to "Mara Quill", "Tomas Reyes" to "The Surveyor", "Maya Okafor" to "Harbourmaster", "Jonah Pike" to "Elias Fenn", "Lena Varga" to "Dr. Ostrow", "Ravi Anand" to "Captain Hale")
        .mapIndexed { i, (n, _) -> Fx("p${i + 1}", n, "Person") }

    private val all: Map<String, Fx> by lazy {
        val eps = shows.flatMap { s -> seasonsOf(s).flatMap { se -> episodesOf(s, se.season!!) } }
        (films + shows + shows.flatMap { seasonsOf(it) } + eps + albums + albums.flatMap { tracksOf(it) } + artists + people).associateBy { it.id }
    }

    private val views = listOf(Triple("lib-films", "Films", "movies"), Triple("lib-shows", "Shows", "tvshows"), Triple("lib-music", "Music", "music"))

    // ---------------------------------------------------------------------------------- answers

    fun answer(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData? = with(scope) {
        if (request.url.host != HOST) return null
        val path = request.url.encodedPath.trimEnd('/')
        val p = request.url.parameters
        fun json(e: JsonElement) = respond(e.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        fun list(items: List<Fx>, total: Int = items.size, start: Int = 0) = json(buildJsonObject {
            put("Items", JsonArray(items.map { item(it, detail = false) }))
            put("TotalRecordCount", total)
            put("StartIndex", start)
        })
        val parts = path.trim('/').split('/')
        when {
            path.endsWith("/System/Info/Public") -> json(buildJsonObject { put("ServerName", "Living Room"); put("Version", "10.11.11"); put("Id", "srv-audit") })
            path.endsWith("/Users/AuthenticateByName") -> json(buildJsonObject {
                put("User", buildJsonObject { put("Id", "u1"); put("Name", USER) })
                put("AccessToken", "audit-token")
                put("ServerId", "srv-audit")
            })
            path.endsWith("/UserViews") -> json(buildJsonObject {
                put("Items", JsonArray(views.map { (id, name, kind) -> buildJsonObject { put("Id", id); put("Name", name); put("Type", "CollectionFolder"); put("CollectionType", kind); put("ImageTags", buildJsonObject { put("Primary", "v1") }) } }))
                put("TotalRecordCount", views.size)
            })
            path.endsWith("/UserItems/Resume") -> list(listOf(all.getValue("m1"), all.getValue("s1s1e3"), all.getValue("m7")))
            path.endsWith("/Shows/NextUp") -> {
                val ups = listOf("s1s1e3", "s2s1e1", "s3s1e1", "s5s1e1", "s6s4e6").map { all.getValue(it) }
                list(p["seriesId"]?.let { s -> ups.filter { it.series == s } } ?: ups)
            }
            path.endsWith("/Items/Latest") -> {
                val items = when (p["parentId"]) {
                    "lib-films" -> films.take(8).reversed()
                    "lib-shows" -> shows
                    "lib-music" -> albums
                    else -> listOf(films[7], shows[2], films[4], shows[1], films[0], films[5])
                }
                json(JsonArray(items.map { item(it, detail = false) }))
            }
            path.endsWith("/PlaybackInfo") -> respond("""{"ErrorCode":"NoCompatibleStream"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            "/Sessions/" in path || path.endsWith("/Sessions/Playing") -> respond("", HttpStatusCode.NoContent)
            "/UserFavoriteItems/" in path || "/UserPlayedItems/" in path -> json(buildJsonObject { put("IsFavorite", true) })
            parts.size >= 4 && parts[parts.size - 4] == "Items" && parts[parts.size - 2] == "Images" -> image(parts[parts.size - 3], parts.last())
            parts.size >= 5 && parts[parts.size - 5] == "Items" && parts[parts.size - 3] == "Images" -> image(parts[parts.size - 4], parts[parts.size - 2])
            path.endsWith("/Persons") -> list(people.filter { it.name.contains(p["searchTerm"].orEmpty(), true) })
            path.endsWith("/Artists") || path.endsWith("/Artists/AlbumArtists") -> list(artists.filter { it.name.contains(p["searchTerm"].orEmpty(), true) })
            parts.size >= 3 && parts[parts.size - 3] == "Shows" && parts.last() == "Seasons" -> list(seasonsOf(all.getValue(parts[parts.size - 2])))
            parts.size >= 3 && parts[parts.size - 3] == "Shows" && parts.last() == "Episodes" -> {
                val show = all.getValue(parts[parts.size - 2])
                val season = p["seasonId"]?.let { all[it]?.season }
                list(if (season != null) episodesOf(show, season) else seasonsOf(show).flatMap { episodesOf(show, it.season!!) })
            }
            parts.size >= 2 && parts[parts.size - 2] == "Items" -> all[parts.last()]?.let { json(item(it, detail = true)) } ?: respond("", HttpStatusCode.NotFound)
            path.endsWith("/Items") -> {
                val parent = p["parentId"]
                val term = p["searchTerm"]
                val types = p["includeItemTypes"]?.split(',')?.toSet()
                var items: List<Fx> = when {
                    p["personIds"] != null -> films.take(5) + shows.take(2)
                    p["albumArtistIds"] != null -> albums.filter { a -> artists.firstOrNull { it.id == p["albumArtistIds"] }?.name == a.artist }
                    parent != null && parent.startsWith("a") -> tracksOf(all.getValue(parent))
                    parent == "lib-films" -> films
                    parent == "lib-shows" -> shows
                    parent == "lib-music" -> albums
                    else -> all.values.toList()
                }
                if (types != null) items = items.filter { it.type in types }
                if (term != null) items = items.filter { it.name.contains(term, true) || (it.series?.let { s -> all[s]?.name?.contains(term, true) } ?: false) }
                when (p["filters"]) {
                    "IsFavorite" -> items = items.filter { it.favorite }
                    "IsUnplayed" -> items = items.filter { !it.played }
                }
                if (p["sortBy"]?.startsWith("DateCreated") == true || p["sortBy"]?.startsWith("PremiereDate") == true) items = items.sortedByDescending { it.year ?: 0 }
                else if (p["sortBy"]?.startsWith("SortName") == true) items = items.sortedBy { it.name.removePrefix("The ") }
                val start = p["startIndex"]?.toIntOrNull() ?: 0
                val limit = p["limit"]?.toIntOrNull() ?: 60
                list(items.drop(start).take(limit), items.size, start)
            }
            else -> null
        }
    }

    private fun item(f: Fx, detail: Boolean): JsonObject = buildJsonObject {
        put("Id", f.id)
        put("Name", f.name)
        put("Type", f.type)
        f.year?.let { put("ProductionYear", it) }
        f.minutes?.let { put("RunTimeTicks", it * 60L * 10_000_000L) }
        f.parent?.let { put("ParentId", it) }
        f.series?.let { s ->
            put("SeriesId", s)
            put("SeriesName", all[s]?.name)
            put("SeriesPrimaryImageTag", "p")
            put("ParentBackdropItemId", s)
            put("ParentBackdropImageTags", buildJsonArray { add(JsonPrimitive("b")) })
            if (all[s]?.logo == true) {
                put("ParentLogoItemId", s)
                put("ParentLogoImageTag", "l")
            }
        }
        if (f.type == "Episode") {
            put("SeasonId", "${f.series}s${f.season}")
            put("SeasonName", "Season ${f.season}")
        }
        f.season?.let { put("ParentIndexNumber", it) }
        f.episode?.let { put("IndexNumber", it) }
        if (f.type == "Season") put("IndexNumber", f.season)
        f.children?.let { put("ChildCount", it) }
        f.rating?.let { put("CommunityRating", it) }
        f.official?.let { put("OfficialRating", it) }
        if (f.genres.isNotEmpty()) put("Genres", JsonArray(f.genres.map { JsonPrimitive(it) }))
        f.artist?.let { a ->
            put("AlbumArtist", a)
            put("Artists", buildJsonArray { add(JsonPrimitive(a)) })
        }
        if (f.type == "Audio") {
            put("AlbumId", f.parent)
            put("Album", all[f.parent]?.name)
            put("AlbumPrimaryImageTag", "p")
        }
        put("ImageTags", buildJsonObject {
            if (f.type != "Audio") put("Primary", "p")
            if (f.logo) put("Logo", "l")
        })
        if (f.type == "Movie" || f.type == "Series") put("BackdropImageTags", buildJsonArray { add(JsonPrimitive("b")) })
        put("UserData", buildJsonObject {
            put("PlaybackPositionTicks", f.resumeMinutes * 60L * 10_000_000L)
            put("Played", f.played)
            put("IsFavorite", f.favorite)
            f.unplayed?.let { put("UnplayedItemCount", it) }
        })
        if (f.type == "Movie" || f.type == "Series") put("PrimaryImageAspectRatio", 2.0 / 3.0)
        if (detail) {
            f.overview?.let { put("Overview", it) }
            if (f.type == "Movie") put("Taglines", buildJsonArray { add(JsonPrimitive("Some lines are only walked once.")) })
            if (f.type == "Movie" || f.type == "Series" || f.type == "Episode") {
                put("People", JsonArray(people.mapIndexed { i, pp ->
                    buildJsonObject {
                        put("Name", pp.name)
                        put("Id", pp.id)
                        put("Role", listOf("Mara Quill", "The Surveyor", "Harbourmaster", "Elias Fenn", "Dr. Ostrow", "Captain Hale")[i])
                        put("Type", "Actor")
                        put("PrimaryImageTag", "p")
                    }
                } + buildJsonObject { put("Name", "Sofia Brandt"); put("Id", "p9"); put("Type", "Director") }))
                put("Studios", buildJsonArray { add(buildJsonObject { put("Name", "Northlight Pictures") }) })
            }
            if (f.type == "Movie" || f.type == "Episode") {
                put("MediaSources", buildJsonArray {
                    add(buildJsonObject {
                        put("Id", "ms-${f.id}")
                        put("Container", "mkv")
                        put("MediaStreams", buildJsonArray {
                            add(buildJsonObject { put("Type", "Video"); put("Index", 0); put("Codec", "hevc"); put("Width", 3840); put("Height", 2160); put("VideoRangeType", "HDR10") })
                            add(buildJsonObject { put("Type", "Audio"); put("Index", 1); put("Codec", "eac3"); put("Channels", 6); put("Language", "eng"); put("IsDefault", true) })
                        })
                    })
                })
            }
        }
    }

    // --------------------------------------------------------------------------------- pictures

    private val pictures = ConcurrentHashMap<String, ByteArray>()

    private fun MockRequestHandleScope.image(id: String, kind: String): HttpResponseData {
        val bytes = pictures.getOrPut("$id/$kind") { draw(id, kind) }
        return respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
    }

    private val palette = listOf(0xFF3E5C9A, 0xFF7A4E9C, 0xFF2F7D74, 0xFFA14A4A, 0xFF4F7FB0, 0xFFB0853A, 0xFF5D6B8C, 0xFF8A3F6B)

    private fun draw(id: String, kind: String): ByteArray {
        val f = all[id]
        val name = f?.name ?: views.firstOrNull { it.first == id }?.second ?: id
        val base = Color(palette[(name.hashCode() and 0x7FFFFFFF) % palette.size].toInt(), true)
        val (w, h) = when {
            kind == "Backdrop" -> 1280 to 720
            kind == "Logo" -> 0 to 0
            id.startsWith("lib-") -> 640 to 360
            f?.type == "Episode" -> 640 to 360
            f?.type == "MusicAlbum" || f?.type == "Audio" || f?.type == "MusicArtist" || f?.type == "Person" -> 512 to 512
            else -> 400 to 600
        }
        val img = if (kind == "Logo") logo(name) else scene(w, h, base, name, title = kind == "Primary" && (f?.type == "Movie" || f?.type == "Series" || f?.type == "MusicAlbum"), person = f?.type == "Person" || f?.type == "MusicArtist")
        return ByteArrayOutputStream().use { out -> ImageIO.write(img, "png", out); out.toByteArray() }
    }

    /** A calm landscape in the item's colour: a sky, a low sun and two ranges of hills; a poster also gets its title. */
    private fun scene(w: Int, h: Int, base: Color, name: String, title: Boolean, person: Boolean): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, base.brighter(), 0f, h.toFloat(), base.darker().darker())
        g.fillRect(0, 0, w, h)
        if (person) {
            g.color = Color(255, 255, 255, 70)
            g.fill(Ellipse2D.Float(w * 0.32f, h * 0.2f, w * 0.36f, w * 0.36f))
            g.fill(Ellipse2D.Float(w * 0.16f, h * 0.62f, w * 0.68f, h * 0.7f))
            g.dispose()
            return img
        }
        val seed = name.hashCode()
        g.color = Color(255, 236, 200, 150)
        val sun = minOf(w, h) * 0.22f
        g.fill(Ellipse2D.Float(w * (0.55f + (seed and 7) / 40f), h * 0.28f, sun, sun))
        for ((layer, alpha) in listOf(0 to 120, 1 to 200)) {
            val path = Path2D.Float()
            path.moveTo(0f, h.toFloat())
            val y = h * (0.62f + layer * 0.12f)
            var x = 0f
            var i = 0
            while (x <= w) {
                path.lineTo(x, y - ((seed shr (i % 16)) and 3) * h * 0.035f - (if (i % 2 == 0) h * 0.05f else 0f))
                x += w / 6f
                i++
            }
            path.lineTo(w.toFloat(), h.toFloat())
            path.closePath()
            g.color = Color(base.darker().darker().red / (layer + 1), base.darker().darker().green / (layer + 1), base.darker().darker().blue / (layer + 1), alpha)
            g.fill(path)
        }
        if (title) {
            g.color = Color(255, 255, 255, 230)
            g.font = Font(Font.SERIF, Font.BOLD, (w / 11).coerceAtLeast(18))
            val lines = wrap(name.uppercase(), g.fontMetrics, (w * 0.84f).toInt())
            lines.forEachIndexed { i, line ->
                val lw = g.fontMetrics.stringWidth(line)
                g.drawString(line, (w - lw) / 2, (h * 0.12f).toInt() + g.fontMetrics.ascent + i * g.fontMetrics.height)
            }
            g.stroke = BasicStroke(2f)
            g.color = Color(255, 255, 255, 120)
            g.drawLine((w * 0.42f).toInt(), (h * 0.12f).toInt() + lines.size * g.fontMetrics.height + 14, (w * 0.58f).toInt(), (h * 0.12f).toInt() + lines.size * g.fontMetrics.height + 14)
        }
        g.dispose()
        return img
    }

    private fun wrap(text: String, fm: java.awt.FontMetrics, width: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (fm.stringWidth(next) > width && line.isNotEmpty()) {
                out += line
                line = word
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    private fun logo(name: String): BufferedImage {
        val font = Font(Font.SERIF, Font.BOLD, 120)
        val width = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().run { getFontMetrics(font).stringWidth(name.uppercase()).also { dispose() } }
        val img = BufferedImage(width + 24, 160, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.font = font
        g.drawString(name.uppercase(), 12, 124)
        g.dispose()
        return img
    }
}
