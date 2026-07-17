package com.v2ray.ang.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern LSPosed/libxposed entry (API 100).
 * Framework loads this in system_server and calls [onSystemServerLoaded].
 */
class XposedInitModern(
    base: XposedInterface,
    param: XposedModuleInterface.ModuleLoadedParam,
) : XposedModule(base, param) {

    override fun onSystemServerLoaded(param: XposedModuleInterface.SystemServerLoadedParam) {
        HookInstaller.install(param.classLoader)
    }
}