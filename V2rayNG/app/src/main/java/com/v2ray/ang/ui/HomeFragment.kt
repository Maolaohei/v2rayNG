package com.v2ray.ang.ui

import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.FragmentHomeBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Slim home: power, connectivity test, speeds, mode (VPN / Proxy only), current node summary.
 * Node selection lives in [SubSettingFragment].
 */
class HomeFragment : BaseFragment<FragmentHomeBinding>() {
    override fun inflateBinding(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)

    private val host: MainActivity
        get() = requireActivity() as MainActivity

    val mainViewModel: MainViewModel by activityViewModels()
    private var speedJob: Job? = null
    private var isServiceRunning: Boolean = false
    private var broadcastStarted: Boolean = false
    private var modeToggleReady: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fab.setOnClickListener { handleFabAction() }
        binding.tvTestState.setOnClickListener { handleLayoutTestClick() }
        binding.layoutCurrentNode.setOnClickListener {
            host.openSubscriptionTab()
        }

        setupModeToggle()
        refreshModeToggle()
        refreshSelectedServerMeta()
        setupViewModel()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            SubscriptionUpdater.sync()
        }
        mainViewModel.reloadServerList()

        host.checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
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
            refreshSelectedServerMeta()
        }
        if (!broadcastStarted) {
            mainViewModel.startListenBroadcast()
            broadcastStarted = true
        }
        mainViewModel.initAssets(requireContext().assets)
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

    private fun startSpeedUpdates() {
        stopSpeedUpdates()
        if (!isAdded || view == null) return
        binding.tvSpeedUpload.text = "0 B/s"
        binding.tvSpeedDownload.text = "0 B/s"

        // Always poll core stats for home UI. Notification speed is independent
        // and may be off; previously home reused notification listeners only when
        // pref_speed_enabled, which left UI stuck at 0 when that pref was false.
        speedJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var lastQueryTime = System.currentTimeMillis()
            while (isActive) {
                delay(1000L)
                if (!CoreServiceManager.isRunning()) continue
                val now = System.currentTimeMillis()
                val elapsed = ((now - lastQueryTime).coerceAtLeast(1L)) / 1000.0
                var up = 0L
                var down = 0L
                try {
                    CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
                        if (stat.tag.startsWith(AppConfig.TAG_PROXY)) {
                            when (stat.direction) {
                                AppConfig.UPLINK -> up += stat.value
                                AppConfig.DOWNLINK -> down += stat.value
                            }
                        }
                    }
                } catch (_: Exception) {
                    // core may restart; keep UI stable
                }
                lastQueryTime = now
                val upText = (up / elapsed).toLong().toSpeedString()
                val downText = (down / elapsed).toLong().toSpeedString()
                withContext(Dispatchers.Main) {
                    if (isAdded && view != null) {
                        binding.tvSpeedUpload.text = upText
                        binding.tvSpeedDownload.text = downText
                    }
                }
            }
        }
    }

    private fun stopSpeedUpdates() {
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
        if (!isAdded || view == null) return
        binding.tvTestState.text = content ?: getString(R.string.home_tap_to_test)
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (!isAdded || view == null) return
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
            setTestState(getString(R.string.connection_connected))
            startSpeedUpdates()
        } else {
            binding.fab.setImageResource(R.drawable.ic_power_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            binding.tvStatusState.text = getString(R.string.home_status_stopped)
            setTestState(getString(R.string.home_tap_to_test))
            stopSpeedUpdates()
        }
        refreshSelectedServerMeta()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshModeToggle()
            refreshSelectedServerMeta()
            if (isServiceRunning) {
                startSpeedUpdates()
            }
        } else {
            stopSpeedUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModeToggle()
        refreshSelectedServerMeta()
        if (isServiceRunning) {
            startSpeedUpdates()
        }
    }

    override fun onPause() {
        stopSpeedUpdates()
        super.onPause()
    }

    override fun onDestroyView() {
        stopSpeedUpdates()
        super.onDestroyView()
    }
}