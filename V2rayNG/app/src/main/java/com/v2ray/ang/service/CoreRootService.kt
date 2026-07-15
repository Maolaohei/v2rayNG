package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * The in-process core is started first (so its listener is up and the foreground
 * notification is posted promptly), then the root routing rules are installed off the
 * main thread only after local SOCKS is accepting connections.
 */
class CoreRootService : Service(), ServiceControl {

    private var setupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = this
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

        setupJob = CoroutineScope(Dispatchers.IO).launch {
            // Wait until local SOCKS is ready before installing hev/iptables.
            if (!waitLocalSocksReady()) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: local SOCKS not ready")
                failAndStop(RootProxyManager.RootError.SOCKS_NOT_READY)
                return@launch
            }
            val err = RootProxyManager.startDetailed(this@CoreRootService)
            if (err != null) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode: $err")
                failAndStop(err)
            }
        }

        return START_STICKY
    }

    private suspend fun waitLocalSocksReady(timeoutMs: Long = 4000L): Boolean {
        val port = SettingsManager.getSocksPort()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!CoreServiceManager.isRunning()) return false
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 200)
                    return true
                }
            } catch (_: Exception) {
                // retry
            }
            delay(100L)
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
        runBlocking { setupJob?.cancelAndJoin() }
        // Remove routing rules BEFORE stopping the core so traffic is never redirected
        // to a dead listener. Synchronous on purpose — leaving rules behind breaks the net.
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
