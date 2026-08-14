package com.v2ray.ang.xposed.hooks.hidevpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkInfo
import android.os.Binder
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.VpnHideContext
import com.v2ray.ang.xposed.VpnSanitizer
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerGetNetworkInfo(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerGetNetworkInfo"
    }

    fun install() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "getNetworkInfo",
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val type = param.args[0] as Int
                    if (type != ConnectivityManager.TYPE_VPN) return
                    val uid = VpnHideContext.effectiveCallerUid()
                    // Soft sanitize only: never rewrite general network topology here.
                    if (!helper.shouldSanitize(uid)) return
                    param.result = null
                }
            },
        )

        XposedApi.findAndHookMethod(
            helper.cls,
            "getNetworkInfoForUid",
            Network::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = param.args[1] as Int
                    // Only rewrite VPN NetworkInfo when this uid is actually on VPN.
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    val info = param.result as? NetworkInfo ?: return
                    if (info.type != ConnectivityManager.TYPE_VPN) return
                    val replacement = helper.getUnderlyingNetworkInfo(param.thisObject, uid)
                    param.result = if (replacement != null) {
                        VpnSanitizer.cloneNetworkInfo(replacement)
                    } else {
                        null
                    }
                }
            },
        )
    }
}