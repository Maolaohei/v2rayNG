package com.v2ray.ang.xposed.hooks.hidevpn

import com.v2ray.ang.xposed.VpnHideContext

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
                    val uid = VpnHideContext.effectiveCallerUid()
                    val service = param.thisObject ?: return
                    if (!helper.shouldHide(service, uid)) return
                    @Suppress("UNCHECKED_CAST")
                    val networks = param.result as? Array<Network> ?: return
                    val filtered = networks.filter { !helper.isVpnNetwork(service, it) }
                    param.result = filtered.toTypedArray()
                }
            },
        )
    }
}