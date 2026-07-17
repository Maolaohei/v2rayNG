package com.v2ray.ang.xposed.hooks.hidevpn

import android.net.ConnectivityManager
import android.net.NetworkInfo
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.VpnSanitizer
import com.v2ray.ang.xposed.hooks.SafeMethodHook

class HookConnectivityManagerConnectivityAction(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerConnectivityAction"
    }

    fun install() {
        XposedApi.findAndHookMethod(
            helper.cls,
            "sendGeneralBroadcast",
            NetworkInfo::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: SafeMethodHook.HookParam) {
                    val info = param.args[0] as? NetworkInfo ?: return
                    if (info.type != ConnectivityManager.TYPE_VPN) return
                    val defaultNai = XposedApi.callMethod(param.thisObject, "getDefaultNetwork")
                        ?: return
                    if (helper.isVpnNai(defaultNai)) {
                        return
                    }
                    val replacement = XposedApi.getObjectField(defaultNai, "networkInfo") as? NetworkInfo
                        ?: return
                    param.args[0] = VpnSanitizer.cloneNetworkInfo(replacement)
                }
            },
        )
    }
}
