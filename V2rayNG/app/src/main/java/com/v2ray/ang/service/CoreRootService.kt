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
                if (recover || !RootProxyManager.isHealthy(this@CoreRootService)) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: network available, recovering session")
                    // Prefer pipeline ensure first; only soft-restart core when we actually lost net.
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

        // Never reuse a previous VPN TUN PFD in root mode.
        CoreServiceManager.bindVpnInterface(null)

        // START_STICKY / system redelivery must NOT full-teardown a healthy pipeline.
        // Re-entry that always called startDetailed() previously caused 1-3s blackholes and
        // "sometimes works" intermittency under memory pressure / task-manager thrash.
        val alreadyLive = CoreServiceManager.isRunning() || CoreServiceManager.hasLiveSession()
        if (alreadyLive) {
            setupJob?.cancel()
            setupJob = serviceScope.launch {
                if (RootProxyManager.isHealthy(this@CoreRootService)) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: re-entry while healthy, skip rebuild")
                    startWatchdog()
                    return@launch
                }
                if (!waitLocalSocksReady()) {
                    // Core may be mid soft-restart; prefer ensure over fail-close on sticky redelivery.
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: re-entry SOCKS not ready yet, ensure later")
                    val err = RootProxyManager.ensureRunning(this@CoreRootService)
                    if (err != null && err != RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Root: re-entry ensure failed: $err")
                    }
                    startWatchdog()
                    return@launch
                }
                LogUtil.w(AppConfig.TAG, "StartCore-Root: re-entry unhealthy, graduated ensure (no forced teardown)")
                val err = RootProxyManager.ensureRunning(this@CoreRootService)
                if (err != null && err != RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                    // Only full rebuild once ensure cannot recover; avoid thrash on sticky restarts.
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: re-entry ensure failed ($err), one full rebuild")
                    val full = RootProxyManager.startDetailed(this@CoreRootService)
                    if (full != null) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Root: re-entry full rebuild failed: $full")
                        // Keep service if session still live; fail-close only when core is gone.
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

        if (!CoreServiceManager.startCoreLoop(null)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start")
            stopService()
            return START_NOT_STICKY
        }

        setupJob?.cancel()
        setupJob = serviceScope.launch {
            if (!waitLocalSocksReady()) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: local SOCKS not ready")
                failAndStop(RootProxyManager.RootError.SOCKS_NOT_READY)
                return@launch
            }
            val err = RootProxyManager.startDetailed(this@CoreRootService)
            if (err != null) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode: $err")
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
     * - After a real onLost→onAvailable transition, also soft-restart the core so
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

            if (!CoreServiceManager.isRunning() && !CoreServiceManager.hasLiveSession()) return@launch

            // 1) Light: rebind dual-mark bypass to current default route (no teardown).
            try {
                RootProxyManager.rebindPhysicalBypass(this@CoreRootService)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-Root: bypass rebind after $reason failed", e)
            }

            // 2) Ensure hev/tun/rules/socks without full rebuild when possible.
            if (CoreServiceManager.isRunning() && !RootProxyManager.isHealthy(this@CoreRootService)) {
                LogUtil.w(AppConfig.TAG, "StartCore-Root: ensuring pipeline after $reason")
                val err = RootProxyManager.ensureRunning(this@CoreRootService)
                if (err != null) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Root: ensure after $reason failed: $err")
                }
            } else if (RootProxyManager.isHealthy(this@CoreRootService)) {
                LogUtil.i(AppConfig.TAG, "StartCore-Root: pipeline healthy after $reason")
            }

            // 3) Only soft-restart core when connectivity actually flapped (lost->available)
            // and pipeline is still unhealthy — avoids needless core thrash on minor callbacks.
            if (softRestartCore && CoreServiceManager.isRunning() && !RootProxyManager.isHealthy(this@CoreRootService)) {
                LogUtil.i(AppConfig.TAG, "StartCore-Root: soft-restart core after $reason (still unhealthy)")
                try {
                    CoreServiceManager.restartCoreLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Root: soft-restart after $reason failed", e)
                }
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
                if (RootProxyManager.isHealthy(this@CoreRootService)) {
                    consecutiveHardFailures = 0
                    delay(30_000L)
                    continue
                }

                LogUtil.w(AppConfig.TAG, "StartCore-Root: pipeline unhealthy, repairing")
                val err = RootProxyManager.ensureRunning(this@CoreRootService)
                if (err == RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: repair backed off, wait for next tick")
                    delay(RootProxyManager.repairBackoffRemainingMs().coerceIn(2_000L, 30_000L))
                    continue
                }

                // After ensure, re-check. Soft/transient probe failures must NOT stop the session.
                val healthy = RootProxyManager.isHealthy(this@CoreRootService)
                if (healthy) {
                    consecutiveHardFailures = 0
                    delay(30_000L)
                    continue
                }

                // Hard fail only when hev+tun are both gone (or su denied / binary missing).
                val hard = err == RootProxyManager.RootError.SU_DENIED ||
                    err == RootProxyManager.RootError.HEV_MISSING ||
                    (!RootProxyManager.isHevAlive(this@CoreRootService) && !RootProxyManager.isTunUp())
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
                    delay(20_000L)
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

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        unregisterNetworkCallback()
        runBlocking { setupJob?.cancelAndJoin() }
        serviceJob.cancel()
        // Remove routing rules BEFORE stopping the core so traffic is never redirected
        // to a dead listener. Synchronous on purpose - leaving rules behind breaks the net.
        RootProxyManager.stop(this)
        CoreServiceManager.stopCoreLoop()
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
