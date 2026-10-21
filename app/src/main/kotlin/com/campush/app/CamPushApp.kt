package com.campush.app

import android.app.Application
import com.campush.app.core.P1Controller
import com.campush.app.core.P1ControllerImpl
import com.campush.app.diag.DiagLog
import com.campush.app.diag.Telemetry

/**
 * 进程级依赖容器：P1Controller 单例。
 * UI 通过 `(application as CamPushApp).controller` 获取；服务与控制器共享同一实例。
 */
class CamPushApp : Application() {

    lateinit var controller: P1Controller
        private set

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        Telemetry.init(this)
        Telemetry.event("app_open", mapOf("appVersion" to BuildConfig.VERSION_NAME))
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        controller = P1ControllerImpl(this)
    }
}
