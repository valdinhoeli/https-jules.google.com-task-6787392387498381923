package com.iptv.player4k

import com.iptv.player4k.util.SpeedTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestTest {
    @Test
    fun mbpsAndVerdict() {
        assertEquals(8.0, SpeedTest.mbps(1_000_000, 1000), 0.001) // 1 MB em 1 s = 8 Mbps
        assertEquals(SpeedTest.Quality.UHD, SpeedTest.verdict(40.0))
        assertEquals(SpeedTest.Quality.FHD, SpeedTest.verdict(12.0))
        assertEquals(SpeedTest.Quality.HD, SpeedTest.verdict(6.0))
        assertEquals(SpeedTest.Quality.SD, SpeedTest.verdict(3.0))
        assertEquals(SpeedTest.Quality.WEAK, SpeedTest.verdict(1.0))
    }

    @Test
    fun picksBestHlsVariantAndResolves() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            hd/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000
            mid/index.m3u8
        """.trimIndent()
        assertEquals("hd/index.m3u8", SpeedTest.hlsBestVariant(master))
        assertEquals("http://h:80/live/u/p/hd/index.m3u8", SpeedTest.resolve("http://h:80/live/u/p/1.m3u8", "hd/index.m3u8"))
        assertEquals("http://x/seg1.ts", SpeedTest.resolve("http://h/a.m3u8", "http://x/seg1.ts"))
    }
}
