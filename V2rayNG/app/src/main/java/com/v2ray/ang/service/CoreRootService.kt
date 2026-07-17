package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.TrafficStatsManager
import com.v2ray.ang.root.RootConnectivitySmoke
import com.v2ray.ang.root.RootDataPlanes
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root-mode service: opens a root TUN fd, starts Bray-Core with that fd, then installs
 * MARK/iptables rules (see [XrayTunRootDataPlane] / [RootProxyManager.installRulesOnly]).
 *
 * No hev-socks5-tunnel hairpin for device capture. A lightweight pipeline watchdog keeps
 * tun/rules alive and fail-closes after repeated hard failures. Network changes trigger
 * a graduated ensure (VPN-equivalent recovery without VpnService).
 */
class CoreRootService : Service(), ServiceControl {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var setupJob: Job? = null
    private var watchdogJob: Job? = null
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val pendingNetworkRecover = AtomicBoolean(false)
    @Volatile
    private var lastSoftRestartAtMs = 0L

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Always rebind hev FWMARK -> main (Magic_V2Ray apply_mark_rule equivalent).
                // Cheap and prevents blackhole after Wi-Fi/cellular handoff without full rebuild.
                serviceScope.launch {
                    try {
                        RootProxyManager.rebindPhysicalBypass(this@CoreRootService)
                    } catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "StartCore-Root: bypass rebind onAvailable failed", e)
                    }
                }
                val recover = pendingNetworkRecover.compareAndSet(true, false)
                // Skip recover storms: if this is not a lost->available flap and SOCKS is live,
                // bypass rebind above is enough. isHealthy(strict su) alone was too noisy.
                val needRecover = recover || !RootDataPlanes.current().isRuntimeLive() ||
                    !RootDataPlanes.current().isHealthy(this@CoreRootService)
                if (needRecover) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: network available, recovering session (flap=$recover)")
                    // Prefer pipeline ensure first. Soft-restart core only after a real
                    // onLost->onAvailable AND local SOCKS/core look dead (avoid TG flap).
                    scheduleNetworkRecover(reason = "network-available", softRestartCore = recover)
                }
            }

            override fun onLost(network: Network) {
                pendingNetworkRecover.set(true)
                LogUtil.i(AppConfig.TAG, "StartCore-Root: network lost, will recover on next available")
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = this
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")
        // FGS contract: startForegroundService() requires startForeground() within ~5-10s.
        // Do this BEFORE any SOCKS/su/hev work so ANR/crash cannot fire mid-setup.
        NotificationManager.showNotification(null)

        // Never reuse a previous VPN TUN PFD in root mode.
        CoreServiceManager.bindVpnInterface(null)

        // START_STICKY / system redelivery must NOT full-teardown a healthy pipeline.
        // Re-entry that always called startDetailed() previously caused 1-3s blackholes and
        // "sometimes works" intermittency under memory pressure / task-manager thrash.
        val plane = RootDataPlanes.current()
        val alreadyLive = CoreServiceManager.isRunning() || CoreServiceManager.hasLiveSession()
        if (alreadyLive) {
            setupJob?.cancel()
            setupJob = serviceScope.launch {
                if (plane.isHealthy(this@CoreRootService)) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: re-entry while healthy, skip rebuild engine=${plane.engine}")
                    runCatching { RootConnectivitySmoke.maybeProbeAfterStart(force = false) }
                    startWatchdog()
                    return@launch
                }
                LogUtil.w(AppConfig.TAG, "StartCore-Root: re-entry unhealthy, graduated ensure engine=${plane.engine}")
                val err = plane.ensure(this@CoreRootService)
                if (err != null && err != RootProxyManager.RootError.REPAIR_BACKED_OFF &&
                    !plane.isRuntimeLive()
                ) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: re-entry ensure failed ($err), one full rebuild")
                    val full = RootDataPlanes.current().start(this@CoreRootService)
                    if (full != null) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Root: re-entry full rebuild failed: $full")
                        if (!CoreServiceManager.isRunning() && !CoreServiceManager.hasLiveSession()) {
                            failAndStop(full)
                            return@launch
                        }
                    }
                }
                startWatchdog()
            }
            return START_STICKY
        }

        // Cold start: open TUN fd + startLoop(fd) + MARK rules inside plane.start.
        // No hev / no SOCKS-hairpin gate for ROOT capture.
        setupJob?.cancel()
        setupJob = serviceScope.launch {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: dataplane engine=${plane.engine}")
            val err = plane.start(this@CoreRootService)
            if (err != null) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode: $err engine=${plane.engine}")
                failAndStop(err)
                return@launch
            }
            startWatchdog()
        }

        // Sticky so system can recreate after low-memory kills; onStartCommand re-enters softly.
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        if (!networkCallbackRegistered.compareAndSet(false, true)) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivity.registerDefaultNetworkCallback(defaultNetworkCallback)
            } else {
                @Suppress("DEPRECATION")
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            }
            LogUtil.i(AppConfig.TAG, "StartCore-Root: network callback registered")
        } catch (e: Exception) {
            networkCallbackRegistered.set(false)
            LogUtil.w(AppConfig.TAG, "StartCore-Root: failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered.compareAndSet(true, false)) return
        try {
            connectivity.unregisterNetworkCallback(defaultNetworkCallback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Root: failed to unregister network callback", e)
        }
    }

    /**
     * Recover after connectivity changes.
     * - Always ensure local ROOT pipeline (hev/tun/rules/socks).
     * - After a real onLost闂傚倷鐒﹂崜姘跺磻閸涱喗鍙忛柣姘辨姜vailable transition, also soft-restart the core so
     *   outbound sockets re-bind like VPN's network recovery path.
     */
    private fun scheduleNetworkRecover(reason: String, softRestartCore: Boolean) {
        serviceScope.launch {
            if (!CoreServiceManager.isRunning() && !CoreServiceManager.hasLiveSession()) return@launch
            if (CoreServiceManager.isSoftRestarting()) {
                LogUtil.i(AppConfig.TAG, "StartCore-Root: skip recover ($reason) during soft-restart")
                return@launch
            }
            // Debounce rapid network flaps.
            delay(800L)
            if (CoreServiceManager.isSoftRestarting()) return@launch

            try {
                MessageUtil.sendMsg2UI(this@CoreRootService, AppConfig.MSG_STATE_NETWORK_RECOVERING, "")
            } catch (_: Exception) {
            }

            if (!CoreServiceManager.isRunning() && !CoreServiceManager.hasLiveSession()) return@launch

            // 1) Light: rebind dual-mark bypass to current default route (no teardown).
            try {
                RootProxyManager.rebindPhysicalBypass(this@CoreRootService)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-Root: bypass rebind after $reason failed", e)
            }

            // 2) One graduated ensure only (rules/TUN). No second dog from UI.
            val plane = RootDataPlanes.current()
            if (CoreServiceManager.isRunning() && !plane.isHealthy(this@CoreRootService)) {
                LogUtil.w(AppConfig.TAG, "StartCore-Root: ensuring pipeline after $reason")
                val err = plane.ensure(this@CoreRootService)
                if (err == RootProxyManager.RootError.TUN_FAILED) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: TUN missing after $reason, full rebuild once")
                    plane.start(this@CoreRootService)
                } else if (err != null && err != RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Root: ensure after $reason failed: $err")
                }
            } else if (plane.isHealthy(this@CoreRootService)) {
                LogUtil.i(AppConfig.TAG, "StartCore-Root: pipeline healthy after $reason")
            }

            // 3) Soft-restart only on real lost->available when core itself is dead.
            // Rules/TUN issues are handled by ensure/rebuild above; do not thrash core for them.
            if (softRestartCore) {
                val coreAlive = try { CoreServiceManager.coreController.isRunning } catch (_: Exception) { false }
                val sessionLive = CoreServiceManager.hasLiveSession()
                if (!coreAlive && sessionLive) {
                    val now = System.currentTimeMillis()
                    if (lastSoftRestartAtMs > 0L && now - lastSoftRestartAtMs < 60_000L) {
                        LogUtil.i(AppConfig.TAG, "StartCore-Root: soft-restart cooldown after $reason, skip")
                    } else {
                        lastSoftRestartAtMs = now
                        LogUtil.i(AppConfig.TAG, "StartCore-Root: soft-restart core after $reason (core dead, session live)")
                        try {
                            CoreServiceManager.restartCoreLoop()
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "StartCore-Root: soft-restart after $reason failed", e)
                        }
                    }
                } else {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: skip soft-restart after $reason (coreAlive=$coreAlive sessionLive=$sessionLive)")
                }
            }
            try {
                MessageUtil.sendMsg2UI(this@CoreRootService, AppConfig.MSG_STATE_NETWORK_RECOVERED, "")
            } catch (_: Exception) {
            }
        }
    }


    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            var consecutiveHardFailures = 0
            // Warm-up: do not fail-close right after start while rules/tun settle.
            delay(30_000L)
            while (isActive && CoreServiceManager.isRunning()) {
                if (CoreServiceManager.isSoftRestarting()) {
                    delay(3_000L)
                    continue
                }
                val plane = RootDataPlanes.current()
                val backoff = plane.repairBackoffRemainingMs()
                if (backoff > 0L) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: repair backoff ${backoff}ms, watchdog waits")
                    delay(backoff.coerceAtMost(30_000L))
                    continue
                }
                if (plane.isHealthy(this@CoreRootService)) {
                    consecutiveHardFailures = 0
                    delay(45_000L)
                    continue
                }

                LogUtil.w(AppConfig.TAG, "StartCore-Root: pipeline unhealthy, repairing engine=${plane.engine}")
                var err = plane.ensure(this@CoreRootService)
                if (err == RootProxyManager.RootError.TUN_FAILED) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: TUN missing, full dataplane rebuild")
                    err = plane.start(this@CoreRootService)
                }
                if (err == RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: repair backed off, wait for next tick")
                    delay(plane.repairBackoffRemainingMs().coerceIn(2_000L, 30_000L))
                    continue
                }

                // After ensure, re-check. Soft/transient probe failures must NOT stop the session.
                val healthy = plane.isHealthy(this@CoreRootService)
                if (healthy) {
                    consecutiveHardFailures = 0
                    delay(30_000L)
                    continue
                }

                // Hard fail: su denied / TUN gone.
                val hard = err == RootProxyManager.RootError.SU_DENIED ||
                    err == RootProxyManager.RootError.TUN_FAILED ||
                    !plane.isRuntimeLive()
                if (hard) {
                    consecutiveHardFailures++
                    LogUtil.e(
                        AppConfig.TAG,
                        "StartCore-Root: hard pipeline failure ($consecutiveHardFailures/5): $err"
                    )
                    if (consecutiveHardFailures >= 5) {
                        failAndStop(err ?: RootProxyManager.lastError ?: RootProxyManager.RootError.UNKNOWN)
                        return@launch
                    }
                    delay(10_000L)
                } else {
                    // Soft unhealthy (rules flap): keep service, retry later.
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: soft unhealthy ($err), keep session and retry")
                    consecutiveHardFailures = 0
                    delay(30_000L)
                }
            }
        }
    }

    private fun failAndStop(error: RootProxyManager.RootError) {
        try {
            MessageUtil.sendMsg2UI(
                this,
                AppConfig.MSG_STATE_START_FAILURE,
                RootProxyManager.userMessage(this, error)
            )
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Root: failed to notify UI", e)
        }
        TrafficStatsManager.stopServiceTracking()
        NotificationManager.cancelNotification()
        stopService()
    }

    /**
     * Swiping the app away must NOT stop the proxy. Foreground service + sticky restart
     * keep the daemon alive; UI will re-REGISTER when MainActivity returns.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogUtil.i(AppConfig.TAG, "StartCore-Root: onTaskRemoved - keep service running")
        // Do not call stopSelf / stopCoreLoop.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        unregisterNetworkCallback()
        runBlocking { setupJob?.cancelAndJoin() }
        serviceJob.cancel()
        // Remove routing rules BEFORE stopping the core so traffic is never redirected
        // to a dead listener. Synchronous on purpose - leaving rules behind breaks the net.
        RootDataPlanes.current().stop(this)
        CoreServiceManager.stopCoreLoop()
        // Bray-Core Android TUN does not close the external fd; Kotlin owns it.
        com.v2ray.ang.root.RootTun.close()
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}


