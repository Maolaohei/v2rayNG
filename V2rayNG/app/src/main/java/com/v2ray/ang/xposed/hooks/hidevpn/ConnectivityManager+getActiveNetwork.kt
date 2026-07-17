package com.v2ray.ang.xposed.hooks.hidevpn

import android.os.Binder
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerGetActiveNetwork(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerGetActiveNetwork"
    }

    fun install() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "getActiveNetwork",
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = Binder.getCallingUid()
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    val replacement = helper.getUnderlyingNetwork(param.thisObject, uid) ?: return
                    param.result = replacement
                }
            },
        )

        XposedApi.findAndHookMethod(
            helper.cls,
            "getActiveNetworkForUid",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = param.args[0] as Int
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    val replacement = helper.getUnderlyingNetwork(param.thisObject, uid) ?: return
                    param.result = replacement
                }
            },
        )
    }
}
