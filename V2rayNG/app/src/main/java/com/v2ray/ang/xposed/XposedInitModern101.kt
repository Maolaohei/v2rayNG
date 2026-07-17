package com.v2ray.ang.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern LSPosed/libxposed entry for API 101/102.
 * Passes the module (XposedInterface) into HookInstaller so hooks use native libxposed.
 * Hot reload remains disabled for system_server stability.
 */
class XposedInitModern101 : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HookInstaller.install(param.classLoader, modern = this)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = false
}
