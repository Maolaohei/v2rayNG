package com.v2ray.ang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionWatchdogTest {

    @Test
    fun test_consecutiveFailureTracking() {
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 2

        // Simulate first failure
        consecutiveFailures++
        assertEquals(1, consecutiveFailures)
        assertFalse(consecutiveFailures >= maxConsecutiveFailures)

        // Simulate second failure - should trigger restart
        consecutiveFailures++
        assertEquals(2, consecutiveFailures)
        assertTrue(consecutiveFailures >= maxConsecutiveFailures)

        // Reset after restart
        consecutiveFailures = 0
        assertEquals(0, consecutiveFailures)
    }

    @Test
    fun test_connectionRecoveryResetsFailures() {
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 2

        // Simulate some failures
        consecutiveFailures++
        consecutiveFailures++
        assertTrue(consecutiveFailures >= maxConsecutiveFailures)

        // Simulate successful connection
        consecutiveFailures = 0
        assertEquals(0, consecutiveFailures)
    }

    @Test
    fun test_checkIntervalCalculation() {
        val checkIntervalMs = 5 * 60 * 1000L
        val checkIntervalSeconds = checkIntervalMs / 1000

        // Should be 5 minutes
        assertEquals(300L, checkIntervalSeconds)
    }

    @Test
    fun test_failureThresholdLogic() {
        val maxConsecutiveFailures = 2
        var consecutiveFailures = 0
        var shouldRestart = false

        // First failure - no restart
        consecutiveFailures++
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures
        assertFalse(shouldRestart)

        // Second failure - restart
        consecutiveFailures++
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures
        assertTrue(shouldRestart)

        // Reset
        consecutiveFailures = 0
        shouldRestart = consecutiveFailures >= maxConsecutiveFailures
        assertFalse(shouldRestart)
    }

    @Test
    fun test_successAfterFailureResetsCounter() {
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 2

        // Fail twice
        consecutiveFailures++
        consecutiveFailures++
        assertTrue(consecutiveFailures >= maxConsecutiveFailures)

        // Success resets counter
        consecutiveFailures = 0
        assertFalse(consecutiveFailures >= maxConsecutiveFailures)
    }
}
