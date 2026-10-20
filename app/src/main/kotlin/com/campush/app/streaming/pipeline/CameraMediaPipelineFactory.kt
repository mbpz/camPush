package com.campush.app.streaming.pipeline

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.campush.app.camera.Camera2SurfacePipeline
import com.campush.app.core.MediaPipeline
import com.campush.app.core.MediaPipelineCallbacks
import com.campush.app.core.MediaPipelineFactory
import com.campush.lan.LanMediaPublisher

/** 组合根：把具体管线适配器装配成 [MediaPipelineFactory]，引擎只看到契约。 */
class CameraMediaPipelineFactory(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : MediaPipelineFactory {

    override fun isSurfaceSupported(): Boolean = Camera2SurfacePipeline.isSupported()

    override fun createSurface(
        publisher: LanMediaPublisher,
        callbacks: MediaPipelineCallbacks,
        nowUs: () -> Long,
    ): MediaPipeline? = if (isSurfaceSupported()) {
        SurfaceMediaPipeline(context.applicationContext, publisher, callbacks, nowUs)
    } else {
        null
    }

    override fun createLegacy(
        publisher: LanMediaPublisher,
        callbacks: MediaPipelineCallbacks,
        nowUs: () -> Long,
    ): MediaPipeline = CameraXMediaPipeline(context.applicationContext, lifecycleOwner, publisher, callbacks, nowUs)
}
