package com.v2ray.ang.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern LSPosed/libxposed entry for API 101/102.
 * Uses only XposedInterface for hooks (no legacy de.robv calls on this path).
 */
class XposedInitModern101 : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // HideVPN only needs system_server. Drop lifecycle callbacks elsewhere.
        if (!param.isSystemServer) {
            runCatching { detach() }
                .onFailure { HookErrorStore.w("XposedInitModern101", "detach non-system failed: ${it.message}") }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HookInstaller.install(param.classLoader, modern = this)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = false
}
