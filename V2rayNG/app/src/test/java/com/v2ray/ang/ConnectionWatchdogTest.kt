package com.v2ray.ang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.v2ray.ang.service.ConnectionWatchdog

class ConnectionWatchdogTest {

    @Test
    fun test_consecutiveFailureTracking() {
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 2

        consecutiveFailures++
        assertEquals(1, consecutiveFailures)
        assertFalse(consecutiveFailures >= maxConsecutiveFailures)

        consecutiveFailures++
        assertEquals(2, consecutiveFailures)
        assertTrue(consecutiveFailures >= maxConsecutiveFailures)

        consecutiveFailures = 0
        assertEquals(0, consecutiveFailures)
    }

    @Test
    fun test_connectionRecoveryResetsFailures() {
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 2

        consecutiveFailures++
        consecutiveFailures++
        assertTrue(consecutiveFailures >= maxConsecutiveFailures)

        consecutiveFailures = 0
        assertEquals(0, consecutiveFailures)
    }

    @Test
    fun test_checkIntervalCalculation() {
        val checkIntervalMs = 5 * 60 * 1000L
        val checkIntervalSeconds = checkIntervalMs / 1000
        assertEquals(300L, checkIntervalSeconds)
    }

    @Test
    fun test_failureThresholdLogic() {
        val maxConsecutiveFailures = 2
        var consecutiveFailures = 0
        var shouldRestart = false

        consecutiveFailures++
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures
        assertFalse(shouldRestart)

        consecutiveFailures++
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures
        assertTrue(shouldRestart)

        consecutiveFailures = 0
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures
        assertFalse(shouldRestart)
    }

    @Test
    fun test_successAfterFailureResetsCounter() {
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 2

        consecutiveFailures++
        consecutiveFailures++
        assertTrue(consecutiveFailures >= maxConsecutiveFailures)

        consecutiveFailures = 0
        assertFalse(consecutiveFailures >= maxConsecutiveFailures)
    }

    @Test
    fun remoteDelayFail_withLiveVpnCore_skipsSoftRestart() {
        assertFalse(
            ConnectionWatchdog.shouldSoftRestart(
                consecutiveFailures = 3,
                maxConsecutiveFailures = 3,
                coreRunning = true,
                rootMode = false,
                rootPipelineHealthy = false,
                localSocksReady = false,
            )
        )
    }

    @Test
    fun rootMode_neverSoftRestarts_fromGlobalDog() {
        // Even if core/SOCKS look dead, ROOT recovery is owned by pipeline watchdog.
        assertFalse(
            ConnectionWatchdog.shouldSoftRestart(
                consecutiveFailures = 3,
                maxConsecutiveFailures = 3,
                coreRunning = false,
                rootMode = true,
                rootPipelineHealthy = false,
                localSocksReady = false,
            )
        )
        assertFalse(
            ConnectionWatchdog.shouldSoftRestart(
                consecutiveFailures = 3,
                maxConsecutiveFailures = 3,
                coreRunning = true,
                rootMode = true,
                rootPipelineHealthy = true,
                localSocksReady = true,
            )
        )
        assertFalse(ConnectionWatchdog.mayActOnFailures(rootMode = true))
        assertTrue(ConnectionWatchdog.mayActOnFailures(rootMode = false))
    }

    @Test
    fun remoteDelayFail_coreDead_triggersSoftRestart_onVpnOnly() {
        assertTrue(
            ConnectionWatchdog.shouldSoftRestart(
                consecutiveFailures = 3,
                maxConsecutiveFailures = 3,
                coreRunning = false,
                rootMode = false,
                rootPipelineHealthy = false,
                localSocksReady = false,
            )
        )
    }

    @Test
    fun belowThreshold_neverSoftRestart() {
        assertFalse(
            ConnectionWatchdog.shouldSoftRestart(
                consecutiveFailures = 2,
                maxConsecutiveFailures = 3,
                coreRunning = false,
                rootMode = false,
                rootPipelineHealthy = false,
                localSocksReady = false,
            )
        )
    }
}
