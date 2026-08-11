package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.regex.PatternSyntaxException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = mutableListOf<String>() // MmkvManager.decodeServerList()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    var keywordFilter = ""
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    /** Bumped whenever core session is ready (start/soft-restart/already-running). */
    val sessionReadyAction by lazy { MutableLiveData<Long>() }
    /** Human-readable start failure for actionable home dialog. */
    val startFailureAction by lazy { MutableLiveData<String>() }
    /** True while ROOT/VPN is rebinding after network change. */
    val networkRecoveringAction by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val selectionChangedAction by lazy { MutableLiveData<String?>() }
    private var selectedServer: String? = null
    private var broadcastRegistered: Boolean = false

    /**
     * Last UI-observed session intent, persisted across process death (PREF_UI_INTENT_RUNNING).
     * Drives initial switch state and stale-message arbitration on the main thread.
     */
    @Volatile
    private var uiIntentRunning: Boolean = false

    /** Wall-clock of the last user intent flip; used to ignore late cross-process messages. */
    @Volatile
    private var lastIntentFlipAtMs: Long = 0L

    /** Set once a live RUNNING/START_SUCCESS arrived since the last init probe. */
    private var sawLiveConfirm: Boolean = false

    /** One-shot daemon state query (REGISTER) pending result callback. */
    private var stateQueryListener: ((Boolean) -> Unit)? = null
    private var stateQueryJob: Job? = null

    /** Initialization probe: if REGISTER gets no live confirmation, drop the optimistic state. */
    private var initProbeJob: Job? = null

    private companion object {
        /** Late START/STOP messages arriving inside this window after an intent flip are stale. */
        const val STALE_MSG_WINDOW_MS = 3_000L
        /** How long the init probe waits for a live confirmation before showing Stopped. */
        const val INIT_PROBE_TIMEOUT_MS = 3_500L
        /** How long a daemon state query waits for the REGISTER reply. */
        const val STATE_QUERY_TIMEOUT_MS = 2_500L
        /** Debounce window for bursty batch-test result broadcasts (H2). */
        const val MEASURE_DEBOUNCE_MS = 300L
    }

    init {
        uiIntentRunning = MmkvManager.decodeSettingsBool(AppConfig.PREF_UI_INTENT_RUNNING, false)
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        if (!broadcastRegistered) {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
            ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
            broadcastRegistered = true
            // First registration only. Restore the last observed intent instead of flashing
            // Stopped: if the session really is down, the REGISTER reply (or init probe) will
            // correct it; if it is up (UI process was killed, service kept running), no flash.
            if (isRunning.value == null) {
                isRunning.value = uiIntentRunning
                sawLiveConfirm = false
                if (uiIntentRunning) {
                    armInitProbe()
                }
            }
        }
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Records a user intent flip (home switch / widget toggle). Persisted so a restarted UI
     * process can restore the switch; also seeds the stale-message arbitration window.
     */
    fun setUiIntent(running: Boolean) {
        uiIntentRunning = running
        lastIntentFlipAtMs = System.currentTimeMillis()
        MmkvManager.encodeSettings(AppConfig.PREF_UI_INTENT_RUNNING, running)
    }

    /**
     * Queries the daemon for the real session state (REGISTER round-trip) and reports the
     * result on the main thread. Falls back to false after [STATE_QUERY_TIMEOUT_MS] when the
     * daemon is unreachable. Used by timeouts that must not trust main-process singletons.
     */
    fun queryDaemonState(onResult: (Boolean) -> Unit) {
        stateQueryListener = onResult
        stateQueryJob?.cancel()
        stateQueryJob = viewModelScope.launch {
            MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
            delay(STATE_QUERY_TIMEOUT_MS)
            val listener = stateQueryListener
            stateQueryListener = null
            listener?.invoke(false)
        }
    }

    /** While an init probe is pending, a live confirmation retires it early. */
    private fun onLiveConfirmed() {
        sawLiveConfirm = true
        initProbeJob?.cancel()
        initProbeJob = null
    }

    /**
     * Optimistic-running safety valve: if the daemon never confirms the session after a UI
     * restart, drop back to Stopped instead of showing a phantom Running state.
     */
    private fun armInitProbe() {
        initProbeJob?.cancel()
        initProbeJob = viewModelScope.launch {
            delay(INIT_PROBE_TIMEOUT_MS)
            if (!sawLiveConfirm && uiIntentRunning && isRunning.value == true) {
                LogUtil.w(AppConfig.TAG, "MainViewModel: init probe timed out, session not confirmed")
                uiIntentRunning = false
                MmkvManager.encodeSettings(AppConfig.PREF_UI_INTENT_RUNNING, false)
                isRunning.value = false
            }
        }
    }

    /** True when a late message contradicts an intent flip made within the stale window. */
    private fun isStaleForIntent(runningMessage: Boolean): Boolean {
        if (System.currentTimeMillis() - lastIntentFlipAtMs >= STALE_MSG_WINDOW_MS) return false
        return runningMessage != uiIntentRunning
    }

    /**
     * A message was dropped as stale inside the intent window. The flip may have come from
     * another entry point (widget / QS tile, which only write MMKV and cannot update this
     * VM's flip timestamp), so the dropped message may have been real. Re-confirm the actual
     * daemon state and correct an optimistic UI instead of leaving it stuck.
     */
    private fun confirmStateAfterStaleDrop() {
        if (stateQueryJob?.isActive == true) return // a query is already in flight
        val intentAtQuery = uiIntentRunning
        queryDaemonState { running ->
            if (running) return@queryDaemonState
            // The user may have flipped the intent again while the query was in flight;
            // only correct the UI when the intent is still what we snapshot at query time.
            if (uiIntentRunning != intentAtQuery) return@queryDaemonState
            if (isRunning.value == true) {
                LogUtil.w(AppConfig.TAG, "MainViewModel: stale-drop query confirmed stopped, correcting UI")
                applyStoppedState(null)
            }
        }
    }

    /** Applies a confirmed live session (RUNNING / START_SUCCESS) and reports query results. */
    private fun applyLiveState(queryListener: ((Boolean) -> Unit)?) {
        stateQueryListener = null
        stateQueryJob?.cancel()
        uiIntentRunning = true
        MmkvManager.encodeSettings(AppConfig.PREF_UI_INTENT_RUNNING, true)
        isRunning.value = true
        onLiveConfirmed()
        // Always notify: soft-restart keeps isRunning=true so LiveData alone won't refresh UI.
        notifySessionReady()
        queryListener?.invoke(true)
    }

    /** Applies a confirmed stopped session and reports query results. */
    private fun applyStoppedState(queryListener: ((Boolean) -> Unit)?) {
        stateQueryListener = null
        stateQueryJob?.cancel()
        uiIntentRunning = false
        MmkvManager.encodeSettings(AppConfig.PREF_UI_INTENT_RUNNING, false)
        isRunning.value = false
        queryListener?.invoke(false)
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        if (broadcastRegistered) {
            getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
            broadcastRegistered = false
        }
        LogUtil.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList() {
        viewModelScope.launch(Dispatchers.IO) {
            serverList = if (subscriptionId.isEmpty()) {
                MmkvManager.decodeAllServerList()
            } else {
                MmkvManager.decodeServerList(subscriptionId)
            }

            updateCache()
            serverListLoaded = true
            withContext(Dispatchers.Main) {
                updateListAction.value = -1
            }
        }
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
            rebuildGuidIndex()
        }
    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId.isEmpty()) {
            return
        }

        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(serversCache, fromPosition, toPosition)
        rebuildGuidIndex()

        MmkvManager.encodeServerList(serverList, subscriptionId)
    }

    /**
     * Updates the cache of servers.
     */
    @Synchronized
    fun updateCache() {
        serversCache.clear()
        selectedServer = MmkvManager.getSelectServer()
        val kw = keywordFilter.trim()
        val searchRegex = try {
            if (kw.isNotEmpty()) Regex(kw, setOf(RegexOption.IGNORE_CASE)) else null
        } catch (e: PatternSyntaxException) {
            null
        }
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val testDelay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            if (kw.isEmpty()) {
                serversCache.add(ServersCache(guid, profile, testDelay))
                continue
            }

            val remarks = profile.remarks
            val description = profile.description.orEmpty()
            val server = profile.server.orEmpty()
            val protocol = profile.configType.name
            if (remarks.matchesPattern(searchRegex, kw)
                || description.matchesPattern(searchRegex, kw)
                || server.matchesPattern(searchRegex, kw)
                || protocol.matchesPattern(searchRegex, kw)
            ) {
                serversCache.add(ServersCache(guid, profile, testDelay))
            }
        }
        rebuildGuidIndex()
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                serverList
            } else {
                serversCache.map { it.guid }.toList()
            }

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing(onlyTcp: Boolean = false) {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())
        updateListAction.value = -1

        viewModelScope.launch(Dispatchers.Default) {
            if (serversCache.isEmpty()) {
                return@launch
            }
            MessageUtil.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    subscriptionId = subscriptionId,
                    serverGuids = if (keywordFilter.isNotEmpty()) serversCache.map { it.guid } else emptyList(),
                    onlyTcp = onlyTcp,
                )
            )
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /** Force UI to leave connecting state even when isRunning was already true (soft switch). */
    private fun notifySessionReady() {
        sessionReadyAction.value = System.currentTimeMillis()
    }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        if (subscriptionId != id) {
            subscriptionId = id
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
            reloadServerList()
        } else if (!serverListLoaded) {
            reloadServerList()
        } else {
            // Same subscription re-entered (tab switch / rotation) with the cache still
            // valid: skip the full MMKV re-decode and just re-dispatch the current list.
            // The value is made unique so LiveData observers fire even on repeat entries.
            updateListAction.value = -1 - (++listRefreshSeq)
        }
    }

    /** True once [reloadServerList] populated the cache for the current subscription. */
    @Volatile
    private var serverListLoaded = false
    private var listRefreshSeq = 0

    /** Batch-test result indices awaiting a debounced list refresh (H2). */
    private val batchMeasureIndices = mutableListOf<Int>()
    private var measureDebounceJob: Job? = null

    /**
     * Gets the subscriptions.
     * @param context The context.
     * @return A pair of lists containing the subscription IDs and remarks.
     */
    fun getSubscriptions(context: Context): List<GroupMapItem> {
        val subscriptions = MmkvManager.decodeSubscriptions()
        if (subscriptionId.isNotEmpty()
            && !subscriptions.map { it.guid }.contains(subscriptionId)
        ) {
            subscriptionIdChanged("")
        }

        val groups = mutableListOf<GroupMapItem>()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)) {
            groups.add(
                GroupMapItem(
                    id = "",
                    remarks = context.getString(R.string.filter_config_all)
                )
            )
        }
        subscriptions.forEach { sub ->
            groups.add(
                GroupMapItem(
                    id = sub.guid,
                    remarks = sub.subscription.remarks
                )
            )
        }
        return groups
    }

    fun getSelectedServer(): String? = selectedServer

    fun setSelectedServer(guid: String) {
        selectedServer = guid
        selectionChangedAction.value = guid
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        return guidToPosition[guid] ?: -1
    }

    /** guid -> position index, kept in sync with [serversCache] (H2: O(1) lookups). */
    private val guidToPosition = HashMap<String, Int>()

    private fun rebuildGuidIndex() {
        guidToPosition.clear()
        serversCache.forEachIndexed { index, item -> guidToPosition[item.guid] = index }
    }

    /**
     * Removes duplicate servers.
     * Excludes servers with complex types (Custom, PolicyGroup, or ProxyChain) from duplicate comparison.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val deleteServer = mutableListOf<String>()
        val seen = HashSet<String>()

        for (sc in serversCache) {
            val profile = sc.profile
            if (profile.configType.isComplexType()) {
                continue
            }
            if (!seen.add(JsonUtil.toJson(profile))) {
                deleteServer.add(sc.guid)
            }
        }
        for (it in deleteServer) {
            MmkvManager.removeServer(it)
        }

        return deleteServer.count()
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val count =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val serversCopy = serversCache.toList()
                for (item in serversCopy) {
                    MmkvManager.removeServer(item.guid)
                }
                serversCache.toList().count()
            }
        return count
    }

    /**
     * Removes invalid servers.
     * @return The number of removed servers.
     */
    fun removeInvalidServer(): Int {
        var count = 0
        if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
            count += MmkvManager.removeInvalidServer("")
        } else {
            val serversCopy = serversCache.toList()
            for (item in serversCopy) {
                count += MmkvManager.removeInvalidServer(item.guid)
            }
        }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long)

        val serverDelays = mutableListOf<ServerDelay>()
        val serverListToSort = MmkvManager.decodeServerList(subId)

        serverListToSort.forEach { key ->
            val delay = MmkvManager.decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
            serverDelays.add(ServerDelay(key, if (delay <= 0L) 999999 else delay))
        }
        serverDelays.sortBy { it.testDelayMillis }

        val sortedServerList = serverDelays.map { it.guid }.toMutableList()

        // Save the sorted list for this subscription
        MmkvManager.encodeServerList(sortedServerList, subId)
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        reloadServerList()
    }

    fun findSubscriptionIdBySelect(): String? {
        // Get the selected server GUID
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            return null
        }

        val config = MmkvManager.decodeServerConfig(selectedGuid)
        return config?.subscriptionId
    }

    fun onTestsFinished() {
        viewModelScope.launch(Dispatchers.Default) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)) {
                removeInvalidServer()
            }

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)) {
                sortByTestResults()
            }

            withContext(Dispatchers.Main) {
                reloadServerList()
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val queryListener = stateQueryListener
            // While a state query is in flight, RUNNING/NOT_RUNNING are treated as the
            // authoritative reply and skip stale arbitration - otherwise the very message
            // meant to correct the UI would be swallowed by the same arbitration again
            // (e.g. widget start after a home stop: the RUNNING reply must reach the UI
            // even though it contradicts the home intent). START_SUCCESS/STOP_SUCCESS are
            // never REGISTER replies and keep full arbitration.
            val queryInFlight = stateQueryJob?.isActive == true || queryListener != null
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    // A late RUNNING from a previous start must not revive a user's stop.
                    if (!queryInFlight && isStaleForIntent(runningMessage = true)) {
                        LogUtil.i(AppConfig.TAG, "MainViewModel: ignore stale RUNNING after intent flip")
                        confirmStateAfterStaleDrop()
                        return
                    }
                    applyLiveState(queryListener)
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    // While a query is in flight this IS the authoritative reply (the daemon
                    // already applied its sticky checks before answering NOT_RUNNING).
                    if (queryInFlight) {
                        applyStoppedState(queryListener)
                        return
                    }
                    // Multi-process REGISTER races are common. Never flash Stopped while:
                    // - soft-restart is in flight, or
                    // - a live session/core is still observed.
                    val stickyLive =
                        CoreServiceManager.isSoftRestarting() ||
                            CoreServiceManager.isRunning() ||
                            CoreServiceManager.hasLiveSession()
                    if (stickyLive) {
                        isRunning.value = true
                    } else if (isRunning.value == true) {
                        LogUtil.i(AppConfig.TAG, "MainViewModel: ignore NOT_RUNNING while UI thinks running")
                        // keep isRunning=true; Home confirm-stop owns deferred clear. If this was
                        // an optimistic init restore, armInitProbe() clears it when nothing confirms.
                    } else {
                        applyStoppedState(queryListener)
                    }
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    // A late START_SUCCESS from a previous start must not revive a user's stop.
                    if (isStaleForIntent(runningMessage = true)) {
                        LogUtil.i(AppConfig.TAG, "MainViewModel: ignore stale START_SUCCESS after intent flip")
                        confirmStateAfterStaleDrop()
                        return
                    }
                    // Soft node-switch restarts should not spam "service started" toasts.
                    val content = intent.getStringExtra("content")
                    if (content != AppConfig.MSG_CONTENT_SOFT_START) {
                        getApplication<AngApplication>().toastSuccess(R.string.toast_services_success)
                    }
                    applyLiveState(queryListener)
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    val errorMessage = intent.getStringExtra("content")
                    val msg = errorMessage?.takeIf { it.isNotBlank() }
                        ?: getApplication<AngApplication>().getString(R.string.toast_services_failure)
                    // Toast kept as light feedback; home shows actionable dialog when visible.
                    getApplication<AngApplication>().toastError(msg)
                    applyStoppedState(queryListener)
                    startFailureAction.value = msg
                }

                AppConfig.MSG_STATE_NETWORK_RECOVERING -> {
                    networkRecoveringAction.value = true
                }

                AppConfig.MSG_STATE_NETWORK_RECOVERED -> {
                    networkRecoveringAction.value = false
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    // A late STOP_SUCCESS from a previous stop must not kill a fresh start.
                    if (isStaleForIntent(runningMessage = false)) {
                        LogUtil.i(AppConfig.TAG, "MainViewModel: ignore stale STOP_SUCCESS after intent flip")
                        confirmStateAfterStaleDrop()
                        return
                    }
                    applyStoppedState(queryListener)
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val content = intent.getStringExtra("content")
                    val index = getPosition(content ?: "")
                    if (index >= 0) {
                        batchMeasureIndices.add(index)
                    }
                    // Batch tests fire one broadcast per node at high concurrency; debounce
                    // the burst into a single list refresh per window (H2). One result ->
                    // precise single-row update; several -> one full DiffUtil pass.
                    measureDebounceJob?.cancel()
                    measureDebounceJob = viewModelScope.launch {
                        delay(MEASURE_DEBOUNCE_MS)
                        val indices = batchMeasureIndices
                        batchMeasureIndices = mutableListOf()
                        updateListAction.value =
                            if (indices.size == 1) indices.first()
                            else -1 - (++listRefreshSeq)
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                    val content = intent.getStringExtra("content")
                    updateTestResultAction.value =
                        getApplication<AngApplication>().getString(R.string.connection_runing_task_left, content)
                }

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    // Always refresh the list: the batch finished either by completing or by
                    // being cancelled (cancel now sends finish with "0" too, upstream #6009).
                    onTestsFinished()
                }
            }
        }
    }
}

