package com.v2ray.ang.xposed.hooks.hidevpn

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkInfo
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import com.v2ray.ang.xposed.hooks.XposedApi
import com.v2ray.ang.xposed.HookErrorStore
import com.v2ray.ang.xposed.PrivilegeSettingsStore
import com.v2ray.ang.xposed.VpnAppStore
import com.v2ray.ang.xposed.VpnSanitizer
import com.v2ray.ang.xposed.hooks.SafeMethodHook
import com.v2ray.ang.xposed.hooks.XHook
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ConnectivityServiceHookHelper(private val classLoader: ClassLoader) : XHook {
    companion object {
        private const val SOURCE = "ConnectivityServiceHookHelper"
    }

    private val hooked = AtomicBoolean(false)
    private val initializerHooked = AtomicBoolean(false)
    private var classLoadUnhook: Any? = null
    private var onTransactUnhook: Any? = null
    private val serviceManagerHooked = AtomicBoolean(false)
    private var connectivityClassLoader: ClassLoader = classLoader
    private val skipLogKeys = ConcurrentHashMap<String, Boolean>()
    val sdkInt = Build.VERSION.SDK_INT

    lateinit var cls: Class<*>
        private set

    private val serviceManagerClass by lazy { Class.forName("android.os.ServiceManager") }
    private val checkServiceMethod by lazy { serviceManagerClass.getMethod("checkService", String::class.java) }

    private var getVpnForUidMethod: Method? = null
    private lateinit var getVpnUnderlyingNetworksMethod: Method
    private lateinit var getNetworkAgentInfoForNetworkMethod: Method
    private var getFilteredNetworkInfoMethod: Method? = null
    private lateinit var getDefaultNetworkMethod: Method
    private lateinit var isVPNMethod: Method
    private var networkMethod: Method? = null

    override fun injectHook() {
        val foundClass = findConnectivityServiceClass()
        if (foundClass != null) {
            installHooks(foundClass, "direct")
            return
        }
        hookConnectivityServiceInitializer()
        hookClassLoaderFallback()
        hookOnTransactFallback()
        tryHookFromServiceManager()
    }

    private fun installHooks(cls: Class<*>, source: String) {
        if (!hooked.compareAndSet(false, true)) {
            return
        }
        this.cls = cls
        connectivityClassLoader = cls.classLoader ?: classLoader
        initMethodCache()
        HookErrorStore.i(
            SOURCE,
            "Installing ConnectivityService hooks ($source) cls=${cls.name} loader=${connectivityClassLoader.javaClass.name}",
        )

        // Install hooks independently so one missing signature cannot kill the whole set.
        val installers = listOf(
            "GetActiveNetwork" to { HookConnectivityManagerGetActiveNetwork(this).install() },
            "GetActiveNetworkInfo" to { HookConnectivityManagerGetActiveNetworkInfo(this).install() },
            "GetNetworkInfo" to { HookConnectivityManagerGetNetworkInfo(this).install() },
            "GetAllNetworkInfo" to { HookConnectivityManagerGetAllNetworkInfo(this).install() },
            "GetAllNetworks" to { HookConnectivityManagerGetAllNetworks(this).install() },
            "GetNetworkForType" to { HookConnectivityManagerGetNetworkForType(this).install() },
            "GetNetworkCapabilities" to { HookConnectivityManagerGetNetworkCapabilities(this).install() },
            "GetLinkProperties" to { HookConnectivityManagerGetLinkProperties(this).install() },
            "RequestNetwork" to { HookConnectivityManagerRequestNetwork(this).install() },
            "GetDefaultProxy" to { HookConnectivityManagerGetDefaultProxy(this).install() },
            "ConnectivityAction" to { HookConnectivityManagerConnectivityAction(this).install() },
            "ProxyChangeAction" to { HookConnectivityManagerProxyChangeAction(this).install() },
        )
        val ok = ArrayList<String>()
        val failed = ArrayList<String>()
        for ((name, block) in installers) {
            try {
                block()
                ok += name
            } catch (e: Throwable) {
                failed += name
                HookErrorStore.e(SOURCE, "install sub-hook failed: $name", e)
            }
        }
        HookErrorStore.i(
            SOURCE,
            "Hooked ConnectivityService ($source) cls=${cls.name} ok=$ok failed=$failed",
        )
        if (ok.none { it == "GetNetworkInfo" || it == "GetNetworkForType" || it == "GetNetworkCapabilities" }) {
            throw IllegalStateException("critical connectivity sanitize hooks missing: failed=$failed")
        }
    }

    private fun initMethodCache() {
        val intType = Int::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!
        val naiClass = resolveConnectivityModuleClass("NetworkAgentInfo", "connectivity")
        if (sdkInt >= 31) {
            getVpnForUidMethod = findDeclaredMethod(cls, "getVpnForUid", intType)
            if (getVpnForUidMethod == null) {
                HookErrorStore.w(SOURCE, "getVpnForUid not found; falling back to underlying networks")
            }
        }
        getVpnUnderlyingNetworksMethod = requireDeclaredMethod(cls, "getVpnUnderlyingNetworks", intType)
        getNetworkAgentInfoForNetworkMethod = requireDeclaredMethod(cls, "getNetworkAgentInfoForNetwork", Network::class.java)
        if (sdkInt >= 31) {
            getFilteredNetworkInfoMethod = findDeclaredMethod(
                cls,
                "getFilteredNetworkInfo",
                naiClass,
                intType,
                booleanType,
            )
            if (getFilteredNetworkInfoMethod == null) {
                HookErrorStore.w(SOURCE, "getFilteredNetworkInfo not found; network info sanitization disabled")
            }
        }
        getDefaultNetworkMethod = requireDeclaredMethod(cls, "getDefaultNetwork")
        isVPNMethod = requireDeclaredMethod(naiClass, "isVPN")
        networkMethod = findDeclaredMethod(naiClass, "network")
        if (networkMethod == null) {
            HookErrorStore.w(SOURCE, "NetworkAgentInfo.network() not found; falling back to field access")
        }
    }

    // region Service Discovery

    private fun findConnectivityServiceClass(): Class<*>? {
        val candidates = listOf(
            "com.android.server.ConnectivityService",
        )
        val loaders = listOf(
            classLoader,
            classLoader.parent,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
            ClassLoader.getSystemClassLoader()?.parent,
        )
        for (name in candidates) {
            for (loader in loaders) {
                try {
                    val found = if (loader != null) {
                        Class.forName(name, false, loader)
                    } else {
                        Class.forName(name)
                    }
                    HookErrorStore.i(
                        SOURCE,
                        "ConnectivityService class found: $name via ${loader?.javaClass?.name ?: "null"}",
                    )
                    return found
                } catch (_: Throwable) {
                }
            }
        }
        HookErrorStore.i(SOURCE, "ConnectivityService class not found in known classloaders")

        val initializerNames = listOf(
            "com.android.server.ConnectivityServiceInitializer",
            "com.android.server.ConnectivityServiceInitializerB",
        )
        for (name in initializerNames) {
            for (loader in loaders) {
                val initCls = try {
                    if (loader != null) Class.forName(name, false, loader) else Class.forName(name)
                } catch (_: Throwable) {
                    null
                } ?: continue
                try {
                    val field = initCls.getDeclaredField("mConnectivity")
                    val fieldType = field.type
                    if (fieldType.name.endsWith(".ConnectivityService")) {
                        HookErrorStore.i(
                            SOURCE,
                            "ConnectivityService class found via $name.mConnectivity: ${fieldType.name}",
                        )
                        return fieldType
                    }
                } catch (_: Throwable) {
                }
            }
        }

        return null
    }

    private fun hookConnectivityServiceInitializer() {
        if (sdkInt < 31) {
            HookErrorStore.d(SOURCE, "Skip ConnectivityServiceInitializer: sdk=$sdkInt (requires API 31+)")
            return
        }
        val candidates = listOf(
            "com.android.server.ConnectivityServiceInitializer",
            "com.android.server.ConnectivityServiceInitializerB",
        )
        val loaders = listOf(
            classLoader,
            classLoader.parent,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
            ClassLoader.getSystemClassLoader()?.parent,
        )
        for (name in candidates) {
            for (loader in loaders) {
                val cls = try {
                    if (loader != null) {
                        Class.forName(name, false, loader)
                    } else {
                        Class.forName(name)
                    }
                } catch (_: Throwable) {
                    null
                } ?: continue
                try {
                    if (initializerHooked.get()) {
                        return
                    }
                    XposedApi.findAndHookConstructor(
                        cls,
                        Context::class.java,
                        object : SafeMethodHook(SOURCE) {
                            override fun afterHook(param: SafeMethodHook.HookParam) {
                                if (hooked.get()) return
                                val instance = param.thisObject ?: return
                                val connectivity = findConnectivityServiceInstance(instance) ?: return
                                installHooks(connectivity.javaClass, "initializer_ctor")
                            }
                        },
                    )
                    XposedApi.findAndHookMethod(
                        cls,
                        "onStart",
                        object : SafeMethodHook(SOURCE) {
                            override fun afterHook(param: SafeMethodHook.HookParam) {
                                if (hooked.get()) return
                                val instance = param.thisObject ?: return
                                val connectivity = findConnectivityServiceInstance(instance) ?: return
                                installHooks(connectivity.javaClass, "initializer")
                            }
                        },
                    )
                    initializerHooked.set(true)
                    HookErrorStore.i(
                        SOURCE,
                        "Hooked $name (ctor/onStart) via ${loader?.javaClass?.name ?: "null"}",
                    )
                    return
                } catch (e: Throwable) {
                    HookErrorStore.w(SOURCE, "Hook $name failed: ${e.message}", e)
                }
            }
        }
        HookErrorStore.d(SOURCE, "ConnectivityServiceInitializer not found in known classloaders")
    }

    private fun hookClassLoaderFallback() {
        if (classLoadUnhook != null) {
            return
        }
        try {
            classLoadUnhook = XposedApi.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : SafeMethodHook(SOURCE) {
                    override fun afterHook(param: SafeMethodHook.HookParam) {
                        val name = param.args[0] as? String ?: return
                        if (hooked.get()) {
                            XposedApi.unhook(classLoadUnhook)
                            classLoadUnhook = null
                            return
                        }
                        when {
                            name == "com.android.server.ConnectivityService" ||
                                name.endsWith(".com.android.server.ConnectivityService") -> {
                                val cls = param.result as? Class<*> ?: return
                                HookErrorStore.i(
                                    SOURCE,
                                    "ConnectivityService loaded via ${param.thisObject?.javaClass?.name}: $name",
                                )
                                installHooks(cls, "loadClass")
                                XposedApi.unhook(classLoadUnhook)
                                classLoadUnhook = null
                            }
                            name == "com.android.server.ConnectivityServiceInitializer" ||
                                name == "com.android.server.ConnectivityServiceInitializerB" -> {
                                if (sdkInt < 31) return
                                if (initializerHooked.get()) return
                                val cls = param.result as? Class<*> ?: return
                                HookErrorStore.i(
                                    SOURCE,
                                    "ConnectivityServiceInitializer loaded via ${param.thisObject?.javaClass?.name}",
                                )
                                hookConnectivityServiceInitializerClass(cls)
                            }
                        }
                    }
                },
            )
            HookErrorStore.i(SOURCE, "Hooked ClassLoader.loadClass for ConnectivityService")
        } catch (e: Throwable) {
            HookErrorStore.w(SOURCE, "Hook ClassLoader.loadClass failed: ${e.message}", e)
        }
    }

    private fun tryHookFromServiceManager() {
        if (hooked.get()) return
        val binder = try {
            checkServiceMethod.invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder
        } catch (_: Throwable) {
            null
        }
        if (binder != null) {
            HookErrorStore.i(
                SOURCE,
                "ConnectivityService binder from ServiceManager: ${binder.javaClass.name}",
            )
            installHooks(binder.javaClass, "ServiceManager.checkService")
            return
        }
        hookServiceManagerAddService()
    }

    private fun hookServiceManagerAddService() {
        if (!serviceManagerHooked.compareAndSet(false, true)) {
            return
        }
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            XposedApi.findAndHookMethod(
                serviceManager,
                "addService",
                String::class.java,
                IBinder::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : SafeMethodHook(SOURCE) {
                    override fun afterHook(param: SafeMethodHook.HookParam) {
                        if (hooked.get()) return
                        val name = param.args[0] as? String ?: return
                        if (name != Context.CONNECTIVITY_SERVICE) return
                        val binder = param.args[1] as? IBinder ?: return
                        HookErrorStore.i(
                            SOURCE,
                            "ConnectivityService registered: ${binder.javaClass.name}",
                        )
                        installHooks(binder.javaClass, "ServiceManager.addService")
                    }
                },
            )
            HookErrorStore.i(SOURCE, "Hooked ServiceManager.addService for ConnectivityService")
        } catch (e: Throwable) {
            HookErrorStore.w(SOURCE, "Hook ServiceManager.addService failed: ${e.message}", e)
        }
    }

    private fun hookOnTransactFallback() {
        if (onTransactUnhook != null) return
        try {
            val stub = XposedApi.findClass("android.net.IConnectivityManager\$Stub", classLoader)
            onTransactUnhook = XposedApi.findAndHookMethod(
                stub,
                "onTransact",
                Int::class.javaPrimitiveType,
                Parcel::class.java,
                Parcel::class.java,
                Int::class.javaPrimitiveType,
                object : SafeMethodHook(SOURCE) {
                    override fun beforeHook(param: SafeMethodHook.HookParam) {
                        if (hooked.get()) {
                            XposedApi.unhook(onTransactUnhook)
                            onTransactUnhook = null
                            return
                        }
                        val serviceClass = param.thisObject?.javaClass ?: return
                        HookErrorStore.i(
                            SOURCE,
                            "ConnectivityService discovered via onTransact: ${serviceClass.name}",
                        )
                        installHooks(serviceClass, "onTransact")
                        XposedApi.unhook(onTransactUnhook)
                        onTransactUnhook = null
                    }
                },
            )
            HookErrorStore.i(SOURCE, "Hooked IConnectivityManager.Stub.onTransact for discovery")
        } catch (e: Throwable) {
            HookErrorStore.w(SOURCE, "Hook onTransact fallback failed: ${e.message}", e)
        }
    }

    private fun hookConnectivityServiceInitializerClass(cls: Class<*>) {
        if (sdkInt < 31) return
        if (initializerHooked.get()) return
        try {
            XposedApi.findAndHookConstructor(
                cls,
                Context::class.java,
                object : SafeMethodHook(SOURCE) {
                    override fun afterHook(param: SafeMethodHook.HookParam) {
                        if (hooked.get()) return
                        val instance = param.thisObject ?: return
                        val connectivity = findConnectivityServiceInstance(instance) ?: return
                        installHooks(connectivity.javaClass, "initializer_ctor")
                    }
                },
            )
            XposedApi.findAndHookMethod(
                cls,
                "onStart",
                object : SafeMethodHook(SOURCE) {
                    override fun afterHook(param: SafeMethodHook.HookParam) {
                        if (hooked.get()) return
                        val instance = param.thisObject ?: return
                        val connectivity = findConnectivityServiceInstance(instance) ?: return
                        installHooks(connectivity.javaClass, "initializer")
                    }
                },
            )
            initializerHooked.set(true)
            HookErrorStore.i(SOURCE, "Hooked ${cls.name} (ctor/onStart) via loadClass")
        } catch (e: Throwable) {
            HookErrorStore.w(SOURCE, "Hook ${cls.name} via loadClass failed: ${e.message}", e)
        }
    }

    private fun findConnectivityServiceInstance(instance: Any): Any? {
        try {
            val direct = XposedApi.getObjectField(instance, "mConnectivity")
            if (direct != null) {
                return direct
            }
        } catch (_: Throwable) {
        }
        return try {
            val fields = instance.javaClass.declaredFields
            for (field in fields) {
                if (field.type.name.endsWith(".ConnectivityService")) {
                    field.isAccessible = true
                    val value = field.get(instance)
                    if (value != null) {
                        return value
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    // endregion

    // region Helper Methods

    fun shouldHide(connectivityService: Any?, uid: Int): Boolean {
        // connectivityService may be null for pure uid-based checks; still allow hide decisions.
        if (!PrivilegeSettingsStore.isEnabled()) {
            logSkipOnce(uid, "hide_disabled", "Skip hide: uid=$uid hide settings disabled")
            return false
        }
        if (!PrivilegeSettingsStore.isUidSelected(uid)) {
            logSkipOnce(uid, "hide_not_selected", "Skip hide: uid=$uid not in hide list")
            return false
        }
        if (VpnAppStore.isVpnUidExcludeSelf(uid)) {
            logSkipOnce(uid, "uid_vpn_app", "Skip hide: uid=$uid vpn app")
            return false
        }
        // Do NOT require hasVpnForUid here.
        // getNetworkInfo(TYPE_VPN)/getNetworkForType(TYPE_VPN) leak even when this uid is not
        // currently routed via VPN (per-app mode / transient states). Selected targets must
        // always have hard VPN fingerprints sanitized while hide is enabled.
        if (connectivityService != null) {
            val hasVpn = runCatching { hasVpnForUid(connectivityService, uid) }.getOrDefault(false)
            if (!hasVpn) {
                logSkipOnce(uid, "uid_no_vpn_soft", "Hide without vpnForUid: uid=$uid (still sanitize fingerprints)")
            }
        }
        return true
    }

    fun hasVpnForUid(connectivityService: Any?, uid: Int): Boolean {
        if (connectivityService == null) return false
        if (sdkInt >= 31) {
            val vpnForUidMethod = getVpnForUidMethod
            if (vpnForUidMethod != null) {
                return vpnForUidMethod.invoke(connectivityService, uid) != null
            }
        }
        @Suppress("UNCHECKED_CAST")
        val networks = getVpnUnderlyingNetworksMethod.invoke(connectivityService, uid) as? Array<Network>
        return networks != null && networks.isNotEmpty()
    }

    fun isVpnNetwork(connectivityService: Any?, network: Network): Boolean {
        if (connectivityService == null) return false
        val nai = getNetworkAgentInfoForNetworkMethod.invoke(connectivityService, network) ?: return false
        return isVpnNai(nai)
    }

    fun isVpnNai(nai: Any): Boolean = isVPNMethod.invoke(nai) as Boolean

    fun getUnderlyingNetwork(connectivityService: Any?, uid: Int): Network? {
        val nai = getUnderlyingNai(connectivityService, uid) ?: return null
        val method = networkMethod
        return if (method != null) {
            method.invoke(nai) as Network?
        } else {
            XposedApi.getObjectField(nai, "network") as? Network
        }
    }

    fun getUnderlyingLinkProperties(connectivityService: Any?, uid: Int): LinkProperties? {
        val nai = getUnderlyingNai(connectivityService, uid) ?: return null
        val lp = XposedApi.getObjectField(nai, "linkProperties") as LinkProperties? ?: return null
        return VpnSanitizer.cloneLinkProperties(lp)
    }

    fun getUnderlyingNetworkInfo(connectivityService: Any?, uid: Int): NetworkInfo? {
        val nai = getUnderlyingNai(connectivityService, uid) ?: return null
        val method = getFilteredNetworkInfoMethod
        if (method != null) {
            return method.invoke(connectivityService, nai, uid, false) as NetworkInfo?
        }
        return XposedApi.getObjectField(nai, "networkInfo") as? NetworkInfo
    }

    fun getUnderlyingNai(connectivityService: Any?, uid: Int): Any? {
        if (connectivityService == null) return null
        @Suppress("UNCHECKED_CAST")
        val networks = getVpnUnderlyingNetworksMethod.invoke(connectivityService, uid) as? Array<Network>
        if (networks != null && networks.isNotEmpty()) {
            return getNetworkAgentInfoForNetworkMethod.invoke(connectivityService, networks[0])
        }
        val defaultNai = getDefaultNetworkMethod.invoke(connectivityService)
        if (defaultNai != null && !isVpnNai(defaultNai)) {
            return defaultNai
        }
        return null
    }

    private fun findDeclaredMethod(target: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = target
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun requireDeclaredMethod(target: Class<*>, name: String, vararg parameterTypes: Class<*>): Method = findDeclaredMethod(target, name, *parameterTypes)
        ?: throw NoSuchMethodException("${target.name}#$name")

    /**
     * Resolves a class from the Connectivity module, handling APEX package rewriting.
     *
     * When the Connectivity module runs as an APEX (Android 12+), all classes get prefixed
     * with "android.net.connectivity.". This method derives the correct prefix from
     * the already-loaded ConnectivityService class.
     *
     * @param simpleClassName Simple class name (e.g., "ProxyTracker")
     * @param subPackage Sub-package under com.android.server (e.g., "connectivity"), or null
     */
    fun resolveConnectivityModuleClass(simpleClassName: String, subPackage: String? = null): Class<*> {
        val base = cls.name
        val serverPackage = if (base.endsWith(".ConnectivityService")) {
            base.removeSuffix(".ConnectivityService")
        } else {
            base.substringBeforeLast(".ConnectivityService", base)
        }

        val fullClassName = if (subPackage != null) {
            "$serverPackage.$subPackage.$simpleClassName"
        } else {
            "$serverPackage.$simpleClassName"
        }

        return XposedApi.findClass(fullClassName, connectivityClassLoader)
    }

    fun resolveNriAndNaiClasses(): Pair<Class<*>, Class<*>> {
        val nriClass = XposedApi.findClass(
            cls.name + '$' + "NetworkRequestInfo",
            connectivityClassLoader,
        )
        val naiClass = resolveConnectivityModuleClass("NetworkAgentInfo", "connectivity")
        return Pair(nriClass, naiClass)
    }

    fun getAsUid(nri: Any): Int {
        val fieldName = if (sdkInt >= 31) "mAsUid" else "mUid"
        return XposedApi.getIntField(nri, fieldName)
    }

    fun logSkipOnce(uid: Int, reason: String, message: String) {
        val key = "$uid:$reason"
        if (skipLogKeys.putIfAbsent(key, true) == null) {
            HookErrorStore.d(SOURCE, message)
        }
    }

    // endregion
}

