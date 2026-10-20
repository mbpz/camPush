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
) {

    private var frameIndex: Int = 0

    fun onFrame(image: Image) {
        frameIndex++
        if (frameIndex % FRAME_INTERVAL != 0) return
        try {
            val (width, height) = YuvUtils.nv21Format(image.width, image.height)
            if (width <= 0 || height <= 0) return
            val nv21 = YuvUtils.imageToNv21(image, width, height)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val output = ByteArrayOutputStream(nv21.size / 4)
            val ok = yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, output)
            if (ok) {
                publisher.publishJpeg(output.toByteArray())
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
        const val FRAME_INTERVAL = 2
    }
}
