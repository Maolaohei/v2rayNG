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
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CoreProxyOnlyService : Service(), ServiceControl {
    private val lifecycleLock = Any()
    private val stopping = AtomicBoolean(false)
    private val startGeneration = AtomicInteger(0)
    @Volatile
    private var isRunning = false

    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        CoreServiceManager.serviceControl = this
    }

    /**
     * Handles the start command for the service.
     * Sticky re-entry / soft node-switch must not thrash a healthy session.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service command received")
        // Promote to FGS immediately (same contract as VPN/ROOT).
        NotificationManager.showNotification(null)

        synchronized(lifecycleLock) {
            val softApply = intent?.getBooleanExtra(AppConfig.EXTRA_SOFT_APPLY_SELECTED, false) == true
            val coreRunning = CoreServiceManager.isRunning()
            val softRestarting = CoreServiceManager.isSoftRestarting()
            val liveSession = CoreServiceManager.hasLiveSession() || isRunning

            CoreServiceManager.serviceControl = this

            if (softApply) {
                if (coreRunning || softRestarting || liveSession) {
                    if (CoreServiceManager.isSelectedConfigActive() && coreRunning && !softRestarting) {
                        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: soft-apply already active, skip rebuild")
                        try {
                            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RUNNING, "")
                        } catch (_: Exception) {
                        }
                        isRunning = true
                        stopping.set(false)
                        return START_STICKY
                    }
                    LogUtil.i(AppConfig.TAG, "StartCore-Proxy: soft-apply selected server")
                    isRunning = true
                    stopping.set(false)
                    CoreServiceManager.applySelectedServer(this)
                    return START_STICKY
                }
                // Fall through to cold start with selected profile.
            } else if (coreRunning || softRestarting) {
                LogUtil.i(AppConfig.TAG, "StartCore-Proxy: re-entry while session live, skip rebuild")
                try {
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RUNNING, "")
                } catch (_: Exception) {
                }
                isRunning = true
                stopping.set(false)
                return START_STICKY
            } else if (liveSession) {
                // Service still up, core down: revive once without thrash.
                LogUtil.i(AppConfig.TAG, "StartCore-Proxy: revive core on existing service")
                stopping.set(false)
                val generation = startGeneration.get()
                if (!CoreServiceManager.startCoreLoop(null)) {
                    if (!CoreServiceManager.isRunning() &&
                        !CoreServiceManager.isSoftRestarting() &&
                        !CoreServiceManager.hasLiveSession() &&
                        generation == startGeneration.get() &&
                        !stopping.get()
                    ) {
                        stopSelf()
                    }
                } else {
                    isRunning = true
                }
                return START_STICKY
            }

            stopping.set(false)
            val generation = startGeneration.incrementAndGet()
            if (!CoreServiceManager.startCoreLoop(null)) {
                if (CoreServiceManager.isRunning() ||
                    CoreServiceManager.isSoftRestarting() ||
                    CoreServiceManager.hasLiveSession()
                ) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Proxy: startCoreLoop false but session live; keep")
                    isRunning = true
                    return START_STICKY
                }
                if (generation != startGeneration.get() || stopping.get()) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Proxy: start failed after stop; skip teardown spam")
                    return START_STICKY
                }
                LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to start core loop")
                stopSelf()
                return START_STICKY
            }
            if (generation != startGeneration.get() || stopping.get()) {
                LogUtil.i(AppConfig.TAG, "StartCore-Proxy: core started after stop request; shutting down")
                stopAllService()
                return START_STICKY
            }
            isRunning = true
        }
        return START_STICKY
    }

    /**
     * Swiping the app away must NOT stop the proxy. Foreground service + sticky restart
     * keep the daemon alive; UI will re-REGISTER when MainActivity returns.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: onTaskRemoved - keep service running")
        // Do not call stopSelf / stopCoreLoop.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        CoreServiceManager.stopCoreLoop()
        isRunning = false
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        stopAllService()
    }

    private fun stopAllService() {
        stopping.set(true)
        startGeneration.incrementAndGet()
        isRunning = false
        try {
            CoreServiceManager.stopCoreLoop()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Proxy: stopCoreLoop failed", e)
        }
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}
