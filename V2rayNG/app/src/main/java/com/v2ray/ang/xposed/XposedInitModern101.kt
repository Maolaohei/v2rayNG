package com.v2ray.ang.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern LSPosed/libxposed entry for API 101/102.
 *
 * Framework instantiates via no-arg constructor, attaches the interface, then
 * calls [onSystemServerStarting] for system_server scope.
 *
 * Hot reload is intentionally disabled: hidevpn hooks still rely on legacy
 * Xposed helpers and system_server state that is safer to reinstall after reboot.
 */
class XposedInitModern101 : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HookInstaller.install(param.classLoader)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Keep process stable; require reboot/module re-enable after updates.
        return false
    }
}
