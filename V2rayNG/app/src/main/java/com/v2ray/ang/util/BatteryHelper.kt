package com.v2ray.ang.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * Soft keep-alive: battery exemption reduces Doze/OEM kills for long-running proxy.
 * Not a guarantee — pairs with FGS + ROOT oom_score.
 */
object BatteryHelper {
    private const val PREF_BATTERY_PROMPT_DONE = "pref_battery_opt_prompt_done"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Request ignore-battery-optimizations once after successful ROOT start.
     * Safe to call repeatedly; only prompts once unless [force].
     */
    @SuppressLint("BatteryLife")
    fun maybeRequestIgnoreBatteryOptimizations(context: Context, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!force && MmkvManager.decodeSettingsBool(PREF_BATTERY_PROMPT_DONE)) return
        if (isIgnoringBatteryOptimizations(context)) {
            MmkvManager.encodeSettings(PREF_BATTERY_PROMPT_DONE, true)
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            MmkvManager.encodeSettings(PREF_BATTERY_PROMPT_DONE, true)
            LogUtil.i(AppConfig.TAG, "BatteryHelper: requested ignore battery optimizations")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "BatteryHelper: request failed, open settings", e)
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                MmkvManager.encodeSettings(PREF_BATTERY_PROMPT_DONE, true)
            } catch (e2: Exception) {
                LogUtil.w(AppConfig.TAG, "BatteryHelper: fallback settings failed", e2)
            }
        }
    }
}
