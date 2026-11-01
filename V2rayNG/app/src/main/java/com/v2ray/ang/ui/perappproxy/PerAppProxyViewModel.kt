package com.v2ray.ang.ui.perappproxy

import android.app.Application
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.AppSelection
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.UidGroup
import com.v2ray.ang.util.UidGroups
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * ViewModel for PerAppProxy screen.
 * Holds all UI state and business logic.
 *
 * Per-app VPN rules are UID-scoped, so the stored selection is always kept group-complete:
 * a shared UID is either fully selected or not selected at all. Half-selected groups left behind
 * by older versions are completed on load, which changes no traffic — the kernel already applied
 * them to the whole group.
 */
class PerAppProxyViewModel(application: Application) : BaseViewModel(application) {

    // Blacklist (apps to be proxied or bypassed)
    private val _blacklist = MutableStateFlow(loadBlacklist())
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    // UI states
    private val _displayedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayedApps: StateFlow<List<AppInfo>> = _displayedApps.asStateFlow()

    private val _perAppProxyEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
    )
    val perAppProxyEnabled: StateFlow<Boolean> = _perAppProxyEnabled.asStateFlow()

    private val _bypassApps = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)
    )
    val bypassApps: StateFlow<Boolean> = _bypassApps.asStateFlow()

    // Shared UID grouping, published for the list rows
    private val _uidGroups = MutableStateFlow<Map<Int, UidGroup>>(emptyMap())
    val uidGroups: StateFlow<Map<Int, UidGroup>> = _uidGroups.asStateFlow()

    private val _pkgToUid = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pkgToUid: StateFlow<Map<String, Int>> = _pkgToUid.asStateFlow()

    // Group shown in the shared-UID dialog
    private val _peers = MutableStateFlow<UidGroup?>(null)
    val peers: StateFlow<UidGroup?> = _peers.asStateFlow()

    // Cached full list for filtering
    private var appsAll: List<AppInfo>? = null
    private var currentQuery = ""
    private var isAppListLoading = false

    // Blacklist operations
    fun toggle(packageName: String) {
        val current = _blacklist.value
        // One tap always flips the whole UID group; splitting it would be a state the kernel
        // cannot express and would make the checkbox lie about what is tunnelled.
        val targets = groupOf(packageName)?.packages?.toSet() ?: setOf(packageName)
        val allSelected = targets.all { it in current }
        replaceBlacklist(if (allSelected) current - targets else current + targets)
        SettingsChangeManager.makeRestartService()
    }

    /** Explicit whole-group switch used by the shared-UID dialog. */
    fun setGroupSelected(uid: Int, selected: Boolean) {
        val group = _uidGroups.value[uid] ?: return
        val current = _blacklist.value
        replaceBlacklist(
            if (selected) current + group.packages else current - group.packages.toSet()
        )
        SettingsChangeManager.makeRestartService()
    }

    fun showPeers(packageName: String) {
        _peers.value = groupOf(packageName)
    }

    fun dismissPeers() {
        _peers.value = null
    }

    private fun groupOf(packageName: String): UidGroup? {
        val uid = _pkgToUid.value[packageName] ?: return null
        return _uidGroups.value[uid]
    }

    private fun loadBlacklist(): Set<String> {
        return MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet()
            ?: emptySet()
    }

    private fun replaceBlacklist(newBlacklist: Set<String>) {
        // Single choke point: every write leaves the selection group-complete.
        val normalized = UidGroups.normalize(newBlacklist, _uidGroups.value, _pkgToUid.value)
        if (normalized == _blacklist.value) return

        _blacklist.value = normalized
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, normalized.toMutableSet())
    }

    // Per‑app proxy switch
    fun setPerAppProxyEnabled(enabled: Boolean) {
        if (_perAppProxyEnabled.value != enabled) {
            _perAppProxyEnabled.value = enabled
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, enabled)
        }
    }

    fun setBypassAppsEnabled(enabled: Boolean) {
        if (_bypassApps.value != enabled) {
            _bypassApps.value = enabled
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, enabled)
        }
    }

    // Load and filter apps
    fun loadApps(context: Context) {
        if (appsAll != null || isAppListLoading) return

        val applicationContext = context.applicationContext
        isAppListLoading = true
        launchLoading {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val list = AppManagerUtil.loadNetworkAppList(applicationContext)
                    sortApps(list)
                }
                appsAll = apps
                _uidGroups.value = UidGroups.of(apps)
                _pkgToUid.value = UidGroups.packageToUid(apps)
                migrateLegacySelection()
                _displayedApps.value = applyFilter(currentQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Error loading apps", e)
            } finally {
                isAppListLoading = false
            }
        }
    }

    /**
     * Completes selections saved by versions that could store a half-selected shared UID.
     *
     * The tunnel already applied the whole UID before this fix, so this only aligns the stored
     * selection and the UI with the traffic that was really happening.
     */
    private fun migrateLegacySelection() {
        val before = _blacklist.value
        if (before.isEmpty()) return

        val normalized = UidGroups.normalize(before, _uidGroups.value, _pkgToUid.value)
        if (normalized == before) return

        val splits = UidGroups.splitGroups(before, _uidGroups.value)
        LogUtil.d(
            AppConfig.TAG,
            "PerAppProxy: normalized ${splits.size} shared-UID group(s), " +
                "${before.size} -> ${normalized.size} package(s)"
        )
        replaceBlacklist(normalized)
        toast(getString(R.string.per_app_uid_normalized_toast, normalized.size - before.size))
    }

    fun filterApps(query: String) {
        currentQuery = query
        _displayedApps.value = applyFilter(query)
    }

    private fun applyFilter(query: String): List<AppInfo> {
        val apps = appsAll ?: return emptyList()
        if (query.isEmpty()) return apps

        return apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    private fun sortApps(apps: List<AppInfo>): List<AppInfo> {
        val collator = Collator.getInstance()
        val selectedPackages = _blacklist.value
        return apps.sortedWith { p1, p2 ->
            val s1 = p1.packageName in selectedPackages
            val s2 = p2.packageName in selectedPackages
            when {
                s1 && !s2 -> -1
                !s1 && s2 -> 1
                p1.isSystemApp && !p2.isSystemApp -> 1
                !p1.isSystemApp && p2.isSystemApp -> -1
                else -> collator.compare(p1.appName, p2.appName)
            }
        }
    }

    // Bulk actions
    fun selectAll() {
        val displayedApps = _displayedApps.value
        val currentSelection = _blacklist.value
        val allSelected = displayedApps.all { it.packageName in currentSelection }
        val newSelection = currentSelection.toMutableSet().apply {
            displayedApps.forEach { app ->
                if (allSelected) remove(app.packageName) else add(app.packageName)
            }
        }
        replaceBlacklist(newSelection)
        enablePerAppProxyAndRestart()
    }

    fun invertSelection() {
        val displayed = _displayedApps.value.map { it.packageName }
        // Shared UID packages cannot be inverted one by one, so they are left untouched.
        val invertible = UidGroups.invertiblePackages(displayed, _uidGroups.value, _pkgToUid.value)
        val skipped = _uidGroups.value.values.count {
            it.isShared && it.packages.any { packageName -> packageName in displayed }
        }
        replaceBlacklist(AppSelection.invert(_blacklist.value, invertible))
        if (skipped > 0) {
            toast(getString(R.string.per_app_uid_invert_skipped, skipped))
        }
        enablePerAppProxyAndRestart()
    }

    fun selectProxyAppAuto(context: Context) {
        val applicationContext = context.applicationContext
        launchLoading {
            val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
            var content = withContext(Dispatchers.IO) {
                HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 5000
                    )
                )
            }
            if (content.isNullOrEmpty()) {
                val proxyUsername = SettingsManager.getSocksUsername()
                val proxyPassword = SettingsManager.getSocksPassword()
                val httpPort = SettingsManager.getHttpPort()
                content = withContext(Dispatchers.IO) {
                    HttpUtil.getUrlContent(
                        UrlContentRequest(
                            url = url,
                            timeout = 5000,
                            httpPort = httpPort,
                            proxyUsername = proxyUsername,
                            proxyPassword = proxyPassword
                        )
                    )
                } ?: ""
            }
            val success = applyProxyAppList(
                content = content,
                context = applicationContext,
                forceGoogleApps = true
            )
            if (success) {
                enablePerAppProxyAndRestart()
            }
        }
    }

    fun importProxyApp(content: String?, context: Context) {
        if (content.isNullOrEmpty()) return

        val applicationContext = context.applicationContext
        launchLoading {
            val success = applyProxyAppList(
                content = content,
                context = applicationContext,
                forceGoogleApps = false
            )
            if (success) {
                enablePerAppProxyAndRestart()
            }
        }
    }

    fun exportProxyApp(): String {
        return buildString {
            append(_bypassApps.value)
            _blacklist.value.forEach { packageName ->
                append(System.lineSeparator())
                append(packageName)
            }
        }
    }

    private suspend fun applyProxyAppList(content: String, context: Context, forceGoogleApps: Boolean): Boolean {
        val installedApps = appsAll ?: return false

        try {
            val proxyAppList = if (content.isEmpty()) {
                withContext(Dispatchers.IO) {
                    Utils.readTextFromAssets(context, "proxy_package_name")
                }
            } else content
            if (proxyAppList.isNullOrEmpty()) return false

            val bypassApps = _bypassApps.value
            val selected = withContext(Dispatchers.Default) {
                AppSelection.fromProxyList(
                    packageNames = installedApps.map { it.packageName },
                    proxyAppList = proxyAppList,
                    bypassApps = bypassApps,
                    forceGoogleApps = forceGoogleApps
                )
            }
            // Imported names may name only one member of a shared UID; keep the group complete.
            val newBlacklist = UidGroups.normalize(selected, _uidGroups.value, _pkgToUid.value)
            replaceBlacklist(newBlacklist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun enablePerAppProxyAndRestart() {
        setPerAppProxyEnabled(true)
        SettingsChangeManager.makeRestartService()
    }
}
