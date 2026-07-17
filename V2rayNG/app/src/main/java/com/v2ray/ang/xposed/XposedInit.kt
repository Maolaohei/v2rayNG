package com.v2ray.ang.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed/Xposed entry for SFA-style hidevpn.
 * Scope must include system framework ("android" / "system") so ConnectivityService hooks apply.
 *
 * Note: modern LSPosed 100/101 prefers XposedModule APIs; we keep classic entry for
 * broader compatibility and fix status probing on the app side first.
 */
class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "android" || pkg == "system") {
            HookInstaller.install(lpparam.classLoader)
        }
    }

    companion object {
        const val TAG = "v2rayng-lsposed"
    }
}