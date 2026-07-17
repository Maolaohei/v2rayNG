package com.v2ray.ang.xposed

import android.content.Context
import com.v2ray.ang.xposed.hooks.HookIConnectivityManagerOnTransact
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.hooks.hidevpn.ConnectivityServiceHookHelper
import com.v2ray.ang.xposed.hooks.hidevpn.HookNetworkCapabilitiesWriteToParcel
import com.v2ray.ang.xposed.hooks.hidevpn.HookNetworkInterfaceGetName
import com.v2ray.ang.xposed.hooks.hidevpnapp.HookPackageManagerGetInstalledPackages
import io.github.libxposed.api.XposedInterface

object HookInstaller {

    private const val TAG = "XposedInit"

    @Volatile
    private var installed = false

    private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }
    private val currentActivityThreadMethod by lazy { activityThreadClass.getMethod("currentActivityThread") }
    private val getSystemContextMethod by lazy { activityThreadClass.getMethod("getSystemContext") }

    fun install(classLoader: ClassLoader, modern: XposedInterface? = null) {
        if (modern != null) {
            XposedApi.attachModern(modern)
            HookErrorStore.i(TAG, "modern libxposed interface attached api=${runCatching { modern.apiVersion }.getOrDefault(-1)}")
        }
        if (installed) {
            HookErrorStore.i(TAG, "install skipped: already installed modern=${XposedApi.isModern()}")
            return
        }
        installed = true
        val systemContext = resolveSystemContext()
        HookErrorStore.i(TAG, "handleSystemServerLoaded modern=${XposedApi.isModern()}")
        val hooks = arrayOf(
            ConnectivityServiceHookHelper(classLoader),
            HookIConnectivityManagerOnTransact(classLoader, systemContext),
            HookPackageManagerGetInstalledPackages(classLoader),
            HookNetworkCapabilitiesWriteToParcel(),
            HookNetworkInterfaceGetName(classLoader),
        )

        hooks.forEach { hook ->
            try {
                hook.injectHook()
            } catch (e: Throwable) {
                HookErrorStore.e(
                    TAG,
                    "Failed to inject ${hook.javaClass.simpleName}",
                    e,
                )
            }
        }
        HookStatusStore.markHookActive()
    }

    private fun resolveSystemContext(): Context? = try {
        val currentThread = currentActivityThreadMethod.invoke(null)
        getSystemContextMethod.invoke(currentThread) as? Context
    } catch (e: Throwable) {
        HookErrorStore.e(TAG, "resolveSystemContext failed", e)
        null
    }
}
