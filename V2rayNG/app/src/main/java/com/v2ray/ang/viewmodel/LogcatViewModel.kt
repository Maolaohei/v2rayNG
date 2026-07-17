package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.util.LogUtil
import java.io.IOException

class LogcatViewModel : ViewModel() {
    private val logsetsAll: MutableList<String> = mutableListOf()
    private var filteredLogs: List<String> = emptyList()
    private var currentFilter: String = ""

    fun getAll(): List<String> = filteredLogs

    /**
     * Dump recent logcat for app diagnostics.
     *
     * Notes:
     * - Does NOT use `logcat -c` (that wipes the device ring buffer and makes the page look
     *   "self-clearing" on next refresh).
     * - Filter is broader than the old `-s GoLog,pkg,...` which dropped most ROOT lines when
     *   tags/priorities did not match exactly.
     * - Newest first for reading; cap keeps UI responsive.
     */
    fun loadLogcat() {
        try {
            // threadtime is more readable; -t caps how much of the ring buffer we pull.
            val cmd = arrayOf(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                "3000",
                // Silent everything, then re-enable the tags we care about.
                "*:S",
                "GoLog:V",
                "$ANG_PACKAGE:V",
                "AndroidRuntime:E",
                "System.err:W",
                "libc:F",
            )
            val process = Runtime.getRuntime().exec(cmd)
            val allText = process.inputStream.bufferedReader().use { it.readLines() }
            val errText = process.errorStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()

            // Newest first.
            val lines = allText.asReversed().toMutableList()
            if (lines.isEmpty()) {
                lines.add(
                    "No matching log lines yet. " +
                        "Open ROOT/VPN once, then pull to refresh. " +
                        "App LogUtil defaults to info (independent of core loglevel)."
                )
                if (errText.isNotEmpty()) {
                    lines.add("logcat stderr: $errText")
                }
            }

            logsetsAll.clear()
            logsetsAll.addAll(lines)
            applyFilter()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Failed to get logcat", e)
            logsetsAll.clear()
            logsetsAll.add("Failed to get logcat: ${e.message}")
            applyFilter()
        }
    }

    /**
     * Clear only the in-app view.
     * Avoid `logcat -c` by default: it empties the system ring buffer for this process and
     * makes subsequent refreshes look like the app "cleared logs by itself".
     */
    fun clearLogcat(wipeSystemBuffer: Boolean = false) {
        try {
            if (wipeSystemBuffer) {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                process.waitFor()
            }
            logsetsAll.clear()
            filteredLogs = emptyList()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Failed to clear logcat", e)
        }
    }

    fun filter(content: String?) {
        currentFilter = content?.trim() ?: ""
        applyFilter()
    }

    private fun applyFilter() {
        filteredLogs = if (currentFilter.isEmpty()) {
            logsetsAll.toList()
        } else {
            logsetsAll.filter { it.contains(currentFilter, ignoreCase = true) }
        }
    }
}
