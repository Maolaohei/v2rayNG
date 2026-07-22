package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import com.v2ray.ang.xposed.PrivilegeSettingsClient

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    @Volatile
    private var isRunning = false
    /** True only while mInterface is established and not closed. lateinit stays "initialized" after close. */
    @Volatile
    private var interfaceOpen = false
    @Volatile
    private var pendingRestart = false
    @Volatile
    private var lastNetworkRecoverAtMs = 0L
    /** True while stopAllService is tearing down TUN / FGS (blocks cold re-entry races). */
    private val stopping = AtomicBoolean(false)
    /** Serializes onStartCommand re-entry and start/stop transitions. */
    private val lifecycleLock = Any()
    /**
     * Generation for in-flight start attempts. Bumped on every user/system stop so a late
     * start cannot revive a session the user already cancelled.
     */
    private val startGeneration = AtomicInteger(0)
    private var tun2SocksService: Tun2SocksControl? = null

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
                if (pendingRestart) {
                    pendingRestart = false
                    // Debounce rapid lost/available flaps (signal dips). Soft-restart drops app TCP.
                    // Still allow a real network handoff recover, but not more than once/min.
                    val now = System.currentTimeMillis()
                    if (lastNetworkRecoverAtMs > 0L && now - lastNetworkRecoverAtMs < 60_000L) {
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: network available after loss, recover cooldown, skip soft-restart")
                        return
                    }
                    if (isRunning && !CoreServiceManager.isSoftRestarting()) {
                        CoreServiceManager.bindVpnInterface(mInterface)
                        // Soft-restart only when the in-process core is actually down.
                        // Remote blips / Wi-Fi handoff with a live core should not drop every TCP.
                        if (CoreServiceManager.isRunning()) {
                            LogUtil.i(
                                AppConfig.TAG,
                                "StartCore-VPN: network available after loss, core still live; skip soft-restart"
                            )
                            try {
                                MessageUtil.sendMsg2UI(this@CoreVpnService, AppConfig.MSG_STATE_NETWORK_RECOVERED, "")
                            } catch (_: Exception) {
                            }
                        } else {
                            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Network available after loss, soft-restarting core")
                            lastNetworkRecoverAtMs = now
                            try {
                                MessageUtil.sendMsg2UI(this@CoreVpnService, AppConfig.MSG_STATE_NETWORK_RECOVERING, "")
                            } catch (_: Exception) {
                            }
                            CoreServiceManager.restartCoreLoop()
                            try {
                                MessageUtil.sendMsg2UI(this@CoreVpnService, AppConfig.MSG_STATE_NETWORK_RECOVERED, "")
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
                pendingRestart = true
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: Network lost, will restart core on new network")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        CoreServiceManager.serviceControl = this
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    /**
     * Swiping the app away must NOT stop the proxy. Foreground service + sticky restart
     * keep the daemon alive; UI will re-REGISTER when MainActivity returns.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: onTaskRemoved - keep service running")
        // Do not call stopSelf / stopCoreLoop.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Unexpected destroy path: stop core first, then close TUN. stopAllService already
        // ran this sequence and set isRunning=false; second stopCoreLoop is a no-op once
        // serviceControl is cleared.
        try {
            if (isRunning) {
                RootLanSharing.stopClientSharing(this)
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to stop LAN sharing in onDestroy", e)
        }
        try {
            CoreServiceManager.stopCoreLoop()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: stopCoreLoop in onDestroy failed", e)
        }
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
            interfaceOpen = false
            isRunning = false
        }
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        // FGS contract: promote first so re-entry / sticky restart cannot miss the timeout.
        NotificationManager.showNotification(null)

        // START_STICKY / secondary startForegroundService re-entry must NOT tear down a healthy
        // VPN session. Closing TUN then hitting "core already running" used to call stopAllService
        // and force users to toggle multiple times before traffic recovered.
        synchronized(lifecycleLock) {
            // If a start arrives while stopAllService holds/just set this flag, we still proceed
            // after acquiring lifecycleLock (stop finishes first). Clear the flag for cold start.
            if (stopping.get()) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: start after/during teardown; will cold-start once lock is free")
            }

            val interfaceReady = interfaceOpen && ::mInterface.isInitialized
            val softApply = intent?.getBooleanExtra(AppConfig.EXTRA_SOFT_APPLY_SELECTED, false) == true
            val action = VpnStartDecision.decide(
                interfaceOpen = interfaceReady,
                isRunningFlag = isRunning,
                coreRunning = CoreServiceManager.isRunning(),
                softRestarting = CoreServiceManager.isSoftRestarting(),
                hasLiveSession = CoreServiceManager.hasLiveSession(),
                softApplySelected = softApply,
                selectedConfigActive = CoreServiceManager.isSelectedConfigActive(),
            )

            if (action != VpnStartDecision.Action.COLD_SETUP) {
                CoreServiceManager.serviceControl = this
                if (interfaceReady) {
                    CoreServiceManager.bindVpnInterface(mInterface)
                }

                when (action) {
                    VpnStartDecision.Action.SKIP_REBUILD_CORE_LIVE -> {
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: re-entry while core live, skip rebuild")
                        // Re-affirm UI truth for sticky / secondary start intents.
                        try {
                            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RUNNING, "")
                        } catch (_: Exception) {
                        }
                        isRunning = true
                        stopping.set(false)
                        return START_STICKY
                    }
                    VpnStartDecision.Action.KEEP_SOFT_RESTART -> {
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: re-entry during soft-restart, queue soft-apply")
                        if (softApply) {
                            // Soft-restart already running; re-apply selected profile when it finishes.
                            CoreServiceManager.applySelectedServer(this)
                        }
                        isRunning = true
                        stopping.set(false)
                        return START_STICKY
                    }
                    VpnStartDecision.Action.SOFT_APPLY_SELECTED -> {
                        // Cross-process node switch: reload selected profile without tearing TUN/FGS.
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: soft-apply selected server")
                        stopping.set(false)
                        isRunning = true
                        CoreServiceManager.applySelectedServer(this)
                        return START_STICKY
                    }
                    VpnStartDecision.Action.REVIVE_CORE_ON_EXISTING_TUN -> {
                        // Service / TUN still around but core is down: revive without closing TUN.
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: re-entry with TUN live but core down, revive core")
                        stopping.set(false)
                        startService()
                        return START_STICKY
                    }
                    VpnStartDecision.Action.COLD_SETUP -> {
                        // unreachable due to outer guard; fall through
                    }
                }
            }

            stopping.set(false)
            val generation = startGeneration.incrementAndGet()
            setupVpnService(generation)
            if (generation != startGeneration.get() || stopping.get()) {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: start aborted after setup (stop raced)")
                return START_STICKY
            }
            startService(generation)
        }
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        startService(startGeneration.get())
    }

    private fun startService(generation: Int) {
        if (generation != startGeneration.get() || stopping.get()) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: startService skipped (generation/stop race)")
            return
        }
        if (!::mInterface.isInitialized || !interfaceOpen) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        // Push hidevpn/rename settings into system_server before traffic/iface probes race.
        runCatching { PrivilegeSettingsClient.sync() }
            .onFailure { LogUtil.w(AppConfig.TAG, "StartCore-VPN: privilege sync failed: ${it.message}") }

        // "Core already running" is success for re-entry / sticky restart; only hard-fail real starts.
        if (CoreServiceManager.isRunning()) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: core already running, treat as started")
            CoreServiceManager.bindVpnInterface(mInterface)
            isRunning = true
            try {
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RUNNING, "")
            } catch (_: Exception) {
            }
            RootLanSharing.startClientSharing(this)
            return
        }

        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            // Soft-restart may briefly own the core; do not tear down a live session.
            if (CoreServiceManager.isRunning() || CoreServiceManager.isSoftRestarting() || CoreServiceManager.hasLiveSession()) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: startCoreLoop returned false but session still live; keep service")
                CoreServiceManager.bindVpnInterface(mInterface)
                isRunning = true
                return
            }
            if (generation != startGeneration.get() || stopping.get()) {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: startCoreLoop failed after user stop; skip teardown spam")
                return
            }
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        if (generation != startGeneration.get() || stopping.get()) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: core started after stop request; shutting down")
            stopAllService()
            return
        }

        isRunning = true
        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    /**
     * Soft-restart reloads only the in-process core. Hev still holds the previous SOCKS
     * target/port, so VPN Hev path must rebind after a successful soft-restart (especially
     * when dynamic SOCKS refreshed). System-TUN path only needs the live PFD rebound.
     */
    fun rebindAfterSoftRestart() {
        if (!::mInterface.isInitialized || !interfaceOpen || !isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: rebindAfterSoftRestart skipped (interface not ready)")
            return
        }
        try {
            CoreServiceManager.bindVpnInterface(mInterface)
            if (SettingsManager.isUsingHevTun()) {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: rebinding Hev tun2socks after soft-restart")
                try {
                    tun2SocksService?.stopTun2Socks()
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: stop Hev before rebind failed", e)
                }
                tun2SocksService = null
                runTun2socks()
            } else {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: system TUN path, PFD rebound after soft-restart")
            }
            // Optional LAN sharing follows the same soft-restart lifecycle.
            RootLanSharing.stopClientSharing(this)
            RootLanSharing.startClientSharing(this)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: rebindAfterSoftRestart failed", e)
        }
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(generation: Int = startGeneration.get()) {
        if (generation != startGeneration.get() || stopping.get()) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: setup aborted (generation/stop race)")
            return
        }
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            try {
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "VPN permission not granted")
            } catch (_: Exception) {
            }
            stopSelf()
            return
        }

        if (configureVpnService(generation) != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            if (generation == startGeneration.get() && !stopping.get()) {
                try {
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "VPN interface setup failed")
                } catch (_: Exception) {
                }
                stopSelf()
            }
            return
        }

        if (generation != startGeneration.get() || stopping.get()) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: setup finished after stop; closing fresh interface")
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                }
            } catch (_: Exception) {
            }
            interfaceOpen = false
            return
        }

        runTun2socks()
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(generation: Int = startGeneration.get()): Boolean {
        if (generation != startGeneration.get() || stopping.get()) {
            return false
        }
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }
        interfaceOpen = false

        if (generation != startGeneration.get() || stopping.get()) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: configure aborted before establish (stop raced)")
            return false
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            val established = builder.establish()
            if (established == null) {
                LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface: builder.establish() returned null")
                return false
            }
            if (generation != startGeneration.get() || stopping.get()) {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: establish completed after stop; closing interface")
                try {
                    established.close()
                } catch (_: Exception) {
                }
                return false
            }
            mInterface = established
            interfaceOpen = true
            CoreServiceManager.bindVpnInterface(mInterface)
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            if (generation == startGeneration.get() && !stopping.get()) {
                stopAllService()
            }
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers.
        // Local DNS / FakeDNS path: push system resolvers to the VPN router addresses so
        // queries enter the TUN inbound and can be hijacked to dns-out by CoreConfigManager.
        // Otherwise use configured public/custom VPN DNS servers.
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
            builder.addDnsServer(vpnConfig.ipv4Router)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
                try {
                    builder.addDnsServer(vpnConfig.ipv6Router)
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: failed to add IPv6 local DNS", e)
                }
            }
            LogUtil.i(
                AppConfig.TAG,
                "StartCore-VPN: local DNS via tunnel router ${vpnConfig.ipv4Router}" +
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
                        "/${vpnConfig.ipv6Router}"
                    } else {
                        ""
                    }
            )
        } else {
            SettingsManager.getVpnDnsServers().forEach {
                if (Utils.isPureIpAddress(it)) {
                    builder.addDnsServer(it)
                }
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }

        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        synchronized(lifecycleLock) {
            // Invalidate any in-flight cold start before mutating TUN/core.
            startGeneration.incrementAndGet()
            stopping.set(true)
            isRunning = false
            pendingRestart = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    connectivity.unregisterNetworkCallback(defaultNetworkCallback)
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to unregister callback", e)
                }
            }

            try {
                tun2SocksService?.stopTun2Socks()
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: stopTun2Socks failed", e)
            }
            tun2SocksService = null

            try {
                RootLanSharing.stopClientSharing(this)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: stop LAN sharing failed", e)
            }

            try {
                // Defer STOP_SUCCESS until TUN is closed. Emitting it at stopCoreLoop made the
                // home switch re-enable while the system iface was still tearing down, so the
                // next Off->On establish raced and needed multiple toggles to recover.
                CoreServiceManager.stopCoreLoop(
                    notifyUi = false,
                    cancelNotification = true,
                    stopWatchdog = true,
                    clearVpnInterface = true,
                )
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: stopCoreLoop failed", e)
            }

            if (isForced) {
                // stopSelf ahead of mInterface.close so core can release ports before TUN dies.
                stopSelf()

                // Allow async core stop + system VpnService teardown to settle before close.
                // 50ms was too short: rapid Off->On re-establish raced the previous iface.
                try {
                    Thread.sleep(180)
                } catch (e: InterruptedException) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
                }

                try {
                    if (::mInterface.isInitialized) {
                        mInterface.close()
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
                }
                interfaceOpen = false
            }

            try {
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: failed to emit STOP_SUCCESS after teardown", e)
            }
            // Keep stopping=true until destroy finishes; onCreate/onStart of a new instance
            // starts with a fresh object so the next cold start is not blocked.
        }
    }
}

