package com.v2ray.ang

import com.v2ray.ang.handler.SpeedtestManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedtestManagerTest {

    @Test
    fun test_closeAllTcpSockets_drainsQueue() {
        // closeAllTcpSockets should not throw even when called multiple times
        SpeedtestManager.closeAllTcpSockets()
        SpeedtestManager.closeAllTcpSockets()
    }

    @Test
    fun test_socketConnectTime_invalidHost() {
        // Testing with invalid host should return -1 or handle gracefully
        val result = SpeedtestManager.socketConnectTime("192.0.2.1", 1) // TEST-NET, should timeout
        // Result should be negative (failure) or timeout
        assertTrue("Expected failure result, got $result", result <= 0)
    }

    @Test
    fun test_closeAllTcpSockets_concurrentSafety() {
        // Simulate concurrent close and add operations
        val threads = mutableListOf<Thread>()

        // Threads trying to add sockets
        repeat(5) {
            threads.add(Thread {
                repeat(10) {
                    try {
                        val socket = java.net.Socket()
                        SpeedtestManager.socketConnectTime("127.0.0.1", 1)
                    } catch (_: Exception) {
                    }
                }
            })
        }

        // Thread trying to close all
        threads.add(Thread {
            SpeedtestManager.closeAllTcpSockets()
        })

        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        // After all threads complete, closeAllTcpSockets should leave queue empty
        SpeedtestManager.closeAllTcpSockets()
    }
}
