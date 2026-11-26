package com.iptv.player4k.epg

import com.iptv.player4k.data.Channel
import com.iptv.player4k.util.Text

/** Um evento esportivo do guia: ao vivo agora ou começando em breve. */
class SportEvent(val channel: Channel, val programme: Programme, val live: Boolean)

/**
 * Encontra eventos esportivos no guia (EPG) da lista:
 * - em canais de esporte (nome/grupo), jogos e transmissões ("A x B", "ao vivo", campeonatos…);
 * - em qualquer canal, confrontos claros ("Flamengo x Palmeiras", "Lakers vs Celtics")
 *   ou títulos com nome de competição.
 */
object SportsEvents {
    private val sportChannel = Regex(
        """(?<![a-z])(esporte|esportes|sport|sports|sportv|espn|premiere|combate|dazn|eleven|bein|""" +
            """futebol|football|soccer|ufc|nba|nfl|nhl|mlb|golf|tenis|tennis|f1|formula|racing|motorsport|""" +
            """fight|luta|ppv|copa|liga|tnt sports|band sports|fox sports|sky sports|arena|canal do inter|furacao)(?![a-z])"""
    )
    /** Confronto: "Time A x Time B", "A vs B", "A vs. B", "A × B". */
    private val versus = Regex("""\S\s+(x|vs\.?|versus|×|-\s*x\s*-)\s+\S""", RegexOption.IGNORE_CASE)
    private val competition = Regex(
        """(?<![a-z])(futebol|campeonato|brasileirao|serie a|serie b|copa do brasil|copa do mundo|libertadores|""" +
            """sul-americana|sulamericana|champions|europa league|premier league|la liga|laliga|bundesliga|ligue 1|""" +
            """paulistao|carioca|gaucho|mineiro|estadual|eliminatorias|amistoso|nba|nfl|nhl|mlb|ufc|mma|boxe|boxing|""" +
            """formula 1|formula1|f1|grande premio|gp de|motogp|stock car|indy|tenis|wimbledon|roland garros|us open|""" +
            """volei|superliga|basquete|nbb|handebol|olimpiada|jogos olimpicos|corrida|maratona|surf|wwe|luta)(?![a-z])"""
    )
    private val liveWord = Regex("""(?<![a-z])(ao vivo|live|en vivo|em directo|direto)(?![a-z])""")
    /** Palavras de evento para títulos sem confronto ("GP de Abu Dhabi", "UFC 300", "Final"). */
    private val eventWord = Regex(
        """(?<![a-z])(gp|grande premio|corrida|final|semifinal|semi-final|quartas|oitavas|rodada|jogo|partida|""" +
            """luta|card principal|treino classificatorio|classificacao|etapa|round|ufc \d+|decisao)(?![a-z])"""
    )
    /** Reprise, VT, compacto… — não é o evento ao vivo. */
    private val rerun = Regex(
        """(?<![a-z])(reprise|reapresentacao|reexibicao|re-exibicao|replay|vt|videotape|gravado|compacto|""" +
            """melhores momentos|highlights|jogo completo|na integra|relembre|memoria|""" +
            """diferido|en diferido|repeticao|repeat|rerun)(?![a-z])"""
    )
    private val rerunMark = Regex("""\((r|rep|re|vt|reprise)\)|\[(r|rep|re|vt|reprise)\]""", RegexOption.IGNORE_CASE)
    /** Programas de estúdio que não são o evento em si. */
    private val notEvent = Regex(
        """(?<![a-z])(resenha|gols da rodada|mesa redonda|programa|jornal|noticias|news|bate-papo|entrevista|""" +
            """documentario|debate|esporte espetacular|globo esporte|sportscenter|pre-jogo|pos-jogo|""" +
            """pre jogo|pos jogo|aquecimento|bastidores|analise|comentarios)(?![a-z])"""
    )
    private val sportCategory = Regex(
        """(?<![a-z])(sport|sports|esporte|esportes|deporte|deportes|futebol|football|soccer|basquete|basketball|""" +
            """volei|volleyball|tenis|tennis|automobilismo|motorsport|racing|luta|fight|boxe|boxing|mma)(?![a-z])"""
    )

    fun isSportCategory(s: String?): Boolean = s != null && sportCategory.containsMatchIn(Text.normalize(s))

    private val filmCategory = Regex(
        """(?<![a-z])(filme|filmes|film|films|movie|movies|cinema|longa|longa-metragem|serie|series|seriado|""" +
            """drama|comedia|comedy|acao|action|aventura|adventure|suspense|thriller|terror|horror|ficcao|""" +
            """romance|animacao|animation|biografia|biography)(?![a-z])"""
    )
    private val novelaCategory = Regex("""(?<![a-z])(novela|novelas|telenovela|telenovelas|soap|soap opera|soaps)(?![a-z])""")

    fun isFilmCategory(s: String?): Boolean = s != null && filmCategory.containsMatchIn(Text.normalize(s))
    fun isNovelaCategory(s: String?): Boolean = s != null && novelaCategory.containsMatchIn(Text.normalize(s))

    /**
     * Canais de filmes/séries (Telecine, HBO, Cine…): passam filmes sobre esporte ("Rocky", "Creed")
     * que não são eventos. Canal que também é de esporte (TNT Sports, Fox Sports) não entra aqui.
     */
    private val movieChannel = Regex(
        """(?<![a-z])(filme|filmes|film|films|movie|movies|cine|cinema|cinemax|telecine|megapix|hbo|max|paramount|""" +
            """universal|warner|tnt|space|axn|sony|amc|tcm|studio|fx|star|lifetime|a&e|syfy|series|serie|seriados|""" +
            """canal brasil|prime video|netflix|starz|showtime|mgm|lionsgate|filmin|cinecanal|golden|tnt series|""" +
            """lancamentos|estreias)(?![a-z])"""
    )

    fun isMovieChannel(ch: Channel): Boolean =
        !isSportsChannel(ch) &&
            (movieChannel.containsMatchIn(Text.normalize(ch.group)) || movieChannel.containsMatchIn(Text.normalize(ch.name)))

    fun isSportsChannel(ch: Channel): Boolean =
        sportChannel.containsMatchIn(Text.normalize(ch.group)) || sportChannel.containsMatchIn(Text.normalize(ch.name))

    /** Reprise/VT/compacto pelo título, descrição ou marca do guia. */
    fun isRerun(p: Programme): Boolean {
        if (p.isRerun) return true
        if (rerunMark.containsMatchIn(p.title)) return true
        val t = Text.normalize(p.title)
        if (rerun.containsMatchIn(t.replace(Regex("[^a-z0-9 ]"), " "))) return true
        val d = p.desc?.let { Text.normalize(it.take(200)) } ?: return false
        return Regex("""(?<![a-z])(reprise|reapresentacao|reexibicao|videotape|compacto|melhores momentos|gravado|replay)(?![a-z])""").containsMatchIn(d)
    }

    /** O título do programa parece um evento esportivo (jogo, corrida, luta…)? */
    fun isEvent(title: String, onSportsChannel: Boolean, liveFlag: Boolean = false, sportCat: Boolean = false): Boolean {
        val t = Text.normalize(title)
        val vs = versus.containsMatchIn(title)
        if (notEvent.containsMatchIn(t) && !vs) return false
        val comp = competition.containsMatchIn(t)
        val live = liveFlag || liveWord.containsMatchIn(t)
        val sporty = onSportsChannel || sportCat
        return when {
            vs -> sporty || comp || live
            live -> sporty || comp
            comp -> eventWord.containsMatchIn(t)
            else -> false
        }
    }

    fun isEvent(p: Programme, onSportsChannel: Boolean): Boolean =
        isEvent(p.title, onSportsChannel, p.isLiveBroadcast, p.isSport)

    /** Chave do evento: o confronto ("flamengo x palmeiras") ou o título inteiro, sem marcas. */
    internal fun eventKey(title: String): String {
        val t = Text.normalize(title).replace(Regex("""\(.*?\)|\[.*?\]"""), " ")
        val m = Regex("""([a-z0-9][a-z0-9 .'-]*?)\s+(x|vs\.?|versus|×)\s+([a-z0-9][a-z0-9 .'-]*)""").find(t)
        val core = if (m != null) {
            // só a palavra colada no "x" de cada lado: "Grêmio x River Plate - Libertadores" -> "gremio x river"
            val a = m.groupValues[1].trim().split(' ').last()
            val b = m.groupValues[3].trim().split(' ').first()
            "$a x $b"
        } else t
        return core.replace(Regex("[^a-z0-9x ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Eventos ao vivo e os que começam nas próximas [horizonMs] (padrão 24 h).
     * Mesmo evento em vários canais (HD, FHD…) aparece uma vez só.
     */
    fun find(
        channels: List<Channel>,
        epg: EpgData,
        now: Long = System.currentTimeMillis(),
        horizonMs: Long = 24 * 3_600_000L,
        maxLive: Int = 100,
        maxUpcoming: Int = 60,
    ): List<SportEvent> {
        class Cand(val ch: Channel, val p: Programme, val key: String)
        val cands = ArrayList<Cand>()
        /** primeira exibição de cada evento no guia (inclui o que já passou) */
        val firstAir = HashMap<String, Long>()
        for (ch in channels) {
            val list = epg.programmes(ch)
            if (list.isEmpty()) continue
            val sports = isSportsChannel(ch)
            // canal de filmes/séries: o "jogo" ali é filme (Rocky, Creed, Duelo de Titãs…)
            if (!sports && isMovieChannel(ch)) continue
            for (p in list) {
                if (p.start > now + horizonMs) break
                if (p.stop - p.start < 10 * 60_000L) continue
                // o guia diz que é filme/série (e não esporte): não é evento ao vivo
                if (p.isFilm && !p.isSport && !p.isLiveBroadcast) continue
                if (!isEvent(p, sports)) continue
                val key = eventKey(p.title)
                val prev = firstAir[key]
                if (prev == null || p.start < prev) firstAir[key] = p.start
                if (p.stop > now && !isRerun(p)) cands += Cand(ch, p, key)
            }
        }
        val live = ArrayList<SportEvent>()
        val upcoming = ArrayList<SportEvent>()
        val seen = HashSet<String>()
        for (c in cands.sortedBy { it.p.start }) {
            // o mesmo jogo já passou antes (mais de 20 min antes): esta é uma reexibição
            val first = firstAir[c.key] ?: c.p.start
            if (!c.p.isLiveBroadcast && c.p.start - first > 20 * 60_000L) continue
            if (!seen.add(c.key + "|" + c.p.start / 600_000L)) continue
            if (c.p.start <= now) live += SportEvent(c.ch, c.p, true) else upcoming += SportEvent(c.ch, c.p, false)
        }
        return live.take(maxLive) + upcoming.take(maxUpcoming)
    }
}
