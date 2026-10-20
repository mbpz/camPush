package com.campush.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.campush.app.core.P1Controller

/**
 * 相机预览组件：AndroidView(PreviewView) + controller.bindPreview/unbindPreview。
 *
 * 预览绑定生命周期解绑由本组件负责；ON_PAUSE/ON_RESUME 只暂停/恢复预览，
 * 推流中不会中断采集与编码（docs/02 §5.4）。
 */
@Composable
fun CameraPreview(controller: P1Controller, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(controller, previewView) {
        controller.bindPreview(previewView)
        onDispose { controller.unbindPreview() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.pausePreview()
                Lifecycle.Event.ON_RESUME -> controller.resumePreview()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
