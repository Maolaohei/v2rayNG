package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MmkvPreferenceDataStore
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.xposed.DetectionResult
import com.v2ray.ang.xposed.PrivilegePortsManager
import com.v2ray.ang.xposed.PrivilegeSettingsClient
import com.v2ray.ang.xposed.VpnDetectionTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_settings, showHomeAsUp = true, title = getString(R.string.title_settings))
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_settings, fragment)
            .addToBackStack(pref.key)
            .commit()
        supportActionBar?.title = pref.title
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            supportActionBar?.title = getString(R.string.title_settings)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val localDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_LOCAL_DNS_ENABLED) }
        private val fakeDns by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FAKE_DNS_ENABLED) }
        private val appendHttpProxy by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_APPEND_HTTP_PROXY) }

        //        private val localDnsPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_LOCAL_DNS_PORT) }
        private val vpnDns by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_DNS) }
        private val vpnBypassLan by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_BYPASS_LAN) }
        private val vpnInterfaceAddress by lazy { findPreference<ListPreference>(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX) }
        private val vpnMtu by lazy { findPreference<EditTextPreference>(AppConfig.PREF_VPN_MTU) }

        private val mux by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_MUX_ENABLED) }
        private val muxConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_CONCURRENCY) }
        private val muxXudpConcurrency by lazy { findPreference<EditTextPreference>(AppConfig.PREF_MUX_XUDP_CONCURRENCY) }
        private val muxXudpQuic by lazy { findPreference<ListPreference>(AppConfig.PREF_MUX_XUDP_QUIC) }

        private val fragment by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_FRAGMENT_ENABLED) }
        private val fragmentPackets by lazy { findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS) }
        private val fragmentLength by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH) }
        private val fragmentInterval by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL) }
        private val fragmentMaxSplit by lazy { findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_MAXSPLIT) }

        private val mode by lazy { findPreference<ListPreference>(AppConfig.PREF_MODE) }
        private val lanSharing by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ROOT_LAN_SHARING) }
        private val privilegeCategory by lazy { findPreference<PreferenceCategory>("pref_category_privilege") }
        private val privilegeHideVpn by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_PRIVILEGE_HIDE_VPN) }
        private val privilegeHideSelfPackage by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_PRIVILEGE_HIDE_SELF_PACKAGE) }
        private val privilegeIfaceRename by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_PRIVILEGE_IFACE_RENAME) }
        private val privilegeIfacePrefix by lazy { findPreference<EditTextPreference>(AppConfig.PREF_PRIVILEGE_IFACE_PREFIX) }
        private val privilegePorts by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_PRIVILEGE_PORTS) }

        private val hevTunLogLevel by lazy { findPreference<ListPreference>(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) }
        private val hevTunRwTimeout by lazy { findPreference<EditTextPreference>(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) }
        private val useHevTun by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_USE_HEV_TUNNEL) }

        private val enableLocalProxy by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_ENABLE_LOCAL_PROXY) }
        private val socksPort by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_PORT) }
        private val dynamicSocksPort by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_DYNAMIC_SOCKS_PORT) }
        private val socksUsername by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_USERNAME) }
        private val socksPassword by lazy { findPreference<EditTextPreference>(AppConfig.PREF_SOCKS_PASSWORD) }
        private val socksEnableUdp by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_SOCKS_ENABLE_UDP) }
        private val proxySharing by lazy { findPreference<CheckBoxPreference>(AppConfig.PREF_PROXY_SHARING) }

        /**
         * Prefer parent fragment callback (More tab), then Activity (standalone settings).
         */
        override fun getCallbackFragment(): Fragment? {
            return parentFragment
        }

        override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
            // Use MMKV as the storage backend for all Preferences
            // This prevents inconsistencies between SharedPreferences and MMKV
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()

            // rootKey enables nested PreferenceScreen navigation (secondary pages).
            setPreferencesFromResource(R.xml.pref_settings, rootKey)

            initPreferenceSummaries()

            localDns?.setOnPreferenceChangeListener { _, any ->
                updateLocalDns(any as Boolean)
                // Local DNS changes system VpnService DNS list; soft-restart is not enough.
                SettingsChangeManager.makeHardRestartService()
                true
            }

            fakeDns?.setOnPreferenceChangeListener { _, _ ->
                SettingsChangeManager.makeHardRestartService()
                true
            }

            vpnInterfaceAddress?.setOnPreferenceChangeListener { _, _ ->
                SettingsChangeManager.makeHardRestartService()
                true
            }

            vpnMtu?.setOnPreferenceChangeListener { _, _ ->
                SettingsChangeManager.makeHardRestartService()
                true
            }

            mux?.setOnPreferenceChangeListener { _, newValue ->
                updateMux(newValue as Boolean)
                true
            }
            muxConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxConcurrency(newValue as String)
                true
            }
            muxXudpConcurrency?.setOnPreferenceChangeListener { _, newValue ->
                updateMuxXudpConcurrency(newValue as String)
                true
            }

            fragment?.setOnPreferenceChangeListener { _, newValue ->
                updateFragment(newValue as Boolean)
                true
            }

            mode?.setOnPreferenceChangeListener { pref, newValue ->
                val valueStr = newValue.toString()
                (pref as? ListPreference)?.let { lp ->
                    val idx = lp.findIndexOfValue(valueStr)
                    lp.summary = if (idx >= 0) lp.entries[idx] else valueStr
                }
                // Proxy/VPN only. SFA-style hidevpn is independent LSPosed hardening.
                val next = if (valueStr == VPN) AppConfig.VPN else AppConfig.MODE_PROXY_ONLY
                val changed = SettingsManager.setRunMode(next)
                updateMode(valueStr)
                if (changed) {
                    hardRestartIfLive()
                }
                true
            }

            mode?.dialogLayoutResource = R.layout.preference_with_help_link

            useHevTun?.setOnPreferenceChangeListener { _, newValue ->
                updateHevTunSettings(newValue as Boolean)
                // Hev on/off switches dataplane (SOCKS bridge vs Xray TUN) and needs hard reapply.
                SettingsChangeManager.makeHardRestartService()
                true
            }

            enableLocalProxy?.setOnPreferenceChangeListener { _, newValue ->
                updateEnableLocalProxy(newValue as Boolean)
                true
            }

            dynamicSocksPort?.setOnPreferenceChangeListener { _, newValue ->
                updateDynamicSocksPort(newValue as Boolean)
                true
            }

            lanSharing?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !RootManager.cachedRoot()) {
                    lifecycleScope.launch {
                        if (checkAndRequestRoot()) {
                            lanSharing?.isChecked = true
                        }
                    }
                    false
                } else {
                    true
                }
            }

        }
    private fun initPreferenceSummaries() {
            fun updateSummary(pref: androidx.preference.Preference) {
                when (pref) {
                    is EditTextPreference -> {
                        if (pref.key == AppConfig.PREF_SOCKS_PASSWORD) {
                            pref.summary = if (pref.text.isNullOrEmpty()) "" else "******"
                        } else {
                            pref.summary = pref.text.orEmpty()
                        }
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            if (p.key == AppConfig.PREF_SOCKS_PASSWORD) {
                                p.summary = if ((newValue as? String).isNullOrEmpty()) "" else "******"
                            } else {
                                p.summary = (newValue as? String).orEmpty()
                            }
                            true
                        }
                    }

                    is ListPreference -> {
                        pref.summary = pref.entry ?: ""
                        pref.setOnPreferenceChangeListener { p, newValue ->
                            val lp = p as ListPreference
                            val idx = lp.findIndexOfValue(newValue as? String)
                            lp.summary = (if (idx >= 0) lp.entries[idx] else newValue) as CharSequence?
                            true
                        }
                    }

                    is CheckBoxPreference, is androidx.preference.SwitchPreferenceCompat -> {
                    }
                }
            }

            fun traverse(group: androidx.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    when (val p = group.getPreference(i)) {
                        is androidx.preference.PreferenceGroup -> traverse(p)
                        else -> updateSummary(p)
                    }
                }
            }

            preferenceScreen?.let { traverse(it) }
            setupToolEntryClicks()
            setupPrivilegePrefs()
        }

        private suspend fun checkAndRequestRoot(): Boolean {
            val hasRoot = RootManager.refresh()
            if (!isAdded) return false
            if (!hasRoot) {
                context?.toastError(R.string.toast_root_required)
            }
            return hasRoot
        }

        override fun onStart() {
            super.onStart()
            refreshPrivilegeSummaries()
            updateHevTunSettings(MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, false))

            // Initialize mode-dependent UI states.
            SettingsManager.migrateRootModeIfUiHidden()
            privilegeCategory?.isVisible = true
            lanSharing?.isVisible = AppConfig.ROOT_MODE_UI_ENABLED
            updateMode(MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, VPN))

            // Initialize local proxy state
            updateEnableLocalProxy(MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true))

            // Initialize mux-dependent UI states
            updateMux(MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false))

            // Initialize fragment-dependent UI states
            updateFragment(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false))

            updateDynamicSocksPort(MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false))
        }

        private fun updateMode(value: String?) {
            SettingsManager.migrateRootModeIfUiHidden()
            // ROOT (if re-enabled) still disables VPN-only prefs; hidevpn is separate LSPosed path.
            val root = AppConfig.ROOT_MODE_UI_ENABLED && SettingsManager.isRootMode()
            val vpn = value == VPN && !root
            privilegeCategory?.isVisible = true
            // Settings mode is Proxy/VPN only while hide-VPN is off.
            mode?.isEnabled = !root
            mode?.summary = if (root) {
                getString(R.string.home_mode_root_settings_summary)
            } else {
                mode?.entry ?: value
            }
            // ROOT-only extras.
            lanSharing?.isVisible = AppConfig.ROOT_MODE_UI_ENABLED
            lanSharing?.isEnabled = root
            localDns?.isEnabled = vpn
            fakeDns?.isEnabled = vpn
            appendHttpProxy?.isEnabled = vpn
//            localDnsPort?.isEnabled = vpn
            vpnDns?.isEnabled = vpn
            vpnBypassLan?.isEnabled = vpn
            vpnInterfaceAddress?.isEnabled = vpn
            vpnMtu?.isEnabled = vpn
            useHevTun?.isEnabled = vpn
            // ROOT freezes dynamic SOCKS so hev/iptables keep a stable local port.
            dynamicSocksPort?.isEnabled = !root && (enableLocalProxy?.isChecked != false)
            if (root) {
                dynamicSocksPort?.summary = getString(R.string.summary_root_dynamic_socks_disabled)
            }
            updateHevTunSettings(false)
            if (vpn) {
                updateLocalDns(
                    MmkvManager.decodeSettingsBool(
                        AppConfig.PREF_LOCAL_DNS_ENABLED,
                        false
                    )
                )
                updateHevTunSettings(
                    MmkvManager.decodeSettingsBool(
                        AppConfig.PREF_USE_HEV_TUNNEL,
                        false
                    )
                )
            }
            // Restore dynamic-socks summary when leaving ROOT.
            if (!root) {
                updateDynamicSocksPort(MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false))
            }
        }


        
        private val privilegeManageApps by lazy { findPreference<Preference>("pref_entry_privilege_manage_apps") }
        private val privilegeModuleStatus by lazy { findPreference<Preference>("pref_entry_privilege_module_status") }
        private val privilegeSelfTest by lazy { findPreference<Preference>("pref_entry_privilege_self_test") }
        @Volatile private var privilegeSelfTestRunning = false
        private var hardRestartRunnable: Runnable? = null

        private fun setupPrivilegePrefs() {
            privilegeHideVpn?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                // Persist happens via PreferenceDataStore; force sync after change.
                view?.post {
                    if (enabled) {
                        val count = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)?.size ?: 0
                        if (count == 0) {
                            context?.let {
                                android.widget.Toast.makeText(
                                    it,
                                    R.string.privilege_empty_target_warning,
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    val ok = PrivilegeSettingsClient.sync()
                    runCatching {
                        PrivilegePortsManager.applyFromPrefs(requireContext().applicationContext)
                    }
                    context?.let {
                        if (ok) {
                            android.widget.Toast.makeText(it, R.string.toast_privilege_sync_ok, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(it, R.string.toast_privilege_sync_fail, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    refreshPrivilegeSummaries()
                }
                true
            }
            privilegeHideSelfPackage?.setOnPreferenceChangeListener { _, _ ->
                view?.post {
                    PrivilegeSettingsClient.sync()
                    refreshPrivilegeSummaries()
                }
                true
            }
            privilegeIfaceRename?.setOnPreferenceChangeListener { _, newValue ->
                view?.post {
                    // Persist already done by PreferenceDataStore; ensure sync before recreate.
                    PrivilegeSettingsClient.sync()
                    refreshPrivilegeSummaries()
                    // Create-time rename only applies when system_server names a new iface.
                    // Re-toggle VPN so jniGetName runs again with the new flag/prefix.
                    hardRestartIfLive()
                }
                true
            }
            privilegeIfacePrefix?.setOnPreferenceChangeListener { _, _ ->
                view?.post {
                    PrivilegeSettingsClient.sync()
                    refreshPrivilegeSummaries()
                    hardRestartIfLive()
                }
                true
            }
            privilegePorts?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                view?.post {
                    MmkvManager.encodeSettings(AppConfig.PREF_PRIVILEGE_PORTS, enabled)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            PrivilegePortsManager.applyFromPrefs(requireContext().applicationContext)
                        }
                        if (!isAdded) return@launch
                        context?.let {
                            val msg = when {
                                !enabled && ok -> R.string.toast_privilege_ports_cleared
                                enabled && ok -> R.string.toast_privilege_ports_applied
                                else -> R.string.toast_privilege_ports_failed
                            }
                            android.widget.Toast.makeText(it, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        refreshPrivilegeSummaries()
                    }
                }
                true
            }
            privilegeManageApps?.setOnPreferenceClickListener {
                // Dedicated hidevpn package set (not per-app proxy set).
                startActivity(Intent(requireContext(), PerAppProxyActivity::class.java).apply {
                    putExtra(PerAppProxyActivity.EXTRA_PRIVILEGE_HIDE_VPN_PICKER, true)
                })
                true
            }
            privilegeModuleStatus?.setOnPreferenceClickListener {
                val probe = PrivilegeSettingsClient.refresh()
                val msg = when (probe.result) {
                    PrivilegeSettingsClient.ProbeResult.ACTIVE ->
                        getString(R.string.toast_privilege_module_active)
                    PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE ->
                        getString(R.string.toast_privilege_module_loaded_inactive)
                    PrivilegeSettingsClient.ProbeResult.TRANSACTION_UNHANDLED ->
                        getString(R.string.toast_privilege_module_unhandled)
                    PrivilegeSettingsClient.ProbeResult.UNAUTHORIZED ->
                        getString(R.string.toast_privilege_module_unauthorized)
                    PrivilegeSettingsClient.ProbeResult.BINDER_UNAVAILABLE ->
                        getString(R.string.toast_privilege_module_binder)
                    PrivilegeSettingsClient.ProbeResult.ERROR ->
                        getString(R.string.toast_privilege_module_error, probe.detail ?: "unknown")
                }
                android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
                PrivilegeSettingsClient.sync()
                refreshPrivilegeSummaries()
                true
            }
            privilegeSelfTest?.setOnPreferenceClickListener {
                runPrivilegeSelfTest()
                true
            }
            // Initial sync + summary when settings opens.
            PrivilegeSettingsClient.sync()
            refreshPrivilegeSummaries()
        }

        private fun runPrivilegeSelfTest() {
            if (!isAdded || privilegeSelfTestRunning) return
            privilegeSelfTestRunning = true
            privilegeSelfTest?.isEnabled = false

            val ctx = requireContext()
            val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.title_privilege_self_test_result)
                .setMessage(R.string.summary_pref_privilege_self_test_running)
                .setCancelable(false)
                .create()
            progress.show()

            viewLifecycleOwner.lifecycleScope.launch {
                var report: String? = null
                var errorMessage: String? = null
                try {
                    // Probe-before is for diagnostics only. Apply settings first so hide/rename
                    // are active before the fingerprint scan (previous order could false-fail).
                    val probeBefore = withContext(Dispatchers.IO) {
                        PrivilegeSettingsClient.refresh()
                    }
                    val syncOk = withContext(Dispatchers.IO) {
                        PrivilegeSettingsClient.sync()
                    }
                    // Brief settle after privilege settings sync (hooks / create-time rename flags).
                    withContext(Dispatchers.IO) {
                        try {
                            Thread.sleep(350)
                        } catch (_: Throwable) {
                        }
                    }
                    val detection = withContext(Dispatchers.IO) {
                        VpnDetectionTest.runDetection(ctx)
                    }
                    val probeAfter = withContext(Dispatchers.IO) {
                        PrivilegeSettingsClient.refresh()
                    }
                    if (!isAdded) return@launch
                    refreshPrivilegeSummaries()
                    report = buildPrivilegeSelfTestReport(
                        detection = detection,
                        probeBefore = probeBefore,
                        probeAfter = probeAfter,
                        syncOk = syncOk,
                    )
                    privilegeSelfTest?.summary = report.lineSequence().firstOrNull().orEmpty()
                } catch (e: Throwable) {
                    errorMessage = e.message ?: e.javaClass.simpleName
                } finally {
                    privilegeSelfTestRunning = false
                    if (isAdded) {
                        privilegeSelfTest?.isEnabled = true
                    }
                    if (progress.isShowing) {
                        runCatching { progress.dismiss() }
                    }
                }

                if (!isAdded) return@launch
                if (report != null) {
                    val finalReport = report
                    val targets = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS).orEmpty()
                    val selfPkg = ctx.packageName
                    val selfMissing = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false) &&
                        !targets.contains(selfPkg)
                    val builder = androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.title_privilege_self_test_result)
                        .setMessage(finalReport)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.summary_pref_privilege_self_test_copy) { _, _ ->
                            Utils.setClipboard(ctx, finalReport)
                            android.widget.Toast.makeText(
                                ctx,
                                R.string.toast_privilege_self_test_copied,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    if (selfMissing) {
                        builder.setNegativeButton(R.string.action_privilege_add_self) { _, _ ->
                            addSelfToHideTargets()
                        }
                    }
                    builder.show()
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.title_privilege_self_test_result)
                        .setMessage(
                            getString(
                                R.string.summary_pref_privilege_self_test_error,
                                errorMessage ?: "unknown",
                            ),
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

        private fun isCoreLikelyRunning(context: Context, detection: DetectionResult): Boolean {
            if (CoreServiceManager.isRunning() ||
                CoreServiceManager.hasLiveSession() ||
                CoreServiceManager.serviceControl != null
            ) {
                return true
            }
            if (detection.nativeDetected) return true
            if (detection.frameworkDetected.any {
                    it == "NetworkInfo" ||
                        it == "NetworkForType" ||
                        it == "NetworkCapabilities" ||
                        it == "LinkProperties" ||
                        it == "ActiveNetworkInfo"
                }
            ) {
                return true
            }
            // Daemon process probe (main process cannot see in-process coreController state).
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val procs = am?.runningAppProcesses.orEmpty()
                val pkg = context.packageName
                procs.any { proc ->
                    val name = proc.processName ?: return@any false
                    name == "$pkg:RunSoLibV2RayDaemon" ||
                        name.endsWith(":RunSoLibV2RayDaemon")
                }
            } catch (_: Throwable) {
                false
            }
        }

        private fun buildPrivilegeSelfTestReport(
            detection: DetectionResult,
            probeBefore: PrivilegeSettingsClient.Probe,
            probeAfter: PrivilegeSettingsClient.Probe,
            syncOk: Boolean,
        ): String {
            val yes = getString(R.string.summary_pref_privilege_self_test_yes)
            val no = getString(R.string.summary_pref_privilege_self_test_no)
            val notDetected = getString(R.string.summary_pref_privilege_self_test_not_detected)
            val hideEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
            val targets = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS).orEmpty()
            val packageName = requireContext().packageName
            val selfInList = targets.contains(packageName)
            // Core lives in :RunSoLibV2RayDaemon; main-process CoreServiceManager is usually empty.
            val running = isCoreLikelyRunning(requireContext(), detection)

            fun moduleText(probe: PrivilegeSettingsClient.Probe): String = when (probe.result) {
                PrivilegeSettingsClient.ProbeResult.ACTIVE -> {
                    val ver = probe.status?.version ?: 0
                    getString(R.string.summary_pref_privilege_module_status_active_on_ver, ver)
                }
                PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE ->
                    getString(R.string.summary_pref_privilege_module_status_loaded_inactive)
                PrivilegeSettingsClient.ProbeResult.TRANSACTION_UNHANDLED ->
                    getString(R.string.summary_pref_privilege_module_status_unhandled)
                PrivilegeSettingsClient.ProbeResult.UNAUTHORIZED ->
                    getString(R.string.summary_pref_privilege_module_status_unauthorized)
                PrivilegeSettingsClient.ProbeResult.BINDER_UNAVAILABLE ->
                    getString(R.string.summary_pref_privilege_module_status_binder)
                PrivilegeSettingsClient.ProbeResult.ERROR ->
                    getString(R.string.summary_pref_privilege_module_status_error)
            }

            // Hard VPN fingerprints only. MissingNotVpn is weak and should not dominate verdict.
            val hardFramework = detection.frameworkDetected.filterNot { it == "MissingNotVpn" }
            val weakFramework = detection.frameworkDetected.filter { it == "MissingNotVpn" }
            val frameworkText = hardFramework
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: if (weakFramework.isNotEmpty()) {
                    weakFramework.joinToString(", ") + " (weak)"
                } else {
                    notDetected
                }
            val frameworkIfaces = detection.frameworkInterfaces
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: notDetected
            val nativeText = if (detection.nativeDetected) yes else notDetected
            val nativeIfaces = detection.nativeInterfaces
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: notDetected
            val httpProxy = detection.httpProxy?.takeIf { it.isNotBlank() } ?: notDetected

            val hardLeaked = hardFramework.isNotEmpty() ||
                detection.nativeDetected ||
                !detection.httpProxy.isNullOrBlank()
            val moduleInjected = probeAfter.result == PrivilegeSettingsClient.ProbeResult.ACTIVE ||
                probeAfter.result == PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE
            val moduleActive = probeAfter.result == PrivilegeSettingsClient.ProbeResult.ACTIVE

            val verdict = when {
                !moduleInjected ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_module_bad)
                probeAfter.result == PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_hook_inactive)
                !hideEnabled || targets.isEmpty() ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_config)
                !running ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_vpn_off)
                !selfInList ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_self_not_targeted)
                hardLeaked ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_leaked)
                moduleActive ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_clean)
                else ->
                    getString(R.string.summary_pref_privilege_self_test_verdict_hook_inactive)
            }

            return buildString {
                appendLine(verdict)
                appendLine()

                appendLine(getString(R.string.summary_pref_privilege_self_test_section_conditions))
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_module_before,
                        moduleText(probeBefore),
                    ),
                )
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_module_after,
                        moduleText(probeAfter),
                    ),
                )
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_sync,
                        if (syncOk) yes else no,
                    ),
                )
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_hide,
                        if (hideEnabled) yes else no,
                    ),
                )
                appendLine(getString(R.string.summary_pref_privilege_self_test_targets, targets.size))
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_self_in_list,
                        if (selfInList) yes else no,
                    ),
                )
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_vpn,
                        if (running) yes else no,
                    ),
                )
                val selfPkg = requireContext().packageName
                val perAppOn = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true
                val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS) == true
                val perAppSet = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty()
                val likelyInTunnel = when {
                    !running -> false
                    !perAppOn || perAppSet.isEmpty() -> true
                    bypassApps -> !perAppSet.contains(selfPkg)
                    else -> perAppSet.contains(selfPkg)
                }
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_in_tunnel,
                        if (likelyInTunnel) yes else no,
                    ),
                )
                val gateText = when {
                    !running -> getString(R.string.summary_pref_privilege_self_test_gate_core_off)
                    !selfInList -> getString(R.string.summary_pref_privilege_self_test_gate_not_listed)
                    !likelyInTunnel -> getString(R.string.summary_pref_privilege_self_test_gate_outside_tunnel)
                    else -> getString(R.string.summary_pref_privilege_self_test_gate_ok)
                }
                appendLine(getString(R.string.summary_pref_privilege_self_test_gate, gateText))
                appendLine()

                appendLine(getString(R.string.summary_pref_privilege_self_test_section_hard))
                val hardParts = mutableListOf<String>()
                if (hardFramework.isNotEmpty()) hardParts += hardFramework.joinToString(", ")
                if (detection.nativeDetected) {
                    hardParts += "Native(" + (detection.nativeInterfaces.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "yes") + ")"
                }
                if (!detection.httpProxy.isNullOrBlank()) hardParts += "HTTP " + detection.httpProxy
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_hard,
                        hardParts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
                            ?: getString(R.string.summary_pref_privilege_self_test_no_hard),
                    ),
                )
                appendLine(getString(R.string.summary_pref_privilege_self_test_section_weak))
                val weakParts = mutableListOf<String>()
                if (weakFramework.isNotEmpty()) weakParts += weakFramework.joinToString(", ")
                if (detection.frameworkInterfaces.isNotEmpty() && hardFramework.isEmpty() && !detection.nativeDetected) {
                    weakParts += "ifaces=" + detection.frameworkInterfaces.joinToString(",")
                }
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_weak,
                        weakParts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
                            ?: getString(R.string.summary_pref_privilege_self_test_no_weak),
                    ),
                )
                appendLine()
                appendLine(getString(R.string.summary_pref_privilege_self_test_section_fingerprints))
                appendLine(getString(R.string.summary_pref_privilege_self_test_framework, frameworkText))
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_framework_ifaces,
                        frameworkIfaces,
                    ),
                )
                appendLine(getString(R.string.summary_pref_privilege_self_test_native, nativeText))
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_native_ifaces,
                        nativeIfaces,
                    ),
                )
                appendLine(getString(R.string.summary_pref_privilege_self_test_http_proxy, httpProxy))
                val portsStatus = PrivilegePortsManager.status(requireContext())
                val portsText = when {
                    !portsStatus.enabledPref -> getString(R.string.summary_pref_privilege_ports_status_off)
                    !portsStatus.root -> getString(R.string.summary_pref_privilege_ports_status_no_root)
                    else -> getString(R.string.summary_pref_privilege_ports_status_on, portsStatus.appliedUids)
                }
                appendLine(getString(R.string.summary_pref_privilege_self_test_ports, portsText))
                appendLine()

                appendLine(getString(R.string.summary_pref_privilege_self_test_section_mask))
                val renameEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_IFACE_RENAME, false)
                val renamePrefix = MmkvManager.decodeSettingsString(AppConfig.PREF_PRIVILEGE_IFACE_PREFIX)
                    ?.takeIf { it.isNotBlank() } ?: "wlan"
                appendLine(
                    getString(
                        R.string.summary_pref_privilege_self_test_mask,
                        if (renameEnabled) yes else no,
                        renamePrefix,
                    ),
                )
                if (renameEnabled) {
                    val stillLegacy = detection.nativeInterfaces.any {
                        it.startsWith("tun") || it.startsWith("ppp") || it.startsWith("tap")
                    }
                    val maskedSeen = detection.nativeInterfaces.any { it.startsWith(renamePrefix) } ||
                        detection.frameworkInterfaces.any { it.startsWith(renamePrefix) }
                    when {
                        stillLegacy && !maskedSeen -> appendLine(
                            getString(R.string.summary_pref_privilege_self_test_mask_note_legacy),
                        )
                        maskedSeen -> appendLine(
                            getString(
                                R.string.summary_pref_privilege_self_test_mask_note_active,
                                renamePrefix,
                            ),
                        )
                        else -> appendLine(
                            getString(R.string.summary_pref_privilege_self_test_mask_note_clean),
                        )
                    }
                } else {
                    appendLine(getString(R.string.summary_pref_privilege_self_test_mask_off))
                }
                appendLine()

                appendLine(getString(R.string.summary_pref_privilege_self_test_section_note))
                append(getString(R.string.summary_pref_privilege_self_test_note))
            }
        }

        private fun refreshPrivilegeSummaries() {
            if (!isAdded) return
            val count = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)?.size ?: 0
            privilegeManageApps?.summary = if (count > 0) {
                getString(R.string.summary_pref_privilege_manage_apps_count, count)
            } else {
                getString(R.string.summary_pref_privilege_manage_apps)
            }

            val hideEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
            val probe = PrivilegeSettingsClient.refresh()
            val status = probe.status
            privilegeModuleStatus?.summary = when (probe.result) {
                PrivilegeSettingsClient.ProbeResult.ACTIVE -> {
                    val ver = status?.version ?: 0
                    if (hideEnabled) {
                        getString(R.string.summary_pref_privilege_module_status_active_on_ver, ver)
                    } else {
                        getString(R.string.summary_pref_privilege_module_status_active_off_ver, ver)
                    }
                }
                PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE ->
                    getString(R.string.summary_pref_privilege_module_status_loaded_inactive)
                PrivilegeSettingsClient.ProbeResult.TRANSACTION_UNHANDLED ->
                    getString(R.string.summary_pref_privilege_module_status_unhandled)
                PrivilegeSettingsClient.ProbeResult.UNAUTHORIZED ->
                    getString(R.string.summary_pref_privilege_module_status_unauthorized)
                PrivilegeSettingsClient.ProbeResult.BINDER_UNAVAILABLE ->
                    getString(R.string.summary_pref_privilege_module_status_binder)
                PrivilegeSettingsClient.ProbeResult.ERROR ->
                    getString(R.string.summary_pref_privilege_module_status_error)
            }

            val rename = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_IFACE_RENAME, false)
            val prefix = MmkvManager.decodeSettingsString(AppConfig.PREF_PRIVILEGE_IFACE_PREFIX)
                ?.takeIf { it.isNotBlank() } ?: "wlan"
            privilegeIfacePrefix?.isEnabled = rename
            privilegeIfacePrefix?.summary = getString(R.string.summary_pref_privilege_iface_prefix_value, prefix)

            val portsEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_PORTS, false)
            // Prefer cached root only on UI thread; avoid blocking su probe here.
            val portsRoot = RootManager.cachedRoot()
            privilegePorts?.summary = when {
                !portsEnabled -> getString(
                    R.string.summary_pref_privilege_ports_status,
                    getString(R.string.summary_pref_privilege_ports_status_off),
                )
                !portsRoot -> getString(
                    R.string.summary_pref_privilege_ports_status,
                    getString(R.string.summary_pref_privilege_ports_status_no_root),
                )
                else -> {
                    val n = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)?.size ?: 0
                    getString(
                        R.string.summary_pref_privilege_ports_status,
                        getString(R.string.summary_pref_privilege_ports_status_on, n),
                    )
                }
            }
        }

        private fun addSelfToHideTargets() {
            if (!isAdded) return
            val ctx = requireContext()
            val pkg = ctx.packageName
            val set = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)?.toMutableSet()
                ?: mutableSetOf()
            if (!set.add(pkg)) {
                refreshPrivilegeSummaries()
                return
            }
            MmkvManager.encodeSettings(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS, set)
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val synced = PrivilegeSettingsClient.sync()
                    PrivilegePortsManager.applyFromPrefs(ctx.applicationContext)
                    synced
                }
                if (!isAdded) return@launch
                android.widget.Toast.makeText(
                    ctx,
                    if (ok) R.string.toast_privilege_add_self_ok else R.string.toast_privilege_sync_fail,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                refreshPrivilegeSummaries()
            }
        }
        private fun hardRestartIfLive() {
            val mainLive = (activity as? MainActivity)?.mainViewModel?.isRunning?.value == true
            val live =
                mainLive ||
                    CoreServiceManager.hasLiveSession() ||
                    CoreServiceManager.serviceControl != null ||
                    CoreServiceManager.isRunning()
            if (!live) return
            // Debounce: rename + prefix toggles often fire back-to-back.
            SettingsChangeManager.makeHardRestartService()
            hardRestartRunnable?.let { view?.removeCallbacks(it) }
            val task = Runnable {
                if (!isAdded) return@Runnable
                val home = (activity as? MainActivity)?.homeFragmentForModeRestart()
                if (home != null && home.isAdded) {
                    home.hardRestartForCurrentMode()
                } else {
                    CoreServiceManager.stopAllModeServices(requireContext())
                    view?.postDelayed({
                        if (!isAdded) return@postDelayed
                        CoreServiceManager.startVService(requireContext())
                    }, 700L)
                }
            }
            hardRestartRunnable = task
            view?.postDelayed(task, 450L)
        }

        private fun setupToolEntryClicks() {
            fun bind(key: String, activityClass: Class<*>) {
                findPreference<Preference>(key)?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), activityClass))
                    true
                }
            }
            bind("pref_entry_per_app_proxy", PerAppProxyActivity::class.java)
            bind("pref_entry_user_asset", UserAssetActivity::class.java)
            bind("pref_entry_logcat", LogcatActivity::class.java)
            bind("pref_entry_backup", BackupActivity::class.java)
            bind("pref_entry_check_update", CheckUpdateActivity::class.java)
            bind("pref_entry_about", AboutActivity::class.java)
        }

        private fun updateLocalDns(enabled: Boolean) {
            fakeDns?.isEnabled = enabled
//            localDnsPort?.isEnabled = enabled
            vpnDns?.isEnabled = !enabled
        }

        private fun updateMux(enabled: Boolean) {
            muxConcurrency?.isEnabled = enabled
            muxXudpConcurrency?.isEnabled = enabled
            muxXudpQuic?.isEnabled = enabled
            if (enabled) {
                updateMuxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"))
                updateMuxXudpConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8"))
            }
        }

        private fun updateMuxConcurrency(value: String?) {
            val concurrency = value?.toIntOrNull() ?: 8
            muxConcurrency?.summary = concurrency.toString()
        }


        private fun updateMuxXudpConcurrency(value: String?) {
            if (value == null) {
                muxXudpQuic?.isEnabled = true
            } else {
                val concurrency = value.toIntOrNull() ?: 8
                muxXudpConcurrency?.summary = concurrency.toString()
                muxXudpQuic?.isEnabled = concurrency >= 0
            }
        }

        private fun updateFragment(enabled: Boolean) {
            fragmentPackets?.isEnabled = enabled
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
            fragmentMaxSplit?.isEnabled = enabled
        }

        private fun updateDynamicSocksPort(enabled: Boolean) {
            socksPort?.isEnabled = (enableLocalProxy?.isChecked == true) && !enabled
        }

        private fun updateEnableLocalProxy(enabled: Boolean) {
            val dynamic = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
            socksPort?.isEnabled = enabled && !dynamic
            dynamicSocksPort?.isEnabled = enabled
            socksUsername?.isEnabled = enabled
            socksPassword?.isEnabled = enabled
            socksEnableUdp?.isEnabled = enabled
            proxySharing?.isEnabled = enabled

            if (!enabled) {
                if (appendHttpProxy?.isChecked == true) {
                    appendHttpProxy?.isChecked = false
                    MmkvManager.encodeSettings(AppConfig.PREF_APPEND_HTTP_PROXY, false)
                }
                appendHttpProxy?.isEnabled = false
            } else {
                val vpn = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) == VPN
                appendHttpProxy?.isEnabled = vpn
            }
        }

        private fun updateHevTunSettings(enabled: Boolean) {
            hevTunLogLevel?.isEnabled = enabled
            hevTunRwTimeout?.isEnabled = enabled

            if (enabled) {
                if (enableLocalProxy?.isChecked == false) {
                    enableLocalProxy?.isChecked = true
                    MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
                }
                enableLocalProxy?.isEnabled = false
            } else {
                enableLocalProxy?.isEnabled = true
            }
            updateEnableLocalProxy(enableLocalProxy?.isChecked == true)
        }
    }

    fun onModeHelpClicked(view: View) {
        Utils.openUri(this, AppConfig.APP_WIKI_MODE)
    }
}

