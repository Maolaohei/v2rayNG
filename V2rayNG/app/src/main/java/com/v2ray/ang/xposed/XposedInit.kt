package com.v2ray.ang.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed/Xposed entry for SFA-style hidevpn.
 * Scope must include system framework ("android") so ConnectivityService hooks apply.
 */
class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            HookInstaller.install(lpparam.classLoader)
        }
    }

    companion object {
        const val TAG = "v2rayng-lsposed"
    }
}