package com.v2ray.ang.xposed

import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object PrivilegeSettingsStore {
    private const val SETTINGS_DIR = "/data/system/v2rayng"
    private const val SETTINGS_FILE = "privilege_hidevpn.conf"

    @Volatile
    private var enabled = false

    @Volatile
    private var packageSet: Set<String> = emptySet()

    @Volatile
    private var interfaceRenameEnabled = false

    @Volatile
    private var interfacePrefix = "en"

    @Volatile
    private var hideSelfPackage = false

    @Volatile
    private var loadedFromDisk = false

    private val uidCache = ConcurrentHashMap<Int, Boolean>()

    @Volatile
    var onUpdated: (() -> Unit)? = null

    private val appGlobalsClass by lazy { Class.forName("android.app.AppGlobals") }
    private val getPackageManagerMethod by lazy { appGlobalsClass.getMethod("getPackageManager") }
    private var getPackagesForUidMethod: Method? = null

    fun update(
        enabled: Boolean,
        packages: Set<String>,
        interfaceRenameEnabled: Boolean,
        interfacePrefix: String,
        hideSelfPackage: Boolean = this.hideSelfPackage,
    ) {
        this.enabled = enabled
        packageSet = packages
        this.interfaceRenameEnabled = interfaceRenameEnabled
        this.interfacePrefix = normalizePrefix(interfacePrefix)
        this.hideSelfPackage = hideSelfPackage
        loadedFromDisk = true
        uidCache.clear()
        HookErrorStore.i(
            "PrivilegeSettingsStore",
            "PrivilegeSettings updated: enabled=$enabled size=${packages.size} rename=$interfaceRenameEnabled prefix=${this.interfacePrefix} hideSelf=$hideSelfPackage",
        )
        writeSettingsFile()
        runCatching { onUpdated?.invoke() }
    }

    fun isEnabled(): Boolean {
        ensureLoaded()
        return enabled
    }

    fun shouldRenameInterface(): Boolean {
        ensureLoaded()
        return interfaceRenameEnabled
    }

    fun interfacePrefix(): String {
        ensureLoaded()
        return interfacePrefix
    }

    fun shouldHideSelfPackage(): Boolean {
        ensureLoaded()
        return hideSelfPackage
    }

    fun isPackageSelected(packageName: String): Boolean {
        ensureLoaded()
        return packageSet.contains(packageName)
    }

    fun isUidSelected(uid: Int): Boolean {
        ensureLoaded()
        val cached = uidCache[uid]
        if (cached != null) {
            return cached
        }
        val selected = getPackagesForUid(uid).any { packageSet.contains(it) }
        uidCache[uid] = selected
        return selected
    }

    fun shouldHideUid(uid: Int): Boolean {
        if (!isEnabled()) {
            return false
        }
        return isUidSelected(uid)
    }

    private fun ensureLoaded() {
        if (loadedFromDisk) return
        synchronized(this) {
            if (loadedFromDisk) return
            loadSettingsFile()
            loadedFromDisk = true
        }
    }

    private fun normalizePrefix(prefix: String): String {
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) {
            return "en"
        }
        val filtered = buildString(trimmed.length) {
            for (ch in trimmed) {
                if (ch.isLetterOrDigit() || ch == '_') {
                    append(ch)
                }
            }
        }
        return if (filtered.isEmpty()) "en" else filtered
    }

    private fun settingsFile(): File = File(File(SETTINGS_DIR), SETTINGS_FILE)

    private fun loadSettingsFile() {
        try {
            val file = settingsFile()
            if (!file.exists()) {
                HookErrorStore.i("PrivilegeSettingsStore", "no persisted privilege settings yet")
                return
            }
            var en = false
            var rename = false
            var prefix = "en"
            var hideSelf = false
            var packages = emptySet<String>()
            file.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when (key) {
                    "enabled" -> en = value == "1" || value.equals("true", ignoreCase = true)
                    "rename" -> rename = value == "1" || value.equals("true", ignoreCase = true)
                    "prefix" -> prefix = normalizePrefix(value)
                    "hide_self" -> hideSelf = value == "1" || value.equals("true", ignoreCase = true)
                    "packages" -> {
                        packages = if (value.isEmpty()) {
                            emptySet()
                        } else {
                            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        }
                    }
                }
            }
            enabled = en
            interfaceRenameEnabled = rename
            interfacePrefix = prefix
            hideSelfPackage = hideSelf
            packageSet = packages
            uidCache.clear()
            HookErrorStore.i(
                "PrivilegeSettingsStore",
                "loaded privilege settings: enabled=$enabled size=${packageSet.size} rename=$interfaceRenameEnabled prefix=$interfacePrefix hideSelf=$hideSelfPackage",
            )
        } catch (e: Throwable) {
            HookErrorStore.e("PrivilegeSettingsStore", "Failed to load privilege settings file", e)
        }
    }

    private fun writeSettingsFile() {
        try {
            val dir = File(SETTINGS_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                HookErrorStore.e("PrivilegeSettingsStore", "Failed to create settings dir: ${dir.path}")
                return
            }
            val file = File(dir, SETTINGS_FILE)
            val packagesLine = packageSet.sorted().joinToString(",")
            val content = buildString {
                append("version=1\n")
                append("enabled=")
                append(if (enabled) "1" else "0")
                append('\n')
                append("rename=")
                append(if (interfaceRenameEnabled) "1" else "0")
                append('\n')
                append("prefix=")
                append(interfacePrefix)
                append('\n')
                append("hide_self=")
                append(if (hideSelfPackage) "1" else "0")
                append('\n')
                append("packages=")
                append(packagesLine)
                append('\n')
            }
            file.writeText(content)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (e: Throwable) {
            HookErrorStore.e("PrivilegeSettingsStore", "Failed to write privilege settings file", e)
        }
    }

    private fun getPackagesForUid(uid: Int): List<String> {
        val pm = getPackageManager() ?: return emptyList()
        return try {
            val method = getPackagesForUidMethod ?: run {
                pm.javaClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType).also {
                    getPackagesForUidMethod = it
                }
            }
            val result = method.invoke(pm, uid)
            when (result) {
                is Array<*> -> result.filterIsInstance<String>()
                is List<*> -> result.filterIsInstance<String>()
                else -> emptyList()
            }
        } catch (e: Throwable) {
            HookErrorStore.e("PrivilegeSettingsStore", "getPackagesForUid failed for uid=$uid", e)
            emptyList()
        }
    }

    private fun getPackageManager(): Any? = try {
        getPackageManagerMethod.invoke(null)
    } catch (e: Throwable) {
        HookErrorStore.e("PrivilegeSettingsStore", "getPackageManager failed", e)
        null
    }
}
