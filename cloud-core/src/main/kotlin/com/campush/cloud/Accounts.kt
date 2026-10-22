package com.campush.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** P3 T3-S1 账号骨架：内存实现（生产需换 DB）；密码 PBKDF2 后续替换。 */
internal class AccountStore {
    data class Account(
        val id: String,
        val email: String,
        val passwordSalt: String,
        val passwordHash: String,
        var plan: String = "free",
        val devices: MutableSet<String> = mutableSetOf(),
        var usedSeconds: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val byEmail = HashMap<String, Account>()
    private val byId = HashMap<String, Account>()
    private val random = SecureRandom()

    fun register(email: String, password: String, deviceId: String?): Account? {
        val normalized = email.trim().lowercase()
        if (!normalized.contains('@') || normalized.length < 5) return null
        if (password.length < 6) return null
        if (byEmail.containsKey(normalized)) return null
        val salt = ByteArray(16).also { random.nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }
        val account = Account(
            id = "a" + UUID.randomUUID().toString().replace("-", "").take(16),
            email = normalized,
            passwordSalt = salt,
            passwordHash = hash(password, salt),
        )
        if (!deviceId.isNullOrBlank()) account.devices += deviceId
        byEmail[normalized] = account
        byId[account.id] = account
        return account
    }

    fun login(email: String, password: String, deviceId: String?): Account? {
        val account = byEmail[email.trim().lowercase()] ?: return null
        if (account.passwordHash != hash(password, account.passwordSalt)) return null
        if (!deviceId.isNullOrBlank()) {
            if (account.devices.size >= 2 && deviceId !in account.devices) return null
            account.devices += deviceId
        }
        return account
    }

    fun byId(id: String): Account? = byId[id]

    fun bind(accountId: String, deviceId: String): Boolean {
        val account = byId[accountId] ?: return false
        if (account.devices.size >= 2 && deviceId !in account.devices) return false
        account.devices += deviceId
        return true
    }

    fun roomLimit(plan: String): Int = when (plan) {
        "pro" -> 10
        "proplus" -> 50
        else -> 1
    }

    fun monthlyMinutes(plan: String): Int = when (plan) {
        "pro" -> 1_000
        "proplus" -> 5_000
        else -> 60
    }

    fun setPlan(accountId: String, plan: String): Account? {
        if (plan !in setOf("free", "pro", "proplus")) return null
        val account = byId[accountId] ?: return null
        account.plan = plan
        return account
    }

    fun recordUsage(accountId: String, seconds: Long) {
        val account = byId[accountId] ?: return
        account.usedSeconds += seconds.coerceAtLeast(0)
    }

    fun usageExceeded(accountId: String, additionalSeconds: Long): Boolean {
        val account = byId[accountId] ?: return false
        return account.usedSeconds + additionalSeconds > monthlyMinutes(account.plan) * 60L
    }

    private fun hash(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var data = (salt + password).toByteArray()
        repeat(10_000) { data = md.digest(data) }
        return Base64.getEncoder().encodeToString(data)
    }
}
