package com.campush.app.streaming

import com.campush.app.core.P1Error

/** 内部/LAN 异常 -> P1 错误码与用户文案映射（docs/00 §9.2、docs/02 §13）。 */
internal object StreamErrors {

    fun map(code: Int, message: String? = null, hint: String? = null): P1Error = when (code) {
        1001 -> P1Error(
            code = 1001,
            message = message ?: "未授予摄像头权限，无法预览与推流",
            hint = hint ?: "请在系统设置中允许相机权限后重试",
        )
        1002 -> P1Error(
            code = 1002,
            message = message ?: "未授予麦克风权限，将静音推流",
            hint = hint ?: "可在系统设置中允许麦克风权限后重新开始推流",
        )
        1003 -> P1Error(
            code = 1003,
            message = message ?: "摄像头不可用，可能被其他应用占用",
            hint = hint ?: "请关闭其他使用摄像头的应用后重试",
        )
        1005 -> P1Error(
            code = 1005,
            message = message ?: "视频编码器初始化失败",
            hint = hint ?: "请重试，或降低分辨率/码率后重新开始推流",
        )
        1007 -> P1Error(
            code = 1007,
            message = message ?: "无法启动后台推流服务",
            hint = hint ?: "请检查系统设置中是否允许通知/后台运行后重试",
        )
        6001 -> P1Error(
            code = 6001,
            message = message ?: "局域网服务启动失败",
            hint = hint ?: "请重试；若仍失败请关闭其它占用端口的应用后重新开始",
        )
        6002 -> P1Error(
            code = 6002,
            message = message ?: "端口被占用，局域网服务无法启动",
            hint = hint ?: "请在设置中更换端口（8089–8099）后重试",
        )
        6003 -> P1Error(
            code = 6003,
            message = message ?: "仅局域网需要连接同一 Wi-Fi 或开启热点",
            hint = hint ?: "请连接 Wi-Fi 或开启热点后重新开始推流",
        )
        6004 -> P1Error(
            code = 6004,
            message = message ?: "疑似路由器 AP 隔离或防火墙",
            hint = hint ?: "请检查路由器「AP 隔离/客户端隔离」设置，或改用手机热点",
        )
        6005 -> P1Error(
            code = 6005,
            message = message ?: "局域网观看人数已达上限",
            hint = hint ?: "局域网模式最多 3 个观看者，请稍后重试或切换公网模式（阶段二开放）",
        )
        6006 -> P1Error(
            code = 6006,
            message = message ?: "局域网媒体分发异常",
            hint = hint ?: "请停止后重新开始推流；若持续出现请反馈设备型号",
        )
        else -> P1Error(
            code = code,
            message = message ?: "推流服务异常（$code）",
            hint = hint,
        )
    }
}
