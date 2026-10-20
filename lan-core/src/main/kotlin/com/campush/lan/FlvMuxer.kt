package com.campush.lan

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 最小 HTTP-FLV 封装器（契约：05 §8.4；任务卡 item 6）。
 *
 * 本类只负责“字节结构正确”，不做 IO。所有 tag 返回值均包含 11 字节 tag header、
 * tag data 与结尾的 4 字节 PreviousTagSize（可直接顺序写入 socket）。
 *
 * FLV 布局：
 * - FLV Header: 'FLV' + version(1) + flags(0x01 video | 0x04 audio) + dataOffset(9)
 * - PreviousTagSize0 = 0
 * - Tag: type(1) + dataSize(3) + timestamp(3) + timestampExtended(1) + streamId(3) +
 *        data(dataSize) + previousTagSize(4)
 */
internal object FlvMuxer {

    const val TAG_AUDIO = 8
    const val TAG_VIDEO = 9
    const val TAG_SCRIPT = 18

    /** FLV header + PreviousTagSize0（共 13 字节）。 */
    fun header(hasAudio: Boolean, hasVideo: Boolean = true): ByteArray {
        var flags = 0
        if (hasVideo) flags = flags or 0x01
        if (hasAudio) flags = flags or 0x04
        val out = ByteArray(13)
        out[0] = 'F'.code.toByte()
        out[1] = 'L'.code.toByte()
        out[2] = 'V'.code.toByte()
        out[3] = 1
        out[4] = flags.toByte()
        // dataOffset = 9
        out[5] = 0
        out[6] = 0
        out[7] = 0
        out[8] = 9
        // PreviousTagSize0 = 0
        return out
    }

    /** 通用 tag 封装（含 PreviousTagSize）。 */
    fun tag(type: Int, timestampMs: Long, data: ByteArray): ByteArray {
        val dataSize = data.size
        val out = ByteArray(11 + dataSize + 4)
        out[0] = type.toByte()
        // DataSize, 24 bit big-endian
        out[1] = ((dataSize ushr 16) and 0xFF).toByte()
        out[2] = ((dataSize ushr 8) and 0xFF).toByte()
        out[3] = (dataSize and 0xFF).toByte()
        // Timestamp 低 24 位 + TimestampExtended 高 8 位
        out[4] = ((timestampMs ushr 16) and 0xFF).toByte()
        out[5] = ((timestampMs ushr 8) and 0xFF).toByte()
        out[6] = (timestampMs and 0xFF).toByte()
        out[7] = ((timestampMs ushr 24) and 0xFF).toByte()
        // StreamID = 0
        System.arraycopy(data, 0, out, 11, dataSize)
        // PreviousTagSize = 11 + dataSize
        val prev = 11 + dataSize
        out[11 + dataSize] = ((prev ushr 24) and 0xFF).toByte()
        out[11 + dataSize + 1] = ((prev ushr 16) and 0xFF).toByte()
        out[11 + dataSize + 2] = ((prev ushr 8) and 0xFF).toByte()
        out[11 + dataSize + 3] = (prev and 0xFF).toByte()
        return out
    }

    /** AVCDecoderConfigurationRecord（ISO 14496-15，AVC sequence header 负载）。 */
    fun avcDecoderConfigurationRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArray(11 + sps.size + pps.size)
        var i = 0
        out[i++] = 1 // configurationVersion
        out[i++] = if (sps.size > 1) sps[1] else 0x42 // AVCProfileIndication
        out[i++] = if (sps.size > 2) sps[2] else 0x00 // profile_compatibility
        out[i++] = if (sps.size > 3) sps[3] else 0x1F // AVCLevelIndication
        out[i++] = 0xFF.toByte() // 6 bits reserved + lengthSizeMinusOne = 3
        out[i++] = 0xE1.toByte() // 3 bits reserved + numOfSequenceParameterSets = 1
        out[i++] = ((sps.size ushr 8) and 0xFF).toByte()
        out[i++] = (sps.size and 0xFF).toByte()
        System.arraycopy(sps, 0, out, i, sps.size)
        i += sps.size
        out[i++] = 1 // numOfPictureParameterSets
        out[i++] = ((pps.size ushr 8) and 0xFF).toByte()
        out[i++] = (pps.size and 0xFF).toByte()
        System.arraycopy(pps, 0, out, i, pps.size)
        return out
    }

    /** AVC sequence header tag（AVCPacketType=0）。 */
    fun avcSequenceHeaderTag(sps: ByteArray, pps: ByteArray, timestampMs: Long = 0L): ByteArray {
        val record = avcDecoderConfigurationRecord(sps, pps)
        val data = ByteArray(5 + record.size)
        data[0] = 0x17 // FrameType=1(key) + CodecID=7(AVC)
        data[1] = 0x00 // AVCPacketType = 0 (sequence header)
        data[2] = 0
        data[3] = 0
        data[4] = 0 // CompositionTime = 0
        System.arraycopy(record, 0, data, 5, record.size)
        return tag(TAG_VIDEO, timestampMs, data)
    }

    /** 把 Annex-B NAL 单元（不含起始码）转成 AVCC 4 字节长度前缀负载。 */
    fun avccPayload(nalUnits: List<ByteArray>): ByteArray {
        var total = 0
        for (nal in nalUnits) total += 4 + nal.size
        val out = ByteArray(total)
        var offset = 0
        for (nal in nalUnits) {
            val size = nal.size
            out[offset] = ((size ushr 24) and 0xFF).toByte()
            out[offset + 1] = ((size ushr 16) and 0xFF).toByte()
            out[offset + 2] = ((size ushr 8) and 0xFF).toByte()
            out[offset + 3] = (size and 0xFF).toByte()
            System.arraycopy(nal, 0, out, offset + 4, size)
            offset += 4 + size
        }
        return out
    }

    /** AVC NALU 帧 tag（AVCPacketType=1，无 B 帧时 cts=0）。 */
    fun videoFrameTag(
        nalUnits: List<ByteArray>,
        isKeyFrame: Boolean,
        timestampMs: Long,
        compositionTimeMs: Int = 0,
    ): ByteArray {
        val payload = avccPayload(nalUnits)
        val data = ByteArray(5 + payload.size)
        val frameType = if (isKeyFrame) 1 else 2
        data[0] = ((frameType shl 4) or 7).toByte()
        data[1] = 0x01 // AVCPacketType = 1 (NALU)
        data[2] = ((compositionTimeMs ushr 16) and 0xFF).toByte()
        data[3] = ((compositionTimeMs ushr 8) and 0xFF).toByte()
        data[4] = (compositionTimeMs and 0xFF).toByte()
        System.arraycopy(payload, 0, data, 5, payload.size)
        return tag(TAG_VIDEO, timestampMs, data)
    }

    /** AAC sequence header tag：AudioSpecificConfig（AACPacketType=0）。 */
    fun aacSequenceHeaderTag(audioSpecificConfig: ByteArray, timestampMs: Long = 0L): ByteArray {
        val data = ByteArray(2 + audioSpecificConfig.size)
        data[0] = 0xAF.toByte() // SoundFormat=10(AAC) + 3/1/1
        data[1] = 0x00 // AACPacketType = 0
        System.arraycopy(audioSpecificConfig, 0, data, 2, audioSpecificConfig.size)
        return tag(TAG_AUDIO, timestampMs, data)
    }

    /** 裸 AAC 帧 tag（AACPacketType=1）。 */
    fun aacFrameTag(frame: ByteArray, timestampMs: Long): ByteArray {
        val data = ByteArray(2 + frame.size)
        data[0] = 0xAF.toByte()
        data[1] = 0x01 // AACPacketType = 1
        System.arraycopy(frame, 0, data, 2, frame.size)
        return tag(TAG_AUDIO, timestampMs, data)
    }

    /**
     * onMetaData script tag（AMF0）。
     *
     * LAN 契约表注明“LAN 不发 script tag”，但任务卡明确要求生成 onMetaData；
     * 本方法独立提供，由 MediaHub / HttpServer 决定是否发送（当前发送，兼容常见 FLV 播放器）。
     */
    fun scriptTag(
        hasAudio: Boolean,
        hasVideo: Boolean = true,
        durationMs: Long = 0L,
        width: Int = 0,
        height: Int = 0,
        frameRate: Double = 0.0,
    ): ByteArray {
        val buffer = ByteArrayOutputStream(256)
        val out = DataOutputStream(buffer)
        writeAmfString(out, "@setDataFrame")
        writeAmfString(out, "onMetaData")

        // ECMA array
        val entries = ArrayList<Pair<String, Any>>()
        entries.add("duration" to durationMs / 1000.0)
        entries.add("width" to width.toDouble())
        entries.add("height" to height.toDouble())
        if (hasVideo) entries.add("videocodecid" to 7.0)
        if (hasAudio) entries.add("audiocodecid" to 10.0)
        entries.add("framerate" to frameRate)

        out.writeByte(0x08)
        out.writeInt(entries.size)
        for ((key, value) in entries) {
            out.writeShort(key.length)
            out.writeBytes(key)
            when (value) {
                is Double -> {
                    out.writeByte(0x00)
                    out.writeLong(java.lang.Double.doubleToLongBits(value))
                }
                is Boolean -> {
                    out.writeByte(0x01)
                    out.writeByte(if (value) 1 else 0)
                }
                else -> writeAmfString(out, value.toString())
            }
        }
        // object end marker
        out.writeShort(0)
        out.writeByte(0x09)
        out.flush()
        return tag(TAG_SCRIPT, 0L, buffer.toByteArray())
    }

    private fun writeAmfString(out: DataOutputStream, value: String) {
        out.writeByte(0x02)
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }
}
