package com.campush.lan

/**
 * 错误码与错误信封（契约来源：docs/00-conventions.md §9.1/§9.2、§17.1/§17.2）。
 *
 * 号段只使用既有码，不新增号段。`LanException.code` 是唯一业务判定依据，
 * HTTP 状态只是语义载体（00 §9.1）。
 */
internal data class ErrorSpec(
    val code: Int,
    val errorName: String,
    val message: String,
    val retryable: Boolean,
    val userHint: String,
    val httpStatus: Int,
)

internal object LanErrors {

    private val specs: Map<Int, ErrorSpec> = listOf(
        ErrorSpec(2002, "room_closed", "The LAN room has been closed", false, "直播已结束", 410),
        ErrorSpec(2007, "invalid_state", "The request cannot be handled in the current state", true, "当前状态不支持该操作，请稍后重试", 400),
        ErrorSpec(3001, "token_missing", "Playback token is missing", false, "播放链接不完整，请向分享者重新获取地址", 401),
        ErrorSpec(3002, "token_invalid", "Playback token is invalid", false, "播放链接无效，请向分享者重新获取地址", 401),
        ErrorSpec(3003, "token_expired", "Playback token has expired", false, "播放凭证已过期，请向分享者重新获取地址", 401),
        ErrorSpec(3004, "pin_required", "A viewing PIN is required for this room", false, "该画面需要观看口令", 401),
        ErrorSpec(3005, "pin_invalid", "The viewing PIN is incorrect", true, "口令错误，请重试", 401),
        ErrorSpec(3006, "pin_locked", "Too many incorrect PIN attempts", false, "口令尝试次数过多，请 10 分钟后再试", 429),
        ErrorSpec(3008, "rate_limited", "Too many requests", true, "请求过于频繁，请稍后重试", 429),
        ErrorSpec(5004, "no_media", "No media is available yet", true, "正在等待画面…", 404),
        ErrorSpec(6001, "lan_service_start_failed", "LAN service failed to start", true, "局域网服务启动失败，请重试", 500),
        ErrorSpec(6002, "port_in_use", "No free LAN port in the configured range", true, "端口被占用，请关闭占用端口的应用或重启设备", 500),
        ErrorSpec(6003, "lan_not_connected", "LAN network is not available", true, "局域网未连接，请检查 Wi-Fi/热点", 503),
        ErrorSpec(6005, "viewer_limit_reached", "LAN viewer limit reached", true, "局域网观看人数已达上限", 429),
        ErrorSpec(6006, "media_pipeline_error", "Media pipeline error", true, "分享者的媒体服务异常，请稍后重试", 500),
    ).associateBy { it.code }

    fun specFor(code: Int): ErrorSpec = specs[code] ?: ErrorSpec(
        code = code,
        errorName = "internal_error",
        message = "Unexpected LAN service error",
        retryable = false,
        userHint = "局域网服务异常，请重试",
        httpStatus = 500,
    )

    fun exception(code: Int, message: String? = null, userHint: String? = null): LanException {
        val spec = specFor(code)
        return LanException(code, message ?: spec.message, userHint ?: spec.userHint)
    }

    /** 严格按 00 §9.1 的字段与顺序生成错误信封。 */
    fun envelope(code: Int, requestId: String, userHintOverride: String? = null): String {
        val spec = specFor(code)
        val fields = LinkedHashMap<String, Any?>()
        fields["code"] = code
        fields["error"] = spec.errorName
        fields["message"] = spec.message
        fields["retryable"] = spec.retryable
        fields["userHint"] = userHintOverride ?: spec.userHint
        fields["requestId"] = requestId
        return MiniJson.write(fields)
    }
}
