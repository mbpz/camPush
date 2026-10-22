package com.campush.app.streaming

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.Image

/**
 * YUV_420_888（CameraX 输出）与 I420 / NV21 之间的转换。
 * 全部使用绝对下标读写，兼容不同 rowStride / pixelStride（部分机型 UV 平面 stride 不规则）。
 */
internal object YuvUtils {

    /** 把相机 YUV_420_888 帧拷入 MediaCodec 的 COLOR_FormatYUV420Flexible 输入 Image（I420 布局）。 */
    fun copyImageToImage(src: Image, dst: Image, width: Int, height: Int) {
        val srcPlanes = src.planes
        val dstPlanes = dst.planes
        if (srcPlanes.size < 3 || dstPlanes.size < 3) return
        copyPlane(srcPlanes[0], dstPlanes[0], width, height)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        copyPlane(srcPlanes[1], dstPlanes[1], chromaWidth, chromaHeight)
        copyPlane(srcPlanes[2], dstPlanes[2], chromaWidth, chromaHeight)
    }

    /**
     * 源图缩放到目标尺寸后拷入 MediaCodec 输入 Image。
     * 用于相机输出尺寸偶发变化（例如 1080/1088 对齐）时避免反复重建编码器。
     */
    fun copyImageToImageScaled(src: Image, dst: Image, dstWidth: Int, dstHeight: Int) {
        val sw = src.width
        val sh = src.height
        if (sw <= 0 || sh <= 0 || dstWidth <= 0 || dstHeight <= 0) return
        val srcPlanes = src.planes
        val dstPlanes = dst.planes
        if (srcPlanes.size < 3 || dstPlanes.size < 3) return
        copyPlaneScaled(srcPlanes[0], dstPlanes[0], sw, sh, dstWidth, dstHeight)
        val cw = dstWidth / 2
        val ch = dstHeight / 2
        copyPlaneScaled(srcPlanes[1], dstPlanes[1], sw / 2, sh / 2, cw, ch)
        copyPlaneScaled(srcPlanes[2], dstPlanes[2], sw / 2, sh / 2, cw, ch)
    }

    private fun copyPlaneScaled(
        src: Image.Plane,
        dst: Image.Plane,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ) {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) return
        val srcBuf = src.buffer
        val dstBuf = dst.buffer
        for (row in 0 until dstHeight) {
            val srcRow = (row.toLong() * srcHeight / dstHeight).toInt()
            val srcBase = srcRow * src.rowStride
            val dstBase = row * dst.rowStride
            for (col in 0 until dstWidth) {
                val srcCol = (col.toLong() * srcWidth / dstWidth).toInt()
                dstBuf.put(dstBase + col * dst.pixelStride, srcBuf.get(srcBase + srcCol * src.pixelStride))
            }
        }
    }

    private val watermarkBitmap: Bitmap by lazy {
        Bitmap.createBitmap(320, 80, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                textSize = 44f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(4f, 0f, 2f, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText("CamPush", 14f, 56f, paint)
        }
    }

    /** 在编码器输入 YUV 图像右下角烧录 CamPush 水印（仅亮度/色度近似，不引入额外编码）。 */
    fun overlayWatermark(dst: Image, width: Int, height: Int) {
        val planes = dst.planes
        if (planes.size < 3 || width <= 0 || height <= 0) return
        val bmp = watermarkBitmap
        val wmW = bmp.width.coerceAtMost(width / 2)
        val wmH = bmp.height.coerceAtMost(height / 2)
        val x0 = width - wmW - 16
        val y0 = height - wmH - 16
        if (x0 < 0 || y0 < 0) return
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val pixels = IntArray(wmW * wmH)
        val scaled = Bitmap.createScaledBitmap(bmp, wmW, wmH, true)
        scaled.getPixels(pixels, 0, wmW, 0, 0, wmW, wmH)
        if (scaled !== bmp) scaled.recycle()
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until wmH) {
            for (col in 0 until wmW) {
                val argb = pixels[row * wmW + col]
                val alpha = (argb ushr 24) and 0xFF
                if (alpha < 24) continue
                val gray = (((argb ushr 16) and 0xFF) * 0.299 +
                    ((argb ushr 8) and 0xFF) * 0.587 +
                    (argb and 0xFF) * 0.114).toInt().coerceIn(0, 255)
                val x = x0 + col
                val y = y0 + row
                val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                val original = yBuf.get(yIndex).toInt() and 0xFF
                val blended = (original * (255 - alpha) + gray * alpha) / 255
                yBuf.put(yIndex, blended.toByte())
                if ((x and 1) == 0 && (y and 1) == 0) {
                    val cx = x / 2
                    val cy = y / 2
                    val ui = cy * uPlane.rowStride + cx * uPlane.pixelStride
                    val vi = cy * vPlane.rowStride + cx * vPlane.pixelStride
                    uBuf.put(ui, 128.toByte())
                    vBuf.put(vi, 128.toByte())
                }
            }
        }
    }

    /** 在 I420/NV12 字节数组中烧录水印（ByteBuffer 编码路径）。 */
    fun overlayWatermarkBytes(bytes: ByteArray, width: Int, height: Int, semiPlanar: Boolean) {
        if (width <= 0 || height <= 0 || bytes.size < width * height * 3 / 2) return
        val bmp = watermarkBitmap
        val wmW = bmp.width.coerceAtMost(width / 2)
        val wmH = bmp.height.coerceAtMost(height / 2)
        val x0 = width - wmW - 16
        val y0 = height - wmH - 16
        if (x0 < 0 || y0 < 0) return
        val pixels = IntArray(wmW * wmH)
        val scaled = Bitmap.createScaledBitmap(bmp, wmW, wmH, true)
        scaled.getPixels(pixels, 0, wmW, 0, 0, wmW, wmH)
        if (scaled !== bmp) scaled.recycle()
        val ySize = width * height
        val chromaSize = (width / 2) * (height / 2)
        for (row in 0 until wmH) {
            for (col in 0 until wmW) {
                val argb = pixels[row * wmW + col]
                val alpha = (argb ushr 24) and 0xFF
                if (alpha < 24) continue
                val gray = (((argb ushr 16) and 0xFF) * 0.299 +
                    ((argb ushr 8) and 0xFF) * 0.587 +
                    (argb and 0xFF) * 0.114).toInt().coerceIn(0, 255)
                val x = x0 + col
                val y = y0 + row
                val yi = y * width + x
                val original = bytes[yi].toInt() and 0xFF
                bytes[yi] = ((original * (255 - alpha) + gray * alpha) / 255).toByte()
                if ((x and 1) == 0 && (y and 1) == 0) {
                    val cx = x / 2
                    val cy = y / 2
                    if (semiPlanar) {
                        val uv = ySize + cy * width + cx * 2
                        if (uv + 1 < bytes.size) {
                            bytes[uv] = 128.toByte()
                            bytes[uv + 1] = 128.toByte()
                        }
                    } else {
                        val u = ySize + cy * (width / 2) + cx
                        val v = ySize + chromaSize + cy * (width / 2) + cx
                        if (v < bytes.size) {
                            bytes[u] = 128.toByte()
                            bytes[v] = 128.toByte()
                        }
                    }
                }
            }
        }
    }

    /** YUV_420_888 -> NV21（Y 平面 + VU 交错），供 YuvImage JPEG 压缩。 */
    fun imageToNv21(src: Image, width: Int, height: Int): ByteArray {
        val frameWidth = width and 1.inv()
        val frameHeight = height and 1.inv()
        val ySize = frameWidth * frameHeight
        val chromaWidth = frameWidth / 2
        val chromaHeight = frameHeight / 2
        val output = ByteArray(ySize + chromaWidth * chromaHeight * 2)
        if (frameWidth <= 0 || frameHeight <= 0) return output

        val planes = src.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val rowBuffer = ByteArray(frameWidth)
        for (row in 0 until frameHeight) {
            val srcPos = row * yPlane.rowStride
            if (yPlane.pixelStride == 1) {
                yPlane.buffer.position(srcPos)
                yPlane.buffer.get(rowBuffer, 0, frameWidth)
            } else {
                for (col in 0 until frameWidth) {
                    rowBuffer[col] = yPlane.buffer.get(srcPos + col * yPlane.pixelStride)
                }
            }
            System.arraycopy(rowBuffer, 0, output, row * frameWidth, frameWidth)
        }

        var dst = ySize
        for (row in 0 until chromaHeight) {
            val vRow = row * vPlane.rowStride
            val uRow = row * uPlane.rowStride
            for (col in 0 until chromaWidth) {
                output[dst++] = vPlane.buffer.get(vRow + col * vPlane.pixelStride)
                output[dst++] = uPlane.buffer.get(uRow + col * uPlane.pixelStride)
            }
        }
        return output
    }

    private fun copyPlane(src: Image.Plane, dst: Image.Plane, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val srcBuffer = src.buffer
        val dstBuffer = dst.buffer
        val rowBuffer = ByteArray(width)
        for (row in 0 until height) {
            val srcRow = row * src.rowStride
            val dstRow = row * dst.rowStride
            if (src.pixelStride == 1) {
                srcBuffer.position(srcRow)
                srcBuffer.get(rowBuffer, 0, width)
            } else {
                for (col in 0 until width) {
                    rowBuffer[col] = srcBuffer.get(srcRow + col * src.pixelStride)
                }
            }
            if (dst.pixelStride == 1) {
                dstBuffer.position(dstRow)
                dstBuffer.put(rowBuffer, 0, width)
            } else {
                for (col in 0 until width) {
                    dstBuffer.put(dstRow + col * dst.pixelStride, rowBuffer[col])
                }
            }
        }
    }


    /**
     * YUV_420_888 -> 缩放到 dstWidth×dstHeight 的 NV21（最近邻采样）。
     * MJPEG 只作降级通道，1080p 全尺寸 JPEG 在 CPU 上过重，缩到 ≤960 宽可把压缩耗时降一个数量级。
     */
    fun imageToNv21Scaled(src: Image, dstWidth: Int, dstHeight: Int): ByteArray {
        val w = dstWidth and 1.inv()
        val h = dstHeight and 1.inv()
        if (w <= 0 || h <= 0) return ByteArray(0)
        val sw = src.width
        val sh = src.height
        if (sw <= 0 || sh <= 0) return ByteArray(0)
        val ySize = w * h
        val cw = w / 2
        val ch = h / 2
        val out = ByteArray(ySize + cw * ch * 2)
        val planes = src.planes
        val yP = planes[0]
        val uP = planes[1]
        val vP = planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yStride = yP.rowStride
        val yPixel = yP.pixelStride
        val uStride = uP.rowStride
        val uPixel = uP.pixelStride
        val vStride = vP.rowStride
        val vPixel = vP.pixelStride
        var dst = 0
        for (row in 0 until h) {
            val srcRow = row * sh / h
            val base = srcRow * yStride
            for (col in 0 until w) {
                val srcCol = col * sw / w
                out[dst++] = yBuf.get(base + srcCol * yPixel)
            }
        }
        val chromaOffset = ySize
        var cPos = chromaOffset
        for (row in 0 until ch) {
            val srcRow = row * (sh / 2) / ch
            val uBase = srcRow * uStride
            val vBase = srcRow * vStride
            for (col in 0 until cw) {
                val srcCol = col * (sw / 2) / cw
                out[cPos++] = vBuf.get(vBase + srcCol * vPixel)
                out[cPos++] = uBuf.get(uBase + srcCol * uPixel)
            }
        }
        return out
    }


    /** YUV_420_888 -> I420（Y、U、V 平面顺序），供 COLOR_FormatYUV420Planar 编码器。 */
    fun imageToI420(src: Image, width: Int, height: Int): ByteArray {
        val w = width and 1.inv()
        val h = height and 1.inv()
        if (w <= 0 || h <= 0) return ByteArray(0)
        val out = ByteArray(w * h * 3 / 2)
        imageToI420Into(src, w, h, out)
        return out
    }

    /** 复用调用方缓冲区的 I420 转换（避免 30fps 下每帧数 MB 的 GC 压力）。 */
    fun imageToI420Into(src: Image, width: Int, height: Int, out: ByteArray) {
        val w = width and 1.inv()
        val h = height and 1.inv()
        if (w <= 0 || h <= 0 || out.size < w * h * 3 / 2) return
        val ySize = w * h
        copyY(src.planes[0], out, 0, w, h)
        copyChromaPlane(src.planes[1], out, ySize, w / 2, h / 2)
        copyChromaPlane(src.planes[2], out, ySize + ySize / 4, w / 2, h / 2)
    }

    /** YUV_420_888 -> NV12（Y 平面 + UV 交错），供 COLOR_FormatYUV420SemiPlanar 编码器。 */
    fun imageToNv12(src: Image, width: Int, height: Int): ByteArray {
        val w = width and 1.inv()
        val h = height and 1.inv()
        if (w <= 0 || h <= 0) return ByteArray(0)
        val out = ByteArray(w * h * 3 / 2)
        imageToNv12Into(src, w, h, out)
        return out
    }

    /** 复用调用方缓冲区的 NV12 转换。 */
    fun imageToNv12Into(src: Image, width: Int, height: Int, out: ByteArray) {
        val w = width and 1.inv()
        val h = height and 1.inv()
        if (w <= 0 || h <= 0 || out.size < w * h * 3 / 2) return
        val ySize = w * h
        copyY(src.planes[0], out, 0, w, h)
        val uPlane = src.planes[1]
        val vPlane = src.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        var pos = ySize
        for (row in 0 until h / 2) {
            val uRow = row * uPlane.rowStride
            val vRow = row * vPlane.rowStride
            for (col in 0 until w / 2) {
                out[pos++] = uBuf.get(uRow + col * uPlane.pixelStride)
                out[pos++] = vBuf.get(vRow + col * vPlane.pixelStride)
            }
        }
    }

    private fun copyY(plane: Image.Plane, out: ByteArray, outOffset: Int, width: Int, height: Int) {
        val buf = plane.buffer
        val rowBytes = width
        for (row in 0 until height) {
            val srcPos = row * plane.rowStride
            if (plane.pixelStride == 1) {
                buf.position(srcPos)
                buf.get(out, outOffset + row * rowBytes, minOf(rowBytes, buf.remaining()))
            } else {
                for (col in 0 until width) {
                    out[outOffset + row * rowBytes + col] = buf.get(srcPos + col * plane.pixelStride)
                }
            }
        }
    }

    private fun copyChromaPlane(plane: Image.Plane, out: ByteArray, outOffset: Int, width: Int, height: Int) {
        val buf = plane.buffer
        for (row in 0 until height) {
            val srcPos = row * plane.rowStride
            for (col in 0 until width) {
                out[outOffset + row * width + col] = buf.get(srcPos + col * plane.pixelStride)
            }
        }
    }

    /** YuvImage 只接受 NV21/NV21 类格式的偶数尺寸。 */
    fun nv21Format(width: Int, height: Int): Pair<Int, Int> =
        if (width > 0 && height > 0) (width and 1.inv()) to (height and 1.inv()) else 0 to 0
}
