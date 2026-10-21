package com.campush.lan

import org.junit.Assert.assertEquals
import org.junit.Test

class TeeLanMediaPublisherTest {

    private class Fake : LanMediaPublisher {
        var h264 = 0; var aac = 0; var jpeg = 0; var fail = false
        override fun publishJpeg(jpeg: ByteArray) { jpeg.let { this.jpeg++ } }
        override fun publishH264(unit: H264AccessUnit) { h264++; if (fail) throw IllegalStateException("boom") }
        override fun publishAac(unit: AacAccessUnit) { aac++ }
        override fun stats(): MediaStats = MediaStats()
        override fun requestKeyFrame() = Unit
    }

    @Test
    fun forwardsToBothAndSwallowsSecondaryFailure() {
        val primary = Fake()
        val secondary = Fake().apply { fail = true }
        val tee = TeeLanMediaPublisher(primary, secondary)
        tee.publishJpeg(byteArrayOf(1))
        tee.publishH264(H264AccessUnit(listOf(byteArrayOf(0x65)), 0, true))
        tee.publishAac(AacAccessUnit(byteArrayOf(1), 0))
        assertEquals(1, primary.jpeg)
        assertEquals(1, primary.h264)
        assertEquals(1, primary.aac)
        assertEquals(1, secondary.h264)
    }
}
