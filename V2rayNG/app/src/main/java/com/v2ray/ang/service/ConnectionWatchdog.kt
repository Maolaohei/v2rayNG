package com.v2ray.ang.service

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object ConnectionWatchdog {
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    private const val MAX_CONSECUTIVE_FAILURES = 2
    /** After a soft-restart, ignore failures briefly so bad networks do not thrash restart loops. */
    private const val RESTART_COOLDOWN_MS = 10 * 60 * 1000L

    private var watchdogJob: Job? = null
    private var consecutiveFailures = 0
    @Volatile
    private var lastRestartAtMs = 0L

    fun start() {
        if (watchdogJob != null) return
        if (!CoreServiceManager.isRunning()) return

        consecutiveFailures = 0
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            delay(CHECK_INTERVAL_MS)
            while (isActive && CoreServiceManager.isRunning()) {
                checkConnection()
                delay(CHECK_INTERVAL_MS)
            }
        }
        LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: Started with interval ${CHECK_INTERVAL_MS / 1000}s")
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        consecutiveFailures = 0
        LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: Stopped")
    }

    private suspend fun checkConnection() {
        if (!CoreServiceManager.isRunning()) {
            stop()
            return
        }
        if (CoreServiceManager.isSoftRestarting()) {
            LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: skip check during soft-restart")
            return
        }
        val now = System.currentTimeMillis()
        if (lastRestartAtMs > 0L && now - lastRestartAtMs < RESTART_COOLDOWN_MS) {
            LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: skip check during restart cooldown")
            return
        }

        try {
            val service = CoreServiceManager.serviceControl?.getService() ?: return
            val testUrl = SettingsManager.getDelayTestUrl()
            val controller = CoreServiceManager.coreController

            var time = -1L
            try {
                time = controller.measureDelay(testUrl)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "ConnectionWatchdog: Primary test failed: ${e.message}")
            }

            if (time == -1L) {
                try {
                    time = controller.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "ConnectionWatchdog: Fallback test failed: ${e.message}")
                }
            }

            if (time >= 0) {
                if (consecutiveFailures > 0) {
                    LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: Connection recovered after $consecutiveFailures failures")
                }
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                LogUtil.w(AppConfig.TAG, "ConnectionWatchdog: Connection test failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)")

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    restartCore()
                    consecutiveFailures = 0
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ConnectionWatchdog: Error during check", e)
        }
    }

    private fun restartCore() {
        LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: Soft-restarting core due to connection failure")
        try {
            lastRestartAtMs = System.currentTimeMillis()
            // Soft-restart: keep Android service + notification; do not emit STOP_SUCCESS.
            CoreServiceManager.restartCoreLoop()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ConnectionWatchdog: Failed to restart core", e)
        }
    }
}
