package com.v2ray.ang.xposed

import android.content.Context
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.root.RootShell
import com.v2ray.ang.util.LogUtil

/**
 * Lightweight vpnhide-ports style activator:
 * for hide-target UIDs, REJECT traffic to loopback (127.0.0.0/8 and ::1).
 *
 * Never applies to this app's own UID. Requires root (su). Idempotent apply/clear.
 */
object PrivilegePortsManager {
    private const val CHAIN4 = "v2ng_ports_out"
    private const val CHAIN6 = "v2ng_ports_out6"
    private const val TAG = "PrivilegePorts"

    data class Status(
        val enabledPref: Boolean,
        val root: Boolean,
        val appliedUids: Int,
        val detail: String,
    )

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_PORTS, false)

    fun status(context: Context): Status {
        val enabled = isEnabled()
        val root = RootManager.isRootAvailable()
        if (!enabled) {
            return Status(false, root, 0, "off")
        }
        if (!root) {
            return Status(true, false, 0, "no_root")
        }
        val uids = resolveTargetUids(context)
        return Status(true, true, uids.size, if (uids.isEmpty()) "no_targets" else "ready")
    }

    /**
     * Apply or clear based on current prefs. Safe to call after hide-target list sync.
     * @return true when desired state was reached (or already clear when off).
     */
    fun applyFromPrefs(context: Context): Boolean {
        val enabled = isEnabled()
        val hideOn = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
        if (!enabled || !hideOn) {
            return clear(context)
        }
        if (!RootManager.isRootAvailable()) {
            LogUtil.w(AppConfig.TAG, "$TAG: root unavailable, skip apply")
            return false
        }
        val uids = resolveTargetUids(context)
        if (uids.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "$TAG: no target uids, clear chains")
            return clear(context)
        }
        return apply(context, uids)
    }

    fun clear(context: Context): Boolean {
        if (!RootManager.isRootAvailable()) {
            return !isEnabled()
        }
        val script = buildString {
            appendLine("set +e")
            for (ipt in listOf("iptables", "ip6tables")) {
                val chain = if (ipt == "iptables") CHAIN4 else CHAIN6
                appendLine("$ipt -D OUTPUT -j $chain 2>/dev/null")
                appendLine("$ipt -F $chain 2>/dev/null")
                appendLine("$ipt -X $chain 2>/dev/null")
            }
            appendLine("exit 0")
        }
        val result = RootShell.runScript(context, "v2ng_ports_clear.sh", script)
        LogUtil.i(AppConfig.TAG, "$TAG: clear code=${result.code}")
        return result.success || result.code == 0
    }

    private fun apply(context: Context, uids: Collection<Int>): Boolean {
        val sorted = uids.toSortedSet()
        val script = buildString {
            appendLine("set -e")
            appendLine("iptables -N $CHAIN4 2>/dev/null || iptables -F $CHAIN4")
            appendLine("iptables -C OUTPUT -j $CHAIN4 2>/dev/null || iptables -I OUTPUT 1 -j $CHAIN4")
            appendLine("iptables -F $CHAIN4")
            for (uid in sorted) {
                appendLine(
                    "iptables -A $CHAIN4 -m owner --uid-owner $uid -d 127.0.0.0/8 -p tcp -j REJECT --reject-with tcp-reset",
                )
                appendLine(
                    "iptables -A $CHAIN4 -m owner --uid-owner $uid -d 127.0.0.0/8 -p udp -j REJECT --reject-with icmp-port-unreachable",
                )
            }
            appendLine("ip6tables -N $CHAIN6 2>/dev/null || ip6tables -F $CHAIN6")
            appendLine("ip6tables -C OUTPUT -j $CHAIN6 2>/dev/null || ip6tables -I OUTPUT 1 -j $CHAIN6")
            appendLine("ip6tables -F $CHAIN6")
            for (uid in sorted) {
                appendLine(
                    "ip6tables -A $CHAIN6 -m owner --uid-owner $uid -d ::1 -p tcp -j REJECT --reject-with tcp-reset",
                )
                appendLine(
                    "ip6tables -A $CHAIN6 -m owner --uid-owner $uid -d ::1 -p udp -j REJECT --reject-with icmp6-port-unreachable",
                )
            }
            appendLine("exit 0")
        }
        val result = RootShell.runScript(context, "v2ng_ports_apply.sh", script)
        LogUtil.i(
            AppConfig.TAG,
            "$TAG: apply uids=${sorted.size} code=${result.code} out=${result.output.take(200)}",
        )
        return result.success
    }

    private fun resolveTargetUids(context: Context): Set<Int> {
        val packages = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)
            .orEmpty()
        val selfUid = context.applicationInfo.uid
        val pm = context.packageManager
        val uids = linkedSetOf<Int>()
        for (pkg in packages) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (info.uid != selfUid) {
                    uids.add(info.uid)
                }
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (e: Throwable) {
                LogUtil.w(AppConfig.TAG, "$TAG: resolve $pkg failed: ${e.message}")
            }
        }
        return uids
    }
}
