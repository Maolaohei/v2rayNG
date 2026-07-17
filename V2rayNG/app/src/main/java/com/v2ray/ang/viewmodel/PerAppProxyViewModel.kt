package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.xposed.PrivilegeSettingsClient

class PerAppProxyViewModel : ViewModel() {
    private var packageSetKey: String = AppConfig.PREF_PER_APP_PROXY_SET
    private var privilegeHideVpnMode: Boolean = false
    private val selectedPackages: MutableSet<String> = HashSet()
    private var initialized: Boolean = false

    fun configureMode(privilegeHideVpnPicker: Boolean) {
        val targetKey = if (privilegeHideVpnPicker) {
            AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS
        } else {
            AppConfig.PREF_PER_APP_PROXY_SET
        }
        if (initialized && privilegeHideVpnMode == privilegeHideVpnPicker && packageSetKey == targetKey) {
            return
        }
        privilegeHideVpnMode = privilegeHideVpnPicker
        packageSetKey = targetKey
        selectedPackages.clear()
        MmkvManager.decodeSettingsStringSet(packageSetKey)?.let { selectedPackages.addAll(it) }
        initialized = true
    }

    fun isPrivilegeHideVpnMode(): Boolean = privilegeHideVpnMode

    fun contains(packageName: String): Boolean = selectedPackages.contains(packageName)

    fun getAll(): Set<String> = selectedPackages.toSet()

    fun selectedCount(): Int = selectedPackages.size

    fun add(packageName: String): Boolean {
        val changed = selectedPackages.add(packageName)
        if (changed) {
            save()
        }
        return changed
    }

    fun remove(packageName: String): Boolean {
        val changed = selectedPackages.remove(packageName)
        if (changed) {
            save()
        }
        return changed
    }

    fun toggle(packageName: String) {
        if (selectedPackages.contains(packageName)) {
            remove(packageName)
        } else {
            add(packageName)
        }
    }

    fun addAll(packages: Collection<String>) {
        if (selectedPackages.addAll(packages)) {
            save()
        }
    }

    fun removeAll(packages: Collection<String>) {
        if (selectedPackages.removeAll(packages.toSet())) {
            save()
        }
    }

    fun clear() {
        if (selectedPackages.isNotEmpty()) {
            selectedPackages.clear()
            save()
        }
    }

    private fun save() {
        ensureInitialized()
        MmkvManager.encodeSettings(packageSetKey, selectedPackages)
        if (privilegeHideVpnMode) {
            // Push hidevpn target list into system_server; no VPN hard-restart needed.
            PrivilegeSettingsClient.sync()
        } else {
            SettingsChangeManager.makeHardRestartService()
        }
    }

    private fun ensureInitialized() {
        if (!initialized) {
            configureMode(false)
        }
    }
}
