package com.iptv.player4k

import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.LibraryBuilder
import com.iptv.player4k.data.M3uParser
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.data.XtreamApi
import com.iptv.player4k.epg.EpgData
import com.iptv.player4k.epg.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class LibraryAndEpgTest {

    @Test
    fun classifiesLiveMoviesAndSeries() {
        val r = M3uParser.parse(
            """
            #EXTM3U url-tvg="http://epg.example/a.xml.gz, http://epg.example/b.xml"
            #EXTINF:-1 group-title="Canais | Filmes e Séries",Telecine HD
            http://srv/live/u/p/10.ts
            #EXTINF:-1 group-title="Filmes | Ação",Duro de Matar (1988)
            http://srv/movie/u/p/20.mp4
            #EXTINF:-1 group-title="Séries | Drama",Breaking Bad S01 E02
            http://srv/series/u/p/30.mkv
            #EXTINF:-1 group-title="Lançamentos",Filme Novo
            http://cdn/filme.mkv
            #EXTINF:-1 group-title="Variados",Minha Serie S02E05 - O Retorno
            http://cdn/x.mp4
            #EXTINF:-1 group-title="SÉRIES 24H",Friends 24h
            http://cdn/friends.m3u8
            """.trimIndent().reader().buffered()
        )
        assertEquals(listOf("http://epg.example/a.xml.gz", "http://epg.example/b.xml"), r.tvgUrls)
        assertEquals(
            listOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.SERIES, ContentKind.MOVIE, ContentKind.SERIES, ContentKind.LIVE),
            r.channels.map { it.kind }
        )
    }

    @Test
    fun buildsSeriesFromM3uEpisodes() {
        fun ep(n: String) = Channel(n, "http://x/${n.hashCode()}.mp4", group = "Séries", kind = ContentKind.SERIES)
        val lib = LibraryBuilder.fromChannels(
            listOf(ep("Dark S01E02 Mentiras"), ep("Dark S01E01 Segredos"), ep("Dark S02E01"), ep("Outra Série T01 E01"))
        )
        assertEquals(2, lib.series.size)
        val dark = lib.series.first { it.name == "Dark" }
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), dark.episodes.map { it.season to it.number })
        assertEquals("Segredos", dark.episodes[0].title)
        assertEquals("Episódio 1", dark.episodes[2].title)
    }

    @Test
    fun splitsYear() {
        assertEquals("Duna" to "2021", LibraryBuilder.splitYear("Duna (2021)"))
        assertEquals("Matrix" to "1999", LibraryBuilder.splitYear("Matrix - 1999"))
        assertEquals("Sem Ano" to null, LibraryBuilder.splitYear("Sem Ano"))
    }

    @Test
    fun parsesXtreamJson() {
        val p = Playlist("1", "x", PlaylistType.XTREAM, server = "http://srv:80", username = "u", password = "p")
        val api = XtreamApi(p)
        val cats = XtreamApi.parseCategories("""[{"category_id":"1","category_name":"Ação","parent_id":0}]""".reader())
        val movies = XtreamApi.parseVodStreams(
            """[{"num":1,"name":"Duna (2021)","stream_id":55,"stream_icon":"http://img/d.jpg","rating":"8.1","added":"1700000000","category_id":"1","container_extension":"mkv"},
               {"num":2,"name":"Sem cat","stream_id":"56","stream_icon":null,"rating":"","category_id":null}]""".reader(),
            cats, api
        )
        assertEquals(2, movies.size)
        assertEquals("Duna", movies[0].name)
        assertEquals("2021", movies[0].year)
        assertEquals(8.1f, movies[0].rating)
        assertEquals("http://srv:80/movie/u/p/55.mkv", movies[0].url)
        assertEquals("Ação", movies[0].category)
        assertEquals("Outros", movies[1].category)
        assertNull(movies[1].poster)

        val series = XtreamApi.parseSeries(
            """[{"name":"Dark","series_id":7,"cover":"http://img/c.jpg","backdrop_path":["http://img/b.jpg"],"category_id":"1","rating":"9","releaseDate":"2017-12-01"}]""".reader(),
            cats
        )
        assertEquals("http://img/b.jpg", series[0].backdrop)
        assertEquals("2017", series[0].year)

        val info = XtreamApi.parseSeriesInfo(
            """{"info":{"plot":"Mistério","cast":"A, B","backdrop_path":[]},
                "episodes":{"2":[{"id":"902","episode_num":1,"title":"Dark - S02E01 - Começo","container_extension":"mp4","info":{"duration_secs":3000}}],
                            "1":[{"id":"901","episode_num":"1","title":"Piloto","container_extension":"mkv","info":{"movie_image":"http://img/e.jpg","duration":"00:50:00"}}]}}""",
            api
        )
        assertEquals("Mistério", info.plot)
        assertEquals(listOf(1, 2), info.episodes.map { it.season })
        assertEquals("http://srv:80/series/u/p/901.mkv", info.episodes[0].url)
        assertEquals(3000, info.episodes[0].durationSecs)
        assertEquals("Começo", info.episodes[1].title)
    }

    @Test
    fun parsesXmltvAndMatchesChannels() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="Globo.br"><display-name>Globo SP</display-name><icon src="x"/></channel>
              <channel id="sbt.br"><display-name lang="pt">SBT</display-name></channel>
              <channel id="ignorado"><display-name>Outro</display-name></channel>
              <programme start="20261125120000 -0300" stop="20261125130000 -0300" channel="Globo.br"><title lang="pt">Jornal Hoje</title><desc>Notícias</desc></programme>
              <programme start="20261125130000 -0300" stop="20261125140000 -0300" channel="Globo.br"><title>Novela</title></programme>
              <programme start="20261125120000 -0300" stop="20261125140000 -0300" channel="sbt.br"><title>Programa</title></programme>
              <programme start="20261125120000 -0300" stop="20261125140000 -0300" channel="ignorado"><title>X</title></programme>
            </tv>"""
        val xpp = KXmlParser()
        xpp.setInput(xml.reader())
        val b = XmltvParser.Builder()
        XmltvParser.parse(xpp, setOf("globo.br"), setOf(EpgData.nameKey("BR: SBT HD")), 0, Long.MAX_VALUE, b)
        val epg = b.build(0)

        val globo = Channel("Globo", "u1", tvgId = "globo.br")
        val sbt = Channel("BR: SBT HD", "u2")
        val outro = Channel("Outro", "u3")
        val t = XmltvParser.parseTime("20261125123000 -0300")
        assertEquals(XmltvParser.parseTime("20261125153000 +0000"), t)
        assertEquals("Jornal Hoje", epg.now(globo, t)?.title)
        assertEquals("Notícias", epg.now(globo, t)?.desc)
        assertEquals("Novela", epg.next(globo, t)?.title)
        assertEquals("Programa", epg.now(sbt, t)?.title)
        assertNull(epg.now(outro, t))
        assertNotNull(epg.programmes(globo).firstOrNull())
        assertTrue(epg.programmes(outro).isEmpty())
    }

    @Test
    fun nameKeys() {
        assertEquals("globosp", EpgData.nameKey("BR: Globo SP FHD [H265]"))
        assertEquals("globosp", EpgData.nameKey("Globo SP"))
        assertEquals("espn2", EpgData.nameKey("ESPN 2 (Alt)"))
    }
}
