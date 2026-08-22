package com.v2ray.ang.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.ui.apppicker.AppPickerActivity
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.Utils
import com.v2ray.ang.xposed.PrivilegePortsManager
import com.v2ray.ang.xposed.PrivilegeSettingsClient
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        SettingsScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onModeHelpClicked = { Utils.openUri(this, AppConfig.APP_WIKI_MODE) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onModeHelpClicked: () -> Unit,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var selectedCategory by rememberSaveable { mutableIntStateOf(-1) }

    // Back key pops sub pages first (fork MoreFragment back-stack behavior)
    BackHandler(enabled = selectedCategory != -1) {
        selectedCategory = -1
    }

    var localDns by rememberMmkvBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
    var fakeDns by rememberMmkvBool(AppConfig.PREF_FAKE_DNS_ENABLED, false)
    var appendHttpProxy by rememberMmkvBool(AppConfig.PREF_APPEND_HTTP_PROXY, false)
    var vpnDns by rememberMmkvString(AppConfig.PREF_VPN_DNS, "")
    var vpnBypassLan by rememberMmkvString(AppConfig.PREF_VPN_BYPASS_LAN, "0")
    var vpnInterfaceAddress by rememberMmkvString(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX, "0")
    var vpnMtu by rememberMmkvString(AppConfig.PREF_VPN_MTU, "")

    var mux by rememberMmkvBool(AppConfig.PREF_MUX_ENABLED, false)
    var muxConcurrency by rememberMmkvString(AppConfig.PREF_MUX_CONCURRENCY, "8")
    var muxXudpConcurrency by rememberMmkvString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8")
    var muxXudpQuic by rememberMmkvString(AppConfig.PREF_MUX_XUDP_QUIC, "reject")

    var fragment by rememberMmkvBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
    var fragmentPackets by rememberMmkvString(AppConfig.PREF_FRAGMENT_PACKETS, "tlshello")
    var fragmentLength by rememberMmkvString(AppConfig.PREF_FRAGMENT_LENGTH, "50-100")
    var fragmentInterval by rememberMmkvString(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20")
    var fragmentMaxSplit by rememberMmkvString(AppConfig.PREF_FRAGMENT_MAXSPLIT, "10")
    var observatoryLeastPingInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
    var observatoryLeastLoadInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
    var observatoryLeastLoadMethod by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
    var observatoryLeastLoadSampling by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING, AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING)
    var observatoryLeastLoadTimeout by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)

    var mode by rememberMmkvString(AppConfig.PREF_MODE, VPN)
    var enableRootMode by rememberMmkvBool(AppConfig.PREF_ROOT_MODE_ENABLE, false)
    var lanSharing by rememberMmkvBool(AppConfig.PREF_ROOT_LAN_SHARING, false)

    // Privilege (hidevpn) settings
    var privilegeHideVpn by rememberMmkvBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
    var privilegeHideSelfPackage by rememberMmkvBool(AppConfig.PREF_PRIVILEGE_HIDE_SELF_PACKAGE, false)
    var privilegePorts by rememberMmkvBool(AppConfig.PREF_PRIVILEGE_PORTS, false)
    var privilegeTargetsCount by remember {
        mutableIntStateOf(MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)?.size ?: 0)
    }
    var privilegeModuleSummary by remember { mutableStateOf("") }
    var privilegeSelfTestUi by remember { mutableStateOf<PrivilegeSelfTestUi?>(null) }
    var privilegeSelfTestError by remember { mutableStateOf<String?>(null) }
    var privilegeSelfTestRunning by remember { mutableStateOf(false) }

    var hevTunLogLevel by rememberMmkvString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL, "warning")
    var hevTunRwTimeout by rememberMmkvString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT, "")
    var useHevTun by rememberMmkvBool(AppConfig.PREF_USE_HEV_TUNNEL, true)

    var enableLocalProxy by rememberMmkvBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
    var socksPort by rememberMmkvString(AppConfig.PREF_SOCKS_PORT, "")
    var dynamicSocksPort by rememberMmkvBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
    var socksUsername by rememberMmkvString(AppConfig.PREF_SOCKS_USERNAME, "")
    var socksPassword by rememberMmkvString(AppConfig.PREF_SOCKS_PASSWORD, "")
    var socksEnableUdp by rememberMmkvBool(AppConfig.PREF_SOCKS_ENABLE_UDP, false)
    var proxySharing by rememberMmkvBool(AppConfig.PREF_PROXY_SHARING, false)

    var speedEnabled by rememberMmkvBool(AppConfig.PREF_SPEED_ENABLED, false)
    var confirmRemove by rememberMmkvBool(AppConfig.PREF_CONFIRM_REMOVE, false)
    var doubleColumnDisplay by rememberMmkvBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    var groupAllDisplay by rememberMmkvBool(AppConfig.PREF_GROUP_ALL_DISPLAY, false)
    var language by remember {
        mutableStateOf(
            MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, "auto") ?: "auto"
        )
    }
    var uiModeNight by rememberMmkvString(AppConfig.PREF_UI_MODE_NIGHT, "0")
    var dynamicColor by rememberMmkvBool(AppConfig.PREF_DYNAMIC_COLOR, true)

    var ipv6Enabled by rememberMmkvBool(AppConfig.PREF_IPV6_ENABLED, false)
    var preferIpv6 by rememberMmkvBool(AppConfig.PREF_PREFER_IPV6, false)
    var sniffingEnabled by rememberMmkvBool(AppConfig.PREF_SNIFFING_ENABLED, true)
    var routeOnlyEnabled by rememberMmkvBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
    var remoteDns by rememberMmkvString(AppConfig.PREF_REMOTE_DNS, "")
    var domesticDns by rememberMmkvString(AppConfig.PREF_DOMESTIC_DNS, "")
    var dnsHosts by rememberMmkvString(AppConfig.PREF_DNS_HOSTS, "")
    var coreLogLevel by rememberMmkvString(AppConfig.PREF_LOGLEVEL, "warning")
    var outboundResolveMethod by rememberMmkvString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "0")

    var isBooted by rememberMmkvBool(AppConfig.PREF_IS_BOOTED, false)
    var delayTestUrl by rememberMmkvString(AppConfig.PREF_DELAY_TEST_URL, "")
    var realPingConcurrency by rememberMmkvString(AppConfig.PREF_REAL_PING_CONCURRENCY, "16")
    var ipApiUrl by rememberMmkvString(AppConfig.PREF_IP_API_URL, "")

    val isVpn = mode == VPN
    val hevTunEnabled = isVpn && useHevTun
    val localProxyForced = hevTunEnabled
    val effectiveLocalProxy = enableLocalProxy || localProxyForced
    val muxXudpConcurrencyInt = muxXudpConcurrency.toIntOrNull() ?: 8

    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    LaunchedEffect(dynamicColorSupported) {
        if (!dynamicColorSupported && dynamicColor) {
            dynamicColor = false
            ThemeManager.setDynamicColorEnabled(false)
        }
    }

    val languageEntries = stringArrayResource(R.array.language_select).toList()
    val languageValues = stringArrayResource(R.array.language_select_value).toList()
    val uiModeNightEntries = stringArrayResource(R.array.ui_mode_night).toList()
    val uiModeNightValues = stringArrayResource(R.array.ui_mode_night_value).toList()
    val bypassLanEntries = stringArrayResource(R.array.vpn_bypass_lan).toList()
    val bypassLanValues = stringArrayResource(R.array.vpn_bypass_lan_value).toList()
    val interfaceAddrEntries = stringArrayResource(R.array.vpn_interface_address).toList()
    val interfaceAddrValues = stringArrayResource(R.array.vpn_interface_address_value).toList()
    val hevLogEntries = stringArrayResource(R.array.hev_tunnel_loglevel).toList()
    val hevLogValues = stringArrayResource(R.array.hev_tunnel_loglevel).toList()
    val coreLogLevelEntries = stringArrayResource(R.array.core_loglevel).toList()
    val coreLogLevelValues = stringArrayResource(R.array.core_loglevel).toList()
    val outboundResolveEntries = stringArrayResource(R.array.outbound_domain_resolve_method).toList()
    val outboundResolveValues = stringArrayResource(R.array.outbound_domain_resolve_method_value).toList()
    val xudpQuicEntries = stringArrayResource(R.array.mux_xudp_quic_entries).toList()
    val xudpQuicValues = stringArrayResource(R.array.mux_xudp_quic_value).toList()
    val fragmentPacketsEntries = stringArrayResource(R.array.fragment_packets).toList()
    val fragmentPacketsValues = stringArrayResource(R.array.fragment_packets).toList()
    val observatoryLeastLoadMethodEntries = stringArrayResource(R.array.observatory_least_load_method).toList()
    val observatoryLeastLoadMethodValues = stringArrayResource(R.array.observatory_least_load_method).toList()
    val modeEntries = stringArrayResource(R.array.mode_entries).toList()
    val modeValues = stringArrayResource(R.array.mode_value).toList()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        privilegeModuleSummary = moduleStatusSummary(context)
    }
    val privilegePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val selected = AppPickerActivity.getSelectedPackages(result.data)
        MmkvManager.encodeSettings(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS, selected.toMutableSet())
        runCatching { PrivilegeSettingsClient.sync() }
        privilegeTargetsCount = selected.size
        privilegeModuleSummary = moduleStatusSummary(context)
    }
    fun addSelfToHideTargets() {
        val pkg = context.packageName
        val set = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)?.toMutableSet()
            ?: mutableSetOf()
        if (set.add(pkg)) {
            MmkvManager.encodeSettings(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS, set)
        }
        runCatching { PrivilegeSettingsClient.sync() }
        privilegeTargetsCount = set.size
        Toast.makeText(context, R.string.toast_privilege_add_self_ok, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = if (showTopBar) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0) // embedded in Main tab: outer Scaffold already applies insets
        },
        topBar = {
            // Top bar only when hosted standalone (embedded in More tab it
            // inherits MainTopBar for both the list and sub pages; back key
            // pops sub pages via BackHandler).
            if (showTopBar) {
                AppTopBar(
                    title = stringResource(categoryTitleRes(selectedCategory)),
                    onBackClick = if (selectedCategory == -1) {
                        onBackClick
                    } else {
                        { selectedCategory = -1 }
                    },
                    isLoading = isLoading
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
        ) {
            if (selectedCategory == -1) {
                // Category list (fork settings layout); About is last, after Maintenance
                settingsCategories
                    .filter { it.second != 9 }
                    .forEach { (res, category) ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = stringResource(res),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            trailingContent = {
                                Text(
                                    text = "›",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            },
                            modifier = Modifier.clickable { selectedCategory = category }
                        )
                    }
                // Maintenance / tools entries (fork: title_settings_tools_core)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.title_settings_tools_core),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                maintenanceEntries.forEach { (labelRes, activityClass) ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        trailingContent = {
                            Text(
                                text = "›",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                        },
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(context, activityClass))
                        }
                    )
                }
                // About (last, opens the details page directly)
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.title_about),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    trailingContent = {
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(context, AboutActivity::class.java))
                    }
                )
            } else {
            if (selectedCategory == 0) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_double_column_display),
                    summary = stringResource(R.string.summary_pref_double_column_display),
                    checked = doubleColumnDisplay,
                    onCheckedChange = {
                        doubleColumnDisplay = it
                        SettingsChangeManager.makeSetupGroupTab()
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_group_all_display),
                    summary = stringResource(R.string.summary_pref_group_all_display),
                    checked = groupAllDisplay,
                    onCheckedChange = {
                        groupAllDisplay = it
                        SettingsChangeManager.makeSetupGroupTab()
                    }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_language),
                    entries = languageEntries,
                    values = languageValues,
                    selectedValue = language,
                    onSelected = {
                        language = it
                        AppLocaleManager.setApplicationLanguage(it)
                    }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_ui_mode_night),
                    entries = uiModeNightEntries,
                    values = uiModeNightValues,
                    selectedValue = uiModeNight,
                    onSelected = {
                        uiModeNight = it
                        ThemeManager.setThemeMode(it)
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_dynamic_color),
                    summary = stringResource(R.string.summary_pref_dynamic_color),
                    checked = dynamicColor,
                    enabled = dynamicColorSupported,
                    onCheckedChange = {
                        dynamicColor = it
                        ThemeManager.setDynamicColorEnabled(it)
                    }
                )
            }

            if (selectedCategory == 3) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_ipv6_enabled),
                    summary = stringResource(R.string.summary_pref_ipv6_enabled),
                    checked = ipv6Enabled,
                    onCheckedChange = { ipv6Enabled = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_prefer_ipv6),
                    summary = stringResource(R.string.summary_pref_prefer_ipv6),
                    checked = preferIpv6,
                    onCheckedChange = { preferIpv6 = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_append_http_proxy),
                    summary = stringResource(R.string.summary_pref_append_http_proxy),
                    checked = appendHttpProxy,
                    enabled = effectiveLocalProxy,
                    onCheckedChange = { appendHttpProxy = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_vpn_bypass_lan),
                    entries = bypassLanEntries,
                    values = bypassLanValues,
                    selectedValue = vpnBypassLan,
                    enabled = isVpn,
                    onSelected = { vpnBypassLan = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_vpn_interface_address),
                    entries = interfaceAddrEntries,
                    values = interfaceAddrValues,
                    selectedValue = vpnInterfaceAddress,
                    enabled = isVpn,
                    onSelected = { vpnInterfaceAddress = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_vpn_mtu),
                    value = vpnMtu,
                    enabled = isVpn,
                    keyboardNumber = true,
                    onValueChanged = { vpnMtu = it }
                )
            }

            if (selectedCategory == 4) {
                // Traffic handling
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_sniffing_enabled),
                    summary = stringResource(R.string.summary_pref_sniffing_enabled),
                    checked = sniffingEnabled,
                    onCheckedChange = { sniffingEnabled = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_route_only_enabled),
                    summary = stringResource(R.string.summary_pref_route_only_enabled),
                    checked = routeOnlyEnabled,
                    onCheckedChange = { routeOnlyEnabled = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_outbound_domain_resolve_method),
                    entries = outboundResolveEntries,
                    values = outboundResolveValues,
                    selectedValue = outboundResolveMethod,
                    onSelected = { outboundResolveMethod = it }
                )
                // TUN engine
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_use_hev_tunnel),
                    summary = stringResource(R.string.summary_pref_use_hev_tunnel),
                    checked = useHevTun,
                    enabled = isVpn,
                    onCheckedChange = {
                        useHevTun = it
                        if (it && !enableLocalProxy) {
                            enableLocalProxy = true
                        }
                    }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_hev_tunnel_loglevel),
                    entries = hevLogEntries,
                    values = hevLogValues,
                    selectedValue = hevTunLogLevel,
                    enabled = hevTunEnabled,
                    onSelected = { hevTunLogLevel = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_hev_tunnel_rw_timeout),
                    value = hevTunRwTimeout,
                    enabled = hevTunEnabled,
                    keyboardNumber = true,
                    onValueChanged = { hevTunRwTimeout = it }
                )
                // Local proxy
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_enable_local_proxy),
                    summary = stringResource(R.string.summary_pref_enable_local_proxy),
                    checked = enableLocalProxy,
                    enabled = !localProxyForced,
                    onCheckedChange = {
                        if (!localProxyForced) {
                            enableLocalProxy = it
                            if (!it && appendHttpProxy) {
                                appendHttpProxy = false
                            }
                        }
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_proxy_sharing_enabled),
                    summary = stringResource(R.string.summary_pref_proxy_sharing_enabled),
                    checked = proxySharing,
                    enabled = effectiveLocalProxy,
                    onCheckedChange = { proxySharing = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_dynamic_socks_port),
                    summary = stringResource(R.string.summary_pref_dynamic_socks_port),
                    checked = dynamicSocksPort,
                    enabled = effectiveLocalProxy,
                    onCheckedChange = { dynamicSocksPort = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_socks_port),
                    value = socksPort,
                    enabled = effectiveLocalProxy && !dynamicSocksPort,
                    keyboardNumber = true,
                    onValueChanged = { socksPort = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_socks_username),
                    value = socksUsername,
                    enabled = effectiveLocalProxy,
                    onValueChanged = { socksUsername = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_socks_password),
                    value = socksPassword,
                    enabled = effectiveLocalProxy,
                    isPassword = true,
                    onValueChanged = { socksPassword = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_socks_enable_udp),
                    summary = stringResource(R.string.summary_pref_socks_enable_udp),
                    checked = socksEnableUdp,
                    enabled = effectiveLocalProxy,
                    onCheckedChange = { socksEnableUdp = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_remote_dns),
                    value = remoteDns,
                    onValueChanged = { remoteDns = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_domestic_dns),
                    value = domesticDns,
                    onValueChanged = { domesticDns = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_dns_hosts),
                    value = dnsHosts,
                    onValueChanged = { dnsHosts = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_core_loglevel),
                    entries = coreLogLevelEntries,
                    values = coreLogLevelValues,
                    selectedValue = coreLogLevel,
                    onSelected = { coreLogLevel = it }
                )
            }

            if (selectedCategory == 5) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_mux_enabled),
                    summary = stringResource(R.string.summary_pref_mux_enabled),
                    checked = mux,
                    onCheckedChange = { mux = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_mux_concurrency),
                    value = muxConcurrency,
                    enabled = mux,
                    keyboardNumber = true,
                    onValueChanged = { muxConcurrency = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_mux_xudp_concurrency),
                    value = muxXudpConcurrency,
                    enabled = mux,
                    keyboardNumber = true,
                    onValueChanged = { muxXudpConcurrency = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_mux_xudp_quic),
                    entries = xudpQuicEntries,
                    values = xudpQuicValues,
                    selectedValue = muxXudpQuic,
                    enabled = mux && muxXudpConcurrencyInt >= 0,
                    onSelected = { muxXudpQuic = it }
                )
            }

            if (selectedCategory == 6) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_fragment_enabled),
                    checked = fragment,
                    onCheckedChange = { fragment = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_fragment_packets),
                    entries = fragmentPacketsEntries,
                    values = fragmentPacketsValues,
                    selectedValue = fragmentPackets,
                    enabled = fragment,
                    onSelected = { fragmentPackets = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_length),
                    value = fragmentLength,
                    enabled = fragment,
                    onValueChanged = { fragmentLength = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_interval),
                    value = fragmentInterval,
                    enabled = fragment,
                    onValueChanged = { fragmentInterval = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_maxsplit),
                    value = fragmentMaxSplit,
                    enabled = fragment,
                    keyboardNumber = true,
                    onValueChanged = { fragmentMaxSplit = it }
                )
            }

            if (selectedCategory == 7) {
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_ping_interval),
                    value = observatoryLeastPingInterval,
                    onValueChanged = {
                        viewModel.validateObservatoryDuration(it)?.let { value ->
                            observatoryLeastPingInterval = value
                        }
                    }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_interval),
                    value = observatoryLeastLoadInterval,
                    onValueChanged = {
                        viewModel.validateObservatoryDuration(it)?.let { value ->
                            observatoryLeastLoadInterval = value
                        }
                    }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_method),
                    entries = observatoryLeastLoadMethodEntries,
                    values = observatoryLeastLoadMethodValues,
                    selectedValue = observatoryLeastLoadMethod,
                    onSelected = { observatoryLeastLoadMethod = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_sampling),
                    value = observatoryLeastLoadSampling,
                    keyboardNumber = true,
                    onValueChanged = {
                        viewModel.validateObservatorySampling(it)?.let { value ->
                            observatoryLeastLoadSampling = value
                        }
                    }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_timeout),
                    value = observatoryLeastLoadTimeout,
                    onValueChanged = {
                        viewModel.validateObservatoryDuration(it)?.let { value ->
                            observatoryLeastLoadTimeout = value
                        }
                    }
                )
            }

            if (selectedCategory == 8) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_is_booted),
                    summary = stringResource(R.string.summary_pref_is_booted),
                    checked = isBooted,
                    onCheckedChange = { isBooted = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_speed_enabled),
                    summary = stringResource(R.string.summary_pref_speed_enabled),
                    checked = speedEnabled,
                    onCheckedChange = { speedEnabled = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_confirm_remove),
                    summary = stringResource(R.string.summary_pref_confirm_remove),
                    checked = confirmRemove,
                    onCheckedChange = { confirmRemove = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_delay_test_url),
                    value = delayTestUrl,
                    onValueChanged = { delayTestUrl = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_real_ping_concurrency),
                    value = realPingConcurrency,
                    keyboardNumber = true,
                    onValueChanged = { realPingConcurrency = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_ip_api_url),
                    value = ipApiUrl,
                    onValueChanged = { ipApiUrl = it }
                )
                SettingsMenuItem(
                    title = stringResource(R.string.per_app_proxy_settings),
                    onClick = {
                        context.startActivity(Intent(context, PerAppProxyActivity::class.java))
                    }
                )
            }

            if (selectedCategory == 1) {
                SettingsListItem(
                    title = stringResource(R.string.title_mode),
                    entries = modeEntries,
                    values = modeValues,
                    selectedValue = mode,
                    onSelected = { mode = it }
                )
                SettingsMenuItem(
                    title = stringResource(R.string.title_mode_help),
                    onClick = onModeHelpClicked
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_root_mode_enabled),
                    summary = stringResource(R.string.summary_root_mode_enabled),
                    checked = enableRootMode,
                    onCheckedChange = { newValue ->
                        if (newValue && !RootManager.cachedRoot()) {
                            viewModel.checkAndRequestRoot {
                                enableRootMode = true
                            }
                        } else {
                            enableRootMode = newValue
                        }
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_root_lan_sharing),
                    summary = stringResource(R.string.summary_root_lan_sharing),
                    checked = lanSharing,
                    onCheckedChange = { newValue ->
                        if (newValue && !RootManager.cachedRoot()) {
                            viewModel.checkAndRequestRoot {
                                lanSharing = true
                            }
                        } else {
                            lanSharing = newValue
                        }
                    }
                )
            }

            if (selectedCategory == 2) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_privilege_hide_vpn),
                    summary = stringResource(R.string.summary_pref_privilege_hide_vpn),
                    checked = privilegeHideVpn,
                    onCheckedChange = { newValue ->
                        privilegeHideVpn = newValue
                        if (newValue && (MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS)?.size ?: 0) == 0) {
                            Toast.makeText(context, R.string.privilege_empty_target_warning, Toast.LENGTH_LONG).show()
                        }
                        val ok = runCatching { PrivilegeSettingsClient.sync() }.getOrDefault(false)
                        Toast.makeText(
                            context,
                            if (ok) R.string.toast_privilege_sync_ok else R.string.toast_privilege_sync_fail,
                            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                        privilegeModuleSummary = moduleStatusSummary(context)
                    }
                )
                SettingsMenuItem(
                    title = stringResource(R.string.title_pref_privilege_manage_apps),
                    subtitle = if (privilegeTargetsCount > 0) {
                        context.getString(R.string.summary_pref_privilege_manage_apps_count, privilegeTargetsCount)
                    } else {
                        context.getString(R.string.summary_pref_privilege_manage_apps)
                    },
                    onClick = {
                        val selected = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS).orEmpty()
                        privilegePickerLauncher.launch(
                            AppPickerActivity.createIntent(
                                context,
                                selected,
                                context.getString(R.string.title_privilege_hide_vpn_apps)
                            )
                        )
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_privilege_hide_self_package),
                    summary = stringResource(R.string.summary_pref_privilege_hide_self_package),
                    checked = privilegeHideSelfPackage,
                    onCheckedChange = { newValue ->
                        privilegeHideSelfPackage = newValue
                        runCatching { PrivilegeSettingsClient.sync() }
                        privilegeModuleSummary = moduleStatusSummary(context)
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_privilege_ports),
                    summary = stringResource(R.string.summary_pref_privilege_ports),
                    checked = privilegePorts,
                    onCheckedChange = { newValue ->
                        privilegePorts = newValue
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                PrivilegePortsManager.applyFromPrefs(context.applicationContext)
                            }
                            val msg = when {
                                !newValue && ok -> R.string.toast_privilege_ports_cleared
                                newValue && ok -> R.string.toast_privilege_ports_applied
                                else -> R.string.toast_privilege_ports_failed
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                SettingsMenuItem(
                    title = stringResource(R.string.title_pref_privilege_module_status),
                    subtitle = privilegeModuleSummary,
                    onClick = {
                        val probe = PrivilegeSettingsClient.refresh()
                        val toastText = when (probe.result) {
                            PrivilegeSettingsClient.ProbeResult.ACTIVE ->
                                context.getString(R.string.toast_privilege_module_active)
                            PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE ->
                                context.getString(R.string.toast_privilege_module_loaded_inactive)
                            PrivilegeSettingsClient.ProbeResult.TRANSACTION_UNHANDLED ->
                                context.getString(R.string.toast_privilege_module_unhandled)
                            PrivilegeSettingsClient.ProbeResult.UNAUTHORIZED ->
                                context.getString(R.string.toast_privilege_module_unauthorized)
                            PrivilegeSettingsClient.ProbeResult.BINDER_UNAVAILABLE ->
                                context.getString(R.string.toast_privilege_module_binder)
                            PrivilegeSettingsClient.ProbeResult.ERROR ->
                                context.getString(R.string.toast_privilege_module_error, probe.detail ?: "unknown")
                        }
                        Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
                        runCatching { PrivilegeSettingsClient.sync() }
                        privilegeModuleSummary = moduleStatusSummary(context)
                    }
                )
                SettingsMenuItem(
                    title = stringResource(R.string.title_pref_privilege_self_test),
                    subtitle = if (privilegeSelfTestRunning) {
                        stringResource(R.string.summary_pref_privilege_self_test_running)
                    } else {
                        stringResource(R.string.summary_pref_privilege_self_test)
                    },
                    onClick = {
                        if (privilegeSelfTestRunning) return@SettingsMenuItem
                        privilegeSelfTestRunning = true
                        runPrivilegeSelfTest(context, scope) { ui, errorMessage ->
                            privilegeSelfTestRunning = false
                            if (ui != null) {
                                privilegeSelfTestUi = ui
                            } else {
                                privilegeSelfTestError = errorMessage ?: "unknown"
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    privilegeSelfTestUi?.let { ui ->
        PrivilegeSelfTestDialog(
            ui = ui,
            onCopy = {
                Utils.setClipboard(context, ui.copyText)
                Toast.makeText(context, R.string.toast_privilege_self_test_copied, Toast.LENGTH_SHORT).show()
            },
            onAddSelf = if (ui.selfMissing) {
                {
                    addSelfToHideTargets()
                    privilegeSelfTestUi = null
                }
            } else null,
            onDismiss = { privilegeSelfTestUi = null }
        )
    }
    privilegeSelfTestError?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = { privilegeSelfTestError = null },
            title = { Text(stringResource(R.string.title_privilege_self_test_result)) },
            text = {
                Text(context.getString(R.string.summary_pref_privilege_self_test_error, errorMessage))
            },
            confirmButton = {
                TextButton(onClick = { privilegeSelfTestError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

// Settings category list (fork layout: category entries -> sub pages)
private val settingsCategories = listOf(
    R.string.title_ui_settings to 0,
    R.string.title_mode_settings to 1,
    R.string.title_privilege_settings to 2,
    R.string.title_vpn_settings to 3,
    R.string.title_core_settings to 4,
    R.string.title_mux_settings to 5,
    R.string.title_fragment_settings to 6,
    R.string.title_observatory_settings to 7,
    R.string.title_advanced to 8,
    R.string.title_about to 9
)

// Maintenance / tools entries (fork: pref_entry_* bound activities)
private val maintenanceEntries = listOf(
    R.string.title_user_asset_setting to UserAssetActivity::class.java,
    R.string.title_logcat to LogcatActivity::class.java,
    R.string.update_check_for_update to CheckUpdateActivity::class.java,
    R.string.title_configuration_backup_restore to BackupActivity::class.java
)

@StringRes
private fun categoryTitleRes(category: Int): Int {
    return settingsCategories.firstOrNull { it.second == category }?.first ?: R.string.title_settings
}
