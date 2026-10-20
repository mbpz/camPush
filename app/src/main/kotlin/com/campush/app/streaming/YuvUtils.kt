package com.campush.app.streaming

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

    /** YuvImage 只接受 NV21/NV21 类格式的偶数尺寸。 */
    fun nv21Format(width: Int, height: Int): Pair<Int, Int> =
        if (width > 0 && height > 0) (width and 1.inv()) to (height and 1.inv()) else 0 to 0
}
