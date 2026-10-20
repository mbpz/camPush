package com.campush.app

import android.app.Application
import com.campush.app.core.P1Controller
import com.campush.app.core.P1ControllerImpl

/**
 * 进程级依赖容器：P1Controller 单例。
 * UI 通过 `(application as CamPushApp).controller` 获取；服务与控制器共享同一实例。
 */
class CamPushApp : Application() {

    lateinit var controller: P1Controller
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        controller = P1ControllerImpl(this)
    }
}
