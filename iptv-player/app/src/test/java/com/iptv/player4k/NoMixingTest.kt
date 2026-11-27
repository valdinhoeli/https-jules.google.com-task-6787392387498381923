package com.iptv.player4k

import com.iptv.player4k.data.ContentKind
import com.iptv.player4k.data.LibraryBuilder
import com.iptv.player4k.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canais nunca podem ir para Filmes/Séries, e filmes/episódios nunca para TV AO VIVO. */
class NoMixingTest {
    private fun k(url: String, group: String, name: String) = M3uParser.classify(url, group, name)

    @Test
    fun movieAndSeriesChannelsStayLive() {
        // canais de filmes/séries de listas M3U comuns (Xtream sem /live/)
        assertEquals(ContentKind.LIVE, k("http://h:80/u/p/101.ts", "Filmes", "HBO HD"))
        assertEquals(ContentKind.LIVE, k("http://h:80/u/p/102.ts", "FILMES E SÉRIES", "Telecine Action FHD"))
        assertEquals(ContentKind.LIVE, k("http://h:80/u/p/103.m3u8", "Séries", "Warner Channel"))
        assertEquals(ContentKind.LIVE, k("http://h:80/u/p/104", "Lançamentos", "Space"))
        assertEquals(ContentKind.LIVE, k("http://h:80/u/p/105.ts", "Filmes 24h", "Velozes 24 Horas"))
        assertEquals(ContentKind.LIVE, k("http://h:80/u/p/106.ts", "Séries 24 horas", "Chaves 24h"))
        assertEquals(ContentKind.LIVE, k("http://h:80/u/p/107.ts", "Séries", "Friends 24h S01"))
        assertEquals(ContentKind.LIVE, k("http://h/live/u/p/1.ts", "Séries", "The Office S01E01"))
    }

    @Test
    fun vodFilesNeverBecomeChannels() {
        assertEquals(ContentKind.MOVIE, k("http://h/u/p/9.mp4", "Canais | Filmes", "Duna"))
        assertEquals(ContentKind.MOVIE, k("http://h/u/p/9.mkv?token=1", "Ao Vivo", "O Canal (2019)"))
        assertEquals(ContentKind.SERIES, k("http://h/u/p/9.mp4", "Séries Netflix", "Dark 01"))
        assertEquals(ContentKind.SERIES, k("http://h/u/p/9.mkv", "Canais", "Dark S01E02"))
        assertEquals(ContentKind.MOVIE, k("http://h/movie/u/p/9.ts", "Canais", "Duna"))
        assertEquals(ContentKind.SERIES, k("http://h/series/u/p/9.ts", "Canais", "Dark"))
    }

    @Test
    fun streamedVodNeedsStrongEvidence() {
        assertEquals(ContentKind.MOVIE, k("http://h/u/p/9.m3u8", "Filmes", "Duna (2021)"))
        assertEquals(ContentKind.SERIES, k("http://h/u/p/9.m3u8", "Séries", "Dark S01E02"))
        assertEquals(ContentKind.SERIES, k("http://h/u/p/9.ts", "Séries Netflix", "Dark Episódio 3"))
        // episódio num grupo de canais é programa ao vivo
        assertEquals(ContentKind.LIVE, k("http://h/u/p/9.ts", "Novelas", "Globo Capítulo 12"))
    }

    @Test
    fun parsedListKeepsTabsSeparated() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Filmes",HBO HD
            http://h:80/u/p/101.ts
            #EXTINF:-1 group-title="Séries",Warner Channel
            http://h:80/u/p/102.ts
            #EXTINF:-1 group-title="Filmes",Duna (2021)
            http://h:80/movie/u/p/201.mp4
            #EXTINF:-1 group-title="Séries",Dark S01E01
            http://h:80/series/u/p/301.mkv
            #EXTINF:-1 group-title="Canais | Lançamentos",Oppenheimer
            http://h:80/u/p/202.mp4
            """.trimIndent().reader().buffered()
        )
        val live = r.channels.filter { it.kind == ContentKind.LIVE }.map { it.name }
        assertEquals(listOf("HBO HD", "Warner Channel"), live)
        val lib = LibraryBuilder.fromChannels(r.channels)
        assertEquals(listOf("Duna", "Oppenheimer"), lib.movies.map { it.name }.sorted())
        assertEquals(listOf("Dark"), lib.series.map { it.name })
        val vodUrls = lib.movies.map { it.url } + lib.series.flatMap { s -> s.episodes.map { it.url } }
        assertTrue(vodUrls.none { it.endsWith("101.ts") || it.endsWith("102.ts") })
    }
}
