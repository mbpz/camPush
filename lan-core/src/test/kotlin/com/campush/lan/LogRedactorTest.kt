package com.campush.lan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun removesTokenPinAndRoomId() {
        val text = "url=/s/r7K2M9X4QP?token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig12345678 pin=1234 roomId=r7K2M9X4QP"
        val out = LogRedactor.redact(text)
        assertFalse(out.contains("eyJhbGci"))
        assertFalse(out.contains("1234"))
        assertFalse(out.contains("r7K2M9X4QP"))
        assertTrue(out.contains("<redacted"))
        assertTrue(out.contains("pin=***"))
        assertTrue(out.contains("<redacted-room>"))
    }
}
