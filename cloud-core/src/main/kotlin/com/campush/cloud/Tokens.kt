package com.campush.cloud

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 极简 HS256 JWT（云端 token 骨架；claims 与 00 §5 对齐）。 */
internal class Tokens(private val secret: ByteArray) {

    data class Claims(
        val sub: String,
        val aud: String,
        val exp: Long,
        val scope: List<String>,
        val pin: Boolean,
    )

    fun issue(roomId: String, aud: String, ttlSec: Long, scope: List<String> = listOf("play"), pin: Boolean = false, now: Long = System.currentTimeMillis()): String {
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = b64(
            """{"iss":"campush","sub":"$roomId","aud":"$aud","scope":[${scope.joinToString(",") { "\"$it\"" }}],"iat":${now / 1000},"exp":${now / 1000 + ttlSec},"pin":$pin}"""
                .toByteArray(),
        )
        val signature = sign("$header.$payload")
        return "$header.$payload.$signature"
    }

    fun verify(token: String): Claims? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        if (sign("${parts[0]}.${parts[1]}") != parts[2]) return null
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1])) }.getOrNull() ?: return null
        val exp = Regex("\"exp\":(\\d+)").find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (exp * 1000L <= System.currentTimeMillis()) return null
        val sub = Regex("\"sub\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1) ?: return null
        val aud = Regex("\"aud\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1) ?: ""
        return Claims(sub, aud, exp, emptyList(), payload.contains("\"pin\":true"))
    }

    private fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return b64(mac.doFinal(data.toByteArray()))
    }

    private fun b64(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    companion object {
        fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
