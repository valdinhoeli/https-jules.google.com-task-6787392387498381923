package com.iptv.player4k

import com.iptv.player4k.data.M3uParser
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.data.QualityTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private fun parse(s: String) = M3uParser.parse(s.reader().buffered(), "Sem grupo").channels

    @Test
    fun parsesAttributesAndTitle() {
        val list = parse(
            """
            #EXTM3U url-tvg="http://epg.example/guide.xml"
            #EXTINF:-1 tvg-id="canal1" tvg-name="Canal 1" tvg-logo="http://logo/1.png" group-title="Notícias, Brasil",Canal 1 FHD
            http://srv.example:8080/live/u/p/1.ts
            #EXTINF:-1 group-title="Filmes",Filme 4K
            #EXTVLCOPT:http-user-agent=MeuPlayer/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            https://cdn.example/filme/index.m3u8
            """.trimIndent()
        )
        assertEquals(2, list.size)
        val c1 = list[0]
        assertEquals("Canal 1 FHD", c1.name)
        assertEquals("Notícias, Brasil", c1.group)
        assertEquals("http://logo/1.png", c1.logo)
        assertEquals("canal1", c1.tvgId)
        assertEquals(QualityTag.FHD, c1.qualityTag)
        assertEquals(emptyMap<String, String>(), c1.headers)

        val c2 = list[1]
        assertEquals(QualityTag.UHD, c2.qualityTag)
        assertEquals("MeuPlayer/1.0", c2.headers["User-Agent"])
        assertEquals("http://ref.example/", c2.headers["Referer"])
    }

    @Test
    fun parsesKodiStyleHeadersAndExtGrp() {
        val list = parse(
            """
            #EXTM3U
            #EXTINF:0,Esporte HD
            #EXTGRP:Esportes
            http://x.example/a.m3u8|User-Agent=Agent%2F2&Referer=http://r/
            """.trimIndent()
        )
        assertEquals(1, list.size)
        assertEquals("http://x.example/a.m3u8", list[0].url)
        assertEquals("Esportes", list[0].group)
        assertEquals("Agent/2", list[0].headers["User-Agent"])
        assertEquals("http://r/", list[0].headers["Referer"])
        assertEquals(QualityTag.HD, list[0].qualityTag)
    }

    @Test
    fun plainUrlListAndBom() {
        val list = parse("﻿#EXTM3U\r\nhttp://a.example/stream1\r\n\r\nhttp://a.example/x/canal.ts\r\n")
        assertEquals(2, list.size)
        assertEquals("stream1", list[0].name)
        assertEquals("Sem grupo", list[0].group)
        assertNull(list[0].logo)
    }

    @Test
    fun qualityTags() {
        assertEquals(QualityTag.UHD, QualityTag.fromName("ESPN [UHD]"))
        assertEquals(QualityTag.FHD, QualityTag.fromName("Globo Full HD"))
        assertEquals(QualityTag.HD, QualityTag.fromName("SporTV HD²"))
        assertNull(QualityTag.fromName("HEVC Channel"))
        assertNull(QualityTag.fromName("Canal Shadow"))
    }

    @Test
    fun xtreamUrl() {
        val p = Playlist("1", "x", PlaylistType.XTREAM, server = "dominio.com:8080/", username = "user", password = "p@ss")
        assertEquals(
            "http://dominio.com:8080/get.php?username=user&password=p%40ss&type=m3u_plus&output=ts",
            p.resolvedUrl()
        )
    }
}
