package com.v2ray.ang

import com.v2ray.ang.service.VpnStartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow-level backtest of home switch / node-switch decisions without Android runtime.
 * Mirrors multi-process ownership: UI selection write + soft-apply intent into daemon.
 */
class VpnSwitchLifecycleTest {

    private data class Session(
        var selectedGuid: String = "A",
        var runningGuid: String? = null,
        var coreRunning: Boolean = false,
        var softRestarting: Boolean = false,
        var interfaceOpen: Boolean = false,
        var isRunningFlag: Boolean = false,
        var hasLiveSession: Boolean = false,
        var pendingSoftApply: Boolean = false,
    )

    private fun softApplyAction(s: Session): VpnStartDecision.Action {
        return VpnStartDecision.decide(
            interfaceOpen = s.interfaceOpen,
            isRunningFlag = s.isRunningFlag,
            coreRunning = s.coreRunning,
            softRestarting = s.softRestarting,
            hasLiveSession = s.hasLiveSession,
            softApplySelected = true,
            selectedConfigActive = s.runningGuid != null && s.runningGuid == s.selectedGuid && s.coreRunning,
        )
    }

    private fun applySoft(s: Session) {
        when (val action = softApplyAction(s)) {
            VpnStartDecision.Action.COLD_SETUP -> {
                s.interfaceOpen = true
                s.coreRunning = true
                s.isRunningFlag = true
                s.hasLiveSession = true
                s.runningGuid = s.selectedGuid
            }
            VpnStartDecision.Action.SOFT_APPLY_SELECTED -> {
                s.softRestarting = true
                // stop core briefly then start selected
                s.coreRunning = false
                s.coreRunning = true
                s.softRestarting = false
                s.runningGuid = s.selectedGuid
                s.isRunningFlag = true
                s.hasLiveSession = true
            }
            VpnStartDecision.Action.KEEP_SOFT_RESTART -> {
                s.pendingSoftApply = true
            }
            VpnStartDecision.Action.SKIP_REBUILD_CORE_LIVE -> {
                // already active
            }
            VpnStartDecision.Action.REVIVE_CORE_ON_EXISTING_TUN -> {
                s.coreRunning = true
                s.runningGuid = s.selectedGuid
            }
        }
    }

    @Test
    fun connected_switch_node_reloads_new_guid() {
        val s = Session(
            selectedGuid = "A",
            runningGuid = "A",
            coreRunning = true,
            interfaceOpen = true,
            isRunningFlag = true,
            hasLiveSession = true,
        )
        s.selectedGuid = "B"
        applySoft(s)
        assertEquals("B", s.runningGuid)
        assertTrue(s.coreRunning)
        assertFalse(s.softRestarting)
    }

    @Test
    fun rapid_double_node_switch_queues_then_applies_latest() {
        val s = Session(
            selectedGuid = "A",
            runningGuid = "A",
            coreRunning = true,
            interfaceOpen = true,
            isRunningFlag = true,
            hasLiveSession = true,
        )
        // first switch in flight
        s.softRestarting = true
        s.coreRunning = false
        s.selectedGuid = "B"
        applySoft(s)
        assertTrue(s.pendingSoftApply)
        // finish first soft-restart on intermediate guid, then drain pending with latest selection
        s.softRestarting = false
        s.coreRunning = true
        s.runningGuid = "A" // still old until pending applied
        s.selectedGuid = "C"
        s.pendingSoftApply = false
        applySoft(s)
        assertEquals("C", s.runningGuid)
    }

    @Test
    fun off_then_on_cold_starts_selected() {
        val s = Session(selectedGuid = "A")
        // user stop
        s.coreRunning = false
        s.interfaceOpen = false
        s.isRunningFlag = false
        s.hasLiveSession = false
        s.runningGuid = null
        // user start
        applySoft(s)
        assertEquals("A", s.runningGuid)
        assertTrue(s.coreRunning)
        assertTrue(s.interfaceOpen)
    }

    @Test
    fun sticky_reentry_without_soft_apply_does_not_change_guid() {
        val s = Session(
            selectedGuid = "B",
            runningGuid = "A",
            coreRunning = true,
            interfaceOpen = true,
            isRunningFlag = true,
            hasLiveSession = true,
        )
        val action = VpnStartDecision.decide(
            interfaceOpen = true,
            isRunningFlag = true,
            coreRunning = true,
            softRestarting = false,
            hasLiveSession = true,
            softApplySelected = false,
        )
        assertEquals(VpnStartDecision.Action.SKIP_REBUILD_CORE_LIVE, action)
        // Without soft-apply flag, running guid stays A (historical bug if misused for node switch).
        assertEquals("A", s.runningGuid)
    }

    @Test
    fun soft_apply_same_guid_skips_thrash() {
        val s = Session(
            selectedGuid = "A",
            runningGuid = "A",
            coreRunning = true,
            interfaceOpen = true,
            isRunningFlag = true,
            hasLiveSession = true,
        )
        val action = softApplyAction(s)
        assertEquals(VpnStartDecision.Action.SKIP_REBUILD_CORE_LIVE, action)
    }
}
