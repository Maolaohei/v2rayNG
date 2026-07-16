package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.TrafficStatsManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.service.ConnectionWatchdog
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.IDialerService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object CoreServiceManager {

    internal val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** True while we intentionally stop the core (user stop or soft-restart). */
    private val intentionalStop = AtomicBoolean(false)
    /** Bumped on every intentional stop; used to retire delayed clear of intentionalStop. */
    private val intentionalStopEpoch = AtomicLong(0)
    /** True while we intentionally stop the core only to restart it in-place. */
    private val softRestarting = AtomicBoolean(false)
    /** True after successful core start until full stop. Survives brief core flaps. */
    private val sessionActive = AtomicBoolean(false)
    /** Bumped on user/full stop so in-flight soft-restarts cannot revive the session. */
    private val sessionGeneration = AtomicInteger(0)
    /** Set when user/system requests a full stop; blocks soft-restart completion. */
    private val userStopRequested = AtomicBoolean(false)
    /** Last VPN tun PFD while the Android service is alive (needed for core soft-restart). */
    @Volatile
    private var activeVpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var receiverRegistered = false
    private var measureJob: Job? = null
    @Volatile
    private var pendingSelectedApply = false

    /** Strong ref while the core service is alive; cleared on full stop/destroy. */
    var serviceControl: ServiceControl? = null
        set(value) {
            field = value
            val service = value?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        clearUserStopGate()
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(friendlyStartError(context, e))
            return false
        }
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        clearUserStopGate()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            context.toast(friendlyStartError(context, e))
        }
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        beginUserStop()
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** Mark a full user/system stop so any in-flight soft-restart cannot revive the session. */
    private fun beginUserStop() {
        userStopRequested.set(true)
        pendingSelectedApply = false
        sessionGeneration.incrementAndGet()
        cancelMeasureDelay("user stop", notifyUi = false)
    }

    /** Clear stop gate when intentionally starting/applying a session. */
    private fun clearUserStopGate() {
        userStopRequested.set(false)
    }


    /** Map technical start failures to short user-facing text (tile/widget/home). */
    private fun friendlyStartError(context: Context, e: Exception): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        RootProxyManager.userMessageOrNull(context, raw)?.let { return it }
        return when {
            SettingsManager.isRootMode() && (
                raw.contains("root", ignoreCase = true) ||
                    raw.contains("su", ignoreCase = true) ||
                    raw.contains("ioctl", ignoreCase = true) ||
                    raw.contains("routing", ignoreCase = true) ||
                    raw.contains("hev", ignoreCase = true) ||
                    raw.contains("tun", ignoreCase = true) ||
                    raw.contains(context.getString(R.string.toast_root_mode_unavailable), ignoreCase = true) ||
                    raw.contains(context.getString(R.string.toast_root_required), ignoreCase = true)
                ) -> context.getString(R.string.toast_root_start_failed)
            raw.contains("No server selected", ignoreCase = true) ->
                context.getString(R.string.app_tile_first_use)
            else -> raw
        }
    }



    /** Mark intentional core stop; delayed clear is retired by epoch so races cannot flip the flag early. */
    private fun markIntentionalStop() {
        val epoch = intentionalStopEpoch.incrementAndGet()
        intentionalStop.set(true)
        serviceScope.launch {
            kotlinx.coroutines.delay(2000L)
            if (epoch == intentionalStopEpoch.get() && !softRestarting.get()) {
                intentionalStop.set(false)
            }
        }
    }

    private fun clearIntentionalStop() {
        intentionalStopEpoch.incrementAndGet()
        intentionalStop.set(false)
    }

    /**
     * Soft-restart only reloads the in-process core. Root mode still needs iptables/tun2socks
     * rebound to the new SOCKS port/listener; VPN-mode LAN sharing uses the same helper path.
     */
    private fun rebindRootRoutingAfterSoftRestart() {
        val service = getService() ?: return
        try {
            if (SettingsManager.isRootMode()) {
                // SOCKS port is stable in ROOT. ensureRunning waits for SOCKS after soft-restart
                // and only rebuilds when hev/tun/rules are actually down.
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: ensuring root routing after soft-restart")
                val err = RootProxyManager.ensureRunning(service)
                if (err == RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: root ensure backed off after soft-restart, keep session")
                } else if (err != null) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: root routing ensure failed: $err")
                    MessageUtil.sendMsg2UI(
                        service,
                        AppConfig.MSG_STATE_START_FAILURE,
                        RootProxyManager.userMessage(service, err)
                    )
                    TrafficStatsManager.stopServiceTracking()
                    NotificationManager.cancelNotification()
                    try { serviceControl?.stopService() } catch (_: Exception) { }
                }
                return
            }
            // VPN-mode optional LAN sharing: rebuild only if preference is still on.
            if (
                service is CoreVpnService &&
                MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING) == true
            ) {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: rebinding LAN sharing after soft-restart")
                com.v2ray.ang.root.RootLanSharing.stopClientSharing(service)
                com.v2ray.ang.root.RootLanSharing.startClientSharing(service)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: rebind root routing failed", e)
            if (SettingsManager.isRootMode()) {
                try {
                    MessageUtil.sendMsg2UI(
                        service,
                        AppConfig.MSG_STATE_START_FAILURE,
                        e.message ?: service.getString(R.string.toast_root_rules_failed)
                    )
                } catch (_: Exception) { }
                TrafficStatsManager.stopServiceTracking()
                NotificationManager.cancelNotification()
                try { serviceControl?.stopService() } catch (_: Exception) { }
            }
        }
    }

    /**
     * Apply the currently selected profile to a live session.
     *
     * Root cause of "switch node while connected sticks on Testing...":
     * UI used hard stop + fixed-delay start. stopLoop/service teardown is async,
     * so start often hit "core already running" and became a no-op, while an
     * in-flight measureDelay was cancelled without clearing home test UI.
     *
     * Soft-restart reloads config on the existing foreground service + TUN.
     */
    fun applySelectedServer(context: Context) {
        clearUserStopGate()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: applySelectedServer")
        // Drop any in-flight latency test; home UI is managed by caller.
        cancelMeasureDelay("node switched", notifyUi = false)
        cancelBatchRealPing()
        val control = serviceControl
        if (control != null) {
            val liveCore = coreController.isRunning || softRestarting.get() || activeVpnInterface != null
            if (liveCore) {
                if (!restartCoreLoop()) {
                    // Another soft-restart is in flight; re-apply when it finishes.
                    pendingSelectedApply = true
                }
            } else {
                // Service process still up but core is down: revive without new FGS start race.
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: revive core on existing service")
                if (!startCoreLoop(activeVpnInterface)) {
                    startVService(context)
                }
            }
            return
        }
        // Service not alive: cold start with the new selection.
        startVService(context)
    }

    /** Cancel in-flight measureDelay; optionally reset home test label. */
    fun cancelMeasureDelay(reason: String = "cancelled", notifyUi: Boolean = true) {
        val job = measureJob
        measureJob = null
        if (job != null) {
            job.cancel()
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: measureDelay cancelled ($reason)")
        }
        if (!notifyUi) return
        // Only clear UI when a test was actually running; avoid clobbering "Connected".
        if (job == null) return
        val service = getService() ?: return
        try {
            MessageUtil.sendMsg2UI(
                service,
                AppConfig.MSG_MEASURE_DELAY_SUCCESS,
                service.getString(R.string.connection_connected)
            )
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: failed to clear measure UI", e)
        }
    }

    /** Cancel background real-ping batch so it cannot fight soft-restart for ports/CPU. */
    private fun cancelBatchRealPing() {
        val service = getService() ?: return
        try {
            MessageUtil.sendMsg2TestService(
                service,
                com.v2ray.ang.dto.TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
            )
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: cancel batch real-ping failed", e)
        }
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /** True while a soft-restart is applying a new core session in-place. */
    fun isSoftRestarting(): Boolean = softRestarting.get()

    /**
     * True while a proxy session should be considered live for UI re-sync.
     * Requires an intentional active session (successful start) plus either a live core
     * or a still-bound service control. Preference alone is never enough.
     */
    fun hasLiveSession(): Boolean {
        if (userStopRequested.get()) return false
        if (coreController.isRunning || softRestarting.get() || activeVpnInterface != null) {
            return true
        }
        if (!sessionActive.get()) return false
        val control = serviceControl
        return control is CoreVpnService ||
            control is CoreRootService ||
            control is CoreProxyOnlyService
    }


    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     * @throws IllegalStateException if the core is already running, no server is selected,
     *   server config cannot be decoded, or server configuration is invalid.
     * @throws Exception if the foreground service fails to start.
     */
    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return
        }

        val guid = MmkvManager.getSelectServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (!config.configType.isComplexType()
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        // refresh socks port when enabled dynamic socks port
        SettingsManager.refreshRuntimeSocksPort()

//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })

        if (config.insecure == true) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_mode_unavailable))
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    /** Keep cached TUN in sync when VPN re-establishes the interface. */
    fun bindVpnInterface(vpnInterface: ParcelFileDescriptor?) {
        activeVpnInterface = vpnInterface
    }

    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            val iface = vpnInterface ?: activeVpnInterface
            doStartCoreLoop(service, iface)
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            if (!softRestarting.get()) {
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                TrafficStatsManager.stopServiceTracking()
                NotificationManager.cancelNotification()
            }
            return false
        }
    }

    /**
     * Soft-restart the Xray core without tearing down the Android foreground service.
     * Used by node switch, ConnectionWatchdog, network recovery, and unexpected shutdown recovery.
     * Does not emit STOP_SUCCESS (avoids UI flipping to stopped while still connected).
     * Work runs on IO; safe to call from main / binder threads.
     */
    fun restartCoreLoop(): Boolean {
        if (getService() == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: restartCoreLoop service is null")
            return false
        }
        if (userStopRequested.get()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: skip soft-restart, user stop requested")
            return false
        }
        if (!softRestarting.compareAndSet(false, true)) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: restart already in progress")
            return false
        }

        val generation = sessionGeneration.get()
        cancelMeasureDelay("soft-restart", notifyUi = false)
        cancelBatchRealPing()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Soft-restarting core gen=$generation")
        serviceScope.launch {
            var startedOk = false
            try {
                if (userStopRequested.get() || generation != sessionGeneration.get()) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: soft-restart aborted before stop (user stop/gen)")
                    return@launch
                }
                stopCoreLoop(
                    notifyUi = false,
                    cancelNotification = false,
                    stopWatchdog = true,
                    clearVpnInterface = false,
                )
                // Wait until native core actually stops (async stopLoop race is the root bug).
                var waited = 0
                while (coreController.isRunning && waited < 50) {
                    if (userStopRequested.get() || generation != sessionGeneration.get()) {
                        LogUtil.i(AppConfig.TAG, "StartCore-Manager: soft-restart aborted while waiting stop")
                        return@launch
                    }
                    kotlinx.coroutines.delay(100L)
                    waited++
                }
                if (coreController.isRunning) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Manager: core still running after stop wait, forcing stopLoop")
                    try { coreController.stopLoop() } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: force stopLoop failed", e)
                    }
                    kotlinx.coroutines.delay(200L)
                }
                if (userStopRequested.get() || generation != sessionGeneration.get()) {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: soft-restart aborted before start (user stop/gen)")
                    return@launch
                }
                val ok = startCoreLoop(activeVpnInterface)
                startedOk = ok
                if (!ok) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Soft-restart failed to start core")
                    // Full converge: tear down service so UI/notification cannot desync.
                    if (!userStopRequested.get()) {
                        val svcControl = serviceControl
                        val svc = svcControl?.getService()
                        if (svc != null) {
                            MessageUtil.sendMsg2UI(svc, AppConfig.MSG_STATE_START_FAILURE, "Soft-restart failed")
                        }
                        TrafficStatsManager.stopServiceTracking()
                        NotificationManager.cancelNotification()
                        try { svcControl?.stopService() } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "StartCore-Manager: stopService after soft-restart failure", e)
                        }
                    }
                } else {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Soft-restart completed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Soft-restart failed", e)
                if (!userStopRequested.get() && generation == sessionGeneration.get()) {
                    val svcControl = serviceControl
                    val svc = svcControl?.getService()
                    if (svc != null) {
                        MessageUtil.sendMsg2UI(svc, AppConfig.MSG_STATE_START_FAILURE, e.message ?: "Soft-restart failed")
                    }
                    TrafficStatsManager.stopServiceTracking()
                    NotificationManager.cancelNotification()
                    try { svcControl?.stopService() } catch (_: Exception) { }
                }
            } finally {
                val canReapply = pendingSelectedApply &&
                    !userStopRequested.get() &&
                    generation == sessionGeneration.get() &&
                    getService() != null
                if (canReapply) {
                    pendingSelectedApply = false
                    softRestarting.set(false)
                    clearIntentionalStop()
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: applying pending selected server after soft-restart")
                    restartCoreLoop()
                } else {
                    if (pendingSelectedApply && (userStopRequested.get() || generation != sessionGeneration.get())) {
                        pendingSelectedApply = false
                        LogUtil.i(AppConfig.TAG, "StartCore-Manager: drop pending selected apply after user stop")
                    } else if (
                        startedOk &&
                        !userStopRequested.get() &&
                        generation == sessionGeneration.get()
                    ) {
                        // Keep softRestarting true while rebinding root rules so watchdog/network
                        // recovery cannot start another soft-restart mid-rebind.
                        rebindRootRoutingAfterSoftRestart()
                    }
                    softRestarting.set(false)
                    clearIntentionalStop()
                }
            }
        }
        return true
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        // Soft-restart may refresh dynamic SOCKS for VPN/Proxy helpers.
        // ROOT freezes SOCKS port so hev/iptables need not rebind on every node switch.
        if (softRestarting.get() && SettingsManager.usesDynamicSocksPort()) {
            SettingsManager.refreshRuntimeSocksPort()
        }
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
            receiverRegistered = true
        }

        currentConfig = config
        // Only Android VPN mode may feed a TUN fd into core. Root/Proxy must never pass a
        // stale VpnService PFD — that triggers "inappropriate ioctl for device" on start.
        if (SettingsManager.isVpnMode()) {
            if (vpnInterface != null) {
                activeVpnInterface = vpnInterface
            }
        } else {
            activeVpnInterface = null
        }
        var tunFd = 0
        if (SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()) {
            tunFd = activeVpnInterface?.fd ?: 0
            if (tunFd <= 0) {
                error("VPN mode requires a valid TUN interface")
            }
        }
        val dialerAddr = if (currentConfig?.browserDialerMode.isNullOrEmpty()) {
            ""
        } else {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        }

        NotificationManager.showNotification(currentConfig)
        CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        coreController.startLoop(result.content, tunFd)

        if (!coreController.isRunning) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        if (config.browserDialerMode == "OkHttp") {
            browserDialer = DialerNativeService()
            browserDialer!!.start(service, dialerAddr)
        } else if (config.browserDialerMode == "WebView") {
            browserDialer = DialerWebviewService()
            browserDialer!!.start(service, dialerAddr)
        }

        val startContent = if (softRestarting.get()) AppConfig.MSG_CONTENT_SOFT_START else ""
        sessionActive.set(true)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, startContent)
        TrafficStatsManager.startServiceTracking()
        NotificationManager.startSpeedNotification()
        ConnectionWatchdog.start()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        return stopCoreLoop(
            notifyUi = true,
            cancelNotification = true,
            stopWatchdog = true,
            clearVpnInterface = true,
        )
    }

    /**
     * @param notifyUi emit MSG_STATE_STOP_SUCCESS (full user stop only)
     * @param cancelNotification clear foreground notification (full stop only)
     * @param stopWatchdog stop connection watchdog
     * @param clearVpnInterface drop cached TUN (full stop / service teardown only)
     */
    fun stopCoreLoop(
        notifyUi: Boolean,
        cancelNotification: Boolean,
        stopWatchdog: Boolean,
        clearVpnInterface: Boolean,
    ): Boolean {
        val service = getService() ?: return false
        if (clearVpnInterface) {
            // Full teardown (user stop / service destroy): invalidate soft-restarts.
            beginUserStop()
        }

        markIntentionalStop()
        if (coreController.isRunning) {
            try {
                coreController.stopLoop()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        if (notifyUi) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        }
        TrafficStatsManager.stopServiceTracking()
        if (cancelNotification) {
            NotificationManager.cancelNotification()
        }
        if (stopWatchdog) {
            ConnectionWatchdog.stop()
        }
        if (clearVpnInterface) {
            activeVpnInterface = null
            serviceControl = null
            sessionActive.set(false)
        }

        if (receiverRegistered && (clearVpnInterface || !softRestarting.get())) {
            try {
                service.unregisterReceiver(mMsgReceive)
                receiverRegistered = false
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
                receiverRegistered = false
            }
        }

        return true
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Always posts a result to UI so the home "Testing..." state cannot stick forever.
     * Cancels any in-flight measurement (e.g. when switching nodes / soft-restarting).
     */
    private fun measureV2rayDelay() {
        // Cancel previous job WITHOUT the cancelMeasureDelay UI path first; we replace it.
        measureJob?.cancel()
        measureJob = serviceScope.launch {
            val service = getService()
            if (service == null) {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: measureDelay aborted, service is null")
                return@launch
            }
            try {
                // Wait briefly if a soft-restart is applying a newly selected node.
                var wait = 0
                while ((softRestarting.get() || !coreController.isRunning) && wait < 20) {
                    kotlinx.coroutines.delay(100L)
                    wait++
                }
                if (!coreController.isRunning || softRestarting.get()) {
                    MessageUtil.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_SUCCESS,
                        service.getString(R.string.connection_test_error, "core not ready")
                    )
                    return@launch
                }

                var time = -1L
                var errorStr = ""
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
                if (time == -1L) {
                    try {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                        errorStr = e.message?.substringAfter("\":") ?: "empty message"
                    }
                }

                if (!coreController.isRunning || softRestarting.get()) {
                    MessageUtil.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_SUCCESS,
                        service.getString(R.string.connection_test_error, "interrupted")
                    )
                    return@launch
                }

                val result = if (time >= 0) {
                    service.getString(R.string.connection_test_available, time)
                } else {
                    service.getString(R.string.connection_test_error, errorStr)
                }
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

                if (time >= 0) {
                    SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                        if (coreController.isRunning && !softRestarting.get()) {
                            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Silent: cancelMeasureDelay() decides whether to clear UI.
                // Replacing measureJob for a new test must not flash "cancelled".
                throw e
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * Instead of stopping the service, attempt to restart the core so the
         * foreground service stays alive and the user's proxy stays available.
         * Skips restart when the stop was triggered intentionally (user-initiated).
         */
        override fun shutdown(): Long {
            if (intentionalStop.get() || softRestarting.get() || userStopRequested.get()) {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core shutdown (intentional/soft-restart), not treating as crash")
                return 0
            }
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core shutdown callback fired (unexpected), attempting soft-restart")
            val svc = getService() ?: return -1
            val generation = sessionGeneration.get()
            serviceScope.launch {
                try {
                    kotlinx.coroutines.delay(800L)
                    if (
                        intentionalStop.get() ||
                        softRestarting.get() ||
                        userStopRequested.get() ||
                        generation != sessionGeneration.get() ||
                        coreController.isRunning
                    ) {
                        return@launch
                    }
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Soft-restarting core after unexpected shutdown")
                    val ok = restartCoreLoop()
                    if (!ok) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: Soft-restart after shutdown failed")
                        MessageUtil.sendMsg2UI(svc, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                        TrafficStatsManager.stopServiceTracking()
                        NotificationManager.cancelNotification()
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to restart core after shutdown", e)
                    MessageUtil.sendMsg2UI(svc, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                    TrafficStatsManager.stopServiceTracking()
                    NotificationManager.cancelNotification()
                }
            }
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    // Soft-restart may briefly report core not running while the foreground
                    // service is still the active session. Prefer RUNNING only for real live
                    // sessions - never solely because the ROOT preference is selected.
                    if (hasLiveSession()) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    beginUserStop()
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service (soft)")
                    // Prefer soft-restart. If one is already running, queue re-apply.
                    if (!restartCoreLoop()) {
                        if (softRestarting.get()) {
                            pendingSelectedApply = true
                        } else {
                            val svc = serviceControl?.getService()
                            if (svc != null) {
                                applySelectedServer(svc)
                            }
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    if (isRunning()) {
                        TrafficStatsManager.startServiceTracking()
                        NotificationManager.startSpeedNotification()
                    }
                }
            }
        }
    }
}
