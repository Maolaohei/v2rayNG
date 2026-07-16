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
                if (pendingNetworkRecover.compareAndSet(true, false) ||
                    !RootProxyManager.isHealthy(this@CoreRootService)
                ) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Root: network available, ensuring pipeline")
                    schedulePipelineEnsure(reason = "network-available")
                }
            }

            override fun onLost(network: Network) {
                pendingNetworkRecover.set(true)
                LogUtil.i(AppConfig.TAG, "StartCore-Root: network lost, will ensure on next available")
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

        // Sticky so system can recreate after low-memory kills; onStartCommand will re-setup.
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

    private fun schedulePipelineEnsure(reason: String) {
        serviceScope.launch {
            if (!CoreServiceManager.isRunning()) return@launch
            if (CoreServiceManager.isSoftRestarting()) {
                LogUtil.i(AppConfig.TAG, "StartCore-Root: skip ensure ($reason) during soft-restart")
                return@launch
            }
            // Small debounce so rapid network flaps do not thrash full rebuilds.
            delay(800L)
            if (!CoreServiceManager.isRunning() || CoreServiceManager.isSoftRestarting()) return@launch
            if (RootProxyManager.isHealthy(this@CoreRootService)) {
                LogUtil.i(AppConfig.TAG, "StartCore-Root: pipeline healthy after $reason")
                return@launch
            }
            LogUtil.w(AppConfig.TAG, "StartCore-Root: ensuring pipeline after $reason")
            val err = RootProxyManager.ensureRunning(this@CoreRootService)
            if (err != null) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: ensure after $reason failed: $err")
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            var consecutiveFailures = 0
            // First check after a warm-up window; then periodically.
            delay(15_000L)
            while (isActive && CoreServiceManager.isRunning()) {
                if (CoreServiceManager.isSoftRestarting()) {
                    delay(2_000L)
                    continue
                }
                if (!RootProxyManager.isHealthy(this@CoreRootService)) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Root: pipeline unhealthy, repairing")
                    val err = RootProxyManager.ensureRunning(this@CoreRootService)
                    if (err != null) {
                        consecutiveFailures++
                        LogUtil.e(
                            AppConfig.TAG,
                            "StartCore-Root: watchdog repair failed ($consecutiveFailures/3): $err"
                        )
                        if (consecutiveFailures >= 3) {
                            failAndStop(err)
                            return@launch
                        }
                    } else {
                        consecutiveFailures = 0
                    }
                } else {
                    consecutiveFailures = 0
                }
                delay(20_000L)
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