package com.iptv.player4k.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.Reader
import java.net.URLEncoder

/** Cliente da API Xtream Codes (player_api.php) para filmes, séries e guia. */
class XtreamApi(creds: XtreamCreds, userAgent: String = "") {
    constructor(p: Playlist) : this(
        XtreamCreds(Playlist.normalizeServer(p.server), p.username.trim(), p.password.trim()), p.userAgent,
    )

    val base = creds.server.trimEnd('/')
    private val user = creds.username
    private val pass = creds.password
    private val userAgent = userAgent.ifBlank { Http.DEFAULT_USER_AGENT }

    fun apiUrl(action: String, extra: String = "") =
        "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=$action$extra"

    fun movieUrl(id: String, ext: String?) = "$base/movie/$user/$pass/$id.${ext.orDefault()}"
    fun episodeUrl(id: String, ext: String?) = "$base/series/$user/$pass/$id.${ext.orDefault()}"
    fun xmltvUrl() = "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

    private fun String?.orDefault() = this?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() } ?: "mp4"

    fun download(action: String, dest: File) {
        val tmp = File(dest.path + ".tmp")
        Http.playlistClient.newCall(request(apiUrl(action))).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Resposta vazia")
            body.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
    }

    fun fetchString(action: String, extra: String): String =
        Http.playlistClient.newCall(request(apiUrl(action, extra))).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("Resposta vazia")
        }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", userAgent).build()

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        // ------------- leitura em streaming das listas grandes -------------

        private fun readScalar(jr: JsonReader): String? = when (jr.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> jr.nextString()
            JsonToken.BOOLEAN -> jr.nextBoolean().toString()
            JsonToken.NULL -> { jr.nextNull(); null }
            JsonToken.BEGIN_ARRAY -> {
                var first: String? = null
                jr.beginArray()
                while (jr.hasNext()) {
                    val v = readScalar(jr)
                    if (first == null && !v.isNullOrBlank()) first = v
                }
                jr.endArray()
                first
            }
            else -> { jr.skipValue(); null }
        }

        private fun readFlat(jr: JsonReader): Map<String, String?> {
            val m = HashMap<String, String?>(32)
            jr.beginObject()
            while (jr.hasNext()) m[jr.nextName()] = readScalar(jr)
            jr.endObject()
            return m
        }

        /** Lê um array de objetos (ou objeto de objetos, usado por alguns painéis). */
        private fun forEachObject(r: Reader, block: (Map<String, String?>) -> Unit) {
            val jr = JsonReader(r)
            jr.isLenient = true
            when (jr.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        if (jr.peek() == JsonToken.BEGIN_OBJECT) block(readFlat(jr)) else jr.skipValue()
                    }
                    jr.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    jr.beginObject()
                    while (jr.hasNext()) {
                        jr.nextName()
                        if (jr.peek() == JsonToken.BEGIN_OBJECT) block(readFlat(jr)) else jr.skipValue()
                    }
                    jr.endObject()
                }
                else -> Unit
            }
        }

        fun parseCategories(r: Reader): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            forEachObject(r) { m ->
                val id = m["category_id"] ?: return@forEachObject
                out[id] = m["category_name"]?.trim()?.ifEmpty { null } ?: "Outros"
            }
            return out
        }

        fun parseVodStreams(r: Reader, cats: Map<String, String>, api: XtreamApi): List<VodItem> {
            val out = ArrayList<VodItem>()
            forEachObject(r) { m ->
                val id = m["stream_id"] ?: return@forEachObject
                val raw = (m["name"] ?: m["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachObject
                val (title, year) = LibraryBuilder.splitYear(raw)
                out += VodItem(
                    id = "x:$id",
                    name = title,
                    url = api.movieUrl(id, m["container_extension"]),
                    poster = m["stream_icon"]?.takeIf { it.startsWith("http") },
                    category = cats[m["category_id"]] ?: "Outros",
                    year = year ?: m["year"]?.take(4)?.takeIf { it.all(Char::isDigit) },
                    rating = rating(m["rating"]),
                    added = m["added"]?.toLongOrNull() ?: 0L,
                    xtreamId = id.toIntOrNull(),
                    age = ageOf(m["age"] ?: m["mpaa_rating"] ?: m["rating_mpaa"] ?: m["certification"]),
                    trailer = com.iptv.player4k.util.Trailer.youtubeId(m["youtube_trailer"] ?: m["trailer"]),
                )
            }
            return out
        }

        fun parseSeries(r: Reader, cats: Map<String, String>): List<SeriesItem> {
            val out = ArrayList<SeriesItem>()
            forEachObject(r) { m ->
                val id = m["series_id"] ?: return@forEachObject
                val raw = m["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachObject
                val (title, year) = LibraryBuilder.splitYear(raw)
                out += SeriesItem(
                    id = "xs:$id",
                    name = title,
                    poster = m["cover"]?.takeIf { it.startsWith("http") },
                    category = cats[m["category_id"]] ?: "Outros",
                    year = year ?: (m["releaseDate"] ?: m["release_date"])?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                    rating = rating(m["rating"]),
                    added = m["last_modified"]?.toLongOrNull() ?: 0L,
                    xtreamId = id.toIntOrNull(),
                    plot = m["plot"]?.takeIf { it.isNotBlank() },
                    genre = m["genre"]?.takeIf { it.isNotBlank() },
                    backdrop = m["backdrop_path"]?.takeIf { it.startsWith("http") },
                    age = ageOf(m["age"] ?: m["mpaa_rating"] ?: m["rating_mpaa"] ?: m["certification"]),
                    trailer = com.iptv.player4k.util.Trailer.youtubeId(m["youtube_trailer"] ?: m["trailer"]),
                )
            }
            return out
        }

        private fun ageOf(s: String?): Int? = com.iptv.player4k.security.AgeRating.parse(s)

        private fun rating(s: String?): Float? = s?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f }

        // ------------- detalhes (respostas pequenas) -------------

        private fun JsonElement?.str(): String? = when {
            this == null || isJsonNull -> null
            isJsonPrimitive -> asString.takeIf { it.isNotBlank() }
            isJsonArray -> asJsonArray.firstNotNullOfOrNull { it.str() }
            else -> null
        }

        private fun JsonElement?.obj(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null

        private fun durationSecs(info: JsonObject?): Int {
            info ?: return 0
            info.get("duration_secs").str()?.toIntOrNull()?.let { return it }
            val d = info.get("duration").str() ?: return 0
            val parts = d.split(':').mapNotNull { it.trim().toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0] * 60
                else -> 0
            }
        }

        fun parseVodInfo(json: String): MediaDetails {
            val root = JsonParser.parseString(json).obj() ?: return MediaDetails()
            val info = root.get("info").obj()
            return MediaDetails(
                plot = info?.get("plot").str() ?: info?.get("description").str(),
                cast = info?.get("cast").str() ?: info?.get("actors").str(),
                director = info?.get("director").str(),
                genre = info?.get("genre").str(),
                year = (info?.get("releasedate").str() ?: info?.get("release_date").str())?.take(4),
                rating = rating(info?.get("rating").str()),
                durationSecs = durationSecs(info),
                backdrop = info?.get("backdrop_path").str()?.takeIf { it.startsWith("http") },
                poster = (info?.get("movie_image").str() ?: info?.get("cover_big").str())?.takeIf { it.startsWith("http") },
                age = ageOf(info?.get("age").str() ?: info?.get("mpaa_rating").str() ?: info?.get("rating_mpaa").str() ?: info?.get("certification").str()),
                trailer = trailerOf(info),
            )
        }

        private fun trailerOf(info: JsonObject?): String? =
            com.iptv.player4k.util.Trailer.youtubeId(info?.get("youtube_trailer").str() ?: info?.get("trailer").str())

        fun parseSeriesInfo(json: String, api: XtreamApi): MediaDetails {
            val root = JsonParser.parseString(json).obj() ?: return MediaDetails()
            val info = root.get("info").obj()
            val episodes = ArrayList<Episode>()
            fun addEpisode(e: JsonObject, seasonKey: Int) {
                val id = e.get("id").str() ?: return
                val season = e.get("season").str()?.toIntOrNull() ?: seasonKey
                val num = e.get("episode_num").str()?.toIntOrNull() ?: (episodes.count { it.season == season } + 1)
                val ei = e.get("info").obj()
                val rawTitle = e.get("title").str() ?: "Episódio $num"
                val title = LibraryBuilder.parseEpisode(rawTitle)?.title?.takeIf { it.isNotBlank() } ?: rawTitle
                episodes += Episode(
                    id = id, season = season, number = num, title = title,
                    url = api.episodeUrl(id, e.get("container_extension").str()),
                    image = ei?.get("movie_image").str()?.takeIf { it.startsWith("http") },
                    plot = ei?.get("plot").str(),
                    durationSecs = durationSecs(ei),
                )
            }
            when (val eps = root.get("episodes")) {
                is JsonObject -> eps.entrySet().forEach { (k, v) ->
                    if (v is JsonArray) v.forEach { it.obj()?.let { e -> addEpisode(e, k.toIntOrNull() ?: 1) } }
                }
                is JsonArray -> eps.forEachIndexed { i, v ->
                    when (v) {
                        is JsonArray -> v.forEach { it.obj()?.let { e -> addEpisode(e, i + 1) } }
                        is JsonObject -> addEpisode(v, 1)
                        else -> Unit
                    }
                }
                else -> Unit
            }
            episodes.sortWith(compareBy({ it.season }, { it.number }))
            return MediaDetails(
                plot = info?.get("plot").str(),
                cast = info?.get("cast").str(),
                director = info?.get("director").str(),
                genre = info?.get("genre").str(),
                year = (info?.get("releaseDate").str() ?: info?.get("release_date").str())?.take(4),
                rating = rating(info?.get("rating").str()),
                backdrop = info?.get("backdrop_path").str()?.takeIf { it.startsWith("http") },
                poster = info?.get("cover").str()?.takeIf { it.startsWith("http") },
                episodes = episodes,
                trailer = trailerOf(info),
                age = ageOf(info?.get("age").str() ?: info?.get("mpaa_rating").str() ?: info?.get("rating_mpaa").str() ?: info?.get("certification").str()),
            )
        }
    }
}
