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
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.TrafficStatsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * Slim home: system switch, connectivity test, region/latency/24h traffic,
 * mode (Proxy only / VPN / ROOT), current node summary.
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
    private val CONNECTING_TIMEOUT_MS = 10_000L
    private val AUTO_PING_DELAY_MS = 700L
    private var connectingTimeoutJob: Job? = null
    private var autoPingJob: Job? = null
    private var lastAutoPingAtMs: Long = 0L
    private var modeSwitchJob: Job? = null
    private var resyncDebounceJob: Job? = null

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
        // Probe root off-main so ROOT button affordance can update without blocking first paint.
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { RootManager.refresh() }
            if (isAdded && view != null) refreshModeToggle()
        }
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
            if (isRunning != true) {
                // Session-ready owns auto region/latency tests to avoid double fire with START_SUCCESS.
                cancelAutoConnectivityTest()
            }
        }
        // Soft node-switch keeps isRunning=true, so this explicit ready signal is required
        // to leave Connecting and re-enable the switch without a manual toggle.
        mainViewModel.sessionReadyAction.observe(viewLifecycleOwner) {
            if (!isAdded || view == null) return@observe
            // Guard against START_SUCCESS racing a user stop / mode hard-restart.
            val live = mainViewModel.isRunning.value == true || CoreServiceManager.isRunning()
            if (!live) return@observe
            applyRunningState(false, true)
            scheduleAutoConnectivityTest(reason = "session-ready")
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
            val next = when (checkedId) {
                R.id.btn_mode_root -> AppConfig.MODE_ROOT
                R.id.btn_mode_vpn -> AppConfig.VPN
                else -> AppConfig.MODE_PROXY_ONLY
            }
            val current = SettingsManager.getRunMode()
            if (current == next) return@addOnButtonCheckedListener
            if (next == AppConfig.MODE_ROOT) {
                // Request root first; only then commit mode.
                modeToggleReady = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { RootManager.refresh() }
                    if (!isAdded || view == null) return@launch
                    if (!ok) {
                        requireContext().toastError(R.string.toast_root_mode_unavailable)
                        refreshModeToggle()
                        return@launch
                    }
                    applyRunMode(next)
                }
                return@addOnButtonCheckedListener
            }
            applyRunMode(next)
        }
        modeToggleReady = true
    }

    private fun applyRunMode(next: String) {
        val changed = SettingsManager.setRunMode(next)
        refreshModeToggle()
        if (!changed) return
        // Switching between Proxy / VPN / ROOT changes service class; soft-restart is not enough.
        val needHardRestart =
            mainViewModel.isRunning.value == true ||
                CoreServiceManager.serviceControl != null ||
                CoreServiceManager.isRunning()
        if (needHardRestart) {
            hardRestartForCurrentMode()
        }
    }

    /**
     * Hard-restart current mode after settings/service-class changes.
     * Soft-restart is not enough when switching Proxy / VPN / ROOT service implementations.
     */
    fun hardRestartForCurrentMode() {
        if (!isAdded) return
        SettingsChangeManager.consumeRestartService()
        modeSwitchJob?.cancel()
        modeToggleReady = false
        binding.modeToggle.isEnabled = false
        applyRunningState(isLoading = true, isRunning = false)
        CoreServiceManager.stopVService(requireContext())
        modeSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            var waited = 0
            while (
                waited < 30 &&
                (
                    CoreServiceManager.serviceControl != null ||
                        CoreServiceManager.isRunning() ||
                        CoreServiceManager.isSoftRestarting()
                )
            ) {
                delay(100L)
                waited++
            }
            if (!isAdded) return@launch
            SettingsChangeManager.consumeRestartService()
            binding.modeToggle.isEnabled = true
            modeToggleReady = true
            refreshModeToggle()
            handleConnectionToggle(wantStart = true)
        }
    }

    private fun refreshModeToggle() {
        if (!isAdded || view == null) return
        val mode = SettingsManager.getRunMode()
        modeToggleReady = false
        when (mode) {
            AppConfig.MODE_ROOT -> binding.modeToggle.check(R.id.btn_mode_root)
            AppConfig.VPN -> binding.modeToggle.check(R.id.btn_mode_vpn)
            else -> binding.modeToggle.check(R.id.btn_mode_proxy)
        }
        // Keep ROOT visible for root users; dim when we already know root is unavailable.
        // Still clickable so users can re-request su after granting superuser.
        val rootKnownUnavailable = !RootManager.cachedRoot() && mode != AppConfig.MODE_ROOT
        binding.btnModeRoot.alpha = if (rootKnownUnavailable) 0.55f else 1.0f
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
        when (SettingsManager.getRunMode()) {
            AppConfig.VPN -> {
                val intent = VpnService.prepare(requireContext())
                if (intent == null) {
                    startV2Ray()
                } else {
                    host.requestVpnPermission.launch(intent)
                }
            }
            else -> startV2Ray() // Proxy only / ROOT
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
        // Soft-apply selected profile while connected.
        // Hard stop + fixed delay start races with async stopLoop and can leave the
        // core on the old config (or half-stopped) while home stays on "Testing...".
        if (!isAdded) return
        lastRegion = null
        lastLatencyMs = null
        refreshSelectedServerMeta()
        refreshMetricsFromCache()
        applyRunningState(isLoading = true, isRunning = false)
        CoreServiceManager.applySelectedServer(requireContext())
        // Soft-restart does not emit STOP; keep connectivity label out of Testing...
        setTestState(getString(R.string.home_status_connecting))
        armConnectingTimeout()
    }

    private fun scheduleAutoConnectivityTest(reason: String) {
        if (!isAdded || view == null) return
        if (mainViewModel.isRunning.value != true && !CoreServiceManager.isRunning()) return
        // Debounce rapid soft-restarts / double ready signals.
        val now = System.currentTimeMillis()
        if (now - lastAutoPingAtMs < 2500L) {
            return
        }
        if (autoPingJob != null) {
            return
        }
        setTestState(getString(R.string.connection_test_testing))
        autoPingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                delay(AUTO_PING_DELAY_MS)
                if (!isAdded || view == null) return@launch
                if (mainViewModel.isRunning.value != true && !CoreServiceManager.isRunning()) return@launch
                if (CoreServiceManager.isSoftRestarting()) {
                    // Core still applying; try once more shortly.
                    delay(500L)
                    if (!isAdded || view == null) return@launch
                    if (mainViewModel.isRunning.value != true && !CoreServiceManager.isRunning()) return@launch
                    if (CoreServiceManager.isSoftRestarting()) return@launch
                }
                lastAutoPingAtMs = System.currentTimeMillis()
                mainViewModel.testCurrentServerRealPing()
            } finally {
                autoPingJob = null
            }
        }
    }

    private fun cancelAutoConnectivityTest() {
        autoPingJob?.cancel()
        autoPingJob = null
    }

    private fun armConnectingTimeout() {
        clearConnectingTimeout()
        connectingTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (!isAdded || view == null) return@launch
            // No START_SUCCESS/FAILURE arrived; recover switch so UI cannot stick forever.
            if (!binding.switchConnection.isEnabled) {
                val running = mainViewModel.isRunning.value == true || CoreServiceManager.isRunning()
                applyRunningState(isLoading = false, isRunning = running)
            }
        }
    }

    private fun clearConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
    }

    private fun setTestState(content: String?) {
        if (!isAdded || view == null) return
        binding.tvTestState.text = content ?: getString(R.string.home_tap_to_test)
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (!isAdded || view == null) return

        if (isLoading) {
            cancelAutoConnectivityTest()
            binding.tvStatusState.text = getString(R.string.home_status_connecting)
            binding.tvSwitchCaption.text = getString(R.string.home_status_connecting)
            switchReady = false
            binding.switchConnection.isEnabled = false
            setTestState(getString(R.string.home_status_connecting))
            return
        }

        clearConnectingTimeout()

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
            // Primary path for bottom-nav show(); onResume alone is not enough while hidden.
            scheduleResyncConnectionState()
            refreshModeToggle()
            refreshSelectedServerMeta()
            binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
            refreshMetricsFromCache()
            startTrafficUpdates()
        } else {
            resyncDebounceJob?.cancel()
            stopTrafficUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        // Avoid double REGISTER when hide/show also fires onHiddenChanged(false).
        if (!isHidden) {
            scheduleResyncConnectionState()
            refreshModeToggle()
            refreshSelectedServerMeta()
            binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
            refreshMetricsFromCache()
            startTrafficUpdates()
        }
    }

    /** Collapse onHiddenChanged + onResume double fire into one REGISTER/resync. */
    private fun scheduleResyncConnectionState() {
        if (!isAdded || view == null) return
        resyncDebounceJob?.cancel()
        resyncDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(250L)
            if (!isAdded || view == null || isHidden) return@launch
            resyncConnectionState()
        }
    }

    /**
     * Returning to the home tab must not flash Stopped while the service is still live.
     * hide/show tab switches re-query state; REGISTER can race, so prefer service-side live signals.
     */
    private fun resyncConnectionState() {
        if (!isAdded || view == null) return
        // Ask service to re-emit RUNNING / NOT_RUNNING (ViewModel guards stale NOT_RUNNING).
        mainViewModel.startListenBroadcast()

        val managerLive = CoreServiceManager.hasLiveSession() || CoreServiceManager.isRunning()
        if (managerLive) {
            if (mainViewModel.isRunning.value != true) {
                mainViewModel.isRunning.value = true
            }
            applyRunningState(isLoading = false, isRunning = true)
            // ROOT: heal hev/rules if they died while app was in background.
            if (SettingsManager.isRootMode() && !CoreServiceManager.isSoftRestarting()) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val err = RootProxyManager.ensureRunning(requireContext().applicationContext)
                    if (err != null && err != RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                        LogUtil.w(AppConfig.TAG, "Home: root ensure on resume failed: $err")
                    }
                }
            }
            return
        }

        // Manager says not live. If ViewModel still thinks running, keep UI briefly and
        // re-check after a short delay to absorb REGISTER races without sticky false Running.
        if (mainViewModel.isRunning.value == true) {
            applyRunningState(isLoading = false, isRunning = true)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(400L)
                if (!isAdded || view == null || isHidden) return@launch
                val stillLive = CoreServiceManager.hasLiveSession() || CoreServiceManager.isRunning()
                if (!stillLive && mainViewModel.isRunning.value == true) {
                    mainViewModel.isRunning.value = false
                    applyRunningState(isLoading = false, isRunning = false)
                } else if (stillLive) {
                    if (mainViewModel.isRunning.value != true) {
                        mainViewModel.isRunning.value = true
                    }
                    applyRunningState(isLoading = false, isRunning = true)
                }
            }
            return
        }
        applyRunningState(isLoading = false, isRunning = false)
    }



    override fun onPause() {
        stopTrafficUpdates()
        super.onPause()
    }

    override fun onDestroyView() {
        resyncDebounceJob?.cancel()
        stopTrafficUpdates()
        clearConnectingTimeout()
        cancelAutoConnectivityTest()
        super.onDestroyView()
    }
}
