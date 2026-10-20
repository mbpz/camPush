package com.campush.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JwtTest {

    private val now = 1_700_000_000_000L
    private val key = ByteArray(32) { (it * 7).toByte() }

    private fun claims(
        aud: String = "page",
        scope: List<String> = listOf("play"),
        exp: Long = now / 1000 + 3600,
        lan: Boolean = true,
        pin: Boolean? = null,
    ) = LanClaims(
        iss = "campush",
        sub = "r23456789a",
        aud = aud,
        scope = scope,
        iat = now / 1000,
        exp = exp,
        jti = "abcdefghjkmnpqrs",
        sid = if (aud == "media") "ps_test" else null,
        pin = pin,
        lan = lan,
    )

    @Test
    fun signAndVerifyRoundTrip() {
        val token = Jwt.sign(claims(pin = true), key)
        assertEquals(3, token.split('.').size)
        assertFalse("Base64URL 必须无填充", token.contains('='))

        val verified = Jwt.verify(token, key, now, "page")
        assertEquals("campush", verified.iss)
        assertEquals("r23456789a", verified.sub)
        assertEquals("page", verified.aud)
        assertEquals(listOf("play"), verified.scope)
        assertEquals(16, verified.jti.length)
        assertEquals(3600L, verified.exp - verified.iat)
        assertTrue(verified.lan)
        assertEquals(true, verified.pin)
    }

    @Test
    fun tamperedPayloadRejected() {
        val token = Jwt.sign(claims(), key)
        val parts = token.split('.').toMutableList()
        val payload = Base64.getUrlDecoder().decode(parts[1])
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        expectLanCode(3002) { Jwt.verify(parts.joinToString("."), key, now, "page") }
    }

    @Test
    fun wrongKeyRejected() {
        val otherKey = ByteArray(32) { 9 }
        expectLanCode(3002) { Jwt.verify(Jwt.sign(claims(), key), otherKey, now, "page") }
    }

    @Test
    fun expiredRejected() {
        expectLanCode(3003) {
            Jwt.verify(Jwt.sign(claims(exp = now / 1000 - 1), key), key, now, "page")
        }
        // exp 恰好等于 now 也视为过期
        expectLanCode(3003) {
            Jwt.verify(Jwt.sign(claims(exp = now / 1000), key), key, now, "page")
        }
    }

    @Test
    fun audienceAndScopeEnforced() {
        val pageToken = Jwt.sign(claims(aud = "page"), key)
        expectLanCode(3002) { Jwt.verify(pageToken, key, now, "media") }

        val publishOnly = Jwt.sign(claims(scope = listOf("publish")), key)
        expectLanCode(3002) { Jwt.verify(publishOnly, key, now, "page") }
    }

    @Test
    fun malformedAndAlgNoneRejected() {
        expectLanCode(3002) { Jwt.verify("not-a-jwt", key, now, "page") }

        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims().toJson().toByteArray())
        expectLanCode(3002) { Jwt.verify("$header.$payload.", key, now, "page") }
    }

    @Test
    fun nonLanTokenRejected() {
        expectLanCode(3002) {
            Jwt.verify(Jwt.sign(claims(lan = false), key), key, now, "page")
        }
    }

    @Test
    fun decodeUnverifiedReadsClaims() {
        val decoded = Jwt.decodeUnverified(Jwt.sign(claims(pin = false), key))
        assertTrue(decoded != null)
        assertEquals(false, decoded!!.pin)
        assertNull(Jwt.decodeUnverified("garbage"))
    }
}
