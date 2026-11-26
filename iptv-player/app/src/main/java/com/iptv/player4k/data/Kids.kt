package com.iptv.player4k.data

import com.iptv.player4k.security.AgeRating
import com.iptv.player4k.util.Text

/** Conteúdo de um tema (KIDS, ESPORTES, NOVELAS) extraído da lista: canais, filmes e séries. */
class ThemeContent(
    val channels: List<Channel>,
    val movies: List<VodItem>,
    val series: List<SeriesItem>,
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val titles get() = movies.size + series.size

    companion object {
        val EMPTY = ThemeContent(emptyList(), emptyList(), emptyList())
    }
}

typealias KidsContent = ThemeContent

/**
 * Identifica o que é infantil pelo grupo/categoria/gênero e por nomes de canais e marcas
 * conhecidas. Nunca entra: conteúdo adulto ou com classificação acima de 12 anos.
 */
object Kids {
    /** grupos/categorias/gêneros infantis */
    private val kidsGroup = Regex(
        """(?<![a-z0-9])(infantil|infantis|infanto|kids?|crianca|criancas|desenhos?|desenhos animados|cartoons?|""" +
            """animacao|animacoes|animados?|animation|familia|family|children|childrens|baby|bebe|bebes|""" +
            """pre-escola|pre escola|educativo|educativos|junior)(?![a-z0-9])"""
    )
    /** canais infantis conhecidos */
    private val kidsChannel = Regex(
        """(?<![a-z0-9])(kids?|infantil|desenhos?|cartoon network|cartoonito|boomerang|disney( channel| junior| jr| xd)?|""" +
            """nick|nickelodeon|nick ?jr|discovery kids|gloob|gloobinho|tooncast|zoomoo|baby ?tv|babyfirst|""" +
            """tv ra.?tim.?bum|ratimbum|nat ?geo kids|duck ?tv|jim ?jam|clan|panda|cbeebies|pbs kids|""" +
            """turma da monica|galinha pintadinha|mundo bita|peppa|patrulha canina|pocoyo|bluey)(?![a-z0-9])"""
    )
    /** títulos de franquias infantis (filmes/séries sem categoria clara) */
    private val kidsTitle = Regex(
        """(?<![a-z0-9])(peppa|patrulha canina|paw patrol|galinha pintadinha|mundo bita|turma da monica|pocoyo|""" +
            """bluey|toy story|procurando nemo|frozen|moana|shrek|madagascar|minions|meu malvado favorito|""" +
            """pokemon|barbie|lego|bob esponja|spongebob|scooby|tom e jerry|tom and jerry|mickey|""" +
            """o rei leao|rei leao|carros|divertida mente|zootopia|encanto|luca|kung fu panda|""" +
            """como treinar o seu dragao|a era do gelo|irmao do jorel|show da luna|o pequeno principe)(?![a-z0-9])"""
    )
    /** não são para crianças mesmo com "animação" (ex.: animação adulta, anime de terror) */
    private val notKids = Regex(
        """(?<![a-z0-9])(adulto|adultos|adult|terror|horror|anime adulto|hentai|erotic[oa]?|\+18|18\+|""" +
            """south park|family guy|uma familia da pesada|rick and morty|rick e morty|bojack|big mouth|""" +
            """american dad|simpsons|futurama|invencivel|invincible|harley quinn|the boys)(?![a-z0-9])"""
    )

    private fun blocked(age: Int?, vararg texts: String?): Boolean =
        (age != null && age > 12) || texts.any { AgeRating.isAdult(it) || (it != null && notKids.containsMatchIn(Text.normalize(it))) }

    private fun match(re: Regex, s: String?) = !s.isNullOrBlank() && re.containsMatchIn(Text.normalize(s))

    fun isKidsChannel(ch: Channel): Boolean =
        !blocked(ch.age, ch.group, ch.name) && (match(kidsGroup, ch.group) || match(kidsChannel, ch.name))

    fun isKidsMovie(m: VodItem): Boolean =
        !blocked(m.age, m.category, m.name) && (match(kidsGroup, m.category) || match(kidsTitle, m.name))

    fun isKidsSeries(s: SeriesItem): Boolean =
        !blocked(s.age, s.category, s.name, s.genre) &&
            (match(kidsGroup, s.category) || match(kidsGroup, s.genre) || match(kidsTitle, s.name))

    fun extract(live: List<Channel>, lib: Library): ThemeContent = ThemeContent(
        live.filter { isKidsChannel(it) },
        lib.movies.filter { isKidsMovie(it) },
        lib.series.filter { isKidsSeries(it) },
    )
}

/** ESPORTES: canais de esporte e filmes/séries das categorias de esporte (lutas, futebol, F1…). */
object Sports {
    private val sportsVod = Regex(
        """(?<![a-z0-9])(esporte|esportes|esportivo|esportivos|sport|sports|futebol|football|soccer|ufc|mma|wwe|""" +
            """luta|lutas|boxe|boxing|fight|fights|f1|formula 1|motogp|nba|nfl|copa do mundo|olimpiadas|""" +
            """olimpicos|jogos olimpicos|surf|skate|tenis|volei|basquete)(?![a-z0-9])"""
    )
    private fun adult(vararg t: String?) = t.any { AgeRating.isAdult(it) }
    private fun match(s: String?) = !s.isNullOrBlank() && sportsVod.containsMatchIn(Text.normalize(s))

    fun isSportsChannel(ch: Channel) = !adult(ch.group, ch.name) && com.iptv.player4k.epg.SportsEvents.isSportsChannel(ch)
    fun isSportsMovie(m: VodItem) = !adult(m.category, m.name) && match(m.category)
    fun isSportsSeries(s: SeriesItem) = !adult(s.category, s.name) && (match(s.category) || match(s.genre))

    fun extract(live: List<Channel>, lib: Library) = ThemeContent(
        live.filter { isSportsChannel(it) },
        lib.movies.filter { isSportsMovie(it) },
        lib.series.filter { isSportsSeries(it) },
    )
}

/** NOVELAS: canais de novelas (Viva, TLNovelas…) e novelas do catálogo (turcas, mexicanas, bíblicas…). */
object Novelas {
    private val novelaWord = Regex("""(?<![a-z0-9])(novela|novelas|telenovela|telenovelas|soap|soaps)(?![a-z0-9])""")
    private val novelaChannel = Regex(
        """^(canal )?viva( hd| fhd| sd| 4k| br)?$|(?<![a-z0-9])(tlnovelas|tl novelas|tv novelas|globoplay novelas|""" +
            """canal viva|novelas 24h|rede viva)(?![a-z0-9])"""
    )
    private fun adult(vararg t: String?) = t.any { AgeRating.isAdult(it) }
    private fun match(re: Regex, s: String?) = !s.isNullOrBlank() && re.containsMatchIn(Text.normalize(s).trim())

    fun isNovelaChannel(ch: Channel) = !adult(ch.group, ch.name) &&
        (match(novelaWord, ch.group) || match(novelaWord, ch.name) || match(novelaChannel, ch.name))
    fun isNovelaTitle(title: String?) = match(novelaWord, title)
    fun isNovelaMovie(m: VodItem) = !adult(m.category, m.name) && match(novelaWord, m.category)
    fun isNovelaSeries(s: SeriesItem) = !adult(s.category, s.name) &&
        (match(novelaWord, s.category) || match(novelaWord, s.genre) || match(novelaWord, s.name))

    fun extract(live: List<Channel>, lib: Library) = ThemeContent(
        live.filter { isNovelaChannel(it) },
        lib.movies.filter { isNovelaMovie(it) },
        lib.series.filter { isNovelaSeries(it) },
    )
}
