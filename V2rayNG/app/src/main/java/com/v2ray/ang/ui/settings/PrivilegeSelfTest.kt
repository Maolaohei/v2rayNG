package com.v2ray.ang.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.xposed.DetectionResult
import com.v2ray.ang.xposed.PrivilegePortsManager
import com.v2ray.ang.xposed.PrivilegeSettingsClient
import com.v2ray.ang.xposed.VpnDetectionTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HideVPN self-test: fingerprint scan + module probe, ported from fork (XML dialog
 * replaced by Compose). Verdict logic is kept verbatim; only core-running heuristics
 * were adapted to the upstream 2.3.3 CoreServiceManager API.
 */

data class PrivilegeSelfTestUi(
    val level: String, // pass | partial | fail | setup
    val verdict: String,
    val headline: String,
    val javaClean: Boolean,
    val javaHits: String,
    val javaIfaces: String,
    val nativeSeen: Boolean,
    val nativeIfaces: String,
    val nativeNote: String,
    val moduleText: String,
    val syncText: String,
    val targetsText: String,
    val vpnText: String,
    val portsText: String,
    val meaning: String,
    val copyText: String,
    val selfMissing: Boolean,
)

fun isCoreLikelyRunning(detection: DetectionResult): Boolean {
    if (CoreServiceManager.isRunning() || CoreServiceManager.serviceControl != null) return true
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
    return false
}

fun buildPrivilegeSelfTestUi(
    context: Context,
    detection: DetectionResult,
    probeBefore: PrivilegeSettingsClient.Probe,
    probeAfter: PrivilegeSettingsClient.Probe,
    syncOk: Boolean,
): PrivilegeSelfTestUi {
    fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)
    val yes = getString(R.string.summary_pref_privilege_self_test_yes)
    val no = getString(R.string.summary_pref_privilege_self_test_no)
    val notDetected = getString(R.string.summary_pref_privilege_self_test_not_detected)
    val hideEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
    val targets = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PRIVILEGE_HIDE_VPN_APPS).orEmpty()
    val packageName = context.packageName
    val selfInList = targets.contains(packageName)
    val running = isCoreLikelyRunning(detection)

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

    val hardFramework = detection.frameworkDetected.filterNot { it == "MissingNotVpn" }
    val frameworkIfaces = detection.frameworkInterfaces
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?: notDetected
    val nativeIfacesRaw = detection.nativeInterfaces
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?: notDetected
    val httpProxy = detection.httpProxy?.takeIf { it.isNotBlank() } ?: notDetected
    val javaLeaked = hardFramework.isNotEmpty()
    val nativeSeen = detection.nativeDetected
    val moduleInjected = probeAfter.result == PrivilegeSettingsClient.ProbeResult.ACTIVE ||
        probeAfter.result == PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE
    val moduleActive = probeAfter.result == PrivilegeSettingsClient.ProbeResult.ACTIVE

    val selfPkg = context.packageName
    val perAppOn = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true
    val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS) == true
    val perAppSet = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty()
    val likelyInTunnel = when {
        !running -> false
        !perAppOn || perAppSet.isEmpty() -> true
        bypassApps -> !perAppSet.contains(selfPkg)
        else -> perAppSet.contains(selfPkg)
    }

    val setupIssue = !moduleInjected ||
        probeAfter.result == PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE ||
        !hideEnabled ||
        targets.isEmpty() ||
        !running ||
        !selfInList

    val level = when {
        setupIssue -> "setup"
        javaLeaked -> "fail"
        nativeSeen -> "partial"
        else -> "pass"
    }

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
        javaLeaked ->
            getString(R.string.summary_pref_privilege_self_test_verdict_leaked)
        nativeSeen ->
            getString(R.string.summary_pref_privilege_self_test_verdict_partial_native)
        moduleActive ->
            getString(R.string.summary_pref_privilege_self_test_verdict_clean)
        else ->
            getString(R.string.summary_pref_privilege_self_test_verdict_hook_inactive)
    }

    val headline = when (level) {
        "setup" -> getString(R.string.summary_pref_privilege_self_test_headline_setup)
        "fail" -> getString(R.string.summary_pref_privilege_self_test_headline_fail)
        "partial" -> getString(R.string.summary_pref_privilege_self_test_headline_partial)
        else -> getString(R.string.summary_pref_privilege_self_test_headline_pass)
    }

    val meaningCore = when (level) {
        "setup" -> getString(R.string.summary_pref_privilege_self_test_note)
        "fail" -> getString(R.string.summary_pref_privilege_self_test_meaning_fail)
        "partial" -> getString(R.string.summary_pref_privilege_self_test_meaning_partial)
        else -> getString(R.string.summary_pref_privilege_self_test_meaning_ok)
    }
    val meaning = if (level == "setup") {
        meaningCore
    } else {
        meaningCore + "\n" + getString(R.string.summary_pref_privilege_self_test_note)
    }

    val portsStatus = PrivilegePortsManager.status(context)
    val portsText = when {
        !portsStatus.enabledPref -> getString(R.string.summary_pref_privilege_ports_status_off)
        !portsStatus.root -> getString(R.string.summary_pref_privilege_ports_status_no_root)
        else -> getString(R.string.summary_pref_privilege_ports_status_on, portsStatus.appliedUids)
    }

    val nativeNote = if (nativeSeen) {
        getString(R.string.summary_pref_privilege_self_test_mask_off)
    } else {
        getString(R.string.summary_pref_privilege_self_test_native_ok)
    }

    val copyText = buildString {
        appendLine(verdict)
        appendLine(headline)
        appendLine()
        appendLine(getString(R.string.summary_pref_privilege_self_test_section_java_title))
        appendLine(
            if (javaLeaked) {
                getString(
                    R.string.summary_pref_privilege_self_test_java_hit,
                    hardFramework.joinToString(", "),
                )
            } else {
                getString(R.string.summary_pref_privilege_self_test_java_ok)
            },
        )
        appendLine(getString(R.string.summary_pref_privilege_self_test_framework_ifaces, frameworkIfaces))
        appendLine()
        appendLine(getString(R.string.summary_pref_privilege_self_test_section_native_title))
        if (nativeSeen) {
            appendLine(
                getString(
                    R.string.summary_pref_privilege_self_test_native_seen,
                    nativeIfacesRaw,
                ),
            )
        } else {
            appendLine(getString(R.string.summary_pref_privilege_self_test_native_ok))
        }
        appendLine()
        appendLine(getString(R.string.summary_pref_privilege_self_test_section_conditions_title))
        appendLine(getString(R.string.summary_pref_privilege_self_test_setup_module, moduleText(probeAfter)))
        appendLine(getString(R.string.summary_pref_privilege_self_test_setup_sync, if (syncOk) yes else no))
        appendLine(
            getString(
                R.string.summary_pref_privilege_self_test_setup_targets,
                targets.size,
                if (selfInList) yes else no,
            ),
        )
        appendLine(
            getString(
                R.string.summary_pref_privilege_self_test_setup_vpn,
                if (running) yes else no,
                if (likelyInTunnel) yes else no,
            ),
        )
        appendLine(getString(R.string.summary_pref_privilege_self_test_setup_ports, portsText))
        appendLine()
        appendLine(getString(R.string.summary_pref_privilege_self_test_section_note_title))
        appendLine(meaning)
        appendLine()
        appendLine(getString(R.string.summary_pref_privilege_self_test_section_fingerprints))
        appendLine(getString(R.string.summary_pref_privilege_self_test_http_proxy_line, httpProxy))
        appendLine(
            getString(
                R.string.summary_pref_privilege_self_test_module_before,
                moduleText(probeBefore),
            ),
        )
    }

    return PrivilegeSelfTestUi(
        level = level,
        verdict = verdict,
        headline = headline,
        javaClean = !javaLeaked,
        javaHits = hardFramework.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: notDetected,
        javaIfaces = frameworkIfaces,
        nativeSeen = nativeSeen,
        nativeIfaces = nativeIfacesRaw,
        nativeNote = nativeNote,
        moduleText = getString(
            R.string.summary_pref_privilege_self_test_setup_module,
            moduleText(probeAfter),
        ),
        syncText = getString(
            R.string.summary_pref_privilege_self_test_setup_sync,
            if (syncOk) yes else no,
        ),
        targetsText = getString(
            R.string.summary_pref_privilege_self_test_setup_targets,
            targets.size,
            if (selfInList) yes else no,
        ),
        vpnText = getString(
            R.string.summary_pref_privilege_self_test_setup_vpn,
            if (running) yes else no,
            if (likelyInTunnel) yes else no,
        ),
        portsText = getString(R.string.summary_pref_privilege_self_test_setup_ports, portsText),
        meaning = meaning,
        copyText = copyText,
        selfMissing = hideEnabled && !selfInList,
    )
}

fun runPrivilegeSelfTest(
    context: Context,
    scope: CoroutineScope,
    onResult: (PrivilegeSelfTestUi?, String?) -> Unit,
) {
    scope.launch {
        var ui: PrivilegeSelfTestUi? = null
        var errorMessage: String? = null
        try {
            // Probe-before is for diagnostics only. Apply settings first so hide
            // is active before the fingerprint scan (previous order could false-fail).
            val probeBefore = withContext(Dispatchers.IO) {
                PrivilegeSettingsClient.refresh()
            }
            val syncOk = withContext(Dispatchers.IO) {
                PrivilegeSettingsClient.sync()
            }
            // Brief settle after privilege settings sync.
            withContext(Dispatchers.IO) {
                try {
                    Thread.sleep(350)
                } catch (_: Throwable) {
                }
            }
            val detection = withContext(Dispatchers.IO) {
                VpnDetectionTest.runDetection(context)
            }
            val probeAfter = withContext(Dispatchers.IO) {
                PrivilegeSettingsClient.refresh()
            }
            ui = buildPrivilegeSelfTestUi(
                context = context,
                detection = detection,
                probeBefore = probeBefore,
                probeAfter = probeAfter,
                syncOk = syncOk,
            )
        } catch (e: Throwable) {
            errorMessage = e.message ?: e.javaClass.simpleName
        }
        onResult(ui, errorMessage)
    }
}

fun moduleStatusSummary(context: Context): String {
    val hideEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PRIVILEGE_HIDE_VPN, false)
    val probe = PrivilegeSettingsClient.refresh()
    val status = probe.status
    return when (probe.result) {
        PrivilegeSettingsClient.ProbeResult.ACTIVE -> {
            val ver = status?.version ?: 0
            if (hideEnabled) {
                context.getString(R.string.summary_pref_privilege_module_status_active_on_ver, ver)
            } else {
                context.getString(R.string.summary_pref_privilege_module_status_active_off_ver, ver)
            }
        }
        PrivilegeSettingsClient.ProbeResult.HOOK_LOADED_INACTIVE ->
            context.getString(R.string.summary_pref_privilege_module_status_loaded_inactive)
        PrivilegeSettingsClient.ProbeResult.TRANSACTION_UNHANDLED ->
            context.getString(R.string.summary_pref_privilege_module_status_unhandled)
        PrivilegeSettingsClient.ProbeResult.UNAUTHORIZED ->
            context.getString(R.string.summary_pref_privilege_module_status_unauthorized)
        PrivilegeSettingsClient.ProbeResult.BINDER_UNAVAILABLE ->
            context.getString(R.string.summary_pref_privilege_module_status_binder)
        PrivilegeSettingsClient.ProbeResult.ERROR ->
            context.getString(R.string.summary_pref_privilege_module_status_error)
    }
}

@Composable
fun PrivilegeSelfTestDialog(
    ui: PrivilegeSelfTestUi,
    onCopy: () -> Unit,
    onAddSelf: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val statusColor = when (ui.level) {
        "pass" -> MaterialTheme.colorScheme.primary
        "fail" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_privilege_self_test_result)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Text(
                    text = ui.verdict,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )
                Text(
                    text = ui.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
                Text(
                    text = ui.copyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.summary_pref_privilege_self_test_copy))
                }
                onAddSelf?.let { addSelf ->
                    TextButton(onClick = addSelf) {
                        Text(stringResource(R.string.action_privilege_add_self))
                    }
                }
            }
        }
    )
}
