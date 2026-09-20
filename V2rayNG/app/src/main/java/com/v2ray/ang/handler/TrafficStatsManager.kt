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
    // Throttle MMKV persistence: memory map is the source of truth, disk is a backup.
    private const val PERSIST_INTERVAL_MS = 60_000L

    data class SpeedSample(val upBytesPerSec: Long, val downBytesPerSec: Long)

    private val speedListeners = CopyOnWriteArrayList<(SpeedSample) -> Unit>()
    private val dayListeners = CopyOnWriteArrayList<(Long) -> Unit>()
    @Volatile
    private var pollJob: Job? = null
    private var lastQueryTime = 0L
    private val serviceTracking = AtomicBoolean(false)

    // In-memory 24h window (hour -> bytes); MMKV is only a restore/persist backup.
    private val daySamples = linkedMapOf<Long, Long>()
    private var daySamplesLoaded = false
    private var daySamplesDirty = false
    private var lastPersistMs = 0L

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
        // Never touch disk on the caller (composition) thread: serve the cached
        // value now and let the polling coroutine backfill after hydration.
        listener(lastDayBytes.get())
        ensurePolling()
    }

    fun removeDayTrafficListener(listener: (Long) -> Unit) {
        dayListeners.remove(listener)
        stopIfIdle()
    }

    fun currentDayBytes(): Long = lastDayBytes.get()

    fun currentSpeed(): SpeedSample = SpeedSample(lastUpBps.get(), lastDownBps.get())

    /** Persist pending samples (e.g. app background). No-op when nothing changed. */
    @Synchronized
    fun flushDaySamples() {
        persistLocked(force = true)
    }

    @Synchronized
    private fun ensurePolling() {
        if (pollJob != null) return
        if (!serviceTracking.get() && speedListeners.isEmpty() && dayListeners.isEmpty()) return
        lastQueryTime = System.currentTimeMillis()
        lastPersistMs = System.currentTimeMillis()
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            // Hydrate the 24h window off the main thread, then publish it.
            hydrate()
            notifyDay()
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
        // Final backup before stopping the loop.
        persistLocked(force = true)
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
                // Accumulate all proxy outbounds, including custom subscription tags that
                // do not use the TAG_PROXY prefix (upstream #5990: they reported 0 B/s).
                if (stat.tag != AppConfig.TAG_BLOCKED && stat.tag != AppConfig.TAG_DIRECT) {
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
            // Idle tick: prune the in-memory window only, no disk I/O.
            pruneIdle(now)
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
        ensureLoadedLocked(now)
        val hour = now / HOUR_MS
        daySamples[hour] = (daySamples[hour] ?: 0L) + delta
        pruneLocked(now)
        daySamplesDirty = true
        lastDayBytes.set(daySamples.values.sum())
        persistLocked(now = now)
    }

    @Synchronized
    private fun pruneIdle(now: Long) {
        if (!daySamplesLoaded) return
        val before = daySamples.size
        pruneLocked(now)
        if (daySamples.size != before) daySamplesDirty = true
        lastDayBytes.set(daySamples.values.sum())
        persistLocked(now = now)
    }

    @Synchronized
    private fun readDayBytes(): Long {
        ensureLoadedLocked(System.currentTimeMillis())
        return lastDayBytes.get()
    }

    /** Loads the window from MMKV (once per process) and publishes the total. */
    private fun hydrate() {
        readDayBytes()
    }

    /** Must hold the object monitor. Loads MMKV once per process. */
    private fun ensureLoadedLocked(now: Long) {
        if (daySamplesLoaded) return
        daySamples.clear()
        val raw = MmkvManager.decodeSettingsString(SAMPLES_KEY).orEmpty()
        if (raw.isNotEmpty()) {
            val minHour = (now - WINDOW_MS) / HOUR_MS
            raw.split(',').forEach { part ->
                val kv = part.split(':', limit = 2)
                if (kv.size == 2) {
                    val h = kv[0].toLongOrNull()
                    val b = kv[1].toLongOrNull()
                    if (h != null && b != null && h >= minHour) {
                        daySamples[h] = (daySamples[h] ?: 0L) + b
                    }
                }
            }
            // Drop expired buckets on next persist instead of writing immediately.
            daySamplesDirty = true
        }
        daySamplesLoaded = true
        lastDayBytes.set(daySamples.values.sum())
    }

    /** Must hold the object monitor. */
    private fun pruneLocked(now: Long) {
        val minHour = (now - WINDOW_MS) / HOUR_MS
        val it = daySamples.keys.iterator()
        while (it.hasNext()) {
            if (it.next() < minHour) it.remove()
        }
    }

    /** Must hold the object monitor. Throttled MMKV backup. */
    private fun persistLocked(now: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (!daySamplesDirty) return
        if (!force && now - lastPersistMs < PERSIST_INTERVAL_MS) return
        val encoded = daySamples.entries.joinToString(",") { "${it.key}:${it.value}" }
        MmkvManager.encodeSettings(SAMPLES_KEY, encoded)
        daySamplesDirty = false
        lastPersistMs = now
    }
}