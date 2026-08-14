package com.v2ray.ang.xposed.hooks.hidevpn

import com.v2ray.ang.xposed.VpnHideContext

import android.net.ConnectivityManager
import android.os.Binder
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerGetNetworkForType(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerGetNetworkForType"
    }

    fun install() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "getNetworkForType",
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val type = param.args[0] as Int
                    if (type != ConnectivityManager.TYPE_VPN) return
                    val uid = VpnHideContext.effectiveCallerUid()
                    // Soft sanitize: hide TYPE_VPN handle without requiring vpnForUid.
                    if (!helper.shouldSanitize(uid)) return
                    param.result = null
                }
            },
        )
    }
}