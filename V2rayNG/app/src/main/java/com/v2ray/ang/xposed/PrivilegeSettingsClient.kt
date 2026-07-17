package com.v2ray.ang.xposed

import android.content.Context
import android.os.RemoteException
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.xposed.ipc.PackageEntry
import com.v2ray.ang.xposed.ipc.ParceledListSlice

/**
 * App-side bridge for LSPosed hidevpn:
 * - query module status via private IConnectivityManager transaction
 * - push privilege settings into system_server hooks
 *
 * Aligned with SFA HookStatusClient + PrivilegeSettingsClient design.
 */
object PrivilegeSettingsClient {
    private const val TAG = "PrivilegeSettingsClient"

    data class ModuleStatus(
        val active: Boolean,
        val lastPatchedAt: Long,
        val version: Int,
        val systemPid: Int,
    )

    enum class ProbeResult {
        ACTIVE,
        HOOK_LOADED_INACTIVE,
        TRANSACTION_UNHANDLED,
        UNAUTHORIZED,
        BINDER_UNAVAILABLE,
        ERROR,
    }

    data class Probe(
        val result: ProbeResult,
        val status: ModuleStatus? = null,
        val detail: String? = null,
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastProbe: Probe = Probe(ProbeResult.BINDER_UNAVAILABLE, detail = "not probed")

    fun register(context: Context) {
        appContext = context.applicationContext
        refresh()
        sync()
    }

    fun lastProbe(): Probe = lastProbe

    fun refresh(): Probe {
        val probe = probeModule()
        lastProbe = probe
        return probe
    }

    fun isModuleActive(): Boolean {
        val probe = refresh()
        return probe.result == ProbeResult.ACTIVE ||
            (probe.status?.active == true)
    }

    fun sync(): Boolean {
        val enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
        val packages = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)
            ?.toList()
            .orEmpty()
        val rename = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_IFACE_RENAME, false)
        val prefix = MmkvManager.decodeSettingsString(AppConfig.PREF_PRIVILEGE_IFACE_PREFIX, "wlan") ?: "wlan"
        return sync(enabled, packages, rename, prefix)
    }

    fun sync(
        enabled: Boolean,
        packages: Collection<String>,
        renameEnabled: Boolean,
        prefix: String,
    ): Boolean {
        return try {
            val binder = ConnectivityBinderUtils.getBinder(appContext) ?: run {
                LogUtil.w(AppConfig.TAG, "$TAG: connectivity binder null")
                lastProbe = Probe(ProbeResult.BINDER_UNAVAILABLE, detail = "binder null on sync")
                return false
            }
            ConnectivityBinderUtils.withParcel { data, reply ->
                data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
                data.writeInt(if (enabled) 1 else 0)
                val entries = packages.map { PackageEntry(it) }
                ParceledListSlice(entries).writeToParcel(data, 0)
                data.writeInt(if (renameEnabled) 1 else 0)
                data.writeString(prefix)
                val ok = binder.transact(
                    HookStatusKeys.TRANSACTION_UPDATE_PRIVILEGE_SETTINGS,
                    data,
                    reply,
                    0,
                )
                reply.readException()
                if (!ok) {
                    LogUtil.w(AppConfig.TAG, "$TAG: update transaction not handled (module inactive?)")
                    lastProbe = Probe(ProbeResult.TRANSACTION_UNHANDLED, detail = "update not handled")
                    return@withParcel false
                }
                LogUtil.i(
                    AppConfig.TAG,
                    "$TAG: synced enabled=$enabled apps=${packages.size} rename=$renameEnabled prefix=$prefix",
                )
                true
            }
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "$TAG: sync unauthorized: ${e.message}")
            lastProbe = Probe(ProbeResult.UNAUTHORIZED, detail = e.message)
            false
        } catch (e: RemoteException) {
            LogUtil.w(AppConfig.TAG, "$TAG: sync remote: ${e.message}")
            lastProbe = Probe(ProbeResult.ERROR, detail = e.message)
            false
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "$TAG: sync failed: ${e.message}")
            lastProbe = Probe(ProbeResult.ERROR, detail = e.message)
            false
        }
    }

    private fun probeModule(): Probe {
        return try {
            val binder = ConnectivityBinderUtils.getBinder(appContext)
                ?: return Probe(ProbeResult.BINDER_UNAVAILABLE, detail = "connectivity binder null")
            ConnectivityBinderUtils.withParcel { data, reply ->
                data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
                val ok = binder.transact(HookStatusKeys.TRANSACTION_STATUS, data, reply, 0)
                if (!ok) {
                    LogUtil.w(AppConfig.TAG, "$TAG: status transaction not handled")
                    return@withParcel Probe(
                        ProbeResult.TRANSACTION_UNHANDLED,
                        detail = "status code not handled; check LSPosed scope=system/android and reboot",
                    )
                }
                reply.readException()
                val status = ModuleStatus(
                    active = reply.readInt() != 0,
                    lastPatchedAt = reply.readLong(),
                    version = reply.readInt(),
                    systemPid = reply.readInt(),
                )
                LogUtil.i(
                    AppConfig.TAG,
                    "$TAG: status active=${status.active} ver=${status.version} pid=${status.systemPid} patched=${status.lastPatchedAt}",
                )
                if (status.active) {
                    Probe(ProbeResult.ACTIVE, status)
                } else {
                    // Hook path answered us, so system_server injection works; hide hooks may still be pending.
                    Probe(ProbeResult.HOOK_LOADED_INACTIVE, status, detail = "hooks loaded but active flag false")
                }
            }
        } catch (e: SecurityException) {
            LogUtil.w(AppConfig.TAG, "$TAG: status unauthorized: ${e.message}")
            Probe(ProbeResult.UNAUTHORIZED, detail = e.message)
        } catch (e: RemoteException) {
            LogUtil.w(AppConfig.TAG, "$TAG: status remote: ${e.message}")
            Probe(ProbeResult.ERROR, detail = e.message)
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "$TAG: status failed: ${e.message}")
            Probe(ProbeResult.ERROR, detail = e.message)
        }
    }
}
