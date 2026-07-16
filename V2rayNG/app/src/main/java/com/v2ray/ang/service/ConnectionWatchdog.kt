package com.v2ray.ang.service

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootProxyManager
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
 * Soft-restart is a last resort for a dead local dataplane only.
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
                return
            }

            consecutiveFailures++
            LogUtil.w(
                AppConfig.TAG,
                "ConnectionWatchdog: Connection test failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)"
            )
            if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return

            // Remote delay is not a reliable proxy for "core is dead".
            // Repair local path when needed; never soft-restart solely because a test URL failed.
            val coreAlive = try {
                controller.isRunning
            } catch (_: Exception) {
                false
            }
            val rootMode = SettingsManager.isRootMode()
            var pipelineHealthy = false
            var socksReady = false
            try {
                socksReady = RootProxyManager.isLocalSocksReady()
            } catch (_: Exception) {
            }

            if (rootMode) {
                pipelineHealthy = try {
                    RootProxyManager.isHealthy(service)
                } catch (_: Exception) {
                    false
                }
                if (!pipelineHealthy) {
                    val backoff = RootProxyManager.repairBackoffRemainingMs()
                    if (backoff > 0L) {
                        LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: ROOT repair backoff ${backoff}ms, skip soft-restart")
                        consecutiveFailures = 0
                        return
                    }
                    LogUtil.w(AppConfig.TAG, "ConnectionWatchdog: ROOT pipeline unhealthy, ensuring before any restart")
                    val ensureErr = RootProxyManager.ensureRunning(service)
                    if (ensureErr == RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                        LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: ROOT repair backed off, skip soft-restart")
                        consecutiveFailures = 0
                        return
                    }
                    pipelineHealthy = RootProxyManager.isHealthy(service)
                    try {
                        socksReady = RootProxyManager.isLocalSocksReady()
                    } catch (_: Exception) {
                    }
                    if (pipelineHealthy) {
                        LogUtil.i(AppConfig.TAG, "ConnectionWatchdog: ROOT pipeline restored, skip soft-restart")
                        consecutiveFailures = 0
                        return
                    }
                }
            }

            val restart = shouldSoftRestart(
                consecutiveFailures = consecutiveFailures,
                maxConsecutiveFailures = MAX_CONSECUTIVE_FAILURES,
                coreRunning = coreAlive,
                rootMode = rootMode,
                rootPipelineHealthy = pipelineHealthy,
                localSocksReady = socksReady,
            )
            if (!restart) {
                LogUtil.i(
                    AppConfig.TAG,
                    "ConnectionWatchdog: remote delay failed but local dataplane usable " +
                        "(core=$coreAlive root=$rootMode healthy=$pipelineHealthy socks=$socksReady); skip soft-restart"
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
     * Soft-restart only when consecutive remote failures hit the threshold AND the local
     * dataplane is not healthy enough to carry traffic.
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
        if (rootMode) {
            if (rootPipelineHealthy && coreRunning && localSocksReady) return false
            if (coreRunning && localSocksReady) return false
            return !coreRunning || !localSocksReady
        }
        // VPN/Proxy: live core => remote-only failure, keep session.
        return !coreRunning
    }
}
