package com.v2ray.ang.xposed

import android.content.Context
import com.v2ray.ang.xposed.hooks.HookIConnectivityManagerOnTransact
import com.v2ray.ang.xposed.hooks.XHook
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

    data class InstallReport(
        val modern: Boolean,
        val success: List<String>,
        val failed: List<String>,
    ) {
        val ok: Boolean get() = failed.isEmpty() && success.isNotEmpty()
    }

    private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }
    private val currentActivityThreadMethod by lazy { activityThreadClass.getMethod("currentActivityThread") }
    private val getSystemContextMethod by lazy { activityThreadClass.getMethod("getSystemContext") }

    fun install(classLoader: ClassLoader, modern: XposedInterface? = null): InstallReport {
        if (modern != null) {
            XposedApi.attachModern(modern)
            HookErrorStore.i(
                TAG,
                "modern libxposed interface attached api=${runCatching { modern.apiVersion }.getOrDefault(-1)}",
            )
        } else if (XposedApi.isModern()) {
            // Should not happen: classic entry after modern attach in same process.
            HookErrorStore.w(TAG, "classic install requested but modern interface already attached")
        }

        if (installed) {
            HookErrorStore.i(TAG, "install skipped: already installed modern=${XposedApi.isModern()}")
            return InstallReport(XposedApi.isModern(), listOf("already-installed"), emptyList())
        }
        installed = true

        // Restore last privilege settings before rename/sanitize hooks run (system_server reboot).
        runCatching { PrivilegeSettingsStore.isEnabled() }
            .onFailure { HookErrorStore.w(TAG, "preload privilege settings failed: ${it.message}") }

        val systemContext = resolveSystemContext()
        HookErrorStore.i(TAG, "handleSystemServerLoaded modern=${XposedApi.isModern()}")

        val hooks: List<Pair<String, XHook>> = listOf(
            "ConnectivityService" to ConnectivityServiceHookHelper(classLoader),
            "IConnectivityManager.onTransact" to HookIConnectivityManagerOnTransact(classLoader, systemContext),
            "PackageManager.hidevpnapp" to HookPackageManagerGetInstalledPackages(classLoader),
            "NetworkCapabilities.writeToParcel" to HookNetworkCapabilitiesWriteToParcel(),
            "NetworkInterface.rename" to HookNetworkInterfaceGetName(classLoader),
        )

        val success = ArrayList<String>()
        val failed = ArrayList<String>()
        for ((name, hook) in hooks) {
            try {
                hook.injectHook()
                success += name
                HookErrorStore.i(TAG, "inject ok: $name")
            } catch (e: Throwable) {
                failed += name
                HookErrorStore.e(TAG, "Failed to inject $name (${hook.javaClass.simpleName})", e)
            }
        }

        // Only mark active when core IPC + at least one connectivity sanitizer installed.
        val coreOk = success.any { it.startsWith("IConnectivityManager") }
        val sanitizeOk = success.any {
            it.startsWith("ConnectivityService") || it.startsWith("NetworkCapabilities")
        }
        if (coreOk && sanitizeOk) {
            HookStatusStore.markHookActive()
            HookErrorStore.i(TAG, "hook active: success=$success failed=$failed")
        } else {
            HookErrorStore.e(
                TAG,
                "hook NOT fully active: success=$success failed=$failed (need IPC + connectivity sanitize)",
            )
        }
        return InstallReport(XposedApi.isModern(), success, failed)
    }

    private fun resolveSystemContext(): Context? = try {
        val currentThread = currentActivityThreadMethod.invoke(null)
        getSystemContextMethod.invoke(currentThread) as? Context
    } catch (e: Throwable) {
        HookErrorStore.e(TAG, "resolveSystemContext failed", e)
        null
    }
}
