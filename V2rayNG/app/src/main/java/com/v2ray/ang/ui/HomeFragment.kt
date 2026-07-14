package com.v2ray.ang.ui

import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.FragmentHomeBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.TrafficStatsManager
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Slim home: system switch, connectivity test, region/latency/24h traffic,
 * mode (VPN / Proxy only), current node summary.
 */
class HomeFragment : BaseFragment<FragmentHomeBinding>() {
    override fun inflateBinding(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)

    private val host: MainActivity
        get() = requireActivity() as MainActivity

    val mainViewModel: MainViewModel by activityViewModels()
    private var isServiceRunning: Boolean = false
    private var broadcastStarted: Boolean = false
    private var modeToggleReady: Boolean = false
    private var switchReady: Boolean = false

    private var lastRegion: String? = null
    private var lastLatencyMs: Long? = null

    private val dayListener: (Long) -> Unit = { total ->
        if (isAdded) {
            view?.post {
                if (isAdded && view != null) {
                    binding.tvTraffic24h.text = total.toTrafficString()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvTestState.setOnClickListener { handleLayoutTestClick() }
        binding.layoutCurrentNode.setOnClickListener { host.openSubscriptionTab() }

        setupConnectionSwitch()
        setupModeToggle()
        refreshModeToggle()
        refreshSelectedServerMeta()
        refreshMetricsFromCache()
        binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
        setupViewModel()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            SubscriptionUpdater.sync()
        }
        mainViewModel.reloadServerList()
        host.checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(viewLifecycleOwner) { content ->
            setTestState(content)
            applyTestResultMetrics(content)
        }
        mainViewModel.isRunning.observe(viewLifecycleOwner) { isRunning ->
            applyRunningState(false, isRunning == true)
        }
        mainViewModel.selectionChangedAction.observe(viewLifecycleOwner) {
            // Node changed: region from previous exit IP is stale.
            lastRegion = null
            lastLatencyMs = null
            refreshSelectedServerMeta()
            refreshMetricsFromCache()
        }
        mainViewModel.updateListAction.observe(viewLifecycleOwner) {
            refreshSelectedServerMeta()
            refreshMetricsFromCache()
        }
        if (!broadcastStarted) {
            mainViewModel.startListenBroadcast()
            broadcastStarted = true
        }
        mainViewModel.initAssets(requireContext().assets)
    }

    private fun setupConnectionSwitch() {
        switchReady = false
        binding.switchConnection.setOnCheckedChangeListener { _, isChecked ->
            if (!switchReady) return@setOnCheckedChangeListener
            if (isChecked == isServiceRunning) return@setOnCheckedChangeListener
            handleConnectionToggle(isChecked)
        }
        switchReady = true
    }

    private fun setupModeToggle() {
        modeToggleReady = false
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || !modeToggleReady) return@addOnButtonCheckedListener
            val next = if (checkedId == R.id.btn_mode_vpn) AppConfig.VPN else "Proxy only"
            val current = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN) ?: AppConfig.VPN
            if (current == next) return@addOnButtonCheckedListener
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, next)
            if (mainViewModel.isRunning.value == true) {
                SettingsChangeManager.makeRestartService()
                restartV2Ray()
            }
        }
        modeToggleReady = true
    }

    private fun refreshModeToggle() {
        if (!isAdded || view == null) return
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN) ?: AppConfig.VPN
        modeToggleReady = false
        if (mode == AppConfig.VPN) {
            binding.modeToggle.check(R.id.btn_mode_vpn)
        } else {
            binding.modeToggle.check(R.id.btn_mode_proxy)
        }
        modeToggleReady = true
    }

    private fun refreshSelectedServerMeta() {
        if (!isAdded || view == null) return
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        binding.tvCurrentNode.text = profile?.remarks ?: getString(R.string.home_select_node_hint)
    }

    private fun refreshMetricsFromCache() {
        if (!isAdded || view == null) return

        val regionText = lastRegion?.takeIf { it.isNotBlank() }
            ?: getString(R.string.home_metric_region_unknown)
        binding.tvMetricRegion.text = regionText

        val latencyFromTest = lastLatencyMs
        val latencyFromAff = MmkvManager.getSelectServer()
            ?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis }
            ?.takeIf { it != 0L }

        when {
            latencyFromTest != null && latencyFromTest >= 0L -> {
                binding.tvMetricLatency.text = getString(R.string.home_metric_latency_ms, latencyFromTest.toInt())
            }
            latencyFromTest != null && latencyFromTest < 0L -> {
                binding.tvMetricLatency.text = getString(R.string.home_metric_latency_fail)
            }
            latencyFromAff != null && latencyFromAff > 0L -> {
                binding.tvMetricLatency.text = getString(R.string.home_metric_latency_ms, latencyFromAff.toInt())
            }
            latencyFromAff != null && latencyFromAff < 0L -> {
                binding.tvMetricLatency.text = getString(R.string.home_metric_latency_fail)
            }
            else -> {
                binding.tvMetricLatency.text = getString(R.string.home_metric_latency_unknown)
            }
        }
    }

    /**
     * Connectivity test payload examples:
     * - "Success: Connection took 123ms"
     * - "连接成功：延时 123 毫秒"
     * - optional second line: "(US) 1.2.3.4"
     */
    private fun applyTestResultMetrics(content: String?) {
        if (content.isNullOrBlank()) return

        val latencyMatch = Regex("""(?i)(?:took|延时)\s*(\d+)\s*(?:ms|毫秒)?|(\d+)\s*ms\b""")
            .find(content)
        val latency = latencyMatch?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.toLongOrNull()
        if (latency != null) {
            lastLatencyMs = latency
        } else if (
            content.contains("Fail", ignoreCase = true) ||
            content.contains("失败") ||
            content.contains("error", ignoreCase = true)
        ) {
            lastLatencyMs = -1L
        }

        // Prefer IP-API country from trailing "(CC) x.x.x.x"
        val regionMatch = Regex("""\(([A-Za-z]{2}|[^)\n]{1,24})\)\s*\S+""")
            .findAll(content)
            .lastOrNull()
        val region = regionMatch?.groupValues?.getOrNull(1)?.trim()
        if (!region.isNullOrBlank() && !region.equals("unknown", ignoreCase = true)) {
            lastRegion = region.uppercase()
        }

        refreshMetricsFromCache()
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) {
            startV2Ray()
        } else {
            applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
        }
    }

    private fun handleConnectionToggle(wantStart: Boolean) {
        applyRunningState(isLoading = true, isRunning = false)
        if (!wantStart) {
            CoreServiceManager.stopVService(requireContext())
            return
        }
        if (SettingsManager.isVpnMode()) {
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

    private fun startTrafficUpdates() {
        TrafficStatsManager.addDayTrafficListener(dayListener)
    }

    private fun stopTrafficUpdates() {
        TrafficStatsManager.removeDayTrafficListener(dayListener)
        if (view != null) {
            // keep last 24h value visible; only refresh from cache
            binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
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
        if (!isAdded || view == null) return
        binding.tvTestState.text = content ?: getString(R.string.home_tap_to_test)
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (!isAdded || view == null) return

        if (isLoading) {
            binding.tvStatusState.text = getString(R.string.home_status_connecting)
            binding.tvSwitchCaption.text = getString(R.string.home_status_connecting)
            switchReady = false
            binding.switchConnection.isEnabled = false
            return
        }

        isServiceRunning = isRunning
        switchReady = false
        binding.switchConnection.isEnabled = true
        binding.switchConnection.isChecked = isRunning
        switchReady = true

        if (isRunning) {
            binding.tvStatusState.text = getString(R.string.home_status_running)
            binding.tvSwitchCaption.text = getString(R.string.home_status_running)
            binding.switchConnection.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            startTrafficUpdates()
        } else {
            binding.tvStatusState.text = getString(R.string.home_status_stopped)
            binding.tvSwitchCaption.text = getString(R.string.home_status_stopped)
            binding.switchConnection.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.home_tap_to_test))
            // Exit IP no longer valid when disconnected.
            lastRegion = null
            stopTrafficUpdates()
            binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
        }
        refreshSelectedServerMeta()
        refreshMetricsFromCache()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshModeToggle()
            refreshSelectedServerMeta()
            binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
            refreshMetricsFromCache()
            startTrafficUpdates()
        } else {
            stopTrafficUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModeToggle()
        refreshSelectedServerMeta()
        binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
        refreshMetricsFromCache()
        startTrafficUpdates()
    }

    override fun onPause() {
        stopTrafficUpdates()
        super.onPause()
    }

    override fun onDestroyView() {
        stopTrafficUpdates()
        super.onDestroyView()
    }
}