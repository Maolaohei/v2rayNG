package com.v2ray.ang

import com.v2ray.ang.handler.SpeedtestManager
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedtestManagerTest {

    @Test
    fun test_closeAllTcpSockets_drainsQueue() {
        SpeedtestManager.closeAllTcpSockets()
        SpeedtestManager.closeAllTcpSockets()
    }

    @Test
    fun test_closeAllTcpSockets_concurrentSafety() {
        val threads = mutableListOf<Thread>()

        repeat(5) {
            threads.add(Thread {
                repeat(3) {
                    SpeedtestManager.closeAllTcpSockets()
                }
            })
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        SpeedtestManager.closeAllTcpSockets()
    }
}
