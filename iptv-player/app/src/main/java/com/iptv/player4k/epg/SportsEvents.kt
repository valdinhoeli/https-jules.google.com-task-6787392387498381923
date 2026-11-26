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
    private val liveWord = Regex("""(?<![a-z])(ao vivo|live|en vivo|direto|jogo|partida|final|semifinal|quartas|rodada)(?![a-z])""")
    /** Programas de estúdio/reprise que não são o evento em si. */
    private val notEvent = Regex(
        """(?<![a-z])(resenha|gols da rodada|melhores momentos|compacto|reprise|vt|replay|highlights|debate|""" +
            """mesa redonda|programa|jornal|noticias|news|bate-papo|entrevista|documentario|classicos)(?![a-z])"""
    )

    fun isSportsChannel(ch: Channel): Boolean =
        sportChannel.containsMatchIn(Text.normalize(ch.group)) || sportChannel.containsMatchIn(Text.normalize(ch.name))

    /** O título do programa parece um evento esportivo? */
    fun isEvent(title: String, onSportsChannel: Boolean): Boolean {
        val t = Text.normalize(title)
        if (notEvent.containsMatchIn(t) && !versus.containsMatchIn(title)) return false
        val vs = versus.containsMatchIn(title)
        val comp = competition.containsMatchIn(t)
        return if (onSportsChannel) vs || comp || liveWord.containsMatchIn(t)
        else (vs && comp) || (vs && liveWord.containsMatchIn(t)) || (comp && liveWord.containsMatchIn(t))
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
        maxLive: Int = 30,
        maxUpcoming: Int = 30,
    ): List<SportEvent> {
        val live = ArrayList<SportEvent>()
        val upcoming = ArrayList<SportEvent>()
        val seen = HashSet<String>()
        for (ch in channels) {
            val list = epg.programmes(ch)
            if (list.isEmpty()) continue
            val sports = isSportsChannel(ch)
            var i = EpgData.firstEndingAfter(list, now)
            while (i < list.size) {
                val p = list[i++]
                if (p.start > now + horizonMs) break
                if (p.stop - p.start < 10 * 60_000L) continue
                if (!isEvent(p.title, sports)) continue
                val key = Text.normalize(p.title) + "|" + p.start / 60_000L
                if (!seen.add(key)) continue
                if (p.start <= now) live += SportEvent(ch, p, true) else upcoming += SportEvent(ch, p, false)
            }
        }
        live.sortBy { it.programme.start }
        upcoming.sortBy { it.programme.start }
        return live.take(maxLive) + upcoming.take(maxUpcoming)
    }
}
