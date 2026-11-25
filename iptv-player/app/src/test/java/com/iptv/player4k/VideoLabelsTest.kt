package com.iptv.player4k

import com.iptv.player4k.util.VideoLabels
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLabelsTest {
    @Test
    fun resolutionLabels() {
        assertEquals("4K UHD", VideoLabels.resolution(3840, 2160))
        assertEquals("4K UHD", VideoLabels.resolution(3840, 1600)) // filme 2.39:1
        assertEquals("Full HD", VideoLabels.resolution(1920, 1080))
        assertEquals("Full HD", VideoLabels.resolution(1080, 1920)) // retrato
        assertEquals("Full HD", VideoLabels.resolution(1920, 800))
        assertEquals("HD", VideoLabels.resolution(1280, 720))
        assertEquals("SD", VideoLabels.resolution(720, 576))
        assertEquals("", VideoLabels.resolution(0, 0))
    }
}
