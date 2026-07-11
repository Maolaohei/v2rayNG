package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.FragmentHomeBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : BaseFragment<FragmentHomeBinding>() {
    override fun inflateBinding(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)

    private val host: MainActivity
        get() = requireActivity() as MainActivity

    val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var speedJob: Job? = null
    private var isServiceRunning: Boolean = false
    private var broadcastStarted: Boolean = false
    private val speedListener: (Long, Long) -> Unit = listener@{ up, down ->
        if (!isAdded) return@listener
        view?.post {
            if (!isAdded) return@post
            binding.tvSpeedUpload.text = up.toSpeedString()
            binding.tvSpeedDownload.text = down.toSpeedString()
        }
    }



    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true


        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.layoutMode.setOnClickListener { toggleProxyMode() }
        binding.btnTestAll.setOnClickListener { testAllConfigs() }

        setupGroupTab()
        refreshModeLabel()
        refreshSelectedServerMeta()
        setupViewModel()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            SubscriptionUpdater.sync()
        }
        mainViewModel.reloadServerList()

        host.checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }




    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(viewLifecycleOwner) { setTestState(it) }
        mainViewModel.isRunning.observe(viewLifecycleOwner) { isRunning ->
            applyRunningState(false, isRunning == true)
        }
        mainViewModel.selectionChangedAction.observe(viewLifecycleOwner) {
            refreshSelectedServerMeta()
        }
        mainViewModel.updateListAction.observe(viewLifecycleOwner) {
            refreshNodesCount()
            refreshSelectedServerMeta()
        }
        if (!broadcastStarted) {
            mainViewModel.startListenBroadcast()
            broadcastStarted = true
        }
        mainViewModel.initAssets(requireContext().assets)
    }

    fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(requireContext())
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.isNotEmpty()
        refreshGroupTabTitles(true)
        refreshNodesCount()
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val groupsToRefresh = if (refreshAll || mainViewModel.subscriptionId.isEmpty()) {
            groupPagerAdapter.groups
        } else {
            groupPagerAdapter.groups.filter { it.id == mainViewModel.subscriptionId }
        }

        groupsToRefresh.forEach { group ->
            if (group.id.isEmpty()) {
                return@forEach
            }
            val tabIndex = groupPagerAdapter.groups.indexOfFirst { it.id == group.id }
            if (tabIndex >= 0) {
                val count = MmkvManager.decodeServerList(group.id).size
                binding.tabGroup.getTabAt(tabIndex)?.text = "${group.remarks} ($count)"
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) {
            startV2Ray()
        } else {
            applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
        }
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(requireContext())
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(requireContext())
            if (intent == null) {
                startV2Ray()
            } else {
                host.requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            setTestState(getString(R.string.home_start_first))
        }
    }

    private fun testAllConfigs() {
        if (mainViewModel.serversCache.isEmpty()) {
            requireContext().toast(R.string.toast_none_data)
            return
        }
        requireContext().toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
        mainViewModel.testAllRealPing()
    }

    private fun toggleProxyMode() {
        val current = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN)
        val next = if (current == AppConfig.VPN) "Proxy only" else AppConfig.VPN
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, next)
        refreshModeLabel()
        if (mainViewModel.isRunning.value == true) {
            SettingsChangeManager.makeRestartService()
            restartV2Ray()
        }
    }

    private fun refreshModeLabel() {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN) ?: AppConfig.VPN
        binding.tvModeValue.text = if (mode == AppConfig.VPN) {
            AppConfig.VPN
        } else {
            getString(R.string.proxy_only)
        }
    }

    private fun refreshNodesCount() {
        binding.tvNodesCount.text = getString(R.string.home_nodes_count, mainViewModel.serversCache.size)
    }

    private fun refreshSelectedServerMeta() {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN) ?: AppConfig.VPN
        val modeLabel = if (mode == AppConfig.VPN) AppConfig.VPN else getString(R.string.proxy_only)
        if (profile == null) {
            binding.tvStatusMeta.text = getString(R.string.home_select_node_hint) + " · " + modeLabel
        } else if (isServiceRunning) {
            binding.tvStatusMeta.text = profile.remarks + " · " + modeLabel
        } else {
            binding.tvStatusMeta.text = getString(R.string.home_select_node_hint) + " · " + profile.remarks + " · " + modeLabel
        }
    }

    private fun startSpeedUpdates() {
        stopSpeedUpdates()
        binding.tvSpeedUpload.text = "0 B/s"
        binding.tvSpeedDownload.text = "0 B/s"
        // When notification speed is enabled, reuse its stats to avoid double-reset.
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            NotificationManager.addSpeedUpdateListener(speedListener)
            return
        }
        speedJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var lastQueryTime = System.currentTimeMillis()
            while (isActive) {
                delay(3000L)
                if (!CoreServiceManager.isRunning()) continue
                val now = System.currentTimeMillis()
                val elapsed = ((now - lastQueryTime).coerceAtLeast(1L)) / 1000.0
                var up = 0L
                var down = 0L
                CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
                    if (stat.tag.startsWith(AppConfig.TAG_PROXY)) {
                        when (stat.direction) {
                            AppConfig.UPLINK -> up += stat.value
                            AppConfig.DOWNLINK -> down += stat.value
                        }
                    }
                }
                lastQueryTime = now
                val upText = (up / elapsed).toLong().toSpeedString()
                val downText = (down / elapsed).toLong().toSpeedString()
                withContext(Dispatchers.Main) {
                    binding.tvSpeedUpload.text = upText
                    binding.tvSpeedDownload.text = downText
                }
            }
        }
    }

    private fun stopSpeedUpdates() {
        NotificationManager.removeSpeedUpdateListener(speedListener)
        speedJob?.cancel()
        speedJob = null
        if (view != null) {
            binding.tvSpeedUpload.text = "0 B/s"
            binding.tvSpeedDownload.text = "0 B/s"
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            requireContext().toast(R.string.title_file_chooser)
            applyRunningState(isLoading = false, isRunning = false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            host.checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }

        CoreServiceManager.startVService(requireContext())
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(requireContext())
        }
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content ?: getString(R.string.home_tap_to_test)
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.tvStatusState.text = getString(R.string.home_status_connecting)
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_fab_active))
            return
        }

        isServiceRunning = isRunning
        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            binding.tvStatusState.text = getString(R.string.home_status_running)
            binding.layoutStatus.setBackgroundResource(R.drawable.bg_home_status_card_running)
            setStatusDot(true)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
            startSpeedUpdates()
        } else {
            binding.fab.setImageResource(R.drawable.ic_power_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            binding.tvStatusState.text = getString(R.string.home_status_stopped)
            binding.layoutStatus.setBackgroundResource(R.drawable.bg_home_status_card)
            setStatusDot(false)
            setTestState(getString(R.string.home_tap_to_test))
            binding.layoutTest.isFocusable = false
            stopSpeedUpdates()
        }
        refreshSelectedServerMeta()
    }

    private fun setStatusDot(running: Boolean) {
        val color = ContextCompat.getColor(
            requireContext(),
            if (running) R.color.colorPing else R.color.color_fab_inactive
        )
        val bg = binding.viewStatusDot.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        setHasOptionsMenu(!hidden)
        setMenuVisibility(!hidden)
        if (!hidden) {
            // Returning from More/Settings may change groups or selected node.
            if (this::groupPagerAdapter.isInitialized) {
                setupGroupTab()
            }
            refreshModeLabel()
            refreshSelectedServerMeta()
            refreshNodesCount()
            if (isServiceRunning) {
                startSpeedUpdates()
            }
            requireActivity().invalidateOptionsMenu()
        } else {
            stopSpeedUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModeLabel()
        refreshSelectedServerMeta()
        refreshNodesCount()
        if (isServiceRunning) {
            startSpeedUpdates()
        }
    }

    override fun onPause() {
        stopSpeedUpdates()
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: android.view.MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_proxy_chain -> {
            importManually(EConfigType.PROXYCHAIN.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.real_ping_all -> {
            requireContext().toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(requireContext(), ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(requireContext(), ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(requireContext(), ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        host.launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(requireContext())
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        host.showLoading()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            requireContext().toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> requireContext().toastError(R.string.toast_failure)
                    }
                    host.hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    requireContext().toastError(R.string.toast_failure)
                    host.hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        host.showLoading()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    requireContext().toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    requireContext().toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    requireContext().toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                host.hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        host.showLoading()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    requireContext().toast(getString(R.string.title_export_config_count, ret))
                else
                    requireContext().toastError(R.string.toast_failure)
                host.hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(requireContext()).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                host.showLoading()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        requireContext().toast(getString(R.string.title_del_config_count, ret))
                        host.hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(requireContext()).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                host.showLoading()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        requireContext().toast(getString(R.string.title_del_duplicate_config_count, ret))
                        host.hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(requireContext()).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                host.showLoading()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        requireContext().toast(getString(R.string.title_del_config_count, ret))
                        host.hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        host.showLoading()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                host.hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        host.launchFileChooser { uri ->
            if (uri != null) {
                readContentFromUri(uri)
            }
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            requireContext().toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            requireContext().toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = childFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            requireContext().toast(R.string.toast_fragment_not_available)
        }
    }




    override fun onDestroyView() {
        stopSpeedUpdates()
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
    }
}