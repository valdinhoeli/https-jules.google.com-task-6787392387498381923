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
    val age: Int? = null,
    val searchKey: String = Text.normalize(name),
)

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
    val age: Int? = null,
    val searchKey: String = Text.normalize(name),
)

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
    val age: Int? = null,
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
    private const val SEP = """[\s._\-|:,()\[\]]*"""
    /** S01E02, S1 E2, S01.E02, T01E02, T1 EP2, S01 EP02 */
    private val reSE = Regex("""(?i)^(.*?)$SEP(?<![A-Z0-9])[ST]\s?(\d{1,2})\s*[._\-x]?\s*E(?:P|PIS[OÓ]DIO)?\.?\s?(\d{1,4})(?![0-9])$SEP(.*)$""")
    /** 1x02, 01x02 */
    private val reX = Regex("""(?i)^(.*?)$SEP(?<![0-9])(\d{1,2})x(\d{2,3})(?![0-9])$SEP(.*)$""")
    /** "Temporada 1 Episódio 2", "1ª Temporada - Ep 02", "Temp 2 Cap 5", "Season 1 Episode 2" */
    private val reLong = Regex(
        """(?i)^(.*?)$SEP(?:(\d{1,2})\s*[ªº°a]?\s*(?:temporada|temp\.?|season)|(?:temporada|temp\.?|season)\s*(\d{1,2}))$SEP(?:epis[oó]dio|episode|ep\.?|cap[ií]tulo|cap\.?|chapter)\s*(\d{1,4})(?![0-9])$SEP(.*)$"""
    )
    /** só o episódio: "Episódio 3", "Ep 03", "Capítulo 12" (temporada 1) */
    private val reEp = Regex("""(?i)^(.*?)$SEP(?<![a-z])(?:epis[oó]dio|episode|ep\.?|cap[ií]tulo|chapter)\s*(\d{1,4})(?![0-9])$SEP(.*)$""")
    private val yearRe = Regex("""\s*[(\[](\d{4})[)\]]\s*|\s+-\s+(\d{4})\s*$""")

    class EpisodeInfo(val base: String, val season: Int, val episode: Int, val title: String)

    private fun clean(s: String) = s.trim().trim('-', '|', ':', '.', ',', '_', ' ', '(', '[').trim()

    /** Reconhece S01E02, 1x02, "Temporada 1 Episódio 2" e "Episódio 2". */
    fun parseEpisode(name: String): EpisodeInfo? {
        val n = name.trim()
        reSE.find(n)?.let { m ->
            return EpisodeInfo(clean(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toInt(), clean(m.groupValues[4]))
        }
        reLong.find(n)?.let { m ->
            val season = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: 1
            return EpisodeInfo(clean(m.groupValues[1]), season, m.groupValues[4].toInt(), clean(m.groupValues[5]))
        }
        reX.find(n)?.let { m ->
            return EpisodeInfo(clean(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toInt(), clean(m.groupValues[4]))
        }
        reEp.find(n)?.let { m ->
            return EpisodeInfo(clean(m.groupValues[1]), 1, m.groupValues[2].toInt(), clean(m.groupValues[3]))
        }
        return null
    }

    /** Formatos "fortes" (com temporada) — usados para classificar um item como série. */
    fun hasStrongEpisodeMarker(name: String): Boolean =
        reSE.containsMatchIn(name) || reX.containsMatchIn(name) || reLong.containsMatchIn(name)

    fun hasEpisodeWord(name: String): Boolean = reEp.containsMatchIn(name)

    private val tags = Regex("""(?i)[\[(](leg|legendado|dub|dublado|nacional|l|4k|fhd|hd|sd|uhd|hdr|multi|dual)[\])]|\b(legendado|dublado|leg|dub)\b""")
    private val nonAlnum = Regex("[^a-z0-9]")

    /** Chave para juntar episódios da mesma série ("Dark", "DARK [L]", "Dark (2017)" → "dark"). */
    fun seriesKey(base: String): String {
        val noTags = tags.replace(splitYear(base).first, " ")
        return nonAlnum.replace(com.iptv.player4k.util.Text.normalize(noTags), "")
    }

    private val trailingNumber = Regex("""^(.*?)[\s._\-|:#]+(\d{1,3})$""")

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
        val seriesCats = LinkedHashSet<String>()

        class Ep(val ch: Channel, val base: String, val season: Int, val number: Int?, val title: String?, val order: Int = 0)
        val position = HashMap<Channel, Int>()
        val eps = ArrayList<Ep>()
        val unparsed = ArrayList<Channel>()

        for ((idx, ch) in channels.withIndex()) when (ch.kind) {
            ContentKind.MOVIE -> {
                val (title, year) = splitYear(ch.name)
                movies += VodItem(
                    id = "m3u:${ch.url}", name = title, url = ch.url, poster = ch.logo,
                    category = ch.group, year = year, headers = ch.headers, age = ch.age, plot = ch.description,
                )
                movieCats += ch.group
            }
            ContentKind.SERIES -> {
                position[ch] = idx
                val info = parseEpisode(ch.name)
                if (info == null) unparsed += ch
                else {
                    // "Episódio 3" sem o nome da série: o grupo da lista é a série
                    val base = info.base.ifBlank { ch.group }
                    eps += Ep(ch, base, info.season, info.episode, info.title.ifBlank { null }, idx)
                }
            }
            ContentKind.LIVE -> Unit
        }

        // Sem marcação de episódio: "Série 01", "Série 02"… no mesmo grupo viram uma série;
        // itens que não seguem nenhum padrão viram séries de um episódio.
        val byTrailing = unparsed.groupBy { ch ->
            trailingNumber.find(ch.name.trim())?.let { ch.group + "\u0000" + seriesKey(it.groupValues[1]) }
        }
        for (ch in unparsed) {
            val m = trailingNumber.find(ch.name.trim())
            val shared = m != null && (byTrailing[ch.group + "\u0000" + seriesKey(m.groupValues[1])]?.size ?: 0) >= 2
            val idx = position[ch] ?: 0
            eps += if (shared) Ep(ch, m!!.groupValues[1], 1, m.groupValues[2].toInt(), null, idx)
            else Ep(ch, ch.name, 1, null, null, idx)
        }
        eps.sortBy { it.order } // mantém a ordem da lista

        // agrupa por série (mesmo grupo + mesmo nome normalizado)
        val groups = LinkedHashMap<String, MutableList<Ep>>()
        for (e in eps) {
            val key = e.ch.group + "|" + seriesKey(e.base).ifEmpty { seriesKey(e.ch.group) }
            groups.getOrPut(key) { ArrayList() } += e
            seriesCats += e.ch.group
        }

        val series = groups.map { (key, list) ->
            val first = list.first()
            val (title, year) = splitYear(clean(first.base).ifBlank { first.ch.group })
            var auto = 0
            val episodes = list.map { e ->
                val n = e.number ?: ++auto
                Episode(
                    id = e.ch.url, season = e.season, number = n,
                    title = e.title ?: if (list.size == 1 && e.number == null) title else "Episódio $n",
                    url = e.ch.url, image = e.ch.logo, plot = e.ch.description, headers = e.ch.headers,
                )
            }.sortedWith(compareBy({ it.season }, { it.number }))
            SeriesItem(
                id = "m3u:$key", name = title,
                poster = list.firstNotNullOfOrNull { it.ch.logo },
                category = first.ch.group, year = year, episodes = episodes, age = first.ch.age,
                plot = list.firstNotNullOfOrNull { it.ch.description },
            )
        }
        return Library(movies, movieCats.toList(), series, seriesCats.toList())
    }
}
