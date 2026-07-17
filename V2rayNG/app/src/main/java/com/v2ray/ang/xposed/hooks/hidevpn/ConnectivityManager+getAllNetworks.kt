package com.v2ray.ang.xposed.hooks.hidevpn

import android.net.Network
import android.os.Binder
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerGetAllNetworks(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerGetAllNetworks"
    }

    fun install() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "getAllNetworks",
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: SafeMethodHook.HookParam) {
                    val uid = Binder.getCallingUid()
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    @Suppress("UNCHECKED_CAST")
                    val networks = param.result as? Array<Network> ?: return
                    val filtered = networks.filter { !helper.isVpnNetwork(param.thisObject, it) }
                    param.result = filtered.toTypedArray()
                }
            },
        )
    }
}
