package com.iptv.player4k

import com.iptv.player4k.data.LibraryBuilder
import com.iptv.player4k.data.M3uParser
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.data.XtreamApi
import com.iptv.player4k.util.Trailer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerTest {
    @Test
    fun youtubeIds() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, Trailer.youtubeId(id))
        assertEquals(id, Trailer.youtubeId("https://www.youtube.com/watch?v=$id&t=10s"))
        assertEquals(id, Trailer.youtubeId("http://youtu.be/$id"))
        assertEquals(id, Trailer.youtubeId("https://www.youtube.com/embed/$id?autoplay=1"))
        assertEquals(id, Trailer.youtubeId("youtube.com/shorts/$id"))
        assertNull(Trailer.youtubeId(""))
        assertNull(Trailer.youtubeId("null"))
        assertNull(Trailer.youtubeId("https://site.com/trailer.mp4"))
        assertTrue(Trailer.searchUrl("Duna", "2021").endsWith("search_query=Duna+2021+trailer"))
    }

    @Test
    fun trailerFromXtreamAndM3u() {
        val api = XtreamApi(Playlist("1", "x", PlaylistType.XTREAM, server = "http://s", username = "u", password = "p"))
        val vod = XtreamApi.parseVodInfo("""{"info":{"plot":"x","youtube_trailer":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}}""")
        assertEquals("dQw4w9WgXcQ", vod.trailer)
        val series = XtreamApi.parseSeries("""[{"name":"Dark","series_id":1,"youtube_trailer":"dQw4w9WgXcQ"}]""".reader(), emptyMap())
        assertEquals("dQw4w9WgXcQ", series[0].trailer)
        val info = XtreamApi.parseSeriesInfo("""{"info":{"youtube_trailer":""},"episodes":{}}""", api)
        assertNull(info.trailer)

        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Filmes" trailer="https://youtu.be/dQw4w9WgXcQ",Duna (2021)
            http://x/duna.mp4
            """.trimIndent().reader().buffered()
        )
        assertEquals("dQw4w9WgXcQ", LibraryBuilder.fromChannels(r.channels).movies[0].trailer)
    }
}
