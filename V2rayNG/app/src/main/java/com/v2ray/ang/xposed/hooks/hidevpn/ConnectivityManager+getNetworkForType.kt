package com.v2ray.ang.xposed.hooks.hidevpn

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
                    val uid = Binder.getCallingUid()
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    param.result = null
                }
            },
        )
    }
}
