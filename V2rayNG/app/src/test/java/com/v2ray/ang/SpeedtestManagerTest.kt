package com.v2ray.ang

import com.v2ray.ang.handler.SpeedtestManager
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpeedtestManager no longer tracks a global TCP socket queue; each
 * socketConnectTime() call opens and closes its own socket in finally.
 * These tests cover the remaining public surface that unit tests can exercise
 * without network (timeout / invalid host fail-fast).
 */
class SpeedtestManagerTest {

    @Test
    fun test_socketConnectTime_invalidHost_returnsNegative() {
        val elapsed = SpeedtestManager.socketConnectTime("invalid.invalid", 1, timeoutMs = 200)
        assertTrue("expected failure for invalid host, got $elapsed", elapsed < 0)
    }

    @Test
    fun test_socketConnectTime_refusedPort_returnsNegative() {
        // Port 1 is typically closed / unprivileged; short timeout keeps CI fast.
        val elapsed = SpeedtestManager.socketConnectTime("127.0.0.1", 1, timeoutMs = 200)
        assertTrue("expected failure for refused/closed port, got $elapsed", elapsed < 0)
    }
}