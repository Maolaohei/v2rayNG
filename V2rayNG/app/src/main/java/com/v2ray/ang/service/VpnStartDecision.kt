package com.v2ray.ang.service

/**
 * Pure decision table for [CoreVpnService] re-entry.
 * Sticky redelivery / secondary startForegroundService must not tear down a healthy VPN session.
 */
object VpnStartDecision {
    enum class Action {
        SKIP_REBUILD_CORE_LIVE,
        KEEP_SOFT_RESTART,
        REVIVE_CORE_ON_EXISTING_TUN,
        COLD_SETUP,
    }

    fun decide(
        interfaceOpen: Boolean,
        isRunningFlag: Boolean,
        coreRunning: Boolean,
        softRestarting: Boolean,
        hasLiveSession: Boolean,
    ): Action {
        val interfaceReady = interfaceOpen
        val coreLive = coreRunning || softRestarting
        val sessionLive = hasLiveSession || isRunningFlag || interfaceReady

        if (!sessionLive && !coreLive) return Action.COLD_SETUP
        if (coreRunning) return Action.SKIP_REBUILD_CORE_LIVE
        if (softRestarting) return Action.KEEP_SOFT_RESTART
        if (interfaceReady) return Action.REVIVE_CORE_ON_EXISTING_TUN
        return Action.COLD_SETUP
    }
}
