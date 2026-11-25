package com.iptv.player4k

import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.LibraryBuilder
import com.iptv.player4k.data.M3uParser
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.data.XtreamCreds
import com.iptv.player4k.epg.EpgData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesAndXtreamTest {

    private fun ep(name: String) = LibraryBuilder.parseEpisode(name)

    @Test
    fun episodeFormats() {
        fun check(name: String, base: String, s: Int, e: Int) {
            val i = ep(name)
            assertNotNull(name, i)
            assertEquals(name, base, i!!.base)
            assertEquals(name, s, i.season)
            assertEquals(name, e, i.episode)
        }
        check("Dark S01E02", "Dark", 1, 2)
        check("Dark S01 E02", "Dark", 1, 2)
        check("Dark.S01.E02.720p", "Dark", 1, 2)
        check("La Casa de Papel T2 EP5", "La Casa de Papel", 2, 5)
        check("Friends - 3x07 - Aquele com o Gato", "Friends", 3, 7)
        check("The Office Temporada 2 Episódio 10", "The Office", 2, 10)
        check("Chaves 1ª Temporada - Ep 03", "Chaves", 1, 3)
        check("Episódio 4", "", 1, 4)
        check("Novela Ouro Capítulo 120", "Novela Ouro", 1, 120)
        assertNull(ep("ESPN 2"))
        assertNull(ep("Duna (2021)"))
        assertNull(ep("Canal 24 Horas"))
    }

    @Test
    fun groupIsTheSeries() {
        // lista onde cada grupo é uma série e os episódios só têm número
        fun ch(name: String, group: String) =
            Channel(name, "http://x/${group.hashCode()}/${name.hashCode()}.mp4", group = group, kind = ContentKind.SERIES)
        val lib = LibraryBuilder.fromChannels(
            listOf(
                ch("Episódio 1", "Breaking Bad"), ch("Episódio 2", "Breaking Bad"), ch("Episódio 3", "Breaking Bad"),
                ch("Dark 01", "Séries Netflix"), ch("Dark 02", "Séries Netflix"),
                ch("Friends 1x01", "Séries Netflix"), ch("Friends 1x02", "Séries Netflix"),
                ch("Documentário Único", "Séries Netflix"),
            )
        )
        assertEquals(listOf("Breaking Bad", "Dark", "Friends", "Documentário Único"), lib.series.map { it.name })
        assertEquals(3, lib.series[0].episodes.size)
        assertEquals(listOf(1, 2), lib.series[1].episodes.map { it.number })
        assertEquals(1, lib.series[3].episodes.size)
    }

    @Test
    fun classifiesEpisodesEvenWithoutSeriesGroup() {
        assertEquals(ContentKind.SERIES, M3uParser.classify("http://x/a.mp4", "Breaking Bad", "Episódio 3"))
        assertEquals(ContentKind.SERIES, M3uParser.classify("http://x/a.mkv", "Netflix", "Dark 1x02"))
        assertEquals(ContentKind.MOVIE, M3uParser.classify("http://x/a.mp4", "Lançamentos", "Duna (2021)"))
        assertEquals(ContentKind.LIVE, M3uParser.classify("http://x/1.ts", "Canais Abertos", "Globo Capítulo 1 Notícias"))
    }

    @Test
    fun readsDescriptionAttribute() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Filmes" plot="Um filme sobre o deserto.",Duna (2021)
            http://x/duna.mp4
            """.trimIndent().reader().buffered()
        )
        assertEquals("Um filme sobre o deserto.", r.channels[0].description)
        assertEquals("Um filme sobre o deserto.", LibraryBuilder.fromChannels(r.channels).movies[0].plot)
    }

    @Test
    fun detectsXtreamPanel() {
        val fromLink = XtreamCreds.fromPlaylist(
            Playlist("1", "x", PlaylistType.URL, url = "http://srv.tv:8080/get.php?username=ana&password=s%40cr&type=m3u_plus&output=ts")
        )
        assertEquals(XtreamCreds("http://srv.tv:8080", "ana", "s@cr"), fromLink)

        val chans = listOf(
            Channel("A", "http://srv.tv:80/ana/pw/101"),
            Channel("B", "http://srv.tv:80/ana/pw/102.ts"),
            Channel("C", "http://srv.tv:80/movie/ana/pw/500.mp4"),
            Channel("D", "http://srv.tv:80/series/ana/pw/900.mkv"),
            Channel("E", "http://outro.com/live/stream.m3u8"),
        )
        assertEquals(XtreamCreds("http://srv.tv:80", "ana", "pw"), XtreamCreds.fromChannels(chans))
        assertNull(XtreamCreds.fromChannels(listOf(Channel("Z", "https://cdn.com/hls/canal/index.m3u8"))))
        assertNull(XtreamCreds.fromPlaylist(Playlist("2", "y", PlaylistType.URL, url = "https://site.com/lista.m3u")))
    }

    @Test
    fun epgNameMatching() {
        assertEquals(EpgData.nameKey("Globo.br"), EpgData.nameKey("BR: GLOBO ᴴᴰ"))
        assertEquals(EpgData.nameKey("SporTV 2"), EpgData.nameKey("SPORTV 2 FHD [ALT]"))
        assertEquals(EpgData.nameKey("Band"), EpgData.nameKey("TV Band HD"))
    }
}
