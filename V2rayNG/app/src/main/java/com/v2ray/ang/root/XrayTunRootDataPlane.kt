package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ROOT dataplane: root-created TUN fd -> Bray-Core Android TUN inbound (gVisor).
 *
 * Capture path: MARK -> v2raytun0 -> core tun. No hev hairpin for system traffic.
 *
 * Start order:
 * 1) clean previous rules/helpers
 * 2) stop core if rebuilding so old fd is not left in use
 * 3) open TUN fd in daemon process
 * 4) start core with tunFd
 * 5) install iptables/ip-rule only
 */
object XrayTunRootDataPlane : RootDataPlane {
    override val engine: RootEngine = RootEngine.XRAY_TUN

    private val repairing = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)
    private val nextRepairAllowedAtMs = AtomicLong(0L)
    private const val REPAIR_BACKOFF_BASE_MS = 2_000L
    private const val REPAIR_BACKOFF_MAX_MS = 60_000L

    @Volatile
    private var lastError: RootProxyManager.RootError? = null

    override fun start(context: Context): RootProxyManager.RootError? {
        lastError = null
        consecutiveFailures.set(0)
        nextRepairAllowedAtMs.set(0L)
        return try {
            // Drop any leftover hev/rules from older builds so only one capture path owns the device.
            RootProxyManager.stop(context)

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

            // One-shot soft connectivity smoke (local SOCKS path). Never fail-close cold start.
            runCatching { RootConnectivitySmoke.maybeProbeAfterStart(force = true) }
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
            lastError = classifyOpenError(e)
            lastError
        }
    }

    override fun stop(context: Context) {
        runCatching { RootProxyManager.stop(context) }
        LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun stop (rules down)")
    }

    /**
     * Graduated repair:
     * - healthy => noop
     * - TUN missing => TUN_FAILED (caller may full start())
     * - rules flaky with live TUN => installRulesOnly
     * Never starts a nested soft-restart from here.
     */
    override fun ensure(context: Context): RootProxyManager.RootError? {
        if (isHealthy(context)) {
            lastError = null
            consecutiveFailures.set(0)
            return null
        }

        val now = System.currentTimeMillis()
        if (now < nextRepairAllowedAtMs.get()) {
            lastError = RootProxyManager.RootError.REPAIR_BACKED_OFF
            return lastError
        }
        if (!repairing.compareAndSet(false, true)) {
            lastError = RootProxyManager.RootError.REPAIR_BACKED_OFF
            return lastError
        }

        try {
            val tunOk = RootTun.isOpen() || RootProxyManager.isTunUp()
            if (!tunOk) {
                LogUtil.e(AppConfig.TAG, "RootDataPlane: xray_tun ensure TUN missing")
                noteFailure()
                lastError = RootProxyManager.RootError.TUN_FAILED
                return lastError
            }

            val coreOk = try {
                CoreServiceManager.isRunning() || CoreServiceManager.hasLiveSession()
            } catch (_: Exception) {
                false
            }
            if (!coreOk) {
                // Soft-restart ownership lives in CoreServiceManager; do not nest it here.
                LogUtil.w(AppConfig.TAG, "RootDataPlane: ensure saw core down; keep TUN and wait soft-restart/session")
                lastError = RootProxyManager.RootError.REPAIR_BACKED_OFF
                return lastError
            }

            val err = RootProxyManager.installRulesOnly(context)
            if (err != null) {
                noteFailure()
                lastError = err
                if (RootTun.isOpen() && (CoreServiceManager.isRunning() || CoreServiceManager.hasLiveSession())) {
                    LogUtil.w(AppConfig.TAG, "RootDataPlane: rules repair failed ($err) but core/tun live; soft keep")
                    return RootProxyManager.RootError.REPAIR_BACKED_OFF
                }
                return err
            }

            if (isHealthy(context)) {
                consecutiveFailures.set(0)
                lastError = null
                LogUtil.i(AppConfig.TAG, "RootDataPlane: xray_tun ensure ok ${snapshot(context)}")
                return null
            }

            noteFailure()
            lastError = RootProxyManager.RootError.RULES_FAILED
            return lastError
        } finally {
            repairing.set(false)
        }
    }

    override fun isHealthy(context: Context, strict: Boolean): Boolean {
        val tunOk = RootTun.isOpen() || RootProxyManager.isTunUp()
        val rulesOk = RootProxyManager.isRulesInstalled()
        val coreOk = try {
            CoreServiceManager.isRunning() || CoreServiceManager.hasLiveSession()
        } catch (_: Exception) {
            false
        }
        return if (strict) tunOk && rulesOk && coreOk else tunOk && rulesOk
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
        return "engine=xray_tun capture=RootTun+MARK fd=$fd healthy=$healthy runtimeLive=$live " +
            "rules=$rules core=$core lastError=$lastError failures=${consecutiveFailures.get()} " +
            "note=health-excludes-hev-and-app-level-DoH"
    }

    fun lastErrorOrNull(): RootProxyManager.RootError? = lastError

    override fun repairBackoffRemainingMs(): Long {
        return (nextRepairAllowedAtMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun noteFailure() {
        val n = consecutiveFailures.incrementAndGet()
        val backoff = (REPAIR_BACKOFF_BASE_MS * (1L shl (n - 1).coerceAtMost(5)))
            .coerceAtMost(REPAIR_BACKOFF_MAX_MS)
        nextRepairAllowedAtMs.set(System.currentTimeMillis() + backoff)
        LogUtil.w(AppConfig.TAG, "RootDataPlane: repair backoff ${backoff}ms after failure#$n")
    }

    private fun classifyOpenError(e: Exception): RootProxyManager.RootError {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("nativeOpenTun", true) -> RootProxyManager.RootError.TUN_FAILED
            msg.contains("helper missing", true) -> RootProxyManager.RootError.TUN_FAILED
            msg.contains("su", true) -> RootProxyManager.RootError.SU_DENIED
            else -> RootProxyManager.RootError.UNKNOWN
        }
    }
}
