package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentSubSettingBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri

/**
 * Subscription tab: group chips + node list (selection).
 * Subscription URL management is in [SubscriptionManageFragment]/[SubSettingActivity].
 */
class SubSettingFragment : BaseFragment<FragmentSubSettingBinding>() {
    override fun inflateBinding(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?) =
        FragmentSubSettingBinding.inflate(inflater, container, false)

    private val host: MainActivity
        get() = requireActivity() as MainActivity

    val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var broadcastStarted: Boolean = false

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        binding.btnTestAll.setOnClickListener { testAllConfigs() }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()
    }

    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(viewLifecycleOwner) {
            refreshNodesCount()
        }
        if (!broadcastStarted) {
            // Home already starts broadcast when present; safe if Sub opens first.
            mainViewModel.startListenBroadcast()
            broadcastStarted = true
        }
    }

    fun setupGroupTab() {
        if (!isAdded || view == null) return
        val groups = mainViewModel.getSubscriptions(requireContext())
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 }
            ?: (groups.size - 1).coerceAtLeast(0)
        if (groups.isNotEmpty()) {
            binding.viewPager.setCurrentItem(targetIndex, false)
        }

        binding.tabGroup.isVisible = groups.isNotEmpty()
        refreshGroupTabTitles(true)
        refreshNodesCount()
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        if (!isAdded || view == null || !this::groupPagerAdapter.isInitialized) return
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

    private fun refreshNodesCount() {
        if (!isAdded || view == null) return
        binding.tvNodesCount.text = getString(R.string.home_nodes_count, mainViewModel.serversCache.size)
    }

    private fun testAllConfigs() {
        if (mainViewModel.serversCache.isEmpty()) {
            requireContext().toast(R.string.toast_none_data)
            return
        }
        requireContext().toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
        mainViewModel.testAllRealPing()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        setHasOptionsMenu(!hidden)
        setMenuVisibility(!hidden)
        if (!hidden) {
            if (this::groupPagerAdapter.isInitialized) {
                setupGroupTab()
            }
            requireActivity().invalidateOptionsMenu()
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::groupPagerAdapter.isInitialized) {
            setupGroupTab()
        }
        refreshNodesCount()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: android.view.MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.manage_subscriptions)?.isVisible = true

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
        R.id.manage_subscriptions -> {
            startActivity(Intent(requireContext(), SubSettingActivity::class.java))
            true
        }
        R.id.import_qrcode -> { importQRcode(); true }
        R.id.import_clipboard -> { importClipboard(); true }
        R.id.import_local -> { importConfigLocal(); true }
        R.id.import_manually_policy_group -> { importManually(EConfigType.POLICYGROUP.value); true }
        R.id.import_manually_proxy_chain -> { importManually(EConfigType.PROXYCHAIN.value); true }
        R.id.import_manually_vmess -> { importManually(EConfigType.VMESS.value); true }
        R.id.import_manually_vless -> { importManually(EConfigType.VLESS.value); true }
        R.id.import_manually_ss -> { importManually(EConfigType.SHADOWSOCKS.value); true }
        R.id.import_manually_socks -> { importManually(EConfigType.SOCKS.value); true }
        R.id.import_manually_http -> { importManually(EConfigType.HTTP.value); true }
        R.id.import_manually_trojan -> { importManually(EConfigType.TROJAN.value); true }
        R.id.import_manually_wireguard -> { importManually(EConfigType.WIREGUARD.value); true }
        R.id.import_manually_hysteria2 -> { importManually(EConfigType.HYSTERIA2.value); true }
        R.id.export_all -> { exportAll(); true }
        R.id.real_ping_all -> {
            requireContext().toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }
        R.id.service_restart -> { host.restartV2Ray(); true }
        R.id.del_all_config -> { delAllConfig(); true }
        R.id.del_duplicate_config -> { delDuplicateConfig(); true }
        R.id.del_invalid_config -> { delInvalidConfig(); true }
        R.id.sort_by_test_results -> { sortByTestResults(); true }
        R.id.sub_update -> { importConfigViaSub(); true }
        R.id.locate_selected_config -> { locateSelectedServer(); true }
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

    private fun importQRcode(): Boolean {
        host.launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    private fun importClipboard(): Boolean {
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

    private fun importConfigLocal(): Boolean {
        try {
            host.launchFileChooser { uri ->
                if (uri != null) readContentFromUri(uri)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }

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
                if (ret > 0) requireContext().toast(getString(R.string.title_export_config_count, ret))
                else requireContext().toastError(R.string.toast_failure)
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
            .setNegativeButton(android.R.string.cancel, null)
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
            .setNegativeButton(android.R.string.cancel, null)
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
            .setNegativeButton(android.R.string.cancel, null)
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

    private fun readContentFromUri(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

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
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

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
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
    }
}