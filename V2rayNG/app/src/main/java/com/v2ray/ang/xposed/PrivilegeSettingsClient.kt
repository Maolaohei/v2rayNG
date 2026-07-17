package com.v2ray.ang.xposed

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.xposed.ipc.PackageEntry
import com.v2ray.ang.xposed.ipc.ParceledListSlice

/**
 * App-side bridge that pushes privilege hidevpn settings into system_server hooks
 * via a private IConnectivityManager transaction (SFA design).
 */
object PrivilegeSettingsClient {
    private const val TAG = "PrivilegeSettingsClient"
    private const val SERVICE = "connectivity"

    @Volatile
    private var appContext: Context? = null

    fun register(context: Context) {
        appContext = context.applicationContext
        sync()
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
            val binder = getServiceBinder(SERVICE) ?: run {
                LogUtil.w(AppConfig.TAG, "$TAG: connectivity binder null")
                return false
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
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
                    return false
                }
                LogUtil.i(
                    AppConfig.TAG,
                    "$TAG: synced enabled=$enabled apps=${packages.size} rename=$renameEnabled prefix=$prefix",
                )
                true
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "$TAG: sync failed: ${e.message}")
            false
        }
    }

    fun isModuleActive(): Boolean {
        return try {
            val binder = getServiceBinder(SERVICE) ?: return false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
                val ok = binder.transact(HookStatusKeys.TRANSACTION_STATUS, data, reply, 0)
                if (!ok) return false
                reply.readException()
                reply.readInt() != 0
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun getServiceBinder(name: String): IBinder? {
        return try {
            val clazz = Class.forName("android.os.ServiceManager")
            val method = clazz.getMethod("getService", String::class.java)
            method.invoke(null, name) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }
}