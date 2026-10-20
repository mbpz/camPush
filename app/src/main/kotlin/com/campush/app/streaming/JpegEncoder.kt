package com.campush.app.streaming

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.campush.lan.LanMediaPublisher
import java.io.ByteArrayOutputStream

/**
 * MJPEG 备用通道：YUV_420_888 -> NV21 -> YuvImage JPEG -> publisher.publishJpeg。
 * 每 [FRAME_INTERVAL] 帧压缩一次（720p 下 JPEG 压缩较重，MJPEG 只作 FLV 失败时的降级通道）。
 */
class JpegEncoder(
    private val publisher: LanMediaPublisher,
    private val quality: Int = 65,
    private val onJpeg: ((ByteArray) -> Unit)? = null,
) {

    private var frameIndex: Int = 0

    fun onFrame(image: Image) {
        frameIndex++
        if (frameIndex % FRAME_INTERVAL != 0) return
        try {
            val (width, height) = YuvUtils.nv21Format(image.width, image.height)
            if (width <= 0 || height <= 0) return
            // 1080p 全尺寸 JPEG 过重：MJPEG 作为降级通道缩到 ≤960 宽，压缩耗时显著下降。
            val scaleW = if (width > MAX_JPEG_WIDTH) MAX_JPEG_WIDTH else width
            val scaleH = (height.toLong() * scaleW / width).toInt() and 1.inv()
            val nv21 = YuvUtils.imageToNv21Scaled(image, scaleW, scaleH)
            if (nv21.isEmpty()) return
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, scaleW, scaleH, null)
            val output = ByteArrayOutputStream(nv21.size / 4)
            val ok = yuvImage.compressToJpeg(Rect(0, 0, scaleW, scaleH), quality, output)
            if (ok) {
                val jpeg = output.toByteArray()
                publisher.publishJpeg(jpeg)
                onJpeg?.invoke(jpeg)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "jpeg encode failed", t)
        }
    }

    fun close() {
        frameIndex = 0
    }

    private companion object {
        const val TAG = "JpegEncoder"
        const val FRAME_INTERVAL = 3
        const val MAX_JPEG_WIDTH = 960
    }
}
