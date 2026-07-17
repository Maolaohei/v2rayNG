package com.v2ray.ang.xposed.hooks.hidevpn

import android.net.Network
import android.net.ProxyInfo
import android.os.Binder
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.VpnHideContext
import com.v2ray.ang.xposed.VpnSanitizer
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerGetDefaultProxy(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerGetDefaultProxy"
    }

    fun install() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "getProxyForNetwork",
            Network::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    param.result as? ProxyInfo ?: return
                    param.result = null
                }
            },
        )

        XposedApi.findAndHookMethod(
            helper.cls,
            "getGlobalProxy",
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!VpnSanitizer.shouldHide(uid)) return
                    param.result as? ProxyInfo ?: return
                    param.result = null
                }
            },
        )
    }
}
