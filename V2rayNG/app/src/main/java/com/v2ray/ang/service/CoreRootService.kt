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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root-mode service: starts the Xray core with a local SOCKS inbound only (no VpnService
 * TUN fd). System-wide traffic is routed by iptables instead (see [RootProxyManager]).
 *
 * Core starts first, then root routing is installed only after local SOCKS accepts
 * connections. A lightweight watchdog keeps hev/tun/rules/socks alive while the service
 * runs and fail-closes after repeated repair failures. Network changes also trigger a
 * lightweight pipeline ensure (VPN-equivalent recovery without VpnService).
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
        val xrayTun = plane.engine == com.v2ray.ang.root.RootEngine.XRAY_TUN
        val alreadyLive = CoreServiceManager.isRunning() || CoreServiceManager.hasLiveSession()
        if (alreadyLive) {
            setupJob?.cancel()
            setupJob = serviceScope.launch {
                if (plane.isHealthy(this@CoreRootService)) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: re-entry while healthy, skip rebuild engine=${plane.engine}")
                    startWatchdog()
                    return@launch
                }
                // hev needs local SOCKS; xray_tun system path does not depend on SOCKS hairpin.
                if (!xrayTun && !waitLocalSocksReady()) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: re-entry SOCKS not ready yet, ensure later")
                    val err = plane.ensure(this@CoreRootService)
                    if (err != null && err != RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Root: re-entry ensure failed: $err")
                    }
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

        // Cold start:
        // - hev: start core (SOCKS only) first, then hev+rules
        // - xray_tun: open fd + startLoop(fd) + rules inside plane.start (needs fd before core)
        if (!xrayTun) {
            if (!CoreServiceManager.startCoreLoop(null)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start (still running or service race)")
                failAndStop(RootProxyManager.RootError.UNKNOWN)
                return START_NOT_STICKY
            }
        }

        setupJob?.cancel()
        setupJob = serviceScope.launch {
            if (!xrayTun && !waitLocalSocksReady()) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: local SOCKS not ready")
                failAndStop(RootProxyManager.RootError.SOCKS_NOT_READY)
                return@launch
            }
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
     * - After a real onLost闂備焦鍓氶崑鍛叏閻氱潪vailable transition, also soft-restart the core so
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

            // 2) Ensure hev/tun/rules/socks without full rebuild when possible.
            if (CoreServiceManager.isRunning() && !RootDataPlanes.current().isHealthy(this@CoreRootService)) {
                LogUtil.w(AppConfig.TAG, "StartCore-Root: ensuring pipeline after $reason")
                val err = RootDataPlanes.current().ensure(this@CoreRootService)
                if (err != null) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Root: ensure after $reason failed: $err")
                }
            } else if (RootDataPlanes.current().isHealthy(this@CoreRootService)) {
                LogUtil.i(AppConfig.TAG, "StartCore-Root: pipeline healthy after $reason")
            }

            // 3) Soft-restart core only after a real lost->available flap when the LOCAL
            // dataplane is dead (core down or SOCKS not accepting). Remote path issues
            // and temporary probe noise must not drop app TCP (Telegram flap).
            if (softRestartCore && CoreServiceManager.isRunning()) {
                val stillUnhealthy = !RootDataPlanes.current().isHealthy(this@CoreRootService)
                val socksReady = RootProxyManager.isLocalSocksReady()
                val coreAlive = try { CoreServiceManager.coreController.isRunning } catch (_: Exception) { false }
                if (stillUnhealthy && !(coreAlive && socksReady)) {
                    val now = System.currentTimeMillis()
                    if (lastSoftRestartAtMs > 0L && now - lastSoftRestartAtMs < 60_000L) {
                        LogUtil.i(AppConfig.TAG, "StartCore-Root: soft-restart cooldown after $reason, skip")
                    } else {
                        lastSoftRestartAtMs = now
                        LogUtil.i(AppConfig.TAG, "StartCore-Root: soft-restart core after $reason (local dataplane dead)")
                        try {
                            CoreServiceManager.restartCoreLoop()
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "StartCore-Root: soft-restart after $reason failed", e)
                        }
                    }
                } else if (stillUnhealthy) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: pipeline soft-unhealthy after $reason but SOCKS up; skip soft-restart")
                } else {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: skip soft-restart after $reason (healthy)")
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
            // Warm-up: do not fail-close right after start while rules/hev settle.
            delay(30_000L)
            while (isActive && CoreServiceManager.isRunning()) {
                if (CoreServiceManager.isSoftRestarting()) {
                    delay(3_000L)
                    continue
                }
                val backoff = RootProxyManager.repairBackoffRemainingMs()
                if (backoff > 0L) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: repair backoff ${backoff}ms, watchdog waits")
                    delay(backoff.coerceAtMost(30_000L))
                    continue
                }
                val plane = RootDataPlanes.current()
                if (plane.isHealthy(this@CoreRootService)) {
                    consecutiveHardFailures = 0
                    delay(45_000L)
                    continue
                }

                LogUtil.w(AppConfig.TAG, "StartCore-Root: pipeline unhealthy, repairing engine=${plane.engine}")
                val err = plane.ensure(this@CoreRootService)
                if (err == RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: repair backed off, wait for next tick")
                    delay(RootProxyManager.repairBackoffRemainingMs().coerceIn(2_000L, 30_000L))
                    continue
                }

                // After ensure, re-check. Soft/transient probe failures must NOT stop the session.
                val healthy = plane.isHealthy(this@CoreRootService)
                if (healthy) {
                    consecutiveHardFailures = 0
                    delay(30_000L)
                    continue
                }

                // Hard fail: su denied / hev binary missing / TUN gone for current engine.
                val hard = err == RootProxyManager.RootError.SU_DENIED ||
                    err == RootProxyManager.RootError.HEV_MISSING ||
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
                    // Soft unhealthy (rules/socks flap): keep service, retry later.
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: soft unhealthy ($err), keep session and retry")
                    consecutiveHardFailures = 0
                    delay(30_000L)
                }
            }
        }
    }



    private suspend fun waitLocalSocksReady(timeoutMs: Long = 6000L): Boolean {
        val port = SettingsManager.getSocksPort()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!CoreServiceManager.isRunning()) return false
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 250)
                    return true
                }
            } catch (_: Exception) {
                // retry
            }
            delay(120L)
        }
        return false
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

