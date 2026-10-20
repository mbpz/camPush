package com.campush.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

class PortAllocatorTest {

    private fun bind(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", port))
    }

    @Test
    fun prefersConfiguredPortThenAscends() {
        assertEquals(listOf(18096, 18097), PortAllocator.candidatePorts(
            LanConfig(port = 18096, portRange = 18096..18097),
        ))
        // 自定义端口优先尝试，随后仍按契约区间顺延
        assertEquals(listOf(8088, 18096, 18097), PortAllocator.candidatePorts(
            LanConfig(port = 8088, portRange = 18096..18097),
        ))
    }

    @Test
    fun skipsOccupiedPort() {
        val blocker = bind(18096)
        try {
            val socket = PortAllocator.allocate(LanConfig(port = 18096, portRange = 18096..18099))
            try {
                assertEquals(18097, socket.localPort)
            } finally {
                socket.close()
            }
        } finally {
            blocker.close()
        }
    }

    @Test
    fun allPortsBusyThrows6002() {
        val first = bind(18098)
        val second = bind(18099)
        try {
            expectLanCode(6002) {
                PortAllocator.allocate(LanConfig(port = 18098, portRange = 18098..18099))
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun bindsOnWildcardAddress() {
        val socket = PortAllocator.allocate(LanConfig(port = 18096, portRange = 18096..18096))
        try {
            assertTrue(socket.isBound)
            assertEquals(18096, socket.localPort)
        } finally {
            socket.close()
        }
    }
}
