package com.campush.app.streaming.wan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink

/**
 * P1：WAN/WebRTC 烧录水印。在 VideoSource 的 I420 帧上叠加 CamPush，再交给编码器。
 */
class WanWatermarkProcessor(
    private val text: String = "CamPush",
    @Volatile var enabled: Boolean = true,
) : VideoProcessor {
    private var sink: VideoSink? = null
    private val watermark: Bitmap by lazy {
        Bitmap.createBitmap(320, 80, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                textSize = 44f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(4f, 0f, 2f, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText(text, 14f, 56f, paint)
        }
    }

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) = Unit
    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        onFrameCaptured(frame, null)
    }

    override fun onFrameCaptured(frame: VideoFrame, parameters: VideoProcessor.FrameAdaptationParameters?) {
        val target = sink ?: run { frame.release(); return }
        if (!enabled) {
            target.onFrame(frame)
            return
        }
        val i420 = runCatching { frame.buffer.toI420() }.getOrNull() ?: run {
            target.onFrame(frame)
            return
        }
        runCatching { overlay(i420) }
        val out = VideoFrame(i420, frame.rotation, frame.timestampNs)
        target.onFrame(out)
        out.release()
        frame.release()
    }

    private fun overlay(buffer: VideoFrame.I420Buffer) {
        val w = buffer.width
        val h = buffer.height
        val wmW = watermark.width.coerceAtMost(w / 2)
        val wmH = watermark.height.coerceAtMost(h / 2)
        val x0 = w - wmW - 16
        val y0 = h - wmH - 16
        if (x0 < 0 || y0 < 0) return
        val pixels = IntArray(wmW * wmH)
        val scaled = Bitmap.createScaledBitmap(watermark, wmW, wmH, true)
        scaled.getPixels(pixels, 0, wmW, 0, 0, wmW, wmH)
        if (scaled !== watermark) scaled.recycle()
        val y = buffer.dataY
        val u = buffer.dataU
        val v = buffer.dataV
        val strideY = buffer.strideY
        val strideU = buffer.strideU
        val strideV = buffer.strideV
        for (row in 0 until wmH) {
            for (col in 0 until wmW) {
                val argb = pixels[row * wmW + col]
                val alpha = (argb ushr 24) and 0xFF
                if (alpha < 24) continue
                val gray = (((argb ushr 16) and 0xFF) * 0.299 +
                    ((argb ushr 8) and 0xFF) * 0.587 +
                    (argb and 0xFF) * 0.114).toInt().coerceIn(0, 255)
                val x = x0 + col
                val yy = y0 + row
                val yi = yy * strideY + x
                if (yi < y.limit()) {
                    val original = y.get(yi).toInt() and 0xFF
                    y.put(yi, ((original * (255 - alpha) + gray * alpha) / 255).toByte())
                }
                if ((x and 1) == 0 && (yy and 1) == 0) {
                    val ui = (yy / 2) * strideU + (x / 2)
                    val vi = (yy / 2) * strideV + (x / 2)
                    if (ui < u.limit()) u.put(ui, 128.toByte())
                    if (vi < v.limit()) v.put(vi, 128.toByte())
                }
            }
        }
    }
}
