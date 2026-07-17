package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBypassListBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.extension.v2RayApplication
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.PerAppProxyViewModel
import com.v2ray.ang.xposed.PrivilegeSettingsClient
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class PerAppProxyActivity : BaseActivity() {
    companion object {
        const val EXTRA_PRIVILEGE_HIDE_VPN_PICKER = "extra_privilege_hide_vpn_picker"
    }

    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }

    private var adapter: PerAppProxyAdapter? = null
    private var appsAll: List<AppInfo>? = null
    private val viewModel: PerAppProxyViewModel by viewModels()
    private var privilegeHideVpnMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privilegeHideVpnMode = intent.getBooleanExtra(EXTRA_PRIVILEGE_HIDE_VPN_PICKER, false)
        viewModel.configureMode(privilegeHideVpnMode)

        val titleRes = if (privilegeHideVpnMode) {
            R.string.title_pref_privilege_manage_apps
        } else {
            R.string.per_app_proxy_settings
        }
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(titleRes))

        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)

        if (privilegeHideVpnMode) {
            // Hide VPN-only capture switches; this page only manages hidevpn package set.
            binding.headerView.visibility = View.GONE
            binding.switchPerAppProxy.setOnCheckedChangeListener(null)
            binding.switchBypassApps.setOnCheckedChangeListener(null)
            binding.tvPerAppRootNote.visibility = View.GONE
            updatePrivilegeWarnings()
        } else {
            binding.switchPerAppProxy.setOnCheckedChangeListener { _, isChecked ->
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, isChecked)
                // ROOT/VPN capture policy changes only take effect after service re-apply.
                SettingsChangeManager.makeHardRestartService()
                updatePerAppWarnings()
            }
            binding.switchPerAppProxy.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)

            binding.switchBypassApps.setOnCheckedChangeListener { _, isChecked ->
                MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, isChecked)
                SettingsChangeManager.makeHardRestartService()
                updatePerAppWarnings()
            }
            binding.switchBypassApps.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)

            binding.layoutSwitchBypassAppsTips.setOnClickListener {
                Toasty.info(this, R.string.summary_pref_per_app_proxy, Toast.LENGTH_LONG, true).show()
            }
            updatePerAppWarnings()
        }

        initList()
    }

    private fun updatePerAppWarnings() {
        if (privilegeHideVpnMode) {
            updatePrivilegeWarnings()
            return
        }
        val perApp = binding.switchPerAppProxy.isChecked
        val bypass = binding.switchBypassApps.isChecked
        val selected = try {
            viewModel.selectedCount()
        } catch (_: Exception) {
            0
        }
        val emptyAllow = perApp && !bypass && selected == 0
        binding.tvPerAppWarning.visibility = if (emptyAllow) View.VISIBLE else View.GONE
        if (emptyAllow) {
            binding.tvPerAppWarning.text = getString(R.string.per_app_empty_allowlist_warning)
        }
        binding.tvPerAppRootNote.visibility =
            if (SettingsManager.isRootMode()) View.VISIBLE else View.GONE
    }

    private fun updatePrivilegeWarnings() {
        val hideEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
        val selected = viewModel.selectedCount()
        val emptyTargets = hideEnabled && selected == 0
        binding.tvPerAppWarning.visibility = if (emptyTargets) View.VISIBLE else View.GONE
        if (emptyTargets) {
            binding.tvPerAppWarning.text = getString(R.string.privilege_empty_target_warning)
        }
        binding.tvPerAppRootNote.visibility = View.GONE
    }

    private fun initList() {
        showLoading()

        lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val appsList = AppManagerUtil.loadNetworkAppList(this@PerAppProxyActivity)

                    val blacklistSet = viewModel.getAll()
                    if (blacklistSet.isNotEmpty()) {
                        appsList.forEach { app ->
                            app.isSelected = if (blacklistSet.contains(app.packageName)) 1 else 0
                        }
                        appsList.sortedWith { p1, p2 ->
                            when {
                                p1.isSelected > p2.isSelected -> -1
                                p1.isSelected < p2.isSelected -> 1
                                p1.isSystemApp > p2.isSystemApp -> 1
                                p1.isSystemApp < p2.isSystemApp -> -1
                                p1.appName.lowercase() > p2.appName.lowercase() -> 1
                                p1.appName.lowercase() < p2.appName.lowercase() -> -1
                                p1.packageName > p2.packageName -> 1
                                p1.packageName < p2.packageName -> -1
                                else -> 0
                            }
                        }
                    } else {
                        val collator = Collator.getInstance()
                        appsList.sortedWith(compareBy(collator) { it.appName })
                    }.also { list ->
                        appsAll = list
                    }
                }

                adapter = PerAppProxyAdapter(apps, viewModel)
                binding.recyclerView.adapter = adapter
                refreshData()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load app list", e)
                toast(R.string.toast_failure)
            } finally {
                hideLoading()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bypass_list, menu)

        if (privilegeHideVpnMode) {
            // China-app import presets belong to per-app proxy only.
            menu.findItem(R.id.select_proxy_app)?.isVisible = false
            menu.findItem(R.id.import_proxy_app)?.isVisible = false
            menu.findItem(R.id.export_proxy_app)?.isVisible = false
        }

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    filterProxyApp(newText.orEmpty())
                    return false
                }
            })
        }

        return super.onCreateOptionsMenu(menu)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.select_all -> {
            selectAllApp()
            if (!privilegeHideVpnMode) {
                allowPerAppProxy()
            }
            true
        }

        R.id.invert_selection -> {
            invertSelection()
            if (!privilegeHideVpnMode) {
                allowPerAppProxy()
            }
            true
        }

        R.id.select_proxy_app -> {
            if (!privilegeHideVpnMode) {
                selectProxyAppAuto()
                allowPerAppProxy()
            }
            true
        }

        R.id.import_proxy_app -> {
            if (!privilegeHideVpnMode) {
                importProxyApp()
                allowPerAppProxy()
            }
            true
        }

        R.id.export_proxy_app -> {
            if (!privilegeHideVpnMode) {
                exportProxyApp()
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun selectAllApp() {
        adapter?.let { adapter ->
            val pkgNames = adapter.apps.map { it.packageName }
            val allSelected = pkgNames.all { viewModel.contains(it) }

            if (allSelected) {
                viewModel.removeAll(pkgNames)
            } else {
                viewModel.addAll(pkgNames)
            }
            refreshData()
        }
    }

    private fun invertSelection() {
        adapter?.let { adapter ->
            adapter.apps.forEach { app ->
                viewModel.toggle(app.packageName)
            }
            refreshData()
        }
    }

    private fun selectProxyAppAuto() {
        toast(R.string.msg_downloading_content)
        showLoading()

        val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
        lifecycleScope.launch(Dispatchers.IO) {
            var content = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000
                )
            )
            if (content.isNullOrEmpty()) {
                val proxyUsername = SettingsManager.getSocksUsername()
                val proxyPassword = SettingsManager.getSocksPassword()
                val httpPort = SettingsManager.getHttpPort()
                content = HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 5000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                ) ?: ""
            }
            launch(Dispatchers.Main) {
                selectProxyApp(content, true)
                toastSuccess(R.string.toast_success)
                hideLoading()
            }
        }
    }

    private fun importProxyApp() {
        val content = Utils.getClipboard(applicationContext)
        if (TextUtils.isEmpty(content)) return
        selectProxyApp(content, false)
        toastSuccess(R.string.toast_success)
    }

    private fun exportProxyApp() {
        var lst = binding.switchBypassApps.isChecked.toString()

        viewModel.getAll().forEach { pkg ->
            lst = lst + System.lineSeparator() + pkg
        }
        Utils.setClipboard(applicationContext, lst)
        toastSuccess(R.string.toast_success)
    }

    private fun allowPerAppProxy() {
        binding.switchPerAppProxy.isChecked = true
        SettingsChangeManager.makeHardRestartService()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun selectProxyApp(content: String, force: Boolean): Boolean {
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                Utils.readTextFromAssets(v2RayApplication, "proxy_package_name")
            } else {
                content
            }
            if (TextUtils.isEmpty(proxyApps)) return false

            viewModel.clear()

            if (binding.switchBypassApps.isChecked) {
                adapter?.let { adapter ->
                    adapter.apps.forEach { app ->
                        val packageName = app.packageName
                        if (!inProxyApps(proxyApps, packageName, force)) {
                            viewModel.add(packageName)
                        }
                    }
                    refreshData()
                }
            } else {
                adapter?.let { adapter ->
                    adapter.apps.forEach { app ->
                        val packageName = app.packageName
                        if (inProxyApps(proxyApps, packageName, force)) {
                            viewModel.add(packageName)
                        }
                    }
                    refreshData()
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun inProxyApps(proxyApps: String, packageName: String, force: Boolean): Boolean {
        if (force) {
            if (packageName == "com.google.android.webview") return false
            if (packageName.startsWith("com.google")) return true
        }

        return proxyApps.indexOf(packageName) >= 0
    }

    private fun filterProxyApp(content: String): Boolean {
        val apps = ArrayList<AppInfo>()

        val key = content.uppercase()
        if (key.isNotEmpty()) {
            appsAll?.forEach {
                if (it.appName.uppercase().indexOf(key) >= 0
                    || it.packageName.uppercase().indexOf(key) >= 0
                ) {
                    apps.add(it)
                }
            }
        } else {
            appsAll?.forEach {
                apps.add(it)
            }
        }

        adapter = PerAppProxyAdapter(apps, adapter?.viewModel ?: viewModel)
        binding.recyclerView.adapter = adapter
        refreshData()
        return true
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        adapter?.notifyDataSetChanged()
        if (privilegeHideVpnMode) {
            updatePrivilegeWarnings()
            maybeAutoEnableHideVpn()
        } else {
            updatePerAppWarnings()
        }
    }

    /**
     * First time user selects hidevpn targets: auto-enable the feature so the list is useful immediately.
     * Does not force-disable when list becomes empty.
     */
    private fun maybeAutoEnableHideVpn() {
        if (!privilegeHideVpnMode) return
        val selected = viewModel.selectedCount()
        if (selected <= 0) return
        val enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
        if (enabled) return
        MmkvManager.encodeSettings(AppConfig.PREF_PRIVILEGE_HIDE_VPN, true)
        PrivilegeSettingsClient.sync()
        updatePrivilegeWarnings()
    }
}
