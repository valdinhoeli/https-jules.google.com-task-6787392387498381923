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
        assertTrue(SportsEvents.isEvent("Vôlei Superliga", true))
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
