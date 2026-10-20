package com.campush.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlvMuxerTest {

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x01)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    @Test
    fun headerLayout() {
        val header = FlvMuxer.header(hasAudio = true, hasVideo = true)
        assertEquals(13, header.size)
        assertEquals('F'.code.toByte(), header[0])
        assertEquals('L'.code.toByte(), header[1])
        assertEquals('V'.code.toByte(), header[2])
        assertEquals(1, header[3].toInt())
        // flags: 0x01 video | 0x04 audio
        assertEquals(0x05, header[4].toInt() and 0xFF)
        assertEquals(0, header[5].toInt())
        assertEquals(0, header[6].toInt())
        assertEquals(0, header[7].toInt())
        assertEquals(9, header[8].toInt())
        assertEquals(0L, FlvParser.readUInt32(header, 9)) // PreviousTagSize0
    }

    @Test
    fun avcSequenceHeaderTag() {
        val tag = FlvMuxer.avcSequenceHeaderTag(sps, pps)
        assertEquals(FlvMuxer.TAG_VIDEO, tag[0].toInt())
        val dataSize = tagDataSize(tag)
        // 5 字节 video header + AVCDecoderConfigurationRecord
        assertEquals(5 + 11 + sps.size + pps.size, dataSize)
        assertEquals(0L, timestamp(tag))
        // video data: key frame(0x17) + AVCPacketType=0 + cts=0
        assertEquals(0x17, tag[11].toInt() and 0xFF)
        assertEquals(0, tag[12].toInt() and 0xFF)
        assertEquals(0, tag[13].toInt() or tag[14].toInt() or tag[15].toInt())
        // AVCDecoderConfigurationRecord
        val record = 11 + 5
        assertEquals(1, tag[record].toInt()) // configurationVersion
        assertEquals(sps[1].toInt() and 0xFF, tag[record + 1].toInt() and 0xFF) // profile
        assertEquals(sps[2].toInt() and 0xFF, tag[record + 2].toInt() and 0xFF) // compatibility
        assertEquals(sps[3].toInt() and 0xFF, tag[record + 3].toInt() and 0xFF) // level
        assertEquals(0xFF, tag[record + 4].toInt() and 0xFF)
        assertEquals(0xE1, tag[record + 5].toInt() and 0xFF)
        assertEquals(sps.size, ((tag[record + 6].toInt() and 0xFF) shl 8) or (tag[record + 7].toInt() and 0xFF))
        assertEquals(1, tag[record + 8 + sps.size].toInt())
        assertEquals(11 + dataSize, FlvParser.readUInt32(tag, 11 + dataSize).toInt())
    }

    @Test
    fun videoFrameTagUsesLengthPrefixedNalUnits() {
        val nalu1 = byteArrayOf(0x41, 0x01, 0x02, 0x03)
        val nalu2 = byteArrayOf(0x41, 0x09)
        val tag = FlvMuxer.videoFrameTag(listOf(nalu1, nalu2), isKeyFrame = false, timestampMs = 1_000L)
        assertEquals(FlvMuxer.TAG_VIDEO, tag[0].toInt())
        assertEquals(1_000L, timestamp(tag))
        assertEquals(2, tag[11].toInt() ushr 4) // FrameType=2 非关键帧
        assertEquals(7, tag[11].toInt() and 0x0F) // CodecID=7 AVC
        assertEquals(1, tag[12].toInt()) // AVCPacketType=1 NALU
        assertEquals(0, tag[13].toInt() or tag[14].toInt() or tag[15].toInt()) // cts=0
        // length-prefixed payload
        assertEquals(nalu1.size, FlvParser.readUInt32(tag, 16).toInt())
        assertTrue(tag.copyOfRange(20, 20 + nalu1.size).contentEquals(nalu1))
        val secondOffset = 20 + nalu1.size
        assertEquals(nalu2.size, FlvParser.readUInt32(tag, secondOffset).toInt())
        assertTrue(tag.copyOfRange(secondOffset + 4, secondOffset + 4 + nalu2.size).contentEquals(nalu2))
        assertEquals(11 + tagDataSize(tag), FlvParser.readUInt32(tag, 11 + tagDataSize(tag)).toInt())
    }

    @Test
    fun audioSequenceAndFrameTags() {
        val asc = byteArrayOf(0x11, 0x90.toByte())
        val seq = FlvMuxer.aacSequenceHeaderTag(asc)
        assertEquals(FlvMuxer.TAG_AUDIO, seq[0].toInt())
        assertEquals(0xAF, seq[11].toInt() and 0xFF)
        assertEquals(0, seq[12].toInt())
        assertEquals(0x11, seq[13].toInt() and 0xFF)
        assertEquals(0x90, seq[14].toInt() and 0xFF)

        val frame = FlvMuxer.aacFrameTag(byteArrayOf(0x21, 0x22), timestampMs = 40L)
        assertEquals(FlvMuxer.TAG_AUDIO, frame[0].toInt())
        assertEquals(40L, timestamp(frame))
        assertEquals(0xAF, frame[11].toInt() and 0xFF)
        assertEquals(1, frame[12].toInt())
        assertEquals(0x21, frame[13].toInt() and 0xFF)
    }

    @Test
    fun scriptTagIsAmfOnMetaData() {
        val tag = FlvMuxer.scriptTag(hasAudio = true, hasVideo = true)
        assertEquals(FlvMuxer.TAG_SCRIPT, tag[0].toInt())
        val text = String(tag.copyOfRange(11, 11 + tagDataSize(tag)), Charsets.ISO_8859_1)
        assertTrue(text.contains("@setDataFrame"))
        assertTrue(text.contains("onMetaData"))
        assertTrue(text.contains("videocodecid"))
        assertEquals(0L, timestamp(tag))
    }

    @Test
    fun timestampExtendedUsesHighByte() {
        val timestampMs = 0x01000005L
        val tag = FlvMuxer.tag(FlvMuxer.TAG_VIDEO, timestampMs, byteArrayOf(1))
        // 低 24 位 big-endian：0x00 0x00 0x05，扩展高位 0x01
        assertEquals(0x00, tag[4].toInt())
        assertEquals(0x00, tag[5].toInt())
        assertEquals(0x05, tag[6].toInt())
        assertEquals(0x01, tag[7].toInt())
        assertEquals(timestampMs, timestamp(tag))
    }

    private fun tagDataSize(tag: ByteArray): Int =
        ((tag[1].toInt() and 0xFF) shl 16) or ((tag[2].toInt() and 0xFF) shl 8) or (tag[3].toInt() and 0xFF)

    private fun timestamp(tag: ByteArray): Long =
        ((tag[7].toLong() and 0xFF) shl 24) or
            ((tag[4].toLong() and 0xFF) shl 16) or
            ((tag[5].toLong() and 0xFF) shl 8) or
            (tag[6].toLong() and 0xFF)
}
