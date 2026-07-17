package com.v2ray.ang.handler

import java.util.concurrent.atomic.AtomicBoolean

object SettingsChangeManager {
    private val _restartService = AtomicBoolean(false)
    private val _hardRestartService = AtomicBoolean(false)
    private val _setupGroupTab = AtomicBoolean(false)

    /**
     * Soft re-apply is enough for node/config content changes that keep the same
     * service class and VpnService capture policy.
     */
    fun makeRestartService() {
        _restartService.set(true)
    }

    /**
     * Capture policy changed (per-app / DNS / interface). Requires rebuilding the
     * Android VpnService interface, not only soft-restarting the core.
     */
    fun makeHardRestartService() {
        _hardRestartService.set(true)
        _restartService.set(true)
    }

    fun consumeRestartService(): Boolean {
        return _restartService.getAndSet(false)
    }

    fun consumeHardRestartService(): Boolean {
        return _hardRestartService.getAndSet(false)
    }

    /** Peek without clearing; used to choose hard vs soft apply path. */
    fun isHardRestartPending(): Boolean = _hardRestartService.get()

    fun makeSetupGroupTab() {
        _setupGroupTab.set(true)
    }

    fun consumeSetupGroupTab(): Boolean {
        return _setupGroupTab.getAndSet(false)
    }
}