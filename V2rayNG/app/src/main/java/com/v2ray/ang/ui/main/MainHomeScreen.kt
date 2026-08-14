package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home connection page (fork layout re-created in Compose).
 * All state/logic comes from upstream MainViewModel/MainAction — no fork logic ported.
 */
@Composable
fun MainHomeScreen(
    isRunning: Boolean,
    isTesting: Boolean,
    statusText: String,
    selectedGuid: String?,
    onAction: (MainAction) -> Unit,
    onOpenSubscriptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selected node remarks
    var nodeRemarks by remember { mutableStateOf("") }
    LaunchedEffect(selectedGuid) {
        nodeRemarks = selectedGuid?.let { MmkvManager.decodeServerConfig(it)?.remarks.orEmpty() } ?: ""
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Cached latency from last test
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedGuid) {
        latencyMs = selectedGuid?.let {
            MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis?.takeIf { d -> d >= 0L }
        }
    }

    // Session traffic (polled while running)
    var uplink by remember { mutableLongStateOf(0L) }
    var downlink by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRunning) {
        while (isRunning) {
            var up = 0L
            var down = 0L
            CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
                when (stat.direction) {
                    AppConfig.UPLINK -> up += stat.value
                    AppConfig.DOWNLINK -> down += stat.value
                }
            }
            uplink = up
            downlink = down
            delay(3000L)
        }
    }

    val statusColor = when {
        isRunning -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.outline
    }
    val statusTitle = when {
        isRunning -> stringResource(R.string.home_status_running)
        else -> stringResource(R.string.home_status_stopped)
    }
    val switchCaption = when {
        isRunning -> statusText.ifBlank { stringResource(R.string.home_status_running) }
        else -> stringResource(R.string.home_status_stopped)
    }

    // Run mode (Proxy / VPN; ROOT button hidden like fork's retired home toggle)
    var rootEnabled by remember {
        mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_MODE_ENABLE, false))
    }
    var prefMode by remember {
        mutableStateOf(MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN) ?: AppConfig.VPN)
    }
    val currentMode = when {
        rootEnabled -> AppConfig.MODE_ROOT
        prefMode == AppConfig.MODE_PROXY_ONLY -> AppConfig.MODE_PROXY_ONLY
        else -> AppConfig.VPN
    }
    val modeHint = when (currentMode) {
        AppConfig.MODE_PROXY_ONLY -> stringResource(R.string.home_mode_hint_proxy)
        AppConfig.MODE_ROOT -> stringResource(R.string.home_mode_hint_root)
        else -> stringResource(R.string.home_mode_hint_vpn)
    }
    fun switchMode(next: String) {
        val changed = when (next) {
            AppConfig.MODE_PROXY_ONLY -> {
                val wasRoot = rootEnabled
                val was = prefMode
                MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
                if (was != AppConfig.MODE_PROXY_ONLY) {
                    MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.MODE_PROXY_ONLY)
                }
                (was != AppConfig.MODE_PROXY_ONLY || wasRoot)
            }
            AppConfig.VPN -> {
                val wasRoot = rootEnabled
                val was = prefMode
                MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
                if (was != AppConfig.VPN) {
                    MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
                }
                (was != AppConfig.VPN || wasRoot)
            }
            AppConfig.MODE_ROOT -> {
                val wasRoot = rootEnabled
                MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, true)
                !wasRoot
            }
            else -> false
        }
        rootEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        prefMode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, AppConfig.VPN) ?: AppConfig.VPN
        if (changed && isRunning) {
            context.toast(R.string.home_mode_switch_restart)
            onAction(MainAction.RestartService)
        }
    }

    fun onModeClick(next: String) {
        if (next == AppConfig.MODE_ROOT) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { RootManager.refresh() }
                if (ok) {
                    switchMode(AppConfig.MODE_ROOT)
                } else {
                    context.toast(R.string.toast_root_mode_unavailable)
                }
            }
        } else {
            switchMode(next)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // ---- Connection status header ----
        Text(
            text = stringResource(R.string.home_connection_status),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = statusTitle,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = statusColor
        )
        Text(
            text = statusText.ifBlank { "" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isTesting) {
                stringResource(R.string.home_status_checking)
            } else if (isRunning) {
                stringResource(R.string.home_tap_to_test)
            } else {
                stringResource(R.string.home_start_first)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = isRunning) { onAction(MainAction.TestCurrentServer) }
        )

        Spacer(Modifier.height(20.dp))

        // ---- VPN switch card ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_mode_vpn),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = switchCaption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isRunning,
                    enabled = selectedGuid != null,
                    onCheckedChange = { onAction(MainAction.ToggleService) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (isRunning) R.string.home_switch_footer_running else R.string.home_switch_footer
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // ---- Status metrics ----
        Text(
            text = stringResource(R.string.home_section_status),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HomeMetric(
                    label = stringResource(R.string.home_metric_region),
                    value = nodeRemarks.ifBlank { stringResource(R.string.home_metric_region_unknown) },
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                VerticalDivider(
                    modifier = Modifier.height(40.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                HomeMetric(
                    label = stringResource(R.string.home_metric_latency),
                    value = latencyMs?.let {
                        stringResource(R.string.home_metric_latency_ms, it.toInt())
                    } ?: stringResource(R.string.home_metric_latency_unknown),
                    valueColor = if (latencyMs != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                VerticalDivider(
                    modifier = Modifier.height(40.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.home_traffic_24h),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "↓ ${formatBytes(downlink)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "↑ ${formatBytes(uplink)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Run mode ----
        Text(
            text = stringResource(R.string.home_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModeButton(
                    label = stringResource(R.string.home_mode_proxy),
                    selected = currentMode == AppConfig.MODE_PROXY_ONLY,
                    onClick = { onModeClick(AppConfig.MODE_PROXY_ONLY) },
                    modifier = Modifier.weight(1f)
                )
                ModeButton(
                    label = stringResource(R.string.home_mode_vpn),
                    selected = currentMode == AppConfig.VPN,
                    onClick = { onModeClick(AppConfig.VPN) },
                    modifier = Modifier.weight(1f)
                )
                ModeButton(
                    label = stringResource(R.string.home_mode_root),
                    selected = currentMode == AppConfig.MODE_ROOT,
                    onClick = { onModeClick(AppConfig.MODE_ROOT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = modeHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // ---- Current node ----
        Text(
            text = stringResource(R.string.home_section_node),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSubscriptions),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_current_node),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = nodeRemarks.ifBlank { stringResource(R.string.home_select_node_hint) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    Text(
                        text = stringResource(R.string.home_goto_subscription_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_change_node),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeMetric(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        ),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(0.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.material3.ButtonDefaults.outlinedButtonBorder
        }
    ) {
        Text(text = label)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
