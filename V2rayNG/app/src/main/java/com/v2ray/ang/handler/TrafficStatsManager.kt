package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single owner of outbound traffic polling.
 * [CoreServiceManager.queryAllOutboundTrafficStats] resets counters; only this
 * class may poll so home UI and notification never zero each other out.
 *
 * Service tracking keeps the 24h window accumulating even when no UI is visible.
 */
object TrafficStatsManager {
    private const val QUERY_INTERVAL_MS = 1000L
    private const val SAMPLES_KEY = "traffic_24h_hour_samples"
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val WINDOW_MS = 24L * HOUR_MS

    data class SpeedSample(val upBytesPerSec: Long, val downBytesPerSec: Long)

    private val speedListeners = CopyOnWriteArrayList<(SpeedSample) -> Unit>()
    private val dayListeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private var pollJob: Job? = null
    private var lastQueryTime = 0L
    private val serviceTracking = AtomicBoolean(false)

    private val lastUpBps = AtomicLong(0)
    private val lastDownBps = AtomicLong(0)
    private val lastDayBytes = AtomicLong(0)

    fun startServiceTracking() {
        serviceTracking.set(true)
        ensurePolling()
    }

    fun stopServiceTracking() {
        serviceTracking.set(false)
        lastUpBps.set(0)
        lastDownBps.set(0)
        notifySpeed()
        stopIfIdle()
    }

    fun addSpeedListener(listener: (SpeedSample) -> Unit) {
        speedListeners.addIfAbsent(listener)
        listener(SpeedSample(lastUpBps.get(), lastDownBps.get()))
        ensurePolling()
    }

    fun removeSpeedListener(listener: (SpeedSample) -> Unit) {
        speedListeners.remove(listener)
        stopIfIdle()
    }

    fun addDayTrafficListener(listener: (Long) -> Unit) {
        dayListeners.addIfAbsent(listener)
        listener(currentDayBytes())
        ensurePolling()
    }

    fun removeDayTrafficListener(listener: (Long) -> Unit) {
        dayListeners.remove(listener)
        stopIfIdle()
    }

    fun currentDayBytes(): Long {
        val cached = lastDayBytes.get()
        return if (cached > 0L || pollJob != null) cached else readDayBytes()
    }

    fun currentSpeed(): SpeedSample = SpeedSample(lastUpBps.get(), lastDownBps.get())

    @Synchronized
    private fun ensurePolling() {
        if (pollJob != null) return
        if (!serviceTracking.get() && speedListeners.isEmpty() && dayListeners.isEmpty()) return
        lastQueryTime = System.currentTimeMillis()
        // hydrate day total once
        lastDayBytes.set(readDayBytes())
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(QUERY_INTERVAL_MS)
                pollOnce()
            }
        }
    }

    @Synchronized
    private fun stopIfIdle() {
        if (serviceTracking.get()) return
        if (speedListeners.isNotEmpty() || dayListeners.isNotEmpty()) return
        pollJob?.cancel()
        pollJob = null
        lastUpBps.set(0)
        lastDownBps.set(0)
    }

    private fun pollOnce() {
        if (!CoreServiceManager.isRunning()) {
            lastUpBps.set(0)
            lastDownBps.set(0)
            notifySpeed()
            return
        }

        val now = System.currentTimeMillis()
        val elapsedMs = (now - lastQueryTime).coerceAtLeast(1L)
        val elapsedSec = elapsedMs / 1000.0
        lastQueryTime = now

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
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "TrafficStatsManager poll failed", e)
            return
        }

        val upBps = (up / elapsedSec).toLong().coerceAtLeast(0L)
        val downBps = (down / elapsedSec).toLong().coerceAtLeast(0L)
        lastUpBps.set(upBps)
        lastDownBps.set(downBps)

        val delta = (up + down).coerceAtLeast(0L)
        if (delta > 0L) {
            addToDayWindow(now, delta)
        } else {
            // Keep window pruned even with idle traffic
            lastDayBytes.set(readDayBytes())
        }

        notifySpeed()
        notifyDay()
    }

    private fun notifySpeed() {
        val sample = SpeedSample(lastUpBps.get(), lastDownBps.get())
        speedListeners.forEach { it(sample) }
    }

    private fun notifyDay() {
        val total = lastDayBytes.get()
        dayListeners.forEach { it(total) }
    }

    @Synchronized
    private fun addToDayWindow(now: Long, delta: Long) {
        val hour = now / HOUR_MS
        val map = linkedMapOf<Long, Long>()
        val raw = MmkvManager.decodeSettingsString(SAMPLES_KEY).orEmpty()
        if (raw.isNotEmpty()) {
            raw.split(',').forEach { part ->
                val kv = part.split(':', limit = 2)
                if (kv.size == 2) {
                    val h = kv[0].toLongOrNull()
                    val b = kv[1].toLongOrNull()
                    if (h != null && b != null) map[h] = b
                }
            }
        }
        map[hour] = (map[hour] ?: 0L) + delta
        val minHour = (now - WINDOW_MS) / HOUR_MS
        val pruned = map.filterKeys { it >= minHour }
        val encoded = pruned.entries.joinToString(",") { "${it.key}:${it.value}" }
        MmkvManager.encodeSettings(SAMPLES_KEY, encoded)
        lastDayBytes.set(pruned.values.sum())
    }

    @Synchronized
    private fun readDayBytes(): Long {
        val now = System.currentTimeMillis()
        val minHour = (now - WINDOW_MS) / HOUR_MS
        val raw = MmkvManager.decodeSettingsString(SAMPLES_KEY).orEmpty()
        if (raw.isEmpty()) {
            lastDayBytes.set(0)
            return 0
        }
        var total = 0L
        val kept = mutableListOf<String>()
        raw.split(',').forEach { part ->
            val kv = part.split(':', limit = 2)
            if (kv.size == 2) {
                val h = kv[0].toLongOrNull()
                val b = kv[1].toLongOrNull()
                if (h != null && b != null && h >= minHour) {
                    total += b
                    kept.add("$h:$b")
                }
            }
        }
        MmkvManager.encodeSettings(SAMPLES_KEY, kept.joinToString(","))
        lastDayBytes.set(total)
        return total
    }
}