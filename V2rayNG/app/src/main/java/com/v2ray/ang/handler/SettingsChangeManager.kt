package com.v2ray.ang.handler

import java.util.concurrent.atomic.AtomicBoolean

object SettingsChangeManager {
    private val _restartService = AtomicBoolean(false)
    private val _setupGroupTab = AtomicBoolean(false)

    fun makeRestartService() {
        _restartService.set(true)
    }

    fun consumeRestartService(): Boolean {
        return _restartService.getAndSet(false)
    }

    fun makeSetupGroupTab() {
        _setupGroupTab.set(true)
    }

    fun consumeSetupGroupTab(): Boolean {
        return _setupGroupTab.getAndSet(false)
    }
}
