package com.v2ray.ang.ui

import android.net.VpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.v2ray.ang.util.BatteryHelper
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
    /** True while user-initiated start/stop transition is in progress. */
    private var uiConnecting: Boolean = false

    private var lastRegion: String? = null
    private var lastLatencyMs: Long? = null
    private val CONNECTING_TIMEOUT_MS = 10_000L
    private val AUTO_PING_DELAY_MS = 700L
    private var connectingTimeoutJob: Job? = null
    private var autoPingJob: Job? = null
    private var lastAutoPingAtMs: Long = 0L
    private var modeSwitchJob: Job? = null
    private var resyncDebounceJob: Job? = null
    /** Confirm-not-live job: never flip Running鈫扴topped on a single flaky probe. */
    private var stopConfirmJob: Job? = null
    /**
     * Sticky UI truth across tab hide/show and process boundaries.
     * Core runs in :RunSoLibV2RayDaemon 鈥?main-process CoreServiceManager.isRunning()
     * is often false even while the daemon is live. Prefer broadcast + sticky flag.
     */
    private var stickyRunning: Boolean = false
    /** null = unknown/not tested, true = internet OK, false = connected but no internet. */
    private var internetReachable: Boolean? = null
    private var lastFailureMessage: String? = null

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
            // Protect in-flight start/stop transitions from REGISTER races.
            if (uiConnecting) {
                // Intent is encoded in the (disabled) switch checked state:
                // starting => checked true; stopping => checked false.
                val wantRunning = binding.switchConnection.isChecked
                if (isRunning == wantRunning) {
                    applyRunningState(isLoading = false, isRunning = wantRunning)
                }
                return@observe
            }
            if (isRunning == true) {
                cancelStopConfirm()
                applyRunningState(false, true)
                maybePromptBatteryExemption()
            } else {
                // User stop leaves the switch intentionally unchecked 鈥?accept immediately.
                // Otherwise multi-process false negatives only defer-clear sticky Running.
                val userIntentStop = !binding.switchConnection.isChecked
                requestStoppedState(source = "livedata", force = userIntentStop)
            }
        }
        // Soft node-switch keeps isRunning=true, so this explicit ready signal is required
        // to leave Connecting and re-enable the switch without a manual toggle.
        mainViewModel.startFailureAction.observe(viewLifecycleOwner) { msg ->
            if (!isAdded || view == null) return@observe
            showStartFailure(msg)
        }
        mainViewModel.networkRecoveringAction.observe(viewLifecycleOwner) { recovering ->
            if (!isAdded || view == null) return@observe
            showNetworkRecovering(recovering == true)
        }
        mainViewModel.sessionReadyAction.observe(viewLifecycleOwner) {
            if (!isAdded || view == null) return@observe
            // Guard against START_SUCCESS racing a user stop / mode hard-restart.
            val live = mainViewModel.isRunning.value == true ||
                stickyRunning ||
                CoreServiceManager.isRunning()
            if (!live && mainViewModel.isRunning.value != true) return@observe
            cancelStopConfirm()
            stickyRunning = true
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
            stickyRunning ||
                mainViewModel.isRunning.value == true ||
                CoreServiceManager.serviceControl != null ||
                CoreServiceManager.isRunning()
        if (needHardRestart) {
            requireContext().toast(R.string.home_mode_switch_restart)
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
        cancelStopConfirm()
        modeToggleReady = false
        binding.modeToggle.isEnabled = false
        stickyRunning = false
        // Keep switch visually starting for the new mode.
        switchReady = false
        binding.switchConnection.isChecked = true
        applyRunningState(isLoading = true, isRunning = false)
        armConnectingTimeout(timeoutMs = 20_000L)

        // Previous wait used main-process CoreServiceManager flags 鈥?always empty because
        // the core runs in :RunSoLibV2RayDaemon. That made VPN鈫扲OOT start immediately while
        // VPN was still tearing down, so ROOT startCoreLoop failed and UI stayed Connecting.
        CoreServiceManager.stopAllModeServices(requireContext())

        modeSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Let STOP broadcast + stopService hit the daemon and drop the VPN TUN.
                delay(350L)
                var waited = 0
                while (waited < 40 && mainViewModel.isRunning.value == true) {
                    delay(100L)
                    waited++
                }
                // Extra settle: VpnService stopSelf + iface close is async after STOP_SUCCESS.
                delay(600L)
                if (!isAdded || view == null) return@launch
                SettingsChangeManager.consumeRestartService()
                // Start the *new* mode service class (prefs already updated by applyRunMode).
                when (SettingsManager.getRunMode()) {
                    AppConfig.VPN -> {
                        val prepare = VpnService.prepare(requireContext())
                        if (prepare == null) {
                            startV2Ray()
                        } else {
                            host.requestVpnPermission.launch(prepare)
                        }
                    }
                    else -> startV2Ray()
                }
            } finally {
                if (isAdded && view != null) {
                    binding.modeToggle.isEnabled = true
                    modeToggleReady = true
                    refreshModeToggle()
                }
            }
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
        binding.tvModeHint.text = when (mode) {
            AppConfig.MODE_ROOT -> getString(R.string.home_mode_hint_root)
            AppConfig.VPN -> getString(R.string.home_mode_hint_vpn)
            else -> getString(R.string.home_mode_hint_proxy)
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
     * - "杩炴帴鎴愬姛锛氬欢鏃?123 姣"
     * - optional second line: "(US) 1.2.3.4"
     */
    private fun applyTestResultMetrics(content: String?) {
        if (content.isNullOrBlank()) return

        val latencyMatch = Regex("""(?i)(?:took|latency|delay|寤舵椂|寤惰繜)\s*(\d+)\s*(?:ms|姣)?|(\d+)\s*ms\b""")
            .find(content)
        val latency = latencyMatch?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.toLongOrNull()
        if (latency != null) {
            lastLatencyMs = latency
            internetReachable = latency >= 0L
        } else if (
            content.contains("Fail", ignoreCase = true) ||
            content.contains("澶辫触") ||
            content.contains("Unavailable", ignoreCase = true) ||
            content.contains("error", ignoreCase = true) ||
            content.contains("timeout", ignoreCase = true) ||
            content.contains("瓒呮椂")
        ) {
            lastLatencyMs = -1L
            internetReachable = false
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
        if (isServiceRunning || stickyRunning) {
            refreshConnectivityChrome()
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) {
            startV2Ray()
        } else {
            applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
        }
    }

    private fun handleConnectionToggle(wantStart: Boolean) {
        // Visual intent: starting keeps switch ON; stopping keeps OFF while disabled.
        switchReady = false
        binding.switchConnection.isChecked = wantStart
        applyRunningState(isLoading = true, isRunning = false)
        if (!wantStart) {
            stickyRunning = false
            CoreServiceManager.stopVService(requireContext())
            return
        }
        armConnectingTimeout()
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
        internetReachable = null
        setTestState(getString(R.string.home_status_checking))
        refreshConnectivityChrome()
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

    private fun armConnectingTimeout(timeoutMs: Long = CONNECTING_TIMEOUT_MS) {
        clearConnectingTimeout()
        connectingTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(timeoutMs)
            if (!isAdded || view == null) return@launch
            // No START_SUCCESS/FAILURE arrived; recover switch so UI cannot stick forever.
            if (uiConnecting || !binding.switchConnection.isEnabled) {
                val running = stickyRunning ||
                    mainViewModel.isRunning.value == true ||
                    CoreServiceManager.isRunning() ||
                    CoreServiceManager.hasLiveSession()
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
            uiConnecting = true
            cancelAutoConnectivityTest()
            binding.tvStatusState.text = getString(R.string.home_status_connecting)
            binding.tvSwitchCaption.text = getString(R.string.home_status_connecting)
            switchReady = false
            binding.switchConnection.isEnabled = false
            // Keep the user's intended checked state while connecting (start=on / stop=off).
            setTestState(getString(R.string.home_status_connecting))
            return
        }

        uiConnecting = false
        clearConnectingTimeout()

        // No-op when UI already matches 鈥?prevents switch/caption flicker on tab resume.
        if (isServiceRunning == isRunning &&
            binding.switchConnection.isEnabled &&
            binding.switchConnection.isChecked == isRunning
        ) {
            stickyRunning = isRunning
            return
        }

        isServiceRunning = isRunning
        stickyRunning = isRunning
        switchReady = false
        binding.switchConnection.isEnabled = true
        if (binding.switchConnection.isChecked != isRunning) {
            binding.switchConnection.isChecked = isRunning
        }
        switchReady = true

        if (isRunning) {
            binding.switchConnection.contentDescription = getString(R.string.action_stop_service)
            startTrafficUpdates()
            refreshConnectivityChrome()
        } else {
            internetReachable = null
            lastFailureMessage = null
            binding.tvStatusState.text = getString(R.string.home_status_stopped)
            binding.tvSwitchCaption.text = getString(R.string.home_status_stopped)
            binding.switchConnection.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.home_tap_to_test))
            binding.tvStatusDetail.visibility = android.view.View.GONE
            lastRegion = null
            stopTrafficUpdates()
            binding.tvTraffic24h.text = TrafficStatsManager.currentDayBytes().toTrafficString()
        }
        refreshSelectedServerMeta()
        refreshMetricsFromCache()
    }

    /** Status title / caption / detail for Running / Unreachable / Checking. */
    private fun refreshConnectivityChrome() {
        if (!isAdded || view == null) return
        if (!isServiceRunning && !stickyRunning) return
        when (internetReachable) {
            true -> {
                binding.tvStatusState.text = getString(R.string.home_status_running)
                binding.tvSwitchCaption.text = getString(R.string.home_status_running)
                binding.tvStatusDetail.visibility = android.view.View.VISIBLE
                binding.tvStatusDetail.text = getString(R.string.home_status_detail_ok)
            }
            false -> {
                binding.tvStatusState.text = getString(R.string.home_status_unreachable)
                binding.tvSwitchCaption.text = getString(R.string.home_status_unreachable)
                binding.tvStatusDetail.visibility = android.view.View.VISIBLE
                binding.tvStatusDetail.text = getString(R.string.home_status_detail_fail)
            }
            null -> {
                if (uiConnecting) {
                    binding.tvStatusState.text = getString(R.string.home_status_connecting)
                    binding.tvSwitchCaption.text = getString(R.string.home_status_connecting)
                } else {
                    binding.tvStatusState.text = getString(R.string.home_status_running)
                    binding.tvSwitchCaption.text = getString(R.string.home_status_running)
                }
                binding.tvStatusDetail.visibility = android.view.View.GONE
            }
        }
    }

    fun showStartFailure(message: String?) {
        if (!isAdded || view == null) return
        lastFailureMessage = message
        stickyRunning = false
        applyRunningState(isLoading = false, isRunning = false)
        val msg = message?.takeIf { it.isNotBlank() } ?: getString(R.string.toast_services_failure)
        val isRootish = SettingsManager.isRootMode() ||
            msg.contains("ROOT", ignoreCase = true) ||
            msg.contains("su", ignoreCase = true)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_error_title)
            .setMessage(msg)
            .setPositiveButton(R.string.home_error_retry) { _, _ ->
                handleConnectionToggle(wantStart = true)
            }
            .setNegativeButton(R.string.home_error_dismiss, null)
        if (isRootish) {
            builder.setNeutralButton(R.string.home_error_use_vpn) { _, _ ->
                applyRunMode(AppConfig.VPN)
            }
        }
        builder.show()
    }

    fun showNetworkRecovering(active: Boolean) {
        if (!isAdded || view == null) return
        if (!stickyRunning && mainViewModel.isRunning.value != true) return
        if (active) {
            binding.tvStatusState.text = getString(R.string.home_status_reconnecting)
            binding.tvSwitchCaption.text = getString(R.string.home_status_reconnecting)
            binding.tvStatusDetail.visibility = android.view.View.GONE
        } else {
            // Keep sticky running; auto retest.
            stickyRunning = true
            applyRunningState(isLoading = false, isRunning = true)
            scheduleAutoConnectivityTest(reason = "network-recovered")
        }
    }

    /** Flip to Stopped only after confirmation 鈥?never on a single false probe. */
    private fun requestStoppedState(source: String, force: Boolean = false) {
        if (!isAdded || view == null) return
        if (uiConnecting) return
        if (force) {
            cancelStopConfirm()
            stickyRunning = false
            if (mainViewModel.isRunning.value == true) {
                mainViewModel.isRunning.value = false
            }
            applyRunningState(isLoading = false, isRunning = false)
            cancelAutoConnectivityTest()
            return
        }
        if (!stickyRunning && isServiceRunning != true && mainViewModel.isRunning.value != true) {
            applyRunningState(isLoading = false, isRunning = false)
            cancelAutoConnectivityTest()
            return
        }
        // Already Running in UI: keep it, confirm asynchronously.
        if (stopConfirmJob?.isActive == true) return
        LogUtil.i(AppConfig.TAG, "Home: defer Stopped from $source (stickyRunning=$stickyRunning)")
        stopConfirmJob = viewLifecycleOwner.lifecycleScope.launch {
            // Two-phase confirm so REGISTER races and multi-process false negatives settle.
            delay(700L)
            if (!isAdded || view == null || isHidden || uiConnecting) return@launch
            if (mainViewModel.isRunning.value == true) {
                cancelStopConfirm()
                applyRunningState(isLoading = false, isRunning = true)
                return@launch
            }
            // Ask daemon again; ignore main-process CoreServiceManager (wrong process).
            mainViewModel.startListenBroadcast()
            delay(900L)
            if (!isAdded || view == null || isHidden || uiConnecting) return@launch
            if (mainViewModel.isRunning.value == true) {
                applyRunningState(isLoading = false, isRunning = true)
                return@launch
            }
            // Still not running after ~1.6s + re-REGISTER: accept Stopped.
            stickyRunning = false
            applyRunningState(isLoading = false, isRunning = false)
            cancelAutoConnectivityTest()
        }
    }


    /** One-shot battery exemption prompt after a live session (ROOT especially). */
    private fun maybePromptBatteryExemption() {
        if (!isAdded || view == null) return
        // Soft keep-alive; safe no-op when already exempt or already prompted.
        BatteryHelper.maybeRequestIgnoreBatteryOptimizations(requireContext().applicationContext)
    }
    private fun cancelStopConfirm() {
        stopConfirmJob?.cancel()
        stopConfirmJob = null
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
        if (uiConnecting) return
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
        if (uiConnecting) return

        // Prefer sticky + ViewModel broadcast truth over main-process CoreServiceManager.
        // Core lives in :RunSoLibV2RayDaemon; main-process isRunning()/hasLiveSession() are
        // often false even when the proxy is healthy 鈥?that caused Off鈫扥n鈫扥ff switch flicker.
        val uiLive = stickyRunning || mainViewModel.isRunning.value == true || isServiceRunning
        if (uiLive) {
            cancelStopConfirm()
            if (mainViewModel.isRunning.value != true) {
                // Keep ViewModel aligned with sticky until daemon re-confirms.
                mainViewModel.isRunning.value = true
            }
            applyRunningState(isLoading = false, isRunning = true)
            // Quiet re-REGISTER: ViewModel ignores NOT_RUNNING while sticky Running.
            mainViewModel.startListenBroadcast()
            // ROOT: never full-heal on every tab resume - that caused random blackholes.
            // Only touch pipeline when local SOCKS is actually down.
            if (SettingsManager.isRootMode() && !RootProxyManager.isRuntimeLive()) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val err = RootProxyManager.ensureRunning(requireContext().applicationContext)
                        if (err != null && err != RootProxyManager.RootError.REPAIR_BACKED_OFF) {
                            LogUtil.w(AppConfig.TAG, "Home: root ensure on resume failed: $err")
                        }
                    } catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "Home: root ensure on resume exception", e)
                    }
                }
            }
            return
        }

        // UI thinks stopped: still REGISTER once to catch daemon-only sessions after process death.
        mainViewModel.startListenBroadcast()
        requestStoppedState(source = "resync")
    }



    override fun onPause() {
        stopTrafficUpdates()
        super.onPause()
    }

    override fun onDestroyView() {
        resyncDebounceJob?.cancel()
        cancelStopConfirm()
        stopTrafficUpdates()
        clearConnectingTimeout()
        cancelAutoConnectivityTest()
        super.onDestroyView()
    }
}
