package com.campush.app.streaming

/**
 * 极简 MPEG-TS 解复用：只处理 PAT/PMT 与 H.264 视频 PES。
 *
 * MediaRecorder(MPEG_2_TS) 把相机 -> 硬件编码器的 H.264 写成 TS 流，
 * 这里把每个视频 PES 还原成一个 Annex-B access unit 交给 MediaHub。
 */
internal class TsDemuxer(
    private val onAccessUnit: (ptsUs: Long, isKey: Boolean, nalUnits: List<ByteArray>) -> Unit,
) {

    private var carry: ByteArray = ByteArray(0)
    private var pmtPid: Int = -1
    private var videoPid: Int = -1

    private var pesPayload: ByteArray = ByteArray(0)
    private var pesSize: Int = 0
    private var pesPts90: Long = -1L
    private var basePts90: Long = -1L
    private var lastPtsUs: Long = -1L

    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val combined = if (carry.isEmpty()) {
            data.copyOfRange(offset, offset + length)
        } else {
            val merged = ByteArray(carry.size + length)
            System.arraycopy(carry, 0, merged, 0, carry.size)
            System.arraycopy(data, offset, merged, carry.size, length)
            merged
        }
        var pos = 0
        while (pos + TS_PACKET_SIZE <= combined.size) {
            if (combined[pos].toInt() and 0xFF != 0x47) {
                pos++
                continue
            }
            parsePacket(combined, pos)
            pos += TS_PACKET_SIZE
        }
        carry = if (pos < combined.size) combined.copyOfRange(pos, combined.size) else ByteArray(0)
    }

    private fun parsePacket(data: ByteArray, pos: Int) {
        val b1 = data[pos + 1].toInt() and 0xFF
        val pusi = b1 and 0x40 != 0
        val pid = ((b1 and 0x1F) shl 8) or (data[pos + 2].toInt() and 0xFF)
        val afc = (data[pos + 3].toInt() shr 4) and 0x03
        var payload = pos + 4
        if (afc == 0 || afc == 2) return
        if (afc == 3) {
            val adaptLen = data[payload].toInt() and 0xFF
            payload += 1 + adaptLen
        }
        if (payload >= pos + TS_PACKET_SIZE) return

        when {
            pid == 0 -> parsePat(data, payload, pos + TS_PACKET_SIZE)
            pid == pmtPid -> parsePmt(data, payload, pos + TS_PACKET_SIZE)
            pid == videoPid -> parseVideo(data, payload, pos + TS_PACKET_SIZE, pusi)
        }
    }

    private fun parsePat(data: ByteArray, payload: Int, end: Int) {
        val section = sectionStart(data, payload, end) ?: return
        if (section + 12 > end) return
        if (data[section].toInt() and 0xFF != 0x00) return
        val programPid = ((data[section + 10].toInt() and 0x1F) shl 8) or (data[section + 11].toInt() and 0xFF)
        if (programPid in 1 until 0x1FFF) pmtPid = programPid
    }

    private fun parsePmt(data: ByteArray, payload: Int, end: Int) {
        val section = sectionStart(data, payload, end) ?: return
        if (section + 12 > end) return
        if (data[section].toInt() and 0xFF != 0x02) return
        val programInfoLen = ((data[section + 10].toInt() and 0x0F) shl 8) or (data[section + 11].toInt() and 0xFF)
        var p = section + 12 + programInfoLen
        while (p + 5 <= end) {
            val streamType = data[p].toInt() and 0xFF
            val elemPid = ((data[p + 1].toInt() and 0x1F) shl 8) or (data[p + 2].toInt() and 0xFF)
            val esInfoLen = ((data[p + 3].toInt() and 0x0F) shl 8) or (data[p + 4].toInt() and 0xFF)
            if (streamType == 0x1B) {
                videoPid = elemPid
                return
            }
            p += 5 + esInfoLen
        }
    }

    private fun sectionStart(data: ByteArray, payload: Int, end: Int): Int? {
        if (payload >= end) return null
        val pointer = data[payload].toInt() and 0xFF
        val start = payload + 1 + pointer
        return if (start + 3 <= end) start else null
    }

    private fun parseVideo(data: ByteArray, payload: Int, end: Int, pusi: Boolean) {
        if (pusi) {
            flushPes()
            if (payload + 9 > end) return
            if (data[payload].toInt() != 0 || data[payload + 1].toInt() != 0 || data[payload + 2].toInt() != 1) return
            val flags = data[payload + 7].toInt() and 0xFF
            val headerLen = data[payload + 8].toInt() and 0xFF
            pesPts90 = if (flags and 0x80 != 0 && payload + 14 <= end) readPts(data, payload + 9) else -1L
            val start = payload + 9 + headerLen
            if (start < end) appendPes(data, start, end)
        } else {
            if (videoPid >= 0 && pesSize >= 0) appendPes(data, payload, end)
        }
    }

    private fun appendPes(data: ByteArray, start: Int, end: Int) {
        if (start >= end) return
        val len = end - start
        if (pesSize + len > pesPayload.size) {
            val grown = ByteArray(maxOf(pesPayload.size * 2, pesSize + len + 4096))
            System.arraycopy(pesPayload, 0, grown, 0, pesSize)
            pesPayload = grown
        }
        System.arraycopy(data, start, pesPayload, pesSize, len)
        pesSize += len
    }

    private fun flushPes() {
        if (pesSize <= 0) {
            pesPts90 = -1L
            return
        }
        val payload = pesPayload.copyOf(pesSize)
        pesSize = 0
        val nals = splitNals(payload)
        val pts90 = pesPts90
        pesPts90 = -1L
        if (nals.isEmpty()) return
        val isKey = nals.any { (it[0].toInt() and 0x1F) == 5 || (it[0].toInt() and 0x1F) == 7 }
        if (pts90 >= 0) {
            if (basePts90 < 0) basePts90 = pts90
            val ptsUs = (pts90 - basePts90) * 1000L / 90L
            lastPtsUs = if (lastPtsUs < 0) ptsUs else maxOf(ptsUs, lastPtsUs + 1)
        } else {
            lastPtsUs = if (lastPtsUs < 0) 0L else lastPtsUs + 33_333L
        }
        onAccessUnit(lastPtsUs, isKey, nals)
    }

    private fun readPts(data: ByteArray, offset: Int): Long {
        return (((data[offset].toLong() and 0x0E) shl 29) or
            ((data[offset + 1].toLong() and 0xFF) shl 22) or
            ((data[offset + 2].toLong() and 0xFE) shl 14) or
            ((data[offset + 3].toLong() and 0xFF) shl 7) or
            ((data[offset + 4].toLong() and 0xFE) shr 1))
    }

    private fun splitNals(buf: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val from = starts[idx]
            var to = if (idx + 1 < starts.size) starts[idx + 1] - 3 else buf.size
            while (to > from && buf[to - 1].toInt() == 0) to--
            if (to > from) out.add(buf.copyOfRange(from, to))
        }
        return out
    }

    companion object {
        const val TS_PACKET_SIZE = 188
    }
}
