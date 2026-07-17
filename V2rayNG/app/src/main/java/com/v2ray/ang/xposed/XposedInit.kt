package com.v2ray.ang.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Classic Xposed/LSPosed entry for frameworks that still use IXposedHookLoadPackage.
 * Modern LSPosed 100/101 uses [XposedInitModern] / [XposedInitModern101].
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