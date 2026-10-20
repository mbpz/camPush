package com.campush.lan

/**
 * PIN 校验与限速（契约：00 §5.2 + §17.1、05 §10）。
 *
 * - 单 IP：5 次/分钟（成功与失败都计数）-> 3008 rate_limited；
 * - 单房间：20 次/分钟 -> 3008；
 * - 连续失败 10 次（IP 与房间双维度同时计数）-> 锁定 10 分钟 -> 3006 pin_locked；
 * - 成功一次重置连续失败计数，但不清空窗口计数（防“成功一次再爆破”）；
 * - 内存计数，服务停止即整体丢弃；线程安全。
 */
internal class PinGuard(
    private val clock: () -> Long,
    private val onLocked: (() -> Unit)? = null,
    private val maxPerIpPerMinute: Int = 5,
    private val maxPerRoomPerMinute: Int = 20,
    private val maxConsecutiveFails: Int = 10,
    private val windowMs: Long = 60_000L,
    private val lockMs: Long = 10 * 60_000L,
) {
    private class Counter {
        var windowStartMs: Long = 0L
        var windowCount: Int = 0
        var consecutiveFails: Int = 0
        var lockedUntilMs: Long = 0L
    }

    private val monitor = Any()
    private val perIp = HashMap<String, Counter>()
    private val perRoom = Counter()

    /**
     * 校验 PIN。
     *
     * @param required 当前房间是否要求 PIN
     * @param expectedPin 房间配置的 PIN
     * @param providedPin 请求体中的 PIN
     * @param remoteIp 观看设备源 IP（限速维度）
     * @throws LanException 3004 / 3005 / 3006 / 3008
     */
    fun verify(required: Boolean, expectedPin: String?, providedPin: String?, remoteIp: String) {
        if (!required) return
        if (providedPin.isNullOrEmpty()) throw LanErrors.exception(3004)
        synchronized(monitor) {
            val now = clock()
            prune(now)
            val ip = perIp.getOrPut(remoteIp) { Counter() }
            rollWindow(ip, now)
            rollWindow(perRoom, now)

            // 1) 锁定优先（锁定期内即使 PIN 正确也拒绝）
            if (ip.lockedUntilMs > now || perRoom.lockedUntilMs > now) throw LanErrors.exception(3006)

            // 2) 窗口限速（成功/失败都计数）
            if (ip.windowCount >= maxPerIpPerMinute || perRoom.windowCount >= maxPerRoomPerMinute) {
                throw LanErrors.exception(3008)
            }
            ip.windowCount++
            perRoom.windowCount++

            // 3) 常量时间比较
            if (expectedPin != null && constantTimeEquals(expectedPin, providedPin)) {
                ip.consecutiveFails = 0
                perRoom.consecutiveFails = 0
                return
            }

            // 4) 连续失败（IP + 房间同时计数）
            ip.consecutiveFails++
            perRoom.consecutiveFails++
            if (ip.consecutiveFails >= maxConsecutiveFails || perRoom.consecutiveFails >= maxConsecutiveFails) {
                ip.lockedUntilMs = now + lockMs
                perRoom.lockedUntilMs = now + lockMs
                onLocked?.invoke()
                throw LanErrors.exception(3006)
            }
            throw LanErrors.exception(3005)
        }
    }

    /** 用于 `Retry-After`：锁定剩余秒数或当前窗口满时的重置秒数。 */
    fun retryAfterSeconds(remoteIp: String): Int {
        synchronized(monitor) {
            val now = clock()
            val ip = perIp[remoteIp]
            val lockedUntil = maxOf(ip?.lockedUntilMs ?: 0L, perRoom.lockedUntilMs)
            if (lockedUntil > now) return secondsUntil(now, lockedUntil)
            val resetAt = maxOf(
                ip?.let { it.windowStartMs + windowMs } ?: 0L,
                perRoom.windowStartMs + windowMs,
            )
            return if (resetAt > now) secondsUntil(now, resetAt) else 0
        }
    }

    /** 供状态/测试观察：当前 IP 是否处于锁定。 */
    fun isLocked(remoteIp: String): Boolean = synchronized(monitor) {
        val now = clock()
        (perIp[remoteIp]?.lockedUntilMs ?: 0L) > now || perRoom.lockedUntilMs > now
    }

    private fun secondsUntil(now: Long, target: Long): Int =
        ((target - now + 999L) / 1000L).toInt().coerceAtLeast(1)

    private fun rollWindow(counter: Counter, now: Long) {
        if (counter.windowStartMs == 0L || now - counter.windowStartMs >= windowMs) {
            counter.windowStartMs = now
            counter.windowCount = 0
        }
    }

    private fun prune(now: Long) {
        val ttl = windowMs * 5
        val it = perIp.entries.iterator()
        while (it.hasNext()) {
            val counter = it.next().value
            val lastActive = maxOf(counter.windowStartMs, counter.lockedUntilMs)
            if (lastActive > 0L && now - lastActive > ttl) it.remove()
        }
    }

    /** 常量时间字符串比较（长度差异也参与异或）。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        var diff = x.size xor y.size
        val length = maxOf(x.size, y.size)
        for (i in 0 until length) {
            val xb = if (i < x.size) x[i].toInt() else 0
            val yb = if (i < y.size) y[i].toInt() else 0
            diff = diff or (xb xor yb)
        }
        return diff == 0
    }
}
