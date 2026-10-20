package com.campush.lan

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 端口选择（契约：00 §8 默认 8089，占用则 8090–8099 顺延；05 §11）。
 *
 * 直接“尝试绑定”而非“先测后绑”，避免 TOCTOU；reuseAddress 允许 TIME_WAIT 复用，
 * 但不会抢占已被其他进程监听的端口。全部候选失败 -> 6002 port_in_use。
 */
internal object PortAllocator {

    fun candidatePorts(config: LanConfig): List<Int> {
        // 契约默认端口 8089 + 8090–8099 顺延；用户显式配置的端口优先尝试一次，
        // 之后仍按契约区间顺延（避免自定义端口被占用时服务不可用）。
        val range = config.portRange
        val rangePorts = if (range.isEmpty()) emptyList() else range.toList()
        return if (config.port in rangePorts) {
            listOf(config.port) + rangePorts.filter { it != config.port }
        } else {
            listOf(config.port) + rangePorts
        }
    }

    fun allocate(config: LanConfig): ServerSocket = allocate(config.bindAddress, candidatePorts(config))

    fun allocate(bindAddress: String, ports: List<Int>): ServerSocket {
        if (ports.isEmpty()) throw LanErrors.exception(6002)
        val address = InetAddress.getByName(bindAddress)
        var lastFailure: Exception? = null
        for (port in ports) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(address, port), 50)
                return socket
            } catch (e: IOException) {
                lastFailure = e
                runCatching { socket.close() }
            } catch (e: SecurityException) {
                lastFailure = e
                runCatching { socket.close() }
            }
        }
        throw LanErrors.exception(
            6002,
            "No free LAN port in ${ports.first()}..${ports.last()}",
            "端口被占用，请关闭占用端口的应用或重启设备" + (lastFailure?.message?.let { "（$it）" } ?: ""),
        )
    }
}
