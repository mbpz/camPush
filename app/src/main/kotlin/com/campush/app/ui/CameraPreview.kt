package com.campush.app.ui

import android.graphics.BitmapFactory
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campush.app.core.P1Controller
import com.campush.app.ui.theme.CamPushColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相机预览：
 * - 空闲时用 CameraX PreviewView（全质量预览）；
 * - 推流时 CameraX Preview 已释放以换取分析帧率，改用编码管线产出的 JPEG（≤640 宽、约 7.5fps）
 *   在 Compose 中渲染，保证画面可见且不抢相机带宽。
 */
@Composable
fun CameraPreview(controller: P1Controller, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (state.streaming) {
        if (state.surfacePipeline) {
            SurfaceStreamPreview(controller = controller, modifier = modifier)
        } else {
            JpegPreview(controller = controller, modifier = modifier)
        }
    } else {
        CameraXPreview(controller = controller, modifier = modifier)
    }
}

@Composable
private fun JpegPreview(controller: P1Controller, modifier: Modifier = Modifier) {
    val jpeg by controller.previewJpeg.collectAsStateWithLifecycle()
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(jpeg) {
        val bytes = jpeg ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CamPushColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = "摄像头实时预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = "正在启动相机…",
                style = MaterialTheme.typography.bodyMedium,
                color = CamPushColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun SurfaceStreamPreview(controller: P1Controller, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val surfaceView = remember(context) { SurfaceView(context) }

    DisposableEffect(controller, surfaceView) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                controller.bindStreamPreview(surfaceView)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                controller.bindStreamPreview(surfaceView)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                controller.unbindStreamPreview()
            }
        }
        surfaceView.holder.addCallback(callback)
        if (surfaceView.holder.surface?.isValid == true) {
            controller.bindStreamPreview(surfaceView)
        }
        onDispose {
            surfaceView.holder.removeCallback(callback)
            controller.unbindStreamPreview()
        }
    }

    AndroidView(factory = { surfaceView }, modifier = modifier)
}

@Composable
private fun CameraXPreview(controller: P1Controller, modifier: Modifier = Modifier) {
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
