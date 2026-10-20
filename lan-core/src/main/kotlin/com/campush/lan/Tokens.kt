package com.campush.lan

import java.security.SecureRandom
import java.util.Arrays

/** Crockford 风格随机串（去除易混字符：i、l、o、0、1）。契约：roomId=r+9 位、jti 16 位。 */
internal object RandomIds {

    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    private val random = SecureRandom()

    fun crockford(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    fun roomId(): String = "r" + crockford(9)

    /** `req_` + 16 位 Crockford（05 §4.3 / 00 §8 公共响应头）。 */
    fun requestId(): String = "req_" + crockford(16)

    fun playSessionId(): String = "ps_" + crockford(16)

    fun alphabet(): String = ALPHABET
}

/**
 * LAN 会话级 token 签发/校验器（05 §4）。
 *
 * 每次 start 生成 256 bit 随机密钥与 roomId；stop 时销毁密钥（zeroize），
 * 销毁后所有校验一律失败（房间已关闭语义）。
 */
internal class Tokens(
    private val config: LanConfig,
    private val clock: () -> Long,
) {
    @Volatile
    private var key: ByteArray? = null

    @Volatile
    var roomId: String = ""
        private set

    fun newSession() {
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        roomId = RandomIds.roomId()
        key = secret
    }

    fun destroy() {
        val current = key
        key = null
        roomId = ""
        if (current != null) Arrays.fill(current, 0)
    }

    private fun requireKey(): ByteArray = key ?: throw LanErrors.exception(2002)

    fun issuePageToken(pinRequired: Boolean): String {
        val issuedAt = clock() / 1000L
        val claims = LanClaims(
            iss = Jwt.ISSUER,
            sub = roomId,
            aud = AUD_PAGE,
            scope = listOf("play"),
            iat = issuedAt,
            exp = issuedAt + config.pageTokenTtlSeconds,
            jti = RandomIds.crockford(16),
            pin = pinRequired,
            lan = true,
        )
        return Jwt.sign(claims, requireKey())
    }

    fun issueMediaToken(playSessionId: String): String {
        val issuedAt = clock() / 1000L
        val claims = LanClaims(
            iss = Jwt.ISSUER,
            sub = roomId,
            aud = AUD_MEDIA,
            scope = listOf("play"),
            iat = issuedAt,
            exp = issuedAt + config.mediaTokenTtlSeconds,
            jti = RandomIds.crockford(16),
            sid = playSessionId,
            lan = true,
        )
        return Jwt.sign(claims, requireKey())
    }

    fun verifyPageToken(token: String): LanClaims = verifyToken(token, AUD_PAGE)

    fun verifyMediaToken(token: String): LanClaims = verifyToken(token, AUD_MEDIA)

    /**
     * 刷新 pageToken：旧 token 在 exp 前仍有效（00 §19.3），返回新 token。
     * LAN M1 由页面在剩余寿命不足时主动调用；不改地址栏。
     */
    fun refreshPageToken(token: String, pinRequired: Boolean): String {
        verifyPageToken(token)
        return issuePageToken(pinRequired)
    }

    private fun verifyToken(token: String, expectedAudience: String): LanClaims {
        val currentKey = requireKey()
        val claims = Jwt.verify(token, currentKey, clock(), expectedAudience, requiredScope = "play")
        if (claims.sub != roomId) throw LanErrors.exception(3002)
        when (expectedAudience) {
            AUD_MEDIA -> if (claims.sid.isNullOrEmpty()) throw LanErrors.exception(3002)
            AUD_PAGE -> if (claims.sid != null) throw LanErrors.exception(3002)
        }
        return claims
    }

    companion object {
        const val AUD_PAGE = "page"
        const val AUD_MEDIA = "media"
    }
}
