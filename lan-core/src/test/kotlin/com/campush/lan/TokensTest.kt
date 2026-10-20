package com.campush.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {

    private var now = 1_700_000_000_000L
    private val config = LanConfig(pageTokenTtlSeconds = 3600, mediaTokenTtlSeconds = 300)

    private fun newTokens(): Tokens = Tokens(config) { now }.apply { newSession() }

    @Test
    fun sessionRoomIdAndPageClaims() {
        val tokens = newTokens()
        val roomId = tokens.roomId
        assertTrue("roomId=$roomId", roomId.length == 10 && roomId.startsWith("r"))
        assertTrue(roomId.drop(1).all { it in RandomIds.alphabet() })

        val page = tokens.issuePageToken(pinRequired = true)
        val claims = Jwt.decodeUnverified(page)!!
        assertEquals(roomId, claims.sub)
        assertEquals("page", claims.aud)
        assertEquals(listOf("play"), claims.scope)
        assertEquals(true, claims.pin)
        assertEquals(true, claims.lan)
        assertEquals("campush", claims.iss)
        assertEquals(3600L, claims.exp - claims.iat)
        assertEquals(16, claims.jti.length)
        assertNull(claims.sid)
        tokens.verifyPageToken(page)
    }

    @Test
    fun mediaClaimsCarryPlaySessionId() {
        val tokens = newTokens()
        val media = tokens.issueMediaToken("ps_test_1234")
        val claims = Jwt.decodeUnverified(media)!!
        assertEquals("media", claims.aud)
        assertEquals("ps_test_1234", claims.sid)
        assertNull(claims.pin)
        assertEquals(300L, claims.exp - claims.iat)
        tokens.verifyMediaToken(media)

        val page = tokens.issuePageToken(false)
        expectLanCode(3002) { tokens.verifyMediaToken(page) }
        expectLanCode(3002) { tokens.verifyPageToken(media) }
    }

    @Test
    fun refreshIssuesNewTokenAndKeepsOldValid() {
        val tokens = newTokens()
        val old = tokens.issuePageToken(pinRequired = false)
        now += 10_000L
        val refreshed = tokens.refreshPageToken(old, pinRequired = true)
        assertNotEquals(old, refreshed)
        tokens.verifyPageToken(old)
        tokens.verifyPageToken(refreshed)
        assertEquals(true, Jwt.decodeUnverified(refreshed)!!.pin)
    }

    @Test
    fun destroyInvalidatesAllTokens() {
        val tokens = newTokens()
        val page = tokens.issuePageToken(false)
        val media = tokens.issueMediaToken("ps_x")
        tokens.destroy()
        assertEquals("", tokens.roomId)
        expectLanCode(2002) { tokens.verifyPageToken(page) }
        expectLanCode(2002) { tokens.verifyMediaToken(media) }
    }

    @Test
    fun jtiIsUniquePerToken() {
        val tokens = newTokens()
        val first = Jwt.decodeUnverified(tokens.issuePageToken(false))!!.jti
        val second = Jwt.decodeUnverified(tokens.issuePageToken(false))!!.jti
        assertNotEquals(first, second)
    }
}
