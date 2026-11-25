package com.iptv.player4k.data

import com.iptv.player4k.util.Text

class VodItem(
    val id: String,
    val name: String,
    val url: String,
    val poster: String?,
    val category: String,
    val year: String? = null,
    val rating: Float? = null,
    /** Data em que entrou no servidor (segundos, Xtream). */
    val added: Long = 0L,
    val xtreamId: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val plot: String? = null,
    val backdrop: String? = null,
) {
    val searchKey = Text.normalize(name)
}

class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val image: String? = null,
    val plot: String? = null,
    val durationSecs: Int = 0,
    val headers: Map<String, String> = emptyMap(),
)

class SeriesItem(
    val id: String,
    val name: String,
    val poster: String?,
    val category: String,
    val year: String? = null,
    val rating: Float? = null,
    val added: Long = 0L,
    val xtreamId: Int? = null,
    val plot: String? = null,
    val genre: String? = null,
    val backdrop: String? = null,
    /** Episódios já conhecidos (listas M3U). No Xtream vêm sob demanda. */
    val episodes: List<Episode> = emptyList(),
) {
    val searchKey = Text.normalize(name)
}

/** Detalhes extras (sinopse, elenco…) e episódios carregados sob demanda. */
class MediaDetails(
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val rating: Float? = null,
    val durationSecs: Int = 0,
    val backdrop: String? = null,
    val poster: String? = null,
    val episodes: List<Episode> = emptyList(),
)

class Library(
    val movies: List<VodItem>,
    val movieCategories: List<String>,
    val series: List<SeriesItem>,
    val seriesCategories: List<String>,
) {
    val isEmpty get() = movies.isEmpty() && series.isEmpty()
    private val movieById by lazy { movies.associateBy { it.id } }
    private val seriesById by lazy { series.associateBy { it.id } }
    fun movie(id: String?) = id?.let { movieById[it] }
    fun series(id: String?) = id?.let { seriesById[it] }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

object LibraryBuilder {
    private val episodeRe =
        Regex("""(?i)^(.*?)[\s._\-|:]*(?<![A-Z0-9])[ST](\d{1,2})\s*[._-]?\s*EP?(\d{1,3})(?![0-9])[\s._\-|:]*(.*)$""")
    private val yearRe = Regex("""\s*[(\[](\d{4})[)\]]\s*|\s+-\s+(\d{4})\s*$""")

    class EpisodeInfo(val base: String, val season: Int, val episode: Int, val title: String)

    fun parseEpisode(name: String): EpisodeInfo? {
        val m = episodeRe.find(name.trim()) ?: return null
        return EpisodeInfo(
            base = m.groupValues[1].trim().trimEnd('-', '|', ':', '.').trim(),
            season = m.groupValues[2].toInt(),
            episode = m.groupValues[3].toInt(),
            title = m.groupValues[4].trim(),
        )
    }

    /** "Filme (2021)" -> ("Filme", "2021") */
    fun splitYear(name: String): Pair<String, String?> {
        val m = yearRe.find(name) ?: return name.trim() to null
        val year = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val title = name.removeRange(m.range).trim().ifEmpty { name.trim() }
        return title to year
    }

    /** Monta filmes e séries a partir de uma lista M3U comum. */
    fun fromChannels(channels: List<Channel>): Library {
        val movies = ArrayList<VodItem>()
        val movieCats = LinkedHashSet<String>()
        val seriesGroups = LinkedHashMap<String, MutableList<Pair<Channel, EpisodeInfo?>>>()
        val seriesCats = LinkedHashSet<String>()

        for (ch in channels) when (ch.kind) {
            ContentKind.MOVIE -> {
                val (title, year) = splitYear(ch.name)
                movies += VodItem(
                    id = "m3u:${ch.url}", name = title, url = ch.url, poster = ch.logo,
                    category = ch.group, year = year, headers = ch.headers,
                )
                movieCats += ch.group
            }
            ContentKind.SERIES -> {
                val info = parseEpisode(ch.name)
                val base = info?.base?.takeIf { it.isNotBlank() } ?: ch.name
                val key = ch.group + "|" + Text.normalize(splitYear(base).first)
                seriesGroups.getOrPut(key) { ArrayList() } += ch to info
                seriesCats += ch.group
            }
            ContentKind.LIVE -> Unit
        }

        val series = seriesGroups.map { (key, list) ->
            val first = list.first().first
            val base = list.first().second?.base?.takeIf { it.isNotBlank() } ?: first.name
            val (title, year) = splitYear(base)
            val eps = list.mapIndexed { i, (ch, info) ->
                val n = info?.episode ?: (i + 1)
                Episode(
                    id = ch.url,
                    season = info?.season ?: 1,
                    number = n,
                    title = info?.title?.takeIf { it.isNotBlank() } ?: "Episódio $n",
                    url = ch.url,
                    image = ch.logo,
                    headers = ch.headers,
                )
            }.sortedWith(compareBy({ it.season }, { it.number }))
            SeriesItem(
                id = "m3u:$key", name = title, poster = first.logo,
                category = first.group, year = year, episodes = eps,
            )
        }
        return Library(movies, movieCats.toList(), series, seriesCats.toList())
    }
}
