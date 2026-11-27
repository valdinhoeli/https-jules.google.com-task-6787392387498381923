package com.iptv.player4k

import com.iptv.player4k.data.AccountInfo
import com.iptv.player4k.data.Channel
import com.iptv.player4k.epg.EpgData
import com.iptv.player4k.epg.Programme
import com.iptv.player4k.epg.SportsEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAndAccountTest {

    @Test
    fun detectsSportsEvents() {
        assertTrue(SportsEvents.isEvent("Brasileirão: Flamengo x Palmeiras", false))
        assertTrue(SportsEvents.isEvent("NBA: Lakers vs. Celtics", false))
        assertTrue(SportsEvents.isEvent("Futebol Ao Vivo: Final", true))
        assertTrue(SportsEvents.isEvent("Vôlei Superliga: Minas x Sesi", true))
        assertTrue(SportsEvents.isEvent("Fórmula 1: GP de Abu Dhabi", false))
        assertTrue(SportsEvents.isEvent("Clássico: Flamengo x Fluminense", false, liveFlag = true))
        assertFalse(SportsEvents.isEvent("Basquete: NBB", true))
        assertFalse(SportsEvents.isEvent("Resenha Esportiva", true))
        assertFalse(SportsEvents.isEvent("Gols da Rodada", true))
        assertFalse(SportsEvents.isEvent("Jornal da Manhã", false))
        assertFalse(SportsEvents.isEvent("Sessão da Tarde: Rocky", false))
        assertTrue(SportsEvents.isSportsChannel(Channel("ESPN 2 HD", "u1", group = "Canais")))
        assertTrue(SportsEvents.isSportsChannel(Channel("Canal 5", "u2", group = "BR | Esportes")))
        assertFalse(SportsEvents.isSportsChannel(Channel("Globo SP", "u3", group = "Abertos")))
    }

    @Test
    fun findsLiveAndUpcomingWithoutDuplicates() {
        val now = 1_000_000_000_000L
        val h = 3_600_000L
        val a = Channel("Sport A HD", "a", tvgId = "a", group = "Esportes")
        val b = Channel("Sport A FHD", "b", tvgId = "b", group = "Esportes")
        val c = Channel("Globo", "c", tvgId = "c", group = "Abertos")
        val game = Programme(now - h, now + h, "Flamengo x Palmeiras - Brasileirão", null)
        val epg = EpgData(
            mapOf(
                "a" to listOf(game, Programme(now + h, now + 2 * h, "Resenha", null)),
                "b" to listOf(Programme(now - h, now + h, "Flamengo x Palmeiras - Brasileirão", null)),
                "c" to listOf(Programme(now - h, now + h, "Novela", null), Programme(now + 3 * h, now + 5 * h, "Copa do Brasil: Grêmio x Inter", null),
                    Programme(now + 30 * h, now + 32 * h, "Libertadores: A x B", null)),
            ),
            emptyMap(), now,
        )
        val ev = SportsEvents.find(listOf(a, b, c), epg, now)
        assertEquals(2, ev.size)
        assertTrue(ev[0].live); assertEquals("a", ev[0].channel.url)
        assertFalse(ev[1].live); assertEquals("c", ev[1].channel.url)
    }

    @Test
    fun skipsRerunsAndRepeatedAirings() {
        val now = 1_000_000_000_000L
        val h = 3_600_000L
        val ch = Channel("Sport TV", "s", tvgId = "s", group = "Esportes")
        val other = Channel("Sport TV 2", "s2", tvgId = "s2", group = "Esportes")
        assertTrue(SportsEvents.isRerun(Programme(0, h, "Flamengo x Palmeiras (VT)", null)))
        assertTrue(SportsEvents.isRerun(Programme(0, h, "Brasileirão: Reprise - Grêmio x Inter", null)))
        assertTrue(SportsEvents.isRerun(Programme(0, h, "Grêmio x Inter", "Compacto do jogo de ontem")))
        assertTrue(SportsEvents.isRerun(Programme(0, h, "Grêmio x Inter", null, Programme.RERUN)))
        assertFalse(SportsEvents.isRerun(Programme(0, h, "R. Madrid x Barcelona", null)))
        assertFalse(SportsEvents.isRerun(Programme(0, h, "Clássico: Flamengo x Fluminense", null)))
        val epg = EpgData(
            mapOf(
                // jogo exibido às 10h (já passou) e reexibido agora: não é ao vivo
                "s" to listOf(
                    Programme(now - 6 * h, now - 4 * h, "Libertadores: Grêmio x River Plate", null),
                    Programme(now - h, now + h, "Libertadores: Grêmio x River Plate", null),
                    Programme(now + h, now + 2 * h, "NBA: Lakers vs Celtics (R)", null),
                    Programme(now + 2 * h, now + 4 * h, "Brasileirão: Santos x Vasco", null, Programme.LIVE),
                ),
                // o mesmo jogo ao vivo em outro canal, no mesmo horário: aparece uma vez
                "s2" to listOf(Programme(now + 2 * h, now + 4 * h, "Santos x Vasco - Brasileirão", null)),
            ),
            emptyMap(), now,
        )
        val ev = SportsEvents.find(listOf(ch, other), epg, now)
        assertEquals(listOf("Brasileirão: Santos x Vasco"), ev.map { it.programme.title })
        assertFalse(ev[0].live)
    }

    @Test
    fun ignoresSportsMoviesOnMovieChannels() {
        val now = 1_000_000_000_000L
        val h = 3_600_000L
        val cine = Channel("Telecine Action HD", "t", tvgId = "t", group = "Filmes")
        val hbo = Channel("HBO 2", "h", tvgId = "h", group = "Canais")
        val tntSports = Channel("TNT Sports HD", "s", tvgId = "s", group = "Canais")
        val globo = Channel("Globo SP", "g", tvgId = "g", group = "Abertos")
        assertTrue(SportsEvents.isMovieChannel(cine))
        assertTrue(SportsEvents.isMovieChannel(hbo))
        assertFalse(SportsEvents.isMovieChannel(tntSports))
        assertFalse(SportsEvents.isMovieChannel(globo))
        val epg = EpgData(
            mapOf(
                "t" to listOf(Programme(now - h, now + h, "Rocky x Drago: A Luta Final", null)),
                "h" to listOf(Programme(now - h, now + h, "Creed: Boxe ao Vivo x Campeão", null)),
                "s" to listOf(Programme(now - h, now + h, "Champions League: Real Madrid x City", null)),
                // canal aberto passando filme de futebol marcado como filme no guia
                "g" to listOf(Programme(now - h, now + h, "Gol! O Sonho: Futebol x Destino", null, Programme.FILM)),
            ),
            emptyMap(), now,
        )
        val ev = SportsEvents.find(listOf(cine, hbo, tntSports, globo), epg, now)
        assertEquals(listOf("Champions League: Real Madrid x City"), ev.map { it.programme.title })
        assertTrue(SportsEvents.isFilmCategory("Filme / Ação"))
        assertTrue(SportsEvents.isNovelaCategory("Novela"))
    }

    @Test
    fun sportsFilmsDoNotCountAsLiveEvents() {
        val now = 1_000_000_000_000L
        val h = 3_600_000L
        // canais de filme com nomes abreviados (Telecine = "TC …") também não entram
        val tc = Channel("TC Pipoca FHD", "tc", tvgId = "tc", group = "Canais")
        val ae = Channel("A&E HD", "ae", tvgId = "ae", group = "Variedades")
        val record = Channel("Record SP", "r", tvgId = "r", group = "Abertos")
        val band = Channel("Band", "b", tvgId = "b", group = "Abertos")
        val espn = Channel("ESPN 2", "e", tvgId = "e", group = "Esportes")
        assertTrue(SportsEvents.isMovieChannel(tc))
        assertTrue(SportsEvents.isMovieChannel(ae))
        assertFalse(SportsEvents.isMovieChannel(espn))
        val epg = EpgData(
            mapOf(
                "tc" to listOf(Programme(now - h, now + h, "Duelo: Brasil x Argentina", null, Programme.SPORT)),
                "ae" to listOf(Programme(now - h, now + h, "Final: Campeões x Rivais", null, Programme.SPORT)),
                // filme de esporte num canal aberto: o guia marca filme E esporte
                "r" to listOf(Programme(now - h, now + h, "Ford vs Ferrari", null, Programme.FILM or Programme.SPORT)),
                // sem marca de filme, mas a descrição é de filme (elenco/direção, ano)
                "b" to listOf(Programme(now - h, now + h, "Boxe: Rocky x Apollo", "Elenco: Sylvester Stallone. Direção: John G. Avildsen.", Programme.SPORT)),
                "e" to listOf(Programme(now - h, now + h, "NBA: Lakers x Celtics", "Transmissão do jogo da temporada regular.", Programme.SPORT)),
            ),
            emptyMap(), now,
        )
        val ev = SportsEvents.find(listOf(tc, ae, record, band, espn), epg, now)
        assertEquals(listOf("NBA: Lakers x Celtics"), ev.map { it.programme.title })
        // transmissão marcada AO VIVO no guia continua valendo mesmo com descrição longa
        assertFalse(SportsEvents.looksLikeFilm(Programme(now, now + h, "Flamengo x Palmeiras", "Direção de imagens ao vivo", Programme.LIVE)))
        assertTrue(SportsEvents.looksLikeFilm(Programme(now, now + h, "Gol! (2005)", null)))
    }

    @Test
    fun liveBroadcastsComeFirst() {
        val now = 1_000_000_000_000L
        val h = 3_600_000L
        val a = Channel("Globo SP", "a", tvgId = "a", group = "Abertos")
        val b = Channel("ESPN", "b", tvgId = "b", group = "Esportes")
        val c = Channel("SporTV 2", "c", tvgId = "c", group = "Esportes")
        val epg = EpgData(
            mapOf(
                // começou antes, canal aberto, sem marca de ao vivo
                "a" to listOf(Programme(now - h, now + h, "Brasileirão: Grêmio x Inter", null)),
                // canal de esporte, sem marca
                "b" to listOf(Programme(now - h / 2, now + h, "NBA: Lakers x Celtics", null)),
                // confirmado AO VIVO pelo guia
                "c" to listOf(Programme(now - h / 4, now + h, "Vôlei: Brasil x Itália", null, Programme.LIVE)),
                // próximo (não está passando) fica depois de todos os ao vivo
                "b2" to emptyList(),
            ),
            emptyMap(), now,
        )
        val ev = SportsEvents.find(listOf(a, b, c), epg, now)
        assertEquals(listOf("Vôlei: Brasil x Itália", "NBA: Lakers x Celtics", "Brasileirão: Grêmio x Inter"), ev.map { it.programme.title })
        assertTrue(ev.all { it.live })
    }

    @Test
    fun parsesXtreamAccount() {
        val now = 1_700_000_000_000L
        val info = AccountInfo.parseXtream(
            """{"user_info":{"auth":1,"status":"Active","exp_date":"1703888000","is_trial":"0","active_cons":"1","max_connections":"2"}}""", now,
        )!!
        assertEquals(1_703_888_000_000L, info.expiresAt)
        assertEquals(45L, info.daysLeft(now))
        assertEquals(2, info.maxConnections)
        assertTrue(info.isActive)
        val unlimited = AccountInfo.parseXtream("""{"user_info":{"status":"Active","exp_date":null}}""", now)!!
        assertNull(unlimited.expiresAt)
        assertNotNull(AccountInfo.fromJson(info.toJson()))
        assertEquals(info, AccountInfo.fromJson(info.toJson()))
        assertNull(AccountInfo.parseXtream("[]", now))
    }
}
