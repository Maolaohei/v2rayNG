package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.AppConfig
import java.util.Locale

/**
 * App-side Android log helper.
 *
 * IMPORTANT: this is independent of Xray core loglevel ([AppConfig.PREF_LOGLEVEL]).
 * Core loglevel only controls the Go/Xray runtime; using it here previously hid most
 * ROOT/UI diagnostics (default core level is "warning", so LogUtil.i never printed).
 */
object LogUtil {

    // App diagnostics default to info so installRulesOnly / DNS / smoke lines are visible.
    private const val DEFAULT_LEVEL = "info"
    private const val CACHE_UNSET = Int.MIN_VALUE

    @Volatile
    private var cachedMinPriority: Int = CACHE_UNSET

    private fun parsePriority(level: String?): Int {
        return when ((level ?: DEFAULT_LEVEL).lowercase(Locale.US)) {
            "verbose" -> Log.VERBOSE
            "debug" -> Log.DEBUG
            "info" -> Log.INFO
            "warn", "warning" -> Log.WARN
            "error" -> Log.ERROR
            "none", "off" -> Int.MAX_VALUE
            else -> Log.INFO
        }
    }

    /**
     * Kept for API compatibility with preference listeners.
     * No longer bound to core loglevel; always re-applies the app-side default.
     */
    @Suppress("unused")
    fun refreshLogLevel() {
        cachedMinPriority = parsePriority(DEFAULT_LEVEL)
    }

    private fun minPriority(): Int {
        val cached = cachedMinPriority
        if (cached != CACHE_UNSET) {
            return cached
        }
        return synchronized(this) {
            val current = cachedMinPriority
            if (current != CACHE_UNSET) {
                current
            } else {
                parsePriority(DEFAULT_LEVEL).also {
                    cachedMinPriority = it
                }
            }
        }
    }

    private fun isEnabled(priority: Int): Boolean {
        return priority >= minPriority()
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(priority)) return

        try {
            when {
                throwable == null -> Log.println(priority, tag, message)
                priority >= Log.ERROR -> Log.e(tag, message, throwable)
                priority == Log.WARN -> Log.w(tag, message, throwable)
                priority == Log.INFO -> Log.i(tag, message, throwable)
                priority == Log.DEBUG -> Log.d(tag, message, throwable)
                else -> Log.v(tag, message, throwable)
            }
        } catch (_: Throwable) {
            // android.util.Log is unavailable on plain JVM unit tests; never crash callers.
        }
    }

    fun d(tag: String = AppConfig.TAG, message: String) = log(Log.DEBUG, tag, message)
    fun i(tag: String = AppConfig.TAG, message: String) = log(Log.INFO, tag, message)
    fun w(tag: String = AppConfig.TAG, message: String) = log(Log.WARN, tag, message)
    fun e(tag: String = AppConfig.TAG, message: String) = log(Log.ERROR, tag, message)

    fun d(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.DEBUG, tag, message, throwable)
    fun i(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.INFO, tag, message, throwable)
    fun w(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.ERROR, tag, message, throwable)
}
