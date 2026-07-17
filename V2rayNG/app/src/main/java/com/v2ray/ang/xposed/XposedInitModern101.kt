package com.v2ray.ang.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern LSPosed/libxposed entry (API 101).
 * Framework instantiates via no-arg constructor and calls [onSystemServerStarting].
 */
class XposedInitModern101 : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HookInstaller.install(param.classLoader)
    }
}