package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.routing.RoutingEditActivity
import com.v2ray.ang.ui.routing.RoutingSettingScreen
import com.v2ray.ang.ui.routing.RoutingSettingsViewModel
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.ui.ScannerActivity
import com.v2ray.ang.ui.settings.SettingsScreen
import com.v2ray.ang.ui.settings.SettingsViewModel
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val isTesting = uiState.isTesting
    val displayText = uiState.statusText
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String) -> Unit = { guid ->
        if (confirmRemove) showRemoveConfirm = guid else onAction(MainAction.RemoveServer(guid))
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    var locateInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    val latestDoubleColumnDisplay by rememberUpdatedState(doubleColumnDisplay)

    var groupsInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        // First load: stay on the home page (page 0); later group changes scroll to selection.
        if (!groupsInitialized) {
            groupsInitialized = true
            return@LaunchedEffect
        }
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)
    val latestLocateInProgress by rememberUpdatedState(locateInProgress)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (!latestLocateInProgress && page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    LaunchedEffect(uiState.locateTarget) {
        val target = uiState.locateTarget ?: return@LaunchedEffect
        if (target.groupIndex !in 0 until pagerState.pageCount) {
            mainViewModel.onAction(MainAction.LocateHandled(target))
            return@LaunchedEffect
        }

        locateInProgress = true
        try {
            if (pagerState.settledPage != target.groupIndex) {
                pagerState.navigateToPageOptimized(
                    targetPage = target.groupIndex,
                    animateAdjacentPage = false
                )
            }
            onAction(MainAction.SelectGroup(target.groupId))

            repeat(10) {
                val ready = if (latestDoubleColumnDisplay) {
                    lazyGridStates[target.groupId] != null
                } else {
                    lazyListStates[target.groupId] != null
                }
                if (ready) return@repeat
                delay(16L)
            }

            if (latestDoubleColumnDisplay) {
                lazyGridStates[target.groupId]?.let { gridState ->
                    gridState.scrollToItem(
                        index = target.itemPosition,
                        scrollOffset = -gridState.layoutInfo.viewportSize.height / 3
                    )
                }
            } else {
                lazyListStates[target.groupId]?.let { listState ->
                    listState.scrollToItem(
                        index = target.itemPosition,
                        scrollOffset = -listState.layoutInfo.viewportSize.height / 3
                    )
                }
            }
        } finally {
            delay(32L)
            locateInProgress = false
            mainViewModel.onAction(MainAction.LocateHandled(target))
        }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            MainTopBar(
                isLoading = isLoading,
                showSearch = showSearch,
                searchQuery = searchQuery,
                onSearchQueryChange = { query: String ->
                    searchQuery = query
                    onAction(MainAction.Search(query))
                },
                onSearchClose = {
                    searchQuery = ""
                    onAction(MainAction.Search(""))
                    showSearch = false
                },
                onSearchToggle = { show: Boolean -> showSearch = show },
                    onAction = onAction,
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                        }
                    }
                )
            },
            bottomBar = {
                MainBottomBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            },
            floatingActionButton = {},
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current

            when (selectedTab) {
                MainTab.Home -> {
                    MainHomeScreen(
                        isRunning = isRunning,
                        isTesting = isTesting,
                        statusText = displayText,
                        selectedGuid = selectedGuid,
                        onAction = onAction,
                        onOpenSubscriptions = { selectedTab = MainTab.Subscription },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                MainTab.Subscription -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Fork layout: nodes count + Test all above group tabs
                        val currentGroup = groups.getOrNull(pagerState.currentPage)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.home_nodes_count,
                                    currentGroup?.let { group ->
                                        mainViewModel.serversForGroup(group.id).value.size
                                    } ?: 0
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { onAction(MainAction.TestAllServers) }) {
                                Text(stringResource(R.string.home_test_all))
                            }
                        }
                        if (groups.size > 1) {
                            GroupTabBar(
                                groups = groups,
                                selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                                mainViewModel = mainViewModel,
                                onTabClick = { targetIndex ->
                                    scope.launch {
                                        pagerState.navigateToPageOptimized(
                                            targetPage = targetIndex,
                                            animateAdjacentPage = true
                                        )
                                    }
                                }
                            )
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = true,
                            beyondViewportPageCount = 1,
                            key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                        ) { page ->
                            val group = groups.getOrNull(page) ?: return@HorizontalPager

                            GroupPagerPage(
                                groupId = group.id,
                                mainViewModel = mainViewModel,
                                selectedGuid = selectedGuid,
                                doubleColumnDisplay = doubleColumnDisplay,
                                confirmRemove = confirmRemove,
                                searchQuery = searchQuery,
                                lazyListStates = lazyListStates,
                                lazyGridStates = lazyGridStates,
                                onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                                onShareServer = { guid, profile ->
                                    shareTarget = Triple(guid, profile, false)
                                },
                                onMoreServer = { guid, profile ->
                                    shareTarget = Triple(guid, profile, true)
                                },
                                onRemoveServer = removeServer,
                                contentPadding = PaddingValues(
                                    start = 0.dp,
                                    top = 0.dp,
                                    end = 0.dp,
                                    bottom = 80.dp
                                )
                            )
                        }
                    }
                }
                MainTab.Routing -> {
                    RoutingTab(
                        context = context,
                        scope = scope,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                MainTab.More -> {
                    // Fork layout: More tab hosts the settings page
                    SettingsScreen(
                        viewModel = viewModel(),
                        onBackClick = {},
                        onModeHelpClicked = { Utils.openUri(context, AppConfig.APP_WIKI_MODE) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
}

@Composable
private fun RoutingTab(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val routingViewModel: RoutingSettingsViewModel = viewModel()
    val domainStrategyState = remember { MutableStateFlow(getDomainStrategy(context)) }
    LaunchedEffect(Unit) {
        routingViewModel.reload()
    }
    RoutingSettingScreen(
        viewModel = routingViewModel,
        domainStrategyState = domainStrategyState,
        onBackClick = {},
        onAddRule = {
            context.startActivity(Intent(context, RoutingEditActivity::class.java))
        },
        onEditRule = { position ->
            context.startActivity(
                Intent(context, RoutingEditActivity::class.java).putExtra("position", position)
            )
        },
        onDomainStrategySelected = { value ->
            MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, value)
            domainStrategyState.value = value
        },
        onImportPredefined = { type ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        SettingsManager.resetRoutingRulesetsFromPresets(context, type)
                        true
                    }.getOrDefault(false)
                }
                if (ok) {
                    context.toastSuccess(com.v2ray.ang.R.string.toast_success)
                    routingViewModel.reload()
                } else {
                    context.toastError(com.v2ray.ang.R.string.toast_failure)
                }
            }
        },
        onImportClipboard = {
            val clipboard = try {
                Utils.getClipboard(context)
            } catch (e: Exception) {
                context.toastError(com.v2ray.ang.R.string.toast_failure)
                return@RoutingSettingScreen
            }
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    SettingsManager.resetRoutingRulesets(clipboard)
                }
                if (ok) {
                    context.toastSuccess(com.v2ray.ang.R.string.toast_success)
                    routingViewModel.reload()
                } else {
                    context.toastError(com.v2ray.ang.R.string.toast_failure)
                }
            }
        },
        onImportQRcode = {
            context.startActivity(Intent(context, ScannerActivity::class.java))
        },
        onExportClipboard = {
            val rulesetList = MmkvManager.decodeRoutingRulesets()
            if (rulesetList.isNullOrEmpty()) {
                context.toastError(com.v2ray.ang.R.string.toast_failure)
            } else {
                Utils.setClipboard(context, JsonUtil.toJson(rulesetList))
                context.toastSuccess(com.v2ray.ang.R.string.toast_success)
            }
        }
    )
}

private fun getDomainStrategy(context: android.content.Context): String {
    val strategies = context.resources.getStringArray(com.v2ray.ang.R.array.routing_domain_strategy)
    return MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: strategies.first()
}
