package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil

/**
 * ROOT dataplane: root-created TUN fd -> Bray-Core Android TUN inbound (gVisor).
 *
 * No hev / no SOCKS hairpin for system traffic. Local SOCKS inbound may still exist
 * for app-local tools, but capture is MARK -> v2raytun0 -> core tun.
 *
 * Start order:
 * 1) clean previous hev/rules
 * 2) stop core if rebuilding so old fd is not left in use
 * 3) open TUN fd in daemon process
 * 4) start core with tunFd
 * 5) install iptables/ip-rule only
 */
object XrayTunRootDataPlane : RootDataPlane {
    override val engine: RootEngine = RootEngine.XRAY_TUN

    override fun start(context: Context): RootProxyManager.RootError? {
        lastError = null
        return try {
            // Drop legacy hev rules/helpers so only one capture path owns the device.
            RootProxyManager.stop(context)

            // Full rebuild cannot reopen TUN while core still holds the previous fd in gVisor.
            if (CoreServiceManager.isRunning()) {
                LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun rebuild, stopping core before reopening fd")
                CoreServiceManager.stopCoreLoop(
                    notifyUi = false,
                    cancelNotification = false,
                    stopWatchdog = true,
                    clearVpnInterface = false,
                )
                var waited = 0
                while (CoreServiceManager.isRunning() && waited < 50) {
                    try {
                        Thread.sleep(100L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    waited++
                }
            }

            val handle = RootTun.open(context)
            LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun fd=${handle.fd} ifname=${handle.ifname}")

            if (!CoreServiceManager.startCoreLoop(null)) {
                LogUtil.e(AppConfig.TAG, "RootDataPlane: xray_tun core start failed")
                RootTun.close()
                lastError = RootProxyManager.RootError.UNKNOWN
                return lastError
            }

            val rulesErr = RootProxyManager.installRulesOnly(context)
            if (rulesErr != null) {
                LogUtil.e(AppConfig.TAG, "RootDataPlane: xray_tun rules failed: $rulesErr")
                RootProxyManager.stop(context)
                CoreServiceManager.stopCoreLoop(
                    notifyUi = false,
                    cancelNotification = false,
                    stopWatchdog = true,
                    clearVpnInterface = false,
                )
                RootTun.close()
                lastError = rulesErr
                return rulesErr
            }

            LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun started ok ${snapshot(context)}")
            null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "RootDataPlane: xray_tun start exception: ${e.message}", e)
            runCatching { RootProxyManager.stop(context) }
            runCatching {
                if (CoreServiceManager.isRunning()) {
                    CoreServiceManager.stopCoreLoop(
                        notifyUi = false,
                        cancelNotification = false,
                        stopWatchdog = true,
                        clearVpnInterface = false,
                    )
                }
            }
            RootTun.close()
            lastError = when {
                e.message?.contains("nativeOpenTun", true) == true -> RootProxyManager.RootError.TUN_FAILED
                e.message?.contains("helper missing", true) == true -> RootProxyManager.RootError.TUN_FAILED
                e.message?.contains("su", true) == true -> RootProxyManager.RootError.SU_DENIED
                else -> RootProxyManager.RootError.UNKNOWN
            }
            lastError
        }
    }

    override fun stop(context: Context) {
        // Rules first so traffic is not steered into a dying tun/core.
        runCatching { RootProxyManager.stop(context) }
        LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun stop (rules down)")
    }

    override fun ensure(context: Context): RootProxyManager.RootError? {
        if (isHealthy(context)) {
            lastError = null
            return null
        }
        // Cannot recreate fd without restarting core; repair rules if tun still open.
        if (!RootTun.isOpen() && !RootProxyManager.isTunUp()) {
            LogUtil.e(AppConfig.TAG, "RootDataPlane: xray_tun ensure TUN missing")
            lastError = RootProxyManager.RootError.TUN_FAILED
            return lastError
        }
        if (!CoreServiceManager.isRunning() && !CoreServiceManager.hasLiveSession()) {
            lastError = RootProxyManager.RootError.UNKNOWN
            return lastError
        }
        val err = RootProxyManager.installRulesOnly(context)
        lastError = err
        if (err == null && isHealthy(context)) {
            LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun ensure repaired rules")
            return null
        }
        return err ?: RootProxyManager.RootError.RULES_FAILED
    }

    override fun isHealthy(context: Context, strict: Boolean): Boolean {
        val tunOk = RootTun.isOpen() || RootProxyManager.isTunUp()
        val rulesOk = RootProxyManager.isRulesInstalled()
        val coreOk = try {
            CoreServiceManager.isRunning() || CoreServiceManager.hasLiveSession()
        } catch (_: Exception) {
            false
        }
        if (strict) {
            return tunOk && rulesOk && coreOk
        }
        return tunOk && rulesOk
    }

    override fun isRuntimeLive(): Boolean {
        return RootTun.isOpen() || RootProxyManager.isTunUp()
    }

    override fun snapshot(context: Context): String {
        val healthy = runCatching { isHealthy(context) }.getOrDefault(false)
        val live = runCatching { isRuntimeLive() }.getOrDefault(false)
        val fd = RootTun.currentFd()
        val rules = runCatching { RootProxyManager.isRulesInstalled() }.getOrDefault(false)
        val core = runCatching { CoreServiceManager.isRunning() }.getOrDefault(false)
        return "engine=xray_tun fd=$fd healthy=$healthy runtimeLive=$live rules=$rules core=$core lastError=$lastError"
    }

    @Volatile
    private var lastError: RootProxyManager.RootError? = null
}
