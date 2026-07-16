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

/**
 * Periodically probes outbound delay through the live core.
 *
 * Ownership:
 * - VPN / Proxy-only: may soft-restart only when the local core is actually dead.
 * - ROOT: observe-only. Local hev/rules/SOCKS recovery belongs solely to
 *   CoreRootService's pipeline watchdog. Acting here caused dual-watchdog thrash.
 *
 * Remote delay failures (blocked test URL / congested node / DNS) must NOT thrash the core:
 * restarting drops every app TCP (Telegram "connecting/connected" flap).
 */
object ConnectionWatchdog {
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    private const val MAX_CONSECUTIVE_FAILURES = 3
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
        val mode = if (SettingsManager.isRootMode()) "ROOT observe-only" else "active"
        LogUtil.i(
            AppConfig.TAG,
            "ConnectionWatchdog: Started with interval ${CHECK_INTERVAL_MS / 1000}s ($mode)"
        )
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
            val controller = CoreServiceManager.coreController
            val testUrl = SettingsManager.getDelayTestUrl()

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
                    LogUtil.i(
                        AppConfig.TAG,
                        "ConnectionWatchdog: Connection recovered after $consecutiveFailures failures"
                    )
                }
                consecutiveFailures = 0
                return
            }

            consecutiveFailures++
            LogUtil.w(
                AppConfig.TAG,
                "ConnectionWatchdog: Connection test failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)"
            )
            if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return

            // ROOT: pipeline dog owns ensure/rebuild/soft-restart. We only log.
            if (SettingsManager.isRootMode()) {
                LogUtil.i(
                    AppConfig.TAG,
                    "ConnectionWatchdog: ROOT observe-only after $consecutiveFailures remote failures; " +
                        "no ensure/soft-restart (pipeline watchdog owns recovery)"
                )
                consecutiveFailures = 0
                return
            }

            val coreAlive = try {
                controller.isRunning
            } catch (_: Exception) {
                false
            }

            val restart = shouldSoftRestart(
                consecutiveFailures = consecutiveFailures,
                maxConsecutiveFailures = MAX_CONSECUTIVE_FAILURES,
                coreRunning = coreAlive,
                rootMode = false,
                rootPipelineHealthy = false,
                localSocksReady = false,
            )
            if (!restart) {
                LogUtil.i(
                    AppConfig.TAG,
                    "ConnectionWatchdog: remote delay failed but core is running; skip soft-restart"
                )
                consecutiveFailures = 0
                return
            }

            restartCore()
            consecutiveFailures = 0
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ConnectionWatchdog: Error during check", e)
        }
    }

    private fun restartCore() {
        LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: Soft-restarting core due to local dataplane failure")
        try {
            lastRestartAtMs = System.currentTimeMillis()
            // Soft-restart: keep Android service + notification; do not emit STOP_SUCCESS.
            CoreServiceManager.restartCoreLoop()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ConnectionWatchdog: Failed to restart core", e)
        }
    }

    /**
     * Pure decision helper for unit tests / review.
     *
     * ROOT: always false ? global dog must not act; pipeline watchdog owns recovery.
     * VPN/Proxy: soft-restart only when consecutive remote failures hit the threshold AND
     * the core is not running.
     */
    fun shouldSoftRestart(
        consecutiveFailures: Int,
        maxConsecutiveFailures: Int,
        coreRunning: Boolean,
        rootMode: Boolean,
        rootPipelineHealthy: Boolean,
        localSocksReady: Boolean,
    ): Boolean {
        if (consecutiveFailures < maxConsecutiveFailures) return false
        // ROOT ownership split: never soft-restart from the global delay dog.
        if (rootMode) return false
        // VPN/Proxy: live core => remote-only failure, keep session.
        return !coreRunning
    }

    /** Whether the global dog is allowed to mutate dataplane (ensure/soft-restart). */
    fun mayActOnFailures(rootMode: Boolean): Boolean = !rootMode
}
