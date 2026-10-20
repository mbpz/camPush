package com.campush.lan

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LAN 本地 JWT claims（契约来源：docs/00-conventions.md §5.1）。
 *
 * 公共 claims 严格按契约：iss="campush"、sub=roomId、aud=page|media、scope=["play"]、
 * iat/exp 秒级、jti 16 位随机串；lan=true 标识端侧签发；pageToken 可带 pin。
 */
internal data class LanClaims(
    val iss: String,
    val sub: String,
    val aud: String,
    val scope: List<String>,
    val iat: Long,
    val exp: Long,
    val jti: String,
    val sid: String? = null,
    val pin: Boolean? = null,
    val lan: Boolean = true,
) {
    fun toJson(): String {
        val fields = LinkedHashMap<String, Any?>()
        fields["iss"] = iss
        fields["sub"] = sub
        fields["aud"] = aud
        fields["scope"] = scope
        fields["iat"] = iat
        fields["exp"] = exp
        fields["jti"] = jti
        if (sid != null) fields["sid"] = sid
        if (pin != null) fields["pin"] = pin
        fields["lan"] = lan
        return MiniJson.write(fields)
    }

    companion object {
        fun fromJson(json: String): LanClaims? {
            val obj = MiniJson.parse(json) as? Map<*, *> ?: return null
            val scope = (obj["scope"] as? List<*>)?.mapNotNull { it as? String } ?: return null
            val iss = obj["iss"] as? String ?: return null
            val sub = obj["sub"] as? String ?: return null
            val aud = obj["aud"] as? String ?: return null
            val jti = obj["jti"] as? String ?: return null
            val iat = (obj["iat"] as? Number)?.toLong() ?: return null
            val exp = (obj["exp"] as? Number)?.toLong() ?: return null
            return LanClaims(
                iss = iss,
                sub = sub,
                aud = aud,
                scope = scope,
                iat = iat,
                exp = exp,
                jti = jti,
                sid = obj["sid"] as? String,
                pin = obj["pin"] as? Boolean,
                lan = obj["lan"] as? Boolean ?: false,
            )
        }
    }
}

/**
 * HS256 JWT 签发 / 校验（Base64URL 无填充、HMAC-SHA256、常量时间比较）。
 *
 * 校验顺序（00 §5.1 撤销语义 / 05 §4.3 的 token 内部分）：签名 -> exp -> aud/scope。
 * 失败以 LanException 抛出：3002 token_invalid / 3003 token_expired。
 */
internal object Jwt {

    const val ISSUER = "campush"

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun sign(claims: LanClaims, key: ByteArray): String {
        val header = encoder.encodeToString(HEADER_JSON.toByteArray(StandardCharsets.UTF_8))
        val payload = encoder.encodeToString(claims.toJson().toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$header.$payload"
        val signature = encoder.encodeToString(hmac(signingInput, key))
        return "$signingInput.$signature"
    }

    fun verify(
        token: String,
        key: ByteArray,
        nowMillis: Long,
        expectedAudience: String,
        requiredScope: String? = "play",
    ): LanClaims {
        val parts = token.split('.')
        if (parts.size != 3) throw LanErrors.exception(3002)

        val headerJson = decodeText(parts[0])
        val header = try {
            MiniJson.parse(headerJson) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw LanErrors.exception(3002)
        if (header["alg"] as? String != "HS256") throw LanErrors.exception(3002)

        val signingInput = "${parts[0]}.${parts[1]}"
        val expectedSignature = hmac(signingInput, key)
        val actualSignature = try {
            decoder.decode(parts[2])
        } catch (_: IllegalArgumentException) {
            throw LanErrors.exception(3002)
        }
        // 常量时间比较，避免时序侧信道（00 §7.2 / 05 §7.2）
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) throw LanErrors.exception(3002)

        val claims = LanClaims.fromJson(decodeText(parts[1])) ?: throw LanErrors.exception(3002)

        // exp（秒级、手机端时钟；exp <= now 即过期）
        if (claims.exp * 1000L <= nowMillis) throw LanErrors.exception(3003)

        // 类型与签名域
        if (claims.iss != ISSUER || !claims.lan) throw LanErrors.exception(3002)
        if (claims.aud != expectedAudience) throw LanErrors.exception(3002)
        if (requiredScope != null && requiredScope !in claims.scope) throw LanErrors.exception(3002)

        return claims
    }

    /** 仅供测试/排障：不校验签名地读出 claims（生产路径禁止使用）。 */
    fun decodeUnverified(token: String): LanClaims? = try {
        val parts = token.split('.')
        if (parts.size != 3) {
            null
        } else {
            LanClaims.fromJson(String(decoder.decode(parts[1]), StandardCharsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }

    private fun decodeText(segment: String): String = try {
        String(decoder.decode(segment), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw LanErrors.exception(3002)
    }

    private fun hmac(input: String, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input.toByteArray(StandardCharsets.UTF_8))
    }

    private val HEADER_JSON = """{"alg":"HS256","typ":"JWT"}"""
}
