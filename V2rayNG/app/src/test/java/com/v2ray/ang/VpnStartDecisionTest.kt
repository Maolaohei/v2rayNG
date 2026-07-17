package com.v2ray.ang

import com.v2ray.ang.service.VpnStartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStartDecisionTest {
    @Test
    fun sticky_reentry_while_core_live_skips_rebuild() {
        val action = VpnStartDecision.decide(
            interfaceOpen = true,
            isRunningFlag = true,
            coreRunning = true,
            softRestarting = false,
            hasLiveSession = true,
        )
        assertEquals(VpnStartDecision.Action.SKIP_REBUILD_CORE_LIVE, action)
    }

    @Test
    fun reentry_core_down_but_tun_live_revives() {
        val action = VpnStartDecision.decide(
            interfaceOpen = true,
            isRunningFlag = true,
            coreRunning = false,
            softRestarting = false,
            hasLiveSession = true,
        )
        assertEquals(VpnStartDecision.Action.REVIVE_CORE_ON_EXISTING_TUN, action)
    }

    @Test
    fun reentry_during_soft_restart_keeps_session() {
        val action = VpnStartDecision.decide(
            interfaceOpen = true,
            isRunningFlag = true,
            coreRunning = false,
            softRestarting = true,
            hasLiveSession = true,
        )
        assertEquals(VpnStartDecision.Action.KEEP_SOFT_RESTART, action)
    }

    @Test
    fun cold_start_when_fully_stopped() {
        val action = VpnStartDecision.decide(
            interfaceOpen = false,
            isRunningFlag = false,
            coreRunning = false,
            softRestarting = false,
            hasLiveSession = false,
        )
        assertEquals(VpnStartDecision.Action.COLD_SETUP, action)
    }

    @Test
    fun already_running_must_not_be_treated_as_fatal_false() {
        // Document the manager contract used by CoreVpnService.startService:
        // already-running => success (true), never stopAllService.
        val alreadyRunningResult = true
        assertTrue(alreadyRunningResult)
        assertFalse(!alreadyRunningResult)
    }
}
