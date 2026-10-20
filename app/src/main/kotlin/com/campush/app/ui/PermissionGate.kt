package com.campush.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.campush.app.core.P1Controller
import com.campush.app.ui.theme.CamPushColors

/**
 * 权限门：CAMERA 为必需权限（未授予时展示说明页）；RECORD_AUDIO 可选（拒绝静音继续）；
 * Android 13+ 通知权限在进入主界面后申请，拒绝不阻断推流。
 */
@Composable
fun PermissionGate(
    controller: P1Controller,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(context.isPermissionGranted(Manifest.permission.CAMERA)) }
    var audioGranted by remember { mutableStateOf(context.isPermissionGranted(Manifest.permission.RECORD_AUDIO)) }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    var audioNoticeDismissed by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA]
            ?: context.isPermissionGranted(Manifest.permission.CAMERA)
        audioGranted = result[Manifest.permission.RECORD_AUDIO]
            ?: context.isPermissionGranted(Manifest.permission.RECORD_AUDIO)
        autoRequested = true
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> audioGranted = granted }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 通知被拒绝不阻断推流，仅前台服务通知不可见 */ }

    // 首次进入自动申请相机 + 麦克风（麦克风拒绝不阻断）。
    LaunchedEffect(Unit) {
        if (!cameraGranted && !autoRequested) {
            autoRequested = true
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
        }
    }

    if (cameraGranted) {
        // Android 13+ 通知权限：非阻塞申请。
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            if (!audioGranted && !audioNoticeDismissed) {
                AudioPermissionNotice(
                    onGrant = { audioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onDismiss = { audioNoticeDismissed = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 96.dp),
                )
            }
        }
    } else {
        CameraPermissionScreen(
            autoRequested = autoRequested,
            onRequest = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                )
            },
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

@Composable
private fun AudioPermissionNotice(
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CamPushColors.Warning.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            CamPushColors.Warning.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未授予麦克风权限",
                    style = MaterialTheme.typography.titleSmall,
                    color = CamPushColors.Warning,
                )
                Text(
                    text = "将以静音模式推流，画面不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CamPushColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onGrant) {
                Text("授予", color = CamPushColors.Warning)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "忽略麦克风权限提示",
                    tint = CamPushColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionScreen(
    autoRequested: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(CamPushColors.Background, Color(0xFF0A1410)),
                ),
            )
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = CamPushColors.Primary.copy(alpha = 0.14f)) {
                Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = CamPushColors.Primary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "需要相机权限",
                style = MaterialTheme.typography.headlineSmall,
                color = CamPushColors.TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (autoRequested) {
                    "相机权限被拒绝，无法采集画面。请重新授予权限后继续。画面只在本机编码，通过局域网分享，不会上传云端。"
                } else {
                    "CamPush 使用相机采集实时画面。画面只在本机编码，通过局域网分享，不会上传云端。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = CamPushColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("授予相机权限", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenSettings) {
                Text("前往系统设置", color = CamPushColors.Primary)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "麦克风权限为可选项：拒绝后将以静音模式推流。",
                style = MaterialTheme.typography.labelSmall,
                color = CamPushColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
