package com.campush.lan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGuardTest {

    private var now = 1_700_000_000_000L

    private fun guard(): PinGuard = PinGuard({ now })

    @Test
    fun missingPinRequiresPin() {
        expectLanCode(3004) { guard().verify(true, "1234", null, "192.0.2.1") }
        expectLanCode(3004) { guard().verify(true, "1234", "", "192.0.2.1") }
    }

    @Test
    fun wrongPinReturns3005() {
        expectLanCode(3005) { guard().verify(true, "1234", "0000", "192.0.2.1") }
    }

    @Test
    fun pinNotRequiredIsNoOp() {
        guard().verify(false, "1234", "0000", "192.0.2.1")
        guard().verify(false, null, null, "192.0.2.1")
    }

    @Test
    fun ipLimitFivePerMinute() {
        val g = guard()
        val ip = "192.0.2.5"
        repeat(5) {
            expectLanCode(3005) { g.verify(true, "1234", "0000", ip) }
        }
        // 第 6 次：窗口内 5 次已满（成功/失败都计数）
        expectLanCode(3008) { g.verify(true, "1234", "1234", ip) }

        // 窗口滑过后恢复
        now += 61_000L
        g.verify(true, "1234", "1234", ip)
    }

    @Test
    fun roomLimitTwentyPerMinute() {
        val g = guard()
        repeat(20) { index ->
            val ip = "10.0.0.${index / 5}"
            if (index % 2 == 0) {
                expectLanCode(3005) { g.verify(true, "1234", "0000", ip) }
            } else {
                g.verify(true, "1234", "1234", ip)
            }
        }
        expectLanCode(3008) { g.verify(true, "1234", "1234", "10.0.0.9") }
        assertTrue(g.retryAfterSeconds("10.0.0.9") in 1..60)
    }

    @Test
    fun tenConsecutiveFailuresLockTenMinutes() {
        val g = guard()
        repeat(5) { expectLanCode(3005) { g.verify(true, "1234", "0000", "192.0.2.10") } }
        repeat(4) { expectLanCode(3005) { g.verify(true, "1234", "0000", "192.0.2.11") } }
        // 第 10 次连续失败（房间维度）触发锁定
        expectLanCode(3006) { g.verify(true, "1234", "0000", "192.0.2.11") }
        // 锁定期内正确 PIN 也拒绝
        expectLanCode(3006) { g.verify(true, "1234", "1234", "192.0.2.10") }
        assertTrue(g.isLocked("192.0.2.10"))
        assertTrue(g.retryAfterSeconds("192.0.2.10") >= 590)

        now += 10 * 60_000L + 1_000L
        assertFalse(g.isLocked("192.0.2.10"))
        g.verify(true, "1234", "1234", "192.0.2.10")
    }

    @Test
    fun successResetsConsecutiveFailures() {
        val g = guard()
        repeat(3) { expectLanCode(3005) { g.verify(true, "1234", "0000", "192.0.2.20") } }
        g.verify(true, "1234", "1234", "192.0.2.23")
        // 再失败 9 次（分散到多个 IP）；若未重置，房间维度会在第 10 次失败时锁定
        for (ip in listOf("192.0.2.24", "192.0.2.25", "192.0.2.26")) {
            repeat(3) { expectLanCode(3005) { g.verify(true, "1234", "0000", ip) } }
        }
        assertFalse(g.isLocked("192.0.2.20"))
    }
}
