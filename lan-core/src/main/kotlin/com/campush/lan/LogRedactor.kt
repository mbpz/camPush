package com.campush.lan

/** 日志脱敏：导出/分享前移除 token、PIN、明文 roomId。 */
object LogRedactor {
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
    private val tokenQuery = Regex("([?&](?:token|mt|pageToken|mediaToken)=)[^&\\s\\\"]+")
    private val pin = Regex("(?i)(pin\\s*[=:]\\s*\"?)\\d{4,6}\"?")
    private val room = Regex("\\br[0-9A-HJKMNP-TV-Z]{9}\\b")

    fun redact(text: String): String = text
        .replace(jwt, "<redacted-token>")
        .replace(tokenQuery, "$1<redacted>")
        .replace(pin, "$1***")
        .replace(room, "<redacted-room>")
}
