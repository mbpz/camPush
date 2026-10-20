package com.campush.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.campush.app.ui.MainScreen
import com.campush.app.ui.PermissionGate
import com.campush.app.ui.theme.CamPushTheme

/**
 * P1 入口：edge-to-edge 深色主题 + 运行时权限门（CAMERA 必需、RECORD_AUDIO 可选、Android 13+ 通知）。
 * controller 由 [CamPushApp] 持有（Lead 注入）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = (application as CamPushApp).controller
        setContent {
            CamPushTheme {
                PermissionGate(controller = controller) {
                    MainScreen(controller = controller)
                }
            }
        }
    }
}
