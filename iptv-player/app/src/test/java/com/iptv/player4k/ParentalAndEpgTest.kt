package com.iptv.player4k

import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.epg.XmltvParser
import com.iptv.player4k.security.AgeRating
import com.iptv.player4k.security.ParentalRules
import com.iptv.player4k.security.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class ParentalAndEpgTest {

    @Test
    fun parsesAgeRatings() {
        assertEquals(0, AgeRating.parse("L"))
        assertEquals(0, AgeRating.parse("Livre"))
        assertEquals(12, AgeRating.parse("12"))
        assertEquals(12, AgeRating.parse("12 anos"))
        assertEquals(18, AgeRating.parse("+18"))
        assertEquals(14, AgeRating.parse("PG-13"))
        assertEquals(16, AgeRating.parse("R"))
        assertEquals(16, AgeRating.parse("TV-MA"))
        assertEquals(18, AgeRating.parse("NC-17"))
        assertEquals(16, AgeRating.parse("15"))
        assertNull(AgeRating.parse(""))
        assertNull(AgeRating.parse(null))
        assertEquals("L", AgeRating.label(0))
        assertEquals("16", AgeRating.label(16))
    }

    @Test
    fun detectsAdultGroups() {
        assertTrue(AgeRating.isAdult("Adultos"))
        assertTrue(AgeRating.isAdult("XXX | Canais"))
        assertTrue(AgeRating.isAdult("Filmes +18"))
        assertTrue(AgeRating.isAdult("ERÓTICOS"))
        assertFalse(AgeRating.isAdult("Esportes"))
        assertFalse(AgeRating.isAdult("Documentários"))
        assertFalse(AgeRating.isAdult("Canal 18 de Julho"))
    }

    @Test
    fun parentalRules() {
        val r = ParentalRules(enabled = true, maxAge = 12, autoLockAdult = true, lockedGroups = setOf("Terror"))
        assertFalse(r.isBlocked(10, "Infantil"))
        assertTrue(r.isBlocked(14, "Ação"))
        assertTrue(r.isBlocked(null, "Terror"))
        assertTrue(r.isBlocked(null, "Adultos"))
        assertFalse(r.isBlocked(null, "Drama")) // sem classificação: liberado
        assertFalse(ParentalRules.OPEN.isBlocked(18, "Adultos"))
        val noAuto = ParentalRules(true, 18, false, emptySet())
        assertFalse(noAuto.isBlocked(null, "Adultos"))
    }

    @Test
    fun pinHash() {
        val h = Pin.hash("1234")
        assertTrue(Pin.verify("1234", h))
        assertFalse(Pin.verify("4321", h))
        assertFalse(Pin.verify("1234", null))
        assertTrue(Pin.isValid("0000"))
        assertFalse(Pin.isValid("12a4"))
        assertFalse(Pin.isValid("123"))
    }

    @Test
    fun derivesXmltvFromXtreamM3uLink() {
        val p = Playlist("1", "x", PlaylistType.URL, url = "http://srv.tv:8080/get.php?username=ana&password=s3cr&type=m3u_plus&output=ts")
        assertEquals("http://srv.tv:8080/xmltv.php?username=ana&password=s3cr", p.derivedXmltvUrl())
        assertNull(Playlist("2", "y", PlaylistType.URL, url = "https://site.com/lista.m3u").derivedXmltvUrl())
    }

    @Test
    fun readsChannelIconsFromXmltv() {
        val xml = """<tv><channel id="a.br"><display-name>Canal A</display-name><icon src="http://img/a.png"/></channel>
            <programme start="20261125120000 +0000" stop="20261125130000 +0000" channel="a.br"><title>X</title></programme></tv>"""
        val xpp = KXmlParser().apply { setInput(xml.reader()) }
        val b = XmltvParser.Builder()
        XmltvParser.parse(xpp, setOf("a.br"), emptySet(), 0, Long.MAX_VALUE, b)
        val epg = b.build(0)
        assertEquals("http://img/a.png", epg.logo(Channel("Canal A", "u", tvgId = "a.br")))
        assertEquals("http://img/a.png", epg.logo(Channel("CANAL A HD", "u2")))
    }
}
