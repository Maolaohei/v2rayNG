package com.v2ray.ang.root

import android.content.Context
import android.os.Process
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootProxyManager.TABLE
import com.v2ray.ang.root.RootProxyManager.TUN
import com.v2ray.ang.root.RootProxyManager.teardown
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Installs and removes the iptables / ip-rule routing that pushes system-wide traffic
 *
 * A bundled `hev-socks5-tunnel` binary (run as root) creates a tun device and forwards it to
 * the in-process core's SOCKS inbound; a mangle MARK chain plus a dedicated routing table /
 * ip rule steer all traffic into the tun. Full TCP + UDP.
 *
 * All rules live in dedicated chains ([AppConfig.ROOT_IPTABLES_CHAIN] in the mangle
 * table, [AppConfig.ROOT_FWD_CHAIN] for LAN sharing) plus a dedicated routing table, so
 * [teardown] is a clean, bounded flush. Teardown runs before every setup (to clear stale
 * rules) and on every stop path 闂?leaving rules behind after the core dies would break
 * the device's connectivity.
 */
object RootProxyManager {

    private const val CHAIN = AppConfig.ROOT_IPTABLES_CHAIN
    private const val TUN = AppConfig.ROOT_TUN_NAME
    private const val TABLE = AppConfig.ROOT_ROUTE_TABLE
    private const val PRIORITY = AppConfig.ROOT_RULE_PRIORITY
    private const val APP_UID_RULE_PREF = 900
    private const val FWMARK = AppConfig.ROOT_FWMARK
    private const val MARK = AppConfig.ROOT_MARK_ROUTE
    private const val BYPASS_PRIORITY = AppConfig.ROOT_BYPASS_RULE_PRIORITY
    // Android app UIDs start at 10000. Magic_V2Ray only marks app range (+ a few system uids)
    // instead of every system uid 鈥?fewer OEM side-effects under all-apps capture.
    private const val APP_UID_RANGE = "10000-2147483647"
    // Xray FakeDNS default pool. MUST be proxied (never LAN-bypassed).
    private const val FAKE_IP_CIDR = "198.18.0.0/15"

    // Local / private / multicast destinations that must never be proxied.
    private val bypassCidrs = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4"
    )

    // IPv6 equivalents (loopback, link-local, ULA/private, multicast). Feeding the v4 list
    // above to ip6tables silently fails, so the v6 chain needs its own.
    private val bypassCidrsV6 = listOf(
        "::1/128", "fe80::/10", "fc00::/7", "ff00::/8"
    )


    enum class RootError {
        SU_DENIED,
        HEV_MISSING,
        TUN_FAILED,
        RULES_FAILED,
        SOCKS_NOT_READY,
        HEV_DEAD,
        REPAIR_BACKED_OFF,
        UNKNOWN,
    }

    /** Last setup error for UI mapping. */
    @Volatile
    var lastError: RootError? = null
        private set

    /** Prevents concurrent teardown+setup from watchdog / home resume / soft-restart. */
    private val repairing = AtomicBoolean(false)
    private val consecutiveRepairFailures = AtomicInteger(0)
    private val nextRepairAllowedAtMs = AtomicLong(0L)
    private val lastFullRebuildAtMs = AtomicLong(0L)
    private const val FULL_REBUILD_MIN_INTERVAL_MS = 120_000L
    private const val HEALTH_CACHE_MS = 800L
    @Volatile private var probeCacheAtMs: Long = 0L
    @Volatile private var probeCache: PipelineProbe? = null
    @Volatile private var socksCacheAtMs: Long = 0L
    @Volatile private var socksCache: Boolean? = null

    fun userMessage(context: Context, error: RootError = lastError ?: RootError.UNKNOWN): String {
        val res = when (error) {
            RootError.SU_DENIED -> com.v2ray.ang.R.string.toast_root_su_denied
            RootError.HEV_MISSING -> com.v2ray.ang.R.string.toast_root_hev_missing
            RootError.TUN_FAILED -> com.v2ray.ang.R.string.toast_root_tun_failed
            RootError.RULES_FAILED -> com.v2ray.ang.R.string.toast_root_rules_failed
            RootError.SOCKS_NOT_READY -> com.v2ray.ang.R.string.toast_root_socks_not_ready
            RootError.HEV_DEAD -> com.v2ray.ang.R.string.toast_root_hev_dead
            RootError.REPAIR_BACKED_OFF -> com.v2ray.ang.R.string.toast_root_repair_backed_off
            RootError.UNKNOWN -> com.v2ray.ang.R.string.toast_root_start_failed
        }
        return context.getString(res)
    }

    fun userMessageOrNull(context: Context, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val mapped = when {
            raw.contains("SU_DENIED", true) || (raw.contains("permission denied", true) && raw.contains("su", true)) -> RootError.SU_DENIED
            raw.contains("HEV_MISSING", true) || raw.contains("hev-socks5-tunnel binary missing", true) -> RootError.HEV_MISSING
            raw.contains("TUN_FAILED", true) || raw.contains("tun device did not come up", true) -> RootError.TUN_FAILED
            raw.contains("SOCKS_NOT_READY", true) -> RootError.SOCKS_NOT_READY
            raw.contains("HEV_DEAD", true) -> RootError.HEV_DEAD
            raw.contains("RULES_FAILED", true) || raw.contains("Root routing", true) -> RootError.RULES_FAILED
            else -> null
        }
        return mapped?.let { userMessage(context, it) }
    }

    private fun runDir(context: Context): File =
        File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR).apply { mkdirs() }

    private fun pidFile(context: Context): File = File(runDir(context), "tun2socks.pid")

    /**
     * True when hev helper process is alive.
     * MUST check via su: app UID cannot reliably see root-owned /proc/<pid> on modern Android,
     * which previously caused false HEV_DEAD and intermittent start failures.
     */
    fun isHevAlive(context: Context): Boolean {
        return probePipeline(context).hevAlive
    }

    /** True when root tun interface is present. */
    fun isTunUp(): Boolean {
        val r = RootShell.exec("ip link show $TUN >/dev/null 2>&1")
        return r.success
    }

    /** True when policy routing + mangle chain from last setup still exist. */
    fun isRulesInstalled(): Boolean = probePipeline(null).rulesOk

    /** True when in-process core SOCKS accepts connections on the configured port. */
    fun isLocalSocksReady(): Boolean {
        val port = SettingsManager.getSocksPort()
        if (port <= 0) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 250)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class PipelineProbe(
        val hevAlive: Boolean,
        val tunUp: Boolean,
        val rulesOk: Boolean,
        /** False when su/probe output is missing or unusable (do not treat as hard death). */
        val reliable: Boolean = true,
    ) {
        val localUp: Boolean get() = hevAlive && tunUp && rulesOk
        val hevTunUp: Boolean get() = hevAlive && tunUp
    }

    /**
     * One su round-trip for hev/tun/rules. Socks is checked in-process (no root needed).
     * Batching avoids 3-4 serial su invocations every watchdog tick.
     *
     * hev liveness uses pid + /proc/comm (not only kill -0) and falls back to "tun up with our
     * address" when Magisk hides root /proc briefly. Unreliable su output is flagged so callers
     * can soft-skip instead of full teardown storms.
     */
    private fun probePipeline(context: Context?): PipelineProbe {
        val pid = if (context != null) {
            try {
                pidFile(context).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val pidCheck = when {
            context == null -> "echo HEV_SKIP; "
            pid != null && pid > 1 -> {
                "if [ -r /proc/$pid/comm ] && kill -0 $pid >/dev/null 2>&1; then " +
                    "COMM=\$(tr -d '\\0' </proc/$pid/comm 2>/dev/null); " +
                    "case \"\$COMM\" in *hev*|*sockstun*|*tun2socks*) echo HEV_OK;; *) echo HEV_DEAD;; esac; " +
                    "else echo HEV_DEAD; fi; "
            }
            else -> "echo HEV_DEAD; "
        }
        val tunAddr = AppConfig.ROOT_TUN_ADDR_V4.substringBefore("/")
        val script = buildString {
            append("echo PROBE_BEGIN; ")
            append(pidCheck)
            append("if ip link show $TUN >/dev/null 2>&1; then echo TUN_OK; else echo TUN_DEAD; fi; ")
            append("if ip -o -4 addr show dev $TUN 2>/dev/null | grep -F '$tunAddr' >/dev/null 2>&1; then echo TUN_ADDR_OK; else echo TUN_ADDR_DEAD; fi; ")
            append("if ip rule show 2>/dev/null | grep -F 'lookup $TABLE' >/dev/null 2>&1; then echo RULE_OK; else echo RULE_DEAD; fi; ")
            append("IPT=iptables; command -v iptables-legacy >/dev/null 2>&1 && IPT=iptables-legacy; ")
            append("if \$IPT -t mangle -nL $CHAIN >/dev/null 2>&1; then echo CHAIN_OK; else echo CHAIN_DEAD; fi; ")
            append("echo PROBE_END")
        }
        val result = try {
            RootShell.exec(script, timeoutSeconds = 8)
        } catch (_: Exception) {
            null
        }
        val out = result?.output.orEmpty()
        val reliable = result != null && out.contains("PROBE_BEGIN") && out.contains("PROBE_END")
        if (!reliable) {
            LogUtil.w(AppConfig.TAG, "RootProxyManager: probe unreliable (su/output incomplete)")
            // Soft defaults: do not claim death when we simply could not observe.
            return PipelineProbe(hevAlive = true, tunUp = true, rulesOk = true, reliable = false)
        }

        var hevAlive = when {
            context == null -> true
            else -> out.contains("HEV_OK")
        }
        val tunUp = out.contains("TUN_OK")
        val tunAddrOk = out.contains("TUN_ADDR_OK")
        // Fallback: pid opaque/stale but our tun is up with expected address -> prefer light repair.
        if (context != null && !hevAlive && tunUp && tunAddrOk) {
            LogUtil.i(AppConfig.TAG, "RootProxyManager: hev pid opaque but tun+addr up; treat as alive")
            hevAlive = true
        }
        val rulesOk = out.contains("RULE_OK") && out.contains("CHAIN_OK")
        return PipelineProbe(hevAlive = hevAlive, tunUp = tunUp, rulesOk = rulesOk, reliable = true)
    }

    /**
     * Local pipeline health: hev + tun + iptables/ip-rule + local SOCKS.
     *
     * @param strict true for post-setup acceptance (must observe rules via su).
     *               false for runtime (prefer not thrashing on flaky su: SOCKS up => keep session).
     */
    fun isHealthy(context: Context, strict: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val probeCached = probeCache
        val probe = if (probeCached != null && probeCached.reliable && now - probeCacheAtMs in 0 until HEALTH_CACHE_MS) {
            probeCached
        } else {
            probePipeline(context).also {
                // Never cache unreliable su probes as durable health.
                if (it.reliable) {
                    probeCache = it
                    probeCacheAtMs = now
                } else {
                    probeCache = null
                    probeCacheAtMs = 0L
                }
            }
        }

        val socksCached = socksCache
        val socks = if (socksCached != null && now - socksCacheAtMs in 0 until HEALTH_CACHE_MS) {
            socksCached
        } else {
            isLocalSocksReady().also {
                socksCache = it
                socksCacheAtMs = now
            }
        }

        // Runtime: flaky su must not look like a dead dataplane (that triggers rebuild storms).
        if (!probe.reliable) {
            if (!strict && socks) {
                LogUtil.i(AppConfig.TAG, "RootProxyManager: unreliable probe but SOCKS up; runtime-healthy")
                return true
            }
            return false
        }
        return probe.localUp && socks
    }

    /**
     * Cheap runtime liveness used by UI / network paths that must not run su every time.
     * True when local SOCKS accepts — the control plane Xray side is up.
     */
    fun isRuntimeLive(): Boolean = isLocalSocksReady()
    /** Remaining repair backoff in ms (0 = allowed now). */
    fun repairBackoffRemainingMs(): Long {
        val left = nextRepairAllowedAtMs.get() - System.currentTimeMillis()
        return if (left > 0L) left else 0L
    }

    private fun noteRepairSuccess() {
        consecutiveRepairFailures.set(0)
        nextRepairAllowedAtMs.set(0L)
    }

    private fun noteRepairFailure() {
        val n = consecutiveRepairFailures.incrementAndGet().coerceAtMost(6)
        // 2s, 4s, 8s, 16s, 32s, 60s cap
        val delayMs = (2000L * (1L shl (n - 1))).coerceAtMost(60_000L)
        nextRepairAllowedAtMs.set(System.currentTimeMillis() + delayMs)
        LogUtil.w(AppConfig.TAG, "RootProxyManager: repair backoff ${delayMs}ms after $n failures")
        invalidateHealthCache()
    }


    private fun invalidateHealthCache() {
        probeCacheAtMs = 0L
        probeCache = null
        socksCacheAtMs = 0L
        socksCache = null
    }

    /**
     * Soft-restart / watchdog helper with graduated repair:
     * 1) fully healthy -> no-op
     * 2) hev/tun/rules up, SOCKS temporarily down -> wait only
     * 3) hev/tun up, rules missing -> reinstall rules only (keep hev)
     * 4) hev dead, tun/device still usable -> restart hev only + light rules
     * 5) otherwise full teardown+setup
     * Re-probes between stages so decisions are not based on a stale snapshot.
     * Concurrent callers wait for the in-flight repair. Failures apply exponential backoff.
     */
    fun ensureRunning(context: Context): RootError? {
        lastError = null
        if (isHealthy(context)) {
            noteRepairSuccess()
            LogUtil.i(AppConfig.TAG, "RootProxyManager: pipeline healthy, skip rebind")
            return null
        }

        // If su probe is flaky but local SOCKS is up, do not tear the datapath down.
        val softProbe = probePipeline(context)
        if (!softProbe.reliable && isLocalSocksReady()) {
            LogUtil.w(AppConfig.TAG, "RootProxyManager: unreliable probe + SOCKS up, soft-skip repair")
            lastError = RootError.REPAIR_BACKED_OFF
            return RootError.REPAIR_BACKED_OFF
        }

        val backoffLeft = repairBackoffRemainingMs()
        if (backoffLeft > 0L) {
            // Distinct from success (null). Soft-restart rebind treats this as soft-skip.
            // Watchdog/home re-check isHealthy and do not count as permanent success.
            LogUtil.i(AppConfig.TAG, "RootProxyManager: repair backoff active (${backoffLeft}ms), skip")
            lastError = RootError.REPAIR_BACKED_OFF
            return RootError.REPAIR_BACKED_OFF
        }

        var probe = probePipeline(context)
        invalidateHealthCache()
        if (probe.localUp && !isLocalSocksReady()) {
            LogUtil.i(AppConfig.TAG, "RootProxyManager: local path up, waiting for SOCKS")
            if (waitUntilSocksReady(timeoutMs = 4000L)) {
                noteRepairSuccess()
                return null
            }
            LogUtil.w(AppConfig.TAG, "RootProxyManager: SOCKS still down after wait")
        }

        if (!repairing.compareAndSet(false, true)) {
            LogUtil.i(AppConfig.TAG, "RootProxyManager: repair already in progress, waiting")
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                if (!repairing.get()) break
                try {
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return if (isHealthy(context)) {
                noteRepairSuccess()
                null
            } else {
                lastError ?: RootError.UNKNOWN
            }
        }

        try {
            invalidateHealthCache()
            probe = probePipeline(context)
            if (!probe.reliable) {
                LogUtil.w(AppConfig.TAG, "RootProxyManager: probe unreliable under repair lock, soft-skip")
                lastError = RootError.REPAIR_BACKED_OFF
                return RootError.REPAIR_BACKED_OFF
            }

            // 3) rules-only when tunnel helper is still up.
            if (probe.hevTunUp && !probe.rulesOk) {
                LogUtil.w(AppConfig.TAG, "RootProxyManager: rules missing, light reinstall")
                val lightErr = reinstallRulesOnly(context)
                invalidateHealthCache()
                if (lightErr == null && isHealthy(context)) {
                    noteRepairSuccess()
                    return null
                }
                LogUtil.w(AppConfig.TAG, "RootProxyManager: light rules reinstall failed ($lightErr), escalate")
                probe = probePipeline(context)
            }

            // 4) hev dead: try restart helper without full teardown first.
            if (!probe.hevAlive) {
                LogUtil.w(AppConfig.TAG, "RootProxyManager: hev dead, trying hev-only restart")
                val hevErr = restartHevOnly(context)
                invalidateHealthCache()
                if (hevErr == null) {
                    val after = probePipeline(context)
                    if (!after.rulesOk) {
                        val lightErr = reinstallRulesOnly(context)
                        invalidateHealthCache()
                        if (lightErr == null && isHealthy(context)) {
                            noteRepairSuccess()
                            return null
                        }
                    } else if (isHealthy(context)) {
                        noteRepairSuccess()
                        return null
                    }
                }
                LogUtil.w(AppConfig.TAG, "RootProxyManager: hev-only restart insufficient, full rebuild")
                probe = probePipeline(context)
            }

            // Fresh check before the expensive full rebuild.
            invalidateHealthCache()
            if (isHealthy(context)) {
                noteRepairSuccess()
                return null
            }

            // Never full-teardown while SOCKS + hev/tun still look usable — that is the main
            // cause of random multi-second blackholes. Prefer light rules / soft-skip.
            probe = probePipeline(context)
            val socksUp = isLocalSocksReady()
            if (socksUp && probe.reliable && probe.hevTunUp) {
                if (!probe.rulesOk) {
                    LogUtil.w(AppConfig.TAG, "RootProxyManager: rules flaky with live hev/SOCKS, light reinstall only")
                    val lightErr = reinstallRulesOnly(context)
                    invalidateHealthCache()
                    if (lightErr == null && isHealthy(context)) {
                        noteRepairSuccess()
                        return null
                    }
                }
                LogUtil.w(AppConfig.TAG, "RootProxyManager: skip full rebuild while SOCKS+hev/tun live")
                lastError = RootError.REPAIR_BACKED_OFF
                noteRepairFailure()
                return RootError.REPAIR_BACKED_OFF
            }
            if (socksUp && !probe.reliable) {
                LogUtil.w(AppConfig.TAG, "RootProxyManager: skip full rebuild (unreliable probe, SOCKS up)")
                lastError = RootError.REPAIR_BACKED_OFF
                return RootError.REPAIR_BACKED_OFF
            }

            val sinceFull = System.currentTimeMillis() - lastFullRebuildAtMs.get()
            if (lastFullRebuildAtMs.get() > 0L && sinceFull < FULL_REBUILD_MIN_INTERVAL_MS) {
                LogUtil.w(
                    AppConfig.TAG,
                    "RootProxyManager: full rebuild rate-limited (${FULL_REBUILD_MIN_INTERVAL_MS - sinceFull}ms left)"
                )
                lastError = RootError.REPAIR_BACKED_OFF
                return RootError.REPAIR_BACKED_OFF
            }

            LogUtil.w(AppConfig.TAG, "RootProxyManager: pipeline unhealthy, full rebuild")
            lastFullRebuildAtMs.set(System.currentTimeMillis())
            val err = startDetailed(context)
            invalidateHealthCache()
            if (err == null) {
                noteRepairSuccess()
            } else {
                noteRepairFailure()
            }
            return err
        } finally {
            repairing.set(false)
        }
    }


    fun startDetailed(context: Context): RootError? {
        lastError = null
        invalidateHealthCache()
        teardown(context)
        val script = buildTun2socksSetup(context)
        if (script == null) {
            lastError = RootError.HEV_MISSING
            return lastError
        }
        val result = RootShell.runScript(context, "setup_rules.sh", script)
        if (!result.success) {
            lastError = classifySetupFailure(result.output)
            LogUtil.e(AppConfig.TAG, "RootProxyManager: setup failed ($lastError):\n${result.output}")
            teardown(context)
            return lastError
        }
        if (!waitUntilHealthy(context, timeoutMs = 4000L)) {
            val p = probePipeline(context)
            lastError = when {
                !isLocalSocksReady() -> RootError.SOCKS_NOT_READY
                !p.tunUp && !p.hevAlive -> RootError.HEV_DEAD
                !p.tunUp -> RootError.TUN_FAILED
                !p.rulesOk -> RootError.RULES_FAILED
                else -> RootError.HEV_DEAD
            }
            LogUtil.e(AppConfig.TAG, "RootProxyManager: post-setup health failed ($lastError)")
            teardown(context)
            return lastError
        }
        return null
    }

    /**
     * Reinstall iptables/ip-rule only, keep running hev/tun to minimize datapath downtime.
     */
    private fun reinstallRulesOnly(context: Context): RootError? {
        lastError = null
        val script = buildRulesOnlySetup(context) ?: run {
            lastError = RootError.HEV_MISSING
            return lastError
        }
        val result = RootShell.runScript(context, "setup_rules_light.sh", script)
        if (!result.success) {
            lastError = classifySetupFailure(result.output)
            LogUtil.e(AppConfig.TAG, "RootProxyManager: light rules setup failed ($lastError):\n${result.output}")
            return lastError
        }
        // Short wait: hev already running; only rules/route must appear.
        val deadline = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy(context)) return null
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return if (isHealthy(context)) null else {
            lastError = RootError.RULES_FAILED
            lastError
        }
    }

    /**
     * Restart only the hev helper process (keep trying to reuse existing tun name/config).
     * Used when pid is dead but we want to avoid a full iptables teardown storm first.
     */
    private fun restartHevOnly(context: Context): RootError? {
        lastError = null
        val bin = File(context.applicationInfo.nativeLibraryDir, AppConfig.ROOT_TUN2SOCKS_BIN)
        if (!bin.exists()) {
            lastError = RootError.HEV_MISSING
            return lastError
        }
        val port = SettingsManager.getSocksPort()
        val ipv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)
        val runDir = File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR).apply { mkdirs() }
        val pidPath = File(runDir, "tun2socks.pid").absolutePath
        val logPath = File(runDir, "tun2socks.log").absolutePath
        val cfgPath = File(runDir, "tun2socks.yml").absolutePath
        val script = buildString {
            appendLine("set -e")
            appendLine("BIN='${bin.absolutePath}'")
            appendLine("if [ ! -e /dev/net/tun ]; then mkdir -p /dev/net; mknod /dev/net/tun c 10 200; chmod 666 /dev/net/tun; fi")
            // stop previous helper if pid file still points somewhere
            appendLine("if [ -f '$pidPath' ]; then kill \$(cat '$pidPath') 2>/dev/null || true; rm -f '$pidPath'; fi")
            appendLine("ip link set dev $TUN down 2>/dev/null || true")
            appendLine("cat > '$cfgPath' <<'HEVCFG'")
            append(buildHevConfig(port, ipv6))
            appendLine("HEVCFG")
            appendLine("nohup \"\$BIN\" '$cfgPath' >'$logPath' 2>&1 &")
            appendLine("T2S_PID=\$!")
            appendLine("echo \$T2S_PID > '$pidPath'")
            appendLine("echo ${AppConfig.ROOT_OOM_SCORE} > /proc/\$T2S_PID/oom_score_adj 2>/dev/null || true")
            appendLine("i=0; while [ \$i -lt 20 ]; do ip link show $TUN >/dev/null 2>&1 && break; sleep 0.3; i=\$((i+1)); done")
            appendLine("ip link show $TUN >/dev/null 2>&1 || { echo 'tun device did not come up'; cat '$logPath' 2>/dev/null; exit 1; }")
            appendLine("ip addr flush dev $TUN 2>/dev/null || true")
            appendLine("ip addr add ${AppConfig.ROOT_TUN_ADDR_V4} dev $TUN 2>/dev/null || true")
            appendLine("ip link set dev $TUN up")
            appendLine("ip route replace default dev $TUN table $TABLE")
            appendLine("ip route replace $FAKE_IP_CIDR dev $TUN table $TABLE 2>/dev/null || true")
            appendLine("ip route replace $FAKE_IP_CIDR dev $TUN 2>/dev/null || true")
        }
        val result = RootShell.runScript(context, "restart_hev_only.sh", script)
        if (!result.success) {
            lastError = classifySetupFailure(result.output)
            LogUtil.e(AppConfig.TAG, "RootProxyManager: hev-only restart failed ($lastError):\n${result.output}")
            return lastError
        }
        val deadline = System.currentTimeMillis() + 3500L
        while (System.currentTimeMillis() < deadline) {
            val p = probePipeline(context)
            if (p.hevAlive && p.tunUp) return null
            try { Thread.sleep(120L) } catch (_: InterruptedException) { break }
        }
        val p = probePipeline(context)
        lastError = when {
            !p.hevAlive -> RootError.HEV_DEAD
            !p.tunUp -> RootError.TUN_FAILED
            else -> RootError.UNKNOWN
        }
        return lastError
    }

    private fun waitUntilHealthy(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy(context, strict = true)) return true
            try {
                Thread.sleep(150L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return isHealthy(context, strict = true)
    }

    private fun waitUntilSocksReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalSocksReady()) return true
            try {
                Thread.sleep(120L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return isLocalSocksReady()
    }

    private fun classifySetupFailure(output: String): RootError {
        val o = output.lowercase()
        return when {
            "binary missing" in o || "hev-socks5" in o && "no such file" in o -> RootError.HEV_MISSING
            "tun device did not come up" in o || "cannot find device" in o -> RootError.TUN_FAILED
            "permission denied" in o || "not allowed to" in o || "su:" in o -> RootError.SU_DENIED
            "not found" in o && "su" in o -> RootError.SU_DENIED
            else -> RootError.RULES_FAILED
        }
    }




    fun start(context: Context): Boolean {
        return startDetailed(context) == null
    }

    /**
     * Set up LAN/tethering sharing while the device itself uses another mode (e.g. VPN
     * mode). Runs a dedicated client tun2socks into the in-process core's SOCKS inbound
     * and forwards tethered clients into it, WITHOUT capturing the device's own traffic
     * (that keeps flowing through the VpnService). Requires root.
     */
    fun startClientSharing(context: Context): Boolean {
        teardown(context)
        val script = buildTun2socksSetup(context, captureDeviceTraffic = false, forceLanShare = true)
            ?: return false
        val result = RootShell.runScript(context, "setup_rules.sh", script)
        if (!result.success) {
            LogUtil.e(AppConfig.TAG, "RootProxyManager: client sharing setup failed:\n${result.output}")
            teardown(context)
            return false
        }
        LogUtil.i(AppConfig.TAG, "RootProxyManager: LAN client sharing installed")
        return true
    }

    /** Remove all rules and stop helper processes. Safe to call repeatedly. */
    fun stop(context: Context) {
        teardown(context)
        RootShell.closeSession()
        LogUtil.i(AppConfig.TAG, "RootProxyManager: rules removed")
    }

    /**
     * Magic_V2Ray-style network rebind: keep hev/TUN, only refresh
     * `fwmark FWMARK -> main` so bypass sockets follow the current default iface.
     * Cheap enough to run on every ConnectivityManager onAvailable.
     */
    fun rebindPhysicalBypass(context: Context): Boolean {
        val script = buildString {
            appendLine("set +e")
            append(buildBypassMarkRules())
            appendLine("echo BYPASS_OK")
        }
        val result = RootShell.runScript(context, "rebind_bypass.sh", script)
        val ok = result.success || result.output.contains("BYPASS_OK")
        if (!ok) {
            LogUtil.w(AppConfig.TAG, "RootProxyManager: physical bypass rebind failed: ${result.output.trim()}")
        } else {
            LogUtil.i(AppConfig.TAG, "RootProxyManager: physical bypass rebind ok")
        }
        return ok
    }

    /** Shell snippet: hev SO_MARK (FWMARK) always prefers main table over TUN policy. */
    private fun buildBypassMarkRules(): String = buildString {
        // IPv4 + IPv6. Priority lower-number = higher precedence than TUN MARK rule.
        appendLine("ip rule del fwmark $FWMARK table main priority $BYPASS_PRIORITY 2>/dev/null || true")
        appendLine("ip rule del pref $BYPASS_PRIORITY 2>/dev/null || true")
        appendLine("ip rule add fwmark $FWMARK lookup main priority $BYPASS_PRIORITY 2>/dev/null || true")
        appendLine("ip -6 rule del fwmark $FWMARK table main priority $BYPASS_PRIORITY 2>/dev/null || true")
        appendLine("ip -6 rule del pref $BYPASS_PRIORITY 2>/dev/null || true")
        appendLine("ip -6 rule add fwmark $FWMARK lookup main priority $BYPASS_PRIORITY 2>/dev/null || true")
        // Keep rp_filter relaxed on TUN (Magic locks this).
        appendLine("echo 0 > /proc/sys/net/ipv4/conf/$TUN/rp_filter 2>/dev/null || true")
        appendLine("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true")
    }

    private fun teardown(context: Context) {
        invalidateHealthCache()
        RootShell.runScript(context, "teardown_rules.sh", buildTeardown(context))
    }

    // --------------------------------------------------------------- TUN2SOCKS

    /**
     * Light path: flush/reinstall marking + policy routing without restarting hev.
     * Assumes hev already created [TUN] and is still alive.
     */
    private fun buildRulesOnlySetup(context: Context): String? {
        val bin = File(context.applicationInfo.nativeLibraryDir, AppConfig.ROOT_TUN2SOCKS_BIN)
        if (!bin.exists()) {
            LogUtil.e(AppConfig.TAG, "RootProxyManager: hev-socks5-tunnel binary missing at ${bin.absolutePath}")
            return null
        }
        val appUid = context.applicationInfo.uid
        val ipv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)
        val perAppEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY)
        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        val selectedUids = if (perAppEnabled) {
            val pkgs = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toList().orEmpty()
            if (pkgs.isNotEmpty()) {
                PackageUidResolver.packageNamesToUids(context, pkgs).distinct()
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        return buildString {
            appendLine("set -e")
            appendLine("IPT=iptables; command -v iptables-legacy >/dev/null 2>&1 && IPT=iptables-legacy")
            appendLine("IP6T=ip6tables; command -v ip6tables-legacy >/dev/null 2>&1 && IP6T=ip6tables-legacy")
            // Keep hev; only rewrite route policy + mangle.
            appendLine("ip link show $TUN >/dev/null 2>&1 || { echo 'tun device did not come up'; exit 1; }")
            appendLine("echo 0 > /proc/sys/net/ipv4/conf/$TUN/rp_filter 2>/dev/null || true")
            appendLine("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true")
            appendLine("ip addr flush dev $TUN 2>/dev/null || true")
            appendLine("ip addr add ${AppConfig.ROOT_TUN_ADDR_V4} dev $TUN 2>/dev/null || true")
            appendLine("ip link set dev $TUN up")
            appendLine("ip route replace default dev $TUN table $TABLE")
            appendLine("ip route replace $FAKE_IP_CIDR dev $TUN table $TABLE 2>/dev/null || true")
            appendLine("ip route replace $FAKE_IP_CIDR dev $TUN 2>/dev/null || true")
            appendLine("ip rule del fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
            appendLine("ip rule add fwmark $MARK table $TABLE priority $PRIORITY")
            // Magic dual-mark: hev SO_MARK(FWMARK) must prefer main over TUN table.
            append(buildBypassMarkRules())
            // Anti-loop harden: core/app UID always prefers main table even if marked.
            appendLine("ip rule del pref $APP_UID_RULE_PREF 2>/dev/null || true")
            appendLine("ip rule add uidrange $appUid-$appUid lookup main priority $APP_UID_RULE_PREF 2>/dev/null || true")
            append(buildMangleMarking("\$IPT", appUid, perAppEnabled, bypassApps, selectedUids))
            appendLine("set +e")
            if (ipv6) {
                appendLine("ip -6 addr add ${AppConfig.ROOT_TUN_ADDR_V6} dev $TUN 2>/dev/null || true")
                appendLine("ip -6 route replace default dev $TUN table $TABLE 2>/dev/null || true")
                appendLine("ip -6 rule del fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
                appendLine("ip -6 rule add fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
                append(buildMangleMarking("\$IP6T", appUid, perAppEnabled, bypassApps, selectedUids))
            } else {
                append(buildV6Blackhole(appUid, perAppEnabled, bypassApps, selectedUids))
            }
        }
    }

    /**
     * @param captureDeviceTraffic when true (Root mode) the device's own OUTPUT traffic is
     *   marked into the tun. When false (VPN-mode LAN sharing) the device keeps using the
     *   VpnService and only forwarded clients are routed into this tun.
     * @param forceLanShare force the LAN/tethering forward rules on regardless of the pref
     *   (used by VPN-mode sharing, where the whole point is forwarding clients).
     */
    private fun buildTun2socksSetup(
        context: Context,
        captureDeviceTraffic: Boolean = true,
        forceLanShare: Boolean = false,
    ): String? {
        val bin = File(context.applicationInfo.nativeLibraryDir, AppConfig.ROOT_TUN2SOCKS_BIN)
        if (!bin.exists()) {
            LogUtil.e(AppConfig.TAG, "RootProxyManager: hev-socks5-tunnel binary missing at ${bin.absolutePath}")
            return null
        }
        val appUid = context.applicationInfo.uid
        val port = SettingsManager.getSocksPort()
        val runDir = File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR).apply { mkdirs() }
        val pidFile = File(runDir, "tun2socks.pid").absolutePath
        val logFile = File(runDir, "tun2socks.log").absolutePath
        val cfgFile = File(runDir, "tun2socks.yml").absolutePath
        val oomGuardPid = File(runDir, "oomguard.pid").absolutePath
        val ipv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)
        val lanShare = forceLanShare || MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)
        val corePid = Process.myPid()

        // Per-app proxy/bypass (mirrors what VpnService does via allowed/disallowed apps).
        val perAppEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY)
        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        val selectedUids = if (perAppEnabled) {
            val pkgs = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toList().orEmpty()
            if (pkgs.isNotEmpty()) {
                // Expand to all UIDs for the package (main + sharedUserId siblings when resolvable).
                PackageUidResolver.packageNamesToUids(context, pkgs).distinct()
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        val perAppMode = when {
            !perAppEnabled -> "all-apps"
            bypassApps -> "bypass-selected"
            else -> "proxy-selected"
        }
        LogUtil.i(
            AppConfig.TAG,
            "RootProxyManager: per-app mode=$perAppMode selectedUids=${selectedUids.size} captureDevice=$captureDeviceTraffic"
        )
        if (perAppEnabled && !bypassApps && selectedUids.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                "RootProxyManager: per-app allow-list is empty 鈥?no app traffic will be marked into TUN"
            )
        }

        return buildString {
            appendLine("set -e")
            appendLine("BIN='${bin.absolutePath}'")
            // Prefer iptables-legacy on mixed nft/legacy OEM stacks (Magisk/KernelSU).
            appendLine("IPT=iptables; command -v iptables-legacy >/dev/null 2>&1 && IPT=iptables-legacy")
            appendLine("IP6T=ip6tables; command -v ip6tables-legacy >/dev/null 2>&1 && IP6T=ip6tables-legacy")
            // Protect the core (this app process) from the Android low-memory killer.
            // system_server keeps recomputing oom_score_adj for app processes, so a single
            // write would be reverted 闂?re-pin it from a small root loop instead.
            appendLine("nohup sh -c 'while true; do echo ${AppConfig.ROOT_OOM_SCORE} > /proc/$corePid/oom_score_adj 2>/dev/null; sleep 5; done' >/dev/null 2>&1 &")
            appendLine("echo \$! > '$oomGuardPid'")
            // tun device node
            appendLine("if [ ! -e /dev/net/tun ]; then mkdir -p /dev/net; mknod /dev/net/tun c 10 200; chmod 666 /dev/net/tun; fi")
            // hev-socks5-tunnel config: it creates the tun ($TUN) itself and forwards it to the
            // in-process core's SOCKS inbound on loopback. MTU comes from the existing VPN MTU
            // setting. hev sockets are SO_MARK'd with FWMARK (see buildHevConfig) so mangle can
            // RETURN them; the core's real outbound still runs as the app uid (uid-owner RETURN).
            appendLine("cat > '$cfgFile' <<'HEVCFG'")
            append(buildHevConfig(port, ipv6))
            appendLine("HEVCFG")
            appendLine("nohup \"\$BIN\" '$cfgFile' >'$logFile' 2>&1 &")
            appendLine("T2S_PID=\$!")
            appendLine("echo \$T2S_PID > '$pidFile'")
            appendLine("echo ${AppConfig.ROOT_OOM_SCORE} > /proc/\$T2S_PID/oom_score_adj 2>/dev/null || true")
            // wait for the interface hev creates to appear
            appendLine("i=0; while [ \$i -lt 20 ]; do ip link show $TUN >/dev/null 2>&1 && break; sleep 0.3; i=\$((i+1)); done")
            appendLine("ip link show $TUN >/dev/null 2>&1 || { echo 'tun device did not come up'; cat '$logFile' 2>/dev/null; exit 1; }")
            // relax reverse-path filtering for the tun
            appendLine("echo 0 > /proc/sys/net/ipv4/conf/$TUN/rp_filter 2>/dev/null || true")
            appendLine("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true")
            // address + default route in a dedicated table
            appendLine("ip addr flush dev $TUN 2>/dev/null || true")
            appendLine("ip addr add ${AppConfig.ROOT_TUN_ADDR_V4} dev $TUN 2>/dev/null || true")
            appendLine("ip link set dev $TUN up")
            // Policy table: default via TUN + FakeDNS pool via TUN (even if main has no connected route).
            appendLine("ip route replace default dev $TUN table $TABLE")
            appendLine("ip route replace $FAKE_IP_CIDR dev $TUN table $TABLE 2>/dev/null || true")
            appendLine("ip route replace $FAKE_IP_CIDR dev $TUN 2>/dev/null || true")
            appendLine("ip rule del fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
            appendLine("ip rule add fwmark $MARK table $TABLE priority $PRIORITY")
            // Magic dual-mark: hev SO_MARK(FWMARK) must prefer main over TUN table.
            append(buildBypassMarkRules())
            // Anti-loop harden: core/app UID always prefers main table even if marked.
            appendLine("ip rule del pref $APP_UID_RULE_PREF 2>/dev/null || true")
            appendLine("ip rule add uidrange $appUid-$appUid lookup main priority $APP_UID_RULE_PREF 2>/dev/null || true")
            // mark the device's own packets into the tun (Root mode only)
            if (captureDeviceTraffic) {
                append(buildMangleMarking("\$IPT", appUid, perAppEnabled, bypassApps, selectedUids))
            }
            // optionally route hotspot / USB-tethered clients through the tun too
            if (lanShare) {
                append(buildLanShareSetup(captureDeviceTraffic, ipv6))
            }
            if (captureDeviceTraffic) {
                // IPv6 is best-effort: never fail the (working) IPv4 setup over it.
                appendLine("set +e")
                if (ipv6) {
                    // route the device's v6 into the tun, same as v4
                    appendLine("ip -6 addr add ${AppConfig.ROOT_TUN_ADDR_V6} dev $TUN 2>/dev/null || true")
                    appendLine("ip -6 route replace default dev $TUN table $TABLE 2>/dev/null || true")
                    appendLine("ip -6 rule add fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
                    append(buildMangleMarking("\$IP6T", appUid, perAppEnabled, bypassApps, selectedUids))
                } else {
                    // v6 disabled: blackhole native v6 egress for the captured apps so they
                    // fall back to v4-through-proxy, matching what a v4-only VpnService does.
                    append(buildV6Blackhole(appUid, perAppEnabled, bypassApps, selectedUids))
                }
            }
        }
    }

    /**
     * hev-socks5-tunnel YAML config. hev creates the tun device named [TUN] itself, assigns it
     * the tun addresses, and forwards everything it receives to the core's SOCKS inbound on
     * loopback (TCP + UDP). MTU is taken from the existing VPN MTU setting. v6 is only given a
     * tun address when IPv6 is enabled; whether v6 actually flows in is decided separately by
     * the v6 route into [TABLE].
     */
    private fun buildHevConfig(socksPort: Int, ipv6: Boolean): String {
        val v4 = AppConfig.ROOT_TUN_ADDR_V4.substringBefore("/")
        val v6 = AppConfig.ROOT_TUN_ADDR_V6.substringBefore("/")
        // Align timeouts/log/auth with VPN TProxyService for stability. Keep multi-queue off.
        // tcp-fastopen is disabled: mixed OEM kernels make it a net loss under ROOT.
        val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT)
            ?: AppConfig.HEVTUN_RW_TIMEOUT
        val parts = timeoutSetting.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
        val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60
        val logLevel = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) ?: "warn"
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val escapedSocksUsername = socksUsername?.replace("'", "''")
        val escapedSocksPassword = socksPassword?.replace("'", "''")
        return buildString {
            appendLine("tunnel:")
            appendLine("  name: '$TUN'")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  multi-queue: false")
            appendLine("  ipv4: '$v4'")
            if (ipv6) appendLine("  ipv6: '$v6'")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: '${AppConfig.LOOPBACK}'")
            appendLine("  udp: 'udp'")
            // Industry-standard anti-loop for root tun2socks: mark hev's own sockets so the
            // mangle chain can RETURN them (see FWMARK RETURN rule). Without this, uid0 hev
            // traffic can be re-captured when loopback/owner matches flap on OEM kernels.
            appendLine("  mark: $FWMARK")
            if (escapedSocksUsername != null && escapedSocksPassword != null) {
                appendLine("  username: '${escapedSocksUsername}'")
                appendLine("  password: '${escapedSocksPassword}'")
            }
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: $logLevel")
        }
    }


    /**
     * mangle OUTPUT marking chain (ipv4/ipv6). Mirrors VpnService's capture behavior:
     * - all-apps (no per-app): mark EVERY remaining uid (incl uid 0 + all system uids), so
     *   nothing is missed;
     * - bypass mode: the selected apps go fully direct, everything else is captured;
     * - proxy mode: only the selected apps are captured.
     */
    private fun buildMangleMarking(
        cmd: String,
        appUid: Int,
        perAppEnabled: Boolean,
        bypassApps: Boolean,
        selectedUids: List<String>,
    ): String {
        val allowMode = perAppEnabled && !bypassApps
        val bypassSelected = perAppEnabled && bypassApps && selectedUids.isNotEmpty()
        return buildString {
            appendLine("$cmd -t mangle -N $CHAIN 2>/dev/null || true")
            appendLine("$cmd -t mangle -F $CHAIN")
            // the app's own core traffic (the real outbound) must not loop back into the tun.
            // The $FWMARK RETURN is kept defensively (hev itself only talks to loopback, which
            // the 127.0.0.0/8 bypass below already RETURNs).
            appendLine("$cmd -t mangle -A $CHAIN -m mark --mark $FWMARK -j RETURN")
            appendLine("$cmd -t mangle -A $CHAIN -m owner --uid-owner $appUid -j RETURN")
            // Anti-loop: never re-capture packets already leaving via our tun.
            appendLine("$cmd -t mangle -A $CHAIN -o $TUN -j RETURN")
            // Anti-loop: loopback control plane (SOCKS / local API) must stay direct.
            appendLine("$cmd -t mangle -A $CHAIN -o lo -j RETURN")
            // Anti-loop: kernel-generated local-to-local packets stay direct.
            appendLine("$cmd -t mangle -A $CHAIN -m addrtype --src-type LOCAL --dst-type LOCAL -j RETURN 2>/dev/null || true")
            // bypass mode: selected apps go fully direct (incl their DNS)
            if (bypassSelected) {
                selectedUids.forEach { appendLine("$cmd -t mangle -A $CHAIN -m owner --uid-owner $it -j RETURN") }
            }
            // Route DNS through the core for ALL modes, with no uid filter. On Android the
            // DNS query is sent by netd (a shared system uid) on behalf of the app, not under
            // the app's own uid, so it can't be attributed to a selected uid via owner-match.
            // This MUST also run before the LAN-bypass RETURNs below, otherwise a query to a
            // LAN/router resolver (192.168.x / 10.x) would be returned direct and resolved by
            // the local ISP resolver (DNS leak + CDN mis-resolution, e.g. Instagram media).
            // The MARK survives a later RETURN, so the marked query still routes into the tun.
            appendLine("$cmd -t mangle -A $CHAIN -p udp --dport 53 -j MARK --set-xmark $MARK/0xffff")
            appendLine("$cmd -t mangle -A $CHAIN -p tcp --dport 53 -j MARK --set-xmark $MARK/0xffff")
            // FakeDNS pool must always go through TUN/proxy. Never treat it as "private LAN".
            if (cmd == "iptables") {
                appendLine("$cmd -t mangle -A $CHAIN -d $FAKE_IP_CIDR -j MARK --set-xmark $MARK/0xffff")
            }
            // keep LAN / private destinations direct (per-family CIDR list)
            val cidrs = if (cmd == "ip6tables") bypassCidrsV6 else bypassCidrs
            cidrs.forEach { appendLine("$cmd -t mangle -A $CHAIN -d $it -j RETURN") }
            if (allowMode) {
                // Proxy ONLY the explicitly selected apps. If nothing resolved (e.g. the
                // selected packages failed to resolve to uids at early boot), mark nothing
                // instead of falling through to the catch-all below: a fail-open here would
                // tunnel every unselected app 闂?both a privacy leak and the "per-app proxies
                // everything after a reboot" bug.
                selectedUids.forEach { appendLine("$cmd -t mangle -A $CHAIN -m owner --uid-owner $it -j MARK --set-xmark $MARK/0xffff") }
            } else {
                // all-apps / bypass-selected: capture app UID range only (Magic_V2Ray style).
                // DNS is already marked above by dport 53 (covers netd). Marking every low
                // system uid (0-9999) has caused OEM instability; skip them on purpose.
                appendLine("$cmd -t mangle -A $CHAIN -m owner --uid-owner $APP_UID_RANGE -j MARK --set-xmark $MARK/0xffff")
            }
            appendLine("$cmd -t mangle -D OUTPUT -j $CHAIN 2>/dev/null || true")
            appendLine("$cmd -t mangle -A OUTPUT -j $CHAIN")
        }
    }

    /**
     * Blackhole native IPv6 egress for the captured app population when IPv6 is NOT routed
     * into the tun. A v4-only VpnService has no v6 route, so the kernel rejects apps' v6 and
     * they fall back to IPv4; Root mode has to reproduce that explicitly, otherwise v6-capable
     * apps reach destinations natively, bypassing the proxy / leaking. REJECT (not DROP) gives
     * an instant failure so happy-eyeballs falls back to v4 without a timeout.
     *
     * Exemptions mirror the v4 chain: the tun2socks helper (fwmark), the app's own core (uid),
     * loopback, link-local / multicast (NDP/RA/MLD) and ULA/LAN destinations. Per-app selection
     * is honored: in bypass mode the bypassed apps keep native v6; in proxy mode only the
     * selected apps lose v6 (everything else stays fully direct).
     */
    private fun buildV6Blackhole(
        appUid: Int,
        perAppEnabled: Boolean,
        bypassApps: Boolean,
        selectedUids: List<String>,
    ): String {
        val chain = AppConfig.ROOT_V6_CHAIN
        val allowMode = perAppEnabled && !bypassApps
        val bypassSelected = perAppEnabled && bypassApps && selectedUids.isNotEmpty()
        val reject = "-j REJECT --reject-with icmp6-adm-prohibited"
        return buildString {
            appendLine("\$IP6T -t filter -N $chain 2>/dev/null || true")
            appendLine("\$IP6T -t filter -F $chain")
            // never touch the helper, the core, loopback, NDP/link-local/multicast or LAN
            appendLine("\$IP6T -t filter -A $chain -m mark --mark $FWMARK -j RETURN")
            appendLine("\$IP6T -t filter -A $chain -m owner --uid-owner $appUid -j RETURN")
            appendLine("\$IP6T -t filter -A $chain -o $TUN -j RETURN 2>/dev/null || true")
            appendLine("\$IP6T -t filter -A $chain -o lo -j RETURN")
            bypassCidrsV6.forEach { appendLine("\$IP6T -t filter -A $chain -d $it -j RETURN") }
            // bypass mode: bypassed apps keep their native v6
            if (bypassSelected) {
                selectedUids.forEach { appendLine("\$IP6T -t filter -A $chain -m owner --uid-owner $it -j RETURN") }
            }
            if (allowMode) {
                // proxy mode: only the selected apps lose v6 (so they fall back to v4-via-proxy).
                // None resolved -> reject nothing, mirroring the v4 chain's fail-closed handling.
                selectedUids.forEach { appendLine("\$IP6T -t filter -A $chain -m owner --uid-owner $it $reject") }
            } else {
                // all-apps / bypass: reject only app UID range (match v4 capture set)
                appendLine("\$IP6T -t filter -A $chain -m owner --uid-owner $APP_UID_RANGE $reject")
            }
            appendLine("\$IP6T -t filter -D OUTPUT -j $chain 2>/dev/null || true")
            appendLine("\$IP6T -t filter -A OUTPUT -j $chain")
        }
    }

    // -------------------------------------------------- LAN / tethering sharing

    /**
     * Route Wi-Fi-hotspot / USB-tethered clients through the tun as well (ipv4).
     * Best-effort: wrapped in `set +e` so a failure here never breaks the working proxy.
     * Mirrors Magic_V2Ray's hotspot rules (FORWARD accept, DNS DNAT, source-based policy
     * routing for private client ranges, MSS clamp).
     */
    private fun buildLanShareSetup(captureDeviceTraffic: Boolean, ipv6: Boolean): String {
        val fwd = AppConfig.ROOT_FWD_CHAIN
        val dnsChain = AppConfig.ROOT_DNS_CHAIN
        val v6fwd = AppConfig.ROOT_V6_FWD_CHAIN
        val v6pre = AppConfig.ROOT_V6_PRE_CHAIN
        // Use the app's configured remote DNS (first plain IPv4) as the DNAT target for
        // tethered clients; fall back to the default when it's a DoH/DoT/IPv6 value that
        // can't be a DNAT target.
        val dns = SettingsManager.getRemoteDnsServers()
            .firstOrNull { Utils.isPureIpAddress(it) && !it.contains(":") }
            ?: AppConfig.ROOT_LAN_DNS
        val lanCidrs = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
        return buildString {
            appendLine("set +e")
            appendLine("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true")
            // forward traffic to/from the tun
            appendLine("iptables -N $fwd 2>/dev/null || true")
            appendLine("iptables -F $fwd")
            appendLine("iptables -A $fwd -i $TUN -j ACCEPT")
            appendLine("iptables -A $fwd -o $TUN -j ACCEPT")
            appendLine("iptables -D FORWARD -j $fwd 2>/dev/null || true")
            appendLine("iptables -I FORWARD -j $fwd")
            // clamp MSS to avoid TLS fragmentation overhead through the tunnel
            appendLine("iptables -t mangle -D FORWARD -o $TUN -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null || true")
            appendLine("iptables -t mangle -A FORWARD -o $TUN -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350")
            // hijack tethered clients' DNS into a dedicated chain so it resolves through the
            // tunnel; the chain keeps teardown independent of the resolver IP
            appendLine("iptables -t nat -N $dnsChain 2>/dev/null || true")
            appendLine("iptables -t nat -F $dnsChain")
            lanCidrs.forEach {
                appendLine("iptables -t nat -A $dnsChain ! -i $TUN -d $it -p udp --dport 53 -j DNAT --to $dns")
            }
            appendLine("iptables -t nat -D PREROUTING -j $dnsChain 2>/dev/null || true")
            appendLine("iptables -t nat -A PREROUTING -j $dnsChain")
            // policy routing: return-path via main, LAN direct, the rest via the tun table
            appendLine("ip rule add iif lo goto 6000 pref 5000 2>/dev/null || true")
            appendLine("ip rule add iif $TUN lookup main suppress_prefixlength 0 pref 5010 2>/dev/null || true")
            appendLine("ip rule add iif $TUN goto 6000 pref 5020 2>/dev/null || true")
            appendLine("ip rule add to 10.0.0.0/8 lookup main pref 5025 2>/dev/null || true")
            appendLine("ip rule add to 172.16.0.0/12 lookup main pref 5026 2>/dev/null || true")
            appendLine("ip rule add to 192.168.0.0/16 lookup main pref 5027 2>/dev/null || true")
            appendLine("ip rule add from 10.0.0.0/8 lookup $TABLE pref 5030 2>/dev/null || true")
            appendLine("ip rule add from 172.16.0.0/12 lookup $TABLE pref 5040 2>/dev/null || true")
            appendLine("ip rule add from 192.168.0.0/16 lookup $TABLE pref 5050 2>/dev/null || true")
            appendLine("ip rule add nop pref 6000 2>/dev/null || true")

            // ---------------------------------------------------------- IPv6 clients
            // Tethered/hotspot clients get a native (RA-assigned) global IPv6. The IPv4 rules
            // above don't touch it, so it egresses the upstream interface directly, bypassing
            // the proxy = IPv6 leak. Handle it explicitly (mirrors vincentng295/Magic_V2Ray
            // cae4f7f): route it through the tun when v6 is enabled, reject it when it isn't.
            appendLine("ip6tables -N $v6fwd 2>/dev/null || true")
            appendLine("ip6tables -F $v6fwd")
            appendLine("ip6tables -D FORWARD -j $v6fwd 2>/dev/null || true")
            appendLine("ip6tables -I FORWARD -j $v6fwd")
            if (ipv6) {
                // When the device itself isn't capturing v6 (VPN-mode sharing) the tun table
                // has no v6 default and the tun has no v6 address 闂?add them so marked client
                // v6 has somewhere to go. In Root mode the device-capture block already did.
                if (!captureDeviceTraffic) {
                    appendLine("ip -6 addr add ${AppConfig.ROOT_TUN_ADDR_V6} dev $TUN 2>/dev/null || true")
                    appendLine("ip -6 route replace default dev $TUN table $TABLE 2>/dev/null || true")
                    appendLine("ip -6 rule add fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
                }
                // allow forwarding to/from the tun
                appendLine("ip6tables -A $v6fwd -i $TUN -j ACCEPT")
                appendLine("ip6tables -A $v6fwd -o $TUN -j ACCEPT")
                // mark forwarded (non-locally-sourced) client v6 into the tun table. DNS first
                // so a query to a LAN/router resolver is still tunneled (MARK survives RETURN);
                // keep loopback, link-local (NDP/RA) and ULA/multicast direct.
                appendLine("ip6tables -t mangle -N $v6pre 2>/dev/null || true")
                appendLine("ip6tables -t mangle -F $v6pre")
                appendLine("ip6tables -t mangle -A $v6pre ! -i $TUN -p udp --dport 53 -j MARK --set-xmark $MARK/0xffff")
                appendLine("ip6tables -t mangle -A $v6pre ! -i $TUN -p tcp --dport 53 -j MARK --set-xmark $MARK/0xffff")
                bypassCidrsV6.forEach { appendLine("ip6tables -t mangle -A $v6pre ! -i $TUN -d $it -j RETURN") }
                appendLine("ip6tables -t mangle -A $v6pre ! -i $TUN -j MARK --set-xmark $MARK/0xffff")
                appendLine("ip6tables -t mangle -D PREROUTING -j $v6pre 2>/dev/null || true")
                appendLine("ip6tables -t mangle -A PREROUTING -j $v6pre")
                // fail closed: any forwarded v6 that wasn't marked into the tun (e.g. the
                // addrtype match is unavailable, or marking failed) is rejected rather than
                // leaked straight out the upstream interface.
                appendLine("ip6tables -A $v6fwd -j REJECT --reject-with icmp6-no-route")
            } else {
                // v6 disabled: reject forwarded clients' native v6 so it can't leak past the
                // proxy (the device's own v6 is blackholed separately in OUTPUT).
                appendLine("ip6tables -A $v6fwd -j REJECT --reject-with icmp6-no-route")
            }
        }
    }

    // ---------------------------------------------------------------- teardown

    private fun buildTeardown(context: Context): String {
        val runDir = File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR)
        val pidFile = File(runDir, "tun2socks.pid").absolutePath
        val oomGuardPid = File(runDir, "oomguard.pid").absolutePath
        val corePid = Process.myPid()
        return buildString {
            appendLine("IPT=iptables; command -v iptables-legacy >/dev/null 2>&1 && IPT=iptables-legacy")
            appendLine("IP6T=ip6tables; command -v ip6tables-legacy >/dev/null 2>&1 && IP6T=ip6tables-legacy")
            // mangle (TUN2SOCKS), both families
            for (cmd in listOf("\$IPT", "\$IP6T")) {
                appendLine("$cmd -t mangle -D OUTPUT -j $CHAIN 2>/dev/null || true")
                appendLine("$cmd -t mangle -F $CHAIN 2>/dev/null || true")
                appendLine("$cmd -t mangle -X $CHAIN 2>/dev/null || true")
            }
            // IPv6 blackhole chain (only set up when v6 is disabled; harmless if absent)
            appendLine("ip6tables -t filter -D OUTPUT -j ${AppConfig.ROOT_V6_CHAIN} 2>/dev/null || true")
            appendLine("ip6tables -t filter -F ${AppConfig.ROOT_V6_CHAIN} 2>/dev/null || true")
            appendLine("ip6tables -t filter -X ${AppConfig.ROOT_V6_CHAIN} 2>/dev/null || true")
            // routing rule + table
            appendLine("ip rule del pref $APP_UID_RULE_PREF 2>/dev/null || true")
            appendLine("ip rule del fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
            appendLine("ip -6 rule del fwmark $MARK table $TABLE priority $PRIORITY 2>/dev/null || true")
            appendLine("ip route flush table $TABLE 2>/dev/null || true")
            appendLine("ip -6 route flush table $TABLE 2>/dev/null || true")
            // LAN / tethering sharing (always cleaned, harmless if it was never set up)
            appendLine("iptables -D FORWARD -j ${AppConfig.ROOT_FWD_CHAIN} 2>/dev/null || true")
            appendLine("iptables -F ${AppConfig.ROOT_FWD_CHAIN} 2>/dev/null || true")
            appendLine("iptables -X ${AppConfig.ROOT_FWD_CHAIN} 2>/dev/null || true")
            appendLine("iptables -t mangle -D FORWARD -o $TUN -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null || true")
            appendLine("iptables -t nat -D PREROUTING -j ${AppConfig.ROOT_DNS_CHAIN} 2>/dev/null || true")
            appendLine("iptables -t nat -F ${AppConfig.ROOT_DNS_CHAIN} 2>/dev/null || true")
            appendLine("iptables -t nat -X ${AppConfig.ROOT_DNS_CHAIN} 2>/dev/null || true")
            // IPv6 LAN-sharing chains (forward accept/reject + forwarded-client marking)
            appendLine("ip6tables -D FORWARD -j ${AppConfig.ROOT_V6_FWD_CHAIN} 2>/dev/null || true")
            appendLine("ip6tables -F ${AppConfig.ROOT_V6_FWD_CHAIN} 2>/dev/null || true")
            appendLine("ip6tables -X ${AppConfig.ROOT_V6_FWD_CHAIN} 2>/dev/null || true")
            appendLine("ip6tables -t mangle -D PREROUTING -j ${AppConfig.ROOT_V6_PRE_CHAIN} 2>/dev/null || true")
            appendLine("ip6tables -t mangle -F ${AppConfig.ROOT_V6_PRE_CHAIN} 2>/dev/null || true")
            appendLine("ip6tables -t mangle -X ${AppConfig.ROOT_V6_PRE_CHAIN} 2>/dev/null || true")
            for (pref in listOf(900, BYPASS_PRIORITY, 5000, 5010, 5020, 5025, 5026, 5027, 5030, 5040, 5050, 6000)) {
                appendLine("ip rule del pref $pref 2>/dev/null || true")
            }
            appendLine("ip rule del fwmark $FWMARK lookup main priority $BYPASS_PRIORITY 2>/dev/null || true")
            appendLine("ip -6 rule del fwmark $FWMARK lookup main priority $BYPASS_PRIORITY 2>/dev/null || true")
            // FakeDNS routes (policy table flushed above; also clear main-table leftovers)
            appendLine("ip route del $FAKE_IP_CIDR dev $TUN 2>/dev/null || true")
            appendLine("ip route del $FAKE_IP_CIDR table $TABLE 2>/dev/null || true")
            // tun device down + helper process
            appendLine("ip link set dev $TUN down 2>/dev/null || true")
            appendLine("[ -f '$pidFile' ] && kill \$(cat '$pidFile') 2>/dev/null || true")
            appendLine("rm -f '$pidFile'")
            // stop the OOM re-pin loop and restore the core process's LMK priority
            appendLine("[ -f '$oomGuardPid' ] && kill \$(cat '$oomGuardPid') 2>/dev/null || true")
            appendLine("rm -f '$oomGuardPid'")
            appendLine("echo 0 > /proc/$corePid/oom_score_adj 2>/dev/null || true")
        }
    }
}
