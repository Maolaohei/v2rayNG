package com.v2ray.ang.xposed.hooks.hidevpn

import com.v2ray.ang.xposed.VpnHideContext

import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Binder
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerGetAllNetworkInfo(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerGetAllNetworkInfo"
    }

    fun install() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "getAllNetworkInfo",
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = VpnHideContext.effectiveCallerUid()
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    @Suppress("UNCHECKED_CAST")
                    val infos = param.result as? Array<NetworkInfo> ?: return
                    val filtered = infos.filter { it.type != ConnectivityManager.TYPE_VPN }
                    param.result = filtered.toTypedArray()
                }
            },
        )
    }
}
