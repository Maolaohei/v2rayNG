package com.v2ray.ang.service

/**
 * Pure decision table for [CoreVpnService] re-entry.
 * Sticky redelivery / secondary startForegroundService must not tear down a healthy VPN session.
 * Soft node-switch (cross-process applySelectedServer) must reload config even when core is live.
 */
object VpnStartDecision {
    enum class Action {
        SKIP_REBUILD_CORE_LIVE,
        KEEP_SOFT_RESTART,
        REVIVE_CORE_ON_EXISTING_TUN,
        /** Live session + user selected a (possibly new) node: soft-restart core in place. */
        SOFT_APPLY_SELECTED,
        COLD_SETUP,
    }

    fun decide(
        interfaceOpen: Boolean,
        isRunningFlag: Boolean,
        coreRunning: Boolean,
        softRestarting: Boolean,
        hasLiveSession: Boolean,
        softApplySelected: Boolean = false,
        selectedConfigActive: Boolean = false,
    ): Action {
        val interfaceReady = interfaceOpen
        val coreLive = coreRunning || softRestarting
        val sessionLive = hasLiveSession || isRunningFlag || interfaceReady

        // Cross-process node switch: never treat as plain sticky re-entry.
        if (softApplySelected) {
            if (!sessionLive && !coreLive) return Action.COLD_SETUP
            // Already on selected profile and core healthy: no thrash.
            if (selectedConfigActive && coreRunning && !softRestarting) {
                return Action.SKIP_REBUILD_CORE_LIVE
            }
            if (softRestarting) return Action.KEEP_SOFT_RESTART
            return Action.SOFT_APPLY_SELECTED
        }

        if (!sessionLive && !coreLive) return Action.COLD_SETUP
        if (coreRunning) return Action.SKIP_REBUILD_CORE_LIVE
        if (softRestarting) return Action.KEEP_SOFT_RESTART
        if (interfaceReady) return Action.REVIVE_CORE_ON_EXISTING_TUN
        return Action.COLD_SETUP
    }
}
