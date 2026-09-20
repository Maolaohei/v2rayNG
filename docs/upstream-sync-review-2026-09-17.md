# 上游同步审计报告 — 2026-09-17

- **fork**: `Maolaohei/v2rayNG` @ `master` = `89c34e0a`（2026-09-11）
- **upstream**: `2dust/v2rayNG` @ `master` = `a9b12426`（2026-09-13，`Coalesce bulk probe result updates (#6205)`）
- **merge-base**: `9be547c3`（2026-08-07，`up 2.3.3`）
- **距离**: fork 领先 101 个提交，落后 74 个提交
- **版本号**: 双方均为 `2.3.8` / `versionCode 748` → 所有 `up 2.3.x` 版本提交无需处理
- **子模块**: `AndroidLibXrayLite` 父仓库索引已指向 `d0c6c4ae`（= 上游最新 pin，xray-core v26.9.9）；工作区仍检出在 `b2138986`，`git submodule update` 即可。`hev-socks5-tunnel` pin 与上游一致。

## 一、筛选方法

1. `git cherry -v HEAD upstream/master` 粗筛 → 44 个候选（无等价 patch），30 个已有等价 patch。
2. 语义重合度精筛（`scripts/coverage.py`）：对每个候选的**新增行**（长度 ≥15）在 fork 对应文件中的命中率打分。
   - ≥80% 视为已实现；30%~80% 人工复核；<30% 视为真缺失。
3. 对每个入选项**打开 fork 源码确认缺陷真实存在**，再用 `git merge-tree --write-base` 检测冲突。

> 注意：patch-id 在本仓库完全不可靠 —— fork 大量 cherry-pick 时做了适配改写，
> 导致 26 个「语义已实现」的提交仍被 `git cherry` 标记为 `+`（例如 `#6047` / `#6085` / `#6071` / `#6194` 等命中率 100%）。
> 反之也有 `#6082` / `#6088` 这类 fork 自己重写了实现（`MainBottomBar.kt` 被整体重写、状态文案迁到 `MainHomeScreen.kt`），命中率 0% 但其实已实现。

## 二、结论总览

| 级别 | 项 | 收益 | 冲突 |
|---|---|---|---|
| **P0 必修** | 路由规则编辑入口 key 不匹配（fork 自研 bug，非上游） | 功能完全不可用 | — |
| **P1 强烈建议** | `#6205` 探测结果合并（性能） | 批量测速 O(N²) → O(N) | 4 文件冲突，需适配 |
| **P1 强烈建议** | `#6203` 请求与生命周期绑定（稳定性） | 消除陈旧结果/取消竞态 | 10 文件冲突，需适配 |
| **P2 建议** | `#6107` 列表行预计算（性能） | 移除合成期磁盘读取 | 需适配 |
| **P2 建议** | `#6105` 延迟测量双重广播修复（稳定性） | 状态行闪烁 + 冗余网络请求 | 仅 1 处 |
| **P3 可选** | `#6129` agent 指南 | 无运行时收益，改善 agent 产出质量 | 无冲突 |
| **P3 可选** | `#6067` 遗漏的 7 个语种翻译 | 本地化 | 需手写 |
| **P4 已跳过** | `#6072/#6068/#6105-UI/#6107-UI/#3033/#384553e7/#f63f6494/#6088/#6082/#up 2.3.x/#submodule/CI` | — | — |

---

## 三、P0 — 路由规则编辑入口失效（fork 自研 bug，与上游无关）

**这不是上游缺失，而是 fork 自己造成的回归。** 但它是本次审计中唯一「用户可见功能完全不可用」的缺陷，故列为最高优先级。

`c548e5f6`（上游 `Use ruleset IDs for routing edits`）在 fork 中被 cherry-pick 为 `03206629`，把路由规则集的查/存/删从「列表下标」改成「稳定 ID」。但 fork 在 2026-08-14 的 UI 重构（`fd5b80b7`）里自建了 `MainScreen` 内嵌路由页，该调用点**从未同步**：

```
MainScreen.kt:368-372                      RoutingSettingActivity.kt:204 / 352
onEditRule = { position ->                 onEditRule: (String) -> Unit
    context.startActivity(                 onEdit = { onEditRule(ruleset.id) }   ← 实参是规则集 ID
        Intent(context, RoutingEditActivity::class.java)
            .putExtra("position", position)  ← 写错 key
    )
}

RoutingEditActivity.kt:60
private val rulesetId by lazy { intent.getStringExtra("ruleset_id") }   ← 读不到 → null
RoutingEditActivity.kt:68-71
initial = rulesetId?.let { SettingsManager.getRoutingRuleset(it) }
if (initial == null) { finish(); return }                                ← 立刻自杀
```

**实际后果**：首页「路由」标签页里点击任意一条规则 → Activity 立刻 `finish()`，**看不到任何界面、无任何提示**；删除/保存同理失效。

**修复**：把 `MainScreen.kt:370` 的 `"position"` 改为 `"ruleset_id"`（一行）。

**关于上游的 `Revert "Use ruleset IDs for routing edits"`（`1af8f467`，2026-09-05）**：
上游撤销了该改动，但提交信息未给理由，且上游 `MainScreen` 根本没有 `onEditRule` 调用点（fork 独有）。
两者并存会互相打架，建议：**只做上面的一行修复，不要跟上游的 revert**。若日后要跟 revert，必须同时把 `RoutingSettingsViewModel.reload()` / `update(position,…)` 的下标语义一并回退。

---

## 四、P1 — `#6205` 合并批量探测结果更新（性能，冲突需适配）

`a9b12426` · eliotcougar · 2026-09-13 · 5 文件 `+125/-24`

### fork 现状（缺陷真实存在）

`MainViewModel.kt:115-121`：

```kotlin
MainServiceEvent.MeasureConfigSuccess -> {
    viewModelScope.launch(ioDispatcher) {
        val gid = testingGroupId ?: uiState.value.selectedGroupId
        cacheMutex.withLock { groupDataCache.remove(gid) }          // 每次结果都丢缓存
        updateGroupUi(gid, loadGroup(gid, forceRefresh = true))     // 每次结果都全量重读磁盘
    }
}
```

`loadGroup(forceRefresh = true)` → `buildServersCache(getServerGuidList(groupId))` → 对**组内每个服务器**执行 `MmkvManager` 解码（`decodeServerConfig` + `decodeAffiliationInfo`）。测速 N 台服务器时，每个结果都触发一次全组重载 ⇒ **O(N²) 次 MMKV 解码 + O(N²) 次列表重建**。500 台的组会做约 25 万次解码。

### 上游做法

- 新增 `dto/RealPingResult.kt`，把 `guid + delayMillis` 从任务进程带到 UI 进程；
- `MainRepository` 把 `MutableSharedFlow(extraBufferCapacity=64, DROP_OLDEST)` 换成 `Channel(UNLIMITED)`（探测结果有限且**不能丢**，原来 64 容量溢出会静默丢结果）；
- `MainViewModel` 用 `pendingTestResults: LinkedHashMap<guid, delay>` 累积，500ms 定时 flush 一次，只对缓存里的对应项做 `copy(testDelayMillis=…)`；
- 批量开始时把 `testDelayMillis` 就地清零，而不是 `groupDataCache.remove(groupId)`。

### 适配建议（fork 无 `ServerRowUiModel` / `MainTestRequests`）

fork 的 `ServersCache` 是 `data class`，可以照搬核心思路但**不要引入上游的 `ServerRowUiModel`**（fork 尚未做 `#6107` 的拆分）：

1. `MainServiceEvent.MeasureConfigSuccess` 增加 `RealPingResult` 载荷（需同时落地 `#6203` 的 `requestId`，见下节）；
2. `MainViewModel` 增加 `pendingTestResults` + `testResultFlushJob`，500ms 合并；
3. flush 时对 `groupDataCache[gid]` 做 `map { if (it.guid in updates) it.copy(testDelayMillis = …) else it }`；
4. 批量开始时用就地清零替换 `groupDataCache.remove(groupId)`。

> 若不想动事件模型，**最小可用版本**：只保留「不删缓存 + 只改延迟字段」，即可消掉绝大部分 O(N²)。`requestId` 与 `RealPingResult` 属于锦上添花。

**冲突**：`CoreTestService.kt` / `MainRepository.kt` / `MainServiceEvent.kt` / `MainViewModel.kt` 全部 content conflict（fork 事件模型无 `requestId`）。建议**语义移植**而非 `cherry-pick`。

---

## 五、P1 — `#6203` 让探测请求与生命周期绑定（稳定性，冲突需适配）

`173f60a1` · eliotcougar · 2026-09-11 · 10 文件 `+247/-79`

### fork 现状（缺陷真实存在，共 4 处）

**1. 事件无 `requestId`，陈旧结果会污染新批次**

`MainServiceEvent.kt` 里 `MeasureConfigSuccess` 是 `data object`、`MeasureConfigFinish(val finishedCount: String?)`，全链路（`MessageHelper` → `CoreTestService` → `MainRepository` → `MainViewModel`）**都没有请求标识**。
取消后立刻开新批次时，旧批次的 `MSG_MEASURE_CONFIG_FINISH` / `CANCEL` 仍会被新批次接收，导致新批次 UI 状态被提前复位。上游引入 `EXTRA_REQUEST_ID` + `MainTestRequests` 正是为此。

**2. 单机延迟测量结果在核心停止后仍会上报**

`CoreServiceManager.kt:315-359`：`measureV2rayDelay()` 用裸 `CoroutineScope(Dispatchers.IO).launch` 启动，**不持有 Job、停止/重载时不取消**。用户测速中停止核心或切换配置时：
- 旧协程继续跑完并 `sendMsg2UI(MSG_MEASURE_DELAY_RESULT)` → UI 显示一个已失效的延迟；
- `stopCoreLoop()` / `reloadCoreLoop()` 未做任何 `cancelChildren()`（上游新增了 `connectionTestScope` + `cancelChildren()`）。

**3. 同上第 198 / 484 行**也是裸 `CoroutineScope(...).launch`，同类问题。

**4. `CoreTestService.activeWorkers` 并发语义不安全**

`CoreTestService.kt:31`：`Collections.synchronizedList(mutableListOf<RealPingWorkerService>())`。
`handleWorkerEvent` 的 `activeWorkers.remove(worker)` + `if (activeWorkers.isEmpty()) stopSelf()` 不是原子操作；`handleMeasureCancel()` / `onDestroy()` 的「先快照再 cancel」与 worker 自身完成路径存在竞态：取消时 worker 同步抛出 `Finish`，可能在新批次刚 `add` 之前/之后判空，导致 `stopSelf()` 误杀新批次，或重复取消。
上游改为 `ConcurrentHashMap<Worker, String>`，用 `remove(worker, requestId)` 原子判存，并且**只对真正属于本次请求的 worker 回发 CANCEL**。

`SubscriptionUpdateService.kt:42` 是同样的 `Collections.synchronizedList` 写法，可一并整改。

**5. 附带：`CoreTestService.kt:128` 的 `lateinit var worker` 被 lambda 捕获**

```kotlin
lateinit var worker: RealPingWorkerService
worker = RealPingWorkerService(..., onEvent = { event -> handleWorkerEvent(event, message) { activeWorkers.remove(worker) } })
activeWorkers.add(worker)
```

目前靠「`start()` 在赋值之后」侥幸成立；一旦 `RealPingWorkerService` 改成构造期/启动期同步回调就会 `UninitializedPropertyAccessException`。属于潜在崩溃点，建议一并去掉 `lateinit`。

**冲突**：`CoreServiceManager.kt` content conflict；其余文件自动合并。仍建议语义移植。

---

## 六、P2 — `#6107` 把服务器行数据移出 composition（性能）

`e2dc37ba` · eliotcougar · 2026-08-29 · 4 文件 `+211/-176`

### fork 现状（缺陷真实存在）

`MainServerPager.kt:287-291`（单列）与 `322-325`（双列）在**每次重组、每一行**执行：

```kotlin
val subRemarks = if (subscriptionId.isEmpty()) {
    MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()?.toString() ?: ""
} else ""
...
statistics = profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
```

- `MmkvManager.decodeSubscription` 是磁盘/MMKV 读，且**未做记忆化** —— 同一订阅的 N 行会重复解码 N 次；
- `AngConfigManager.generateDescription(profile)` 会做协议相关的字符串拼装（含 JSON 解析路径），每行每次重组都跑；
- 滚动时的每一次重组都会重跑上述逻辑，是列表卡顿的主要来源。

上游 `#6107` 的做法：新增 `MainServerRowModels.kt`（`ServerRowUiModel` + `ServerRowActions`），把「行显示数据」在 ViewModel 侧预计算进 `serverGroupState(groupId)` 的 `rows`，Composable 只读现成字段。

### 适配建议（fork 无 `ServerRowUiModel`，需自建）

fork 目前是 `MainViewModel.mutableServersForGroup(groupId)` 直接暴露 `List<ServersCache>`。可低成本分两步：

1. **先做最小改动**：在 `MainServerPager` 的 `GroupPagerPage` 里对当前页的 `servers` 做一次 `remember(servers, subscriptionId) { … }` 预算出 `(guid → subRemarks)` 映射与 `statistics` 字符串，行内只查表。这一步不需要动 ViewModel，就能消掉「每行每次重组都读磁盘」。
2. **再做完整版**：引入 `ServerRowUiModel`，在 ViewModel 构建 `ServersCache` 时一并算出展示字段。

**冲突**：`MainScreen.kt` content conflict（该文件在 fork 已被大幅重写），其余可自动合并。建议只移植思路。

---

## 七、P2 — `#6105` 单机延迟测量双重广播（稳定性 + 冗余网络请求）

`26099c19` · eliotcougar · 2026-08-29 · 2 文件 `+68/-56`

该提交包含两部分，**必须拆开看**：

- `MainGroupTab.kt` 部分（`PrimaryScrollableTabRow` → `LazyRow`）：**上游自己在 5 天后用 `3dc0173b` 撤销了**，且 fork 的 `MainGroupTab.kt` 是自研实现（带 `homeTab` 参数），**不要移植**。
- `CoreServiceManager.kt` 部分：**这是真修复，值得移植**。

### fork 现状（缺陷真实存在）

`CoreServiceManager.kt:340-358`：

```kotlin
val result = ConnectionTestResult(delayMillis = time, errorMessage = errorStr)
MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)   // 第 1 次：无 IP
if (time >= 0) {
    SpeedtestManager.getRemoteIPInfo()?.let { ip ->
        MessageHelper.sendMsg2UI(..., result.copy(country = ip.country, ipAddress = ip.ipAddress))  // 第 2 次
    }
}
```

UI 侧 `MainViewModel.kt:111-113` 每次 `MeasureDelayResult` 都 `_uiState.update`。结果：状态行先显示「延迟 xx ms」，随后被「延迟 xx ms\n(国家) IP」覆盖 —— **可见闪烁**，且 UI 多收一次广播。

上游做法：先取 `endpoint`，把 `country`/`ipAddress` 直接塞进**唯一一次** `ConnectionTestResult`。

**冲突**：`MainGroupTab.kt` content conflict（且我们本来就不移植该文件）。该改动只需手改 `CoreServiceManager.kt` 约 15 行，不建议走 cherry-pick。

---

## 八、P3 — 可选低风险项

### 8.1 `#6129` Docs/agent guidelines（无冲突，`git merge-tree` 返回 CLEAN）

`c73498b0` · 11 文件 `+387/-84`

上游把 fork 现有的 `docs/AGENTS.md`（84 行，已被上游删除）升级为：

- 根 `AGENTS.md`（188 行，作用域与优先级、需求语言、验证口径、PR 边界）；
- `V2rayNG/app/src/main/java/com/v2ray/ang/service/AGENTS.md`（92 行）；
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/AGENTS.md`（95 行）；
- 以及 `CLAUDE.md` / `GEMINI.md` / `.github/copilot-instructions.md` 路由 shim。

本 fork 高度依赖 agent 开发（101 个独有提交中大量是 agent 产出），这些指南对**代码质量与验证纪律**有直接帮助，且是唯一零冲突项。注意其中「指令文件以 `upstream/master` 为基线同步」的约定与 fork 现状需要自行调整。

### 8.2 `#6067` 遗漏的 7 个语种翻译

`2607b5cb` 的 `title_pref_dynamic_color` / `summary_pref_dynamic_color` 在 fork 中**键已存在但值为英文兜底**：

| 语言 | fork 现状 |
|---|---|
| `values-ar` | `Enable dynamic color`（未翻译） |
| `values-bn` | 未翻译 |
| `values-bqi-rIR` | 未翻译 |
| `values-fa` | 未翻译 |
| `values-ru` | 未翻译 |
| `values-vi` | 未翻译 |
| `values-zh-rTW` | 未翻译（`zh-rCN` 已有「开启莫奈取色」） |

上游该提交提供了全部 7 种译文，可直接抄。

### 8.3 CI 版本

`.github/workflows/build.yml:28` 使用 `actions/setup-java@v5`，上游 `fdacf58e` 升到 `6.0.0`。低优先级，升级前建议先确认 workflow 结构差异（fork 无 `release.yml`）。

---

## 九、明确跳过清单（含理由）

| 提交 | 标题 | 跳过理由 |
|---|---|---|
| `b348ca79` `9fcb1a30` `13138ddd` `2020807c` `200c59f7` | `up 2.3.4~2.3.8` | 双方版本号已一致（2.3.8 / 748） |
| `d4fc9150` `53c65063` | `Update AndroidLibXrayLite` | 父仓库索引已指向 `d0c6c4ae`（= 上游最新 pin）；仅需 `git submodule update` |
| `1fa0ccb4` `49fe45c4` `4b3c8177` `75c9194e` `1ee12135` `63f55724` `a1b45bbf` `5bfcdf90` `8d9152bb` `06bcd4c1` `4004cee2` `5d76fcd0` `49835a5c` `739e303f` `9cdbc5ce` `e8a82d98` `8dcff423` `258de209` `37b42f04` `780d1942` `b9c2decb` `3c9336f6` | 各种 fix / a11y / 本地化 / 性能 | 命中率 87%~100%，fork 已实现（多数是 fork 自己 cherry-pick 后改写，patch-id 失效才被标为 `+`） |
| `10932f57` `c1374a73` | a11y 状态播报 / asset 更新 toast | 命中率 0%，但 fork 已用等价实现覆盖：状态文案迁至 `MainHomeScreen.kt`（`943ba12c`）、toast 见 `efee5bb4`；且 `MainBottomBar.kt` 已被 fork 整体重写，无 `displayText` |
| `36796fee` | 抽屉头部改用 app icon | fork 抽屉头部为自研品牌区（显示 `Bray-Core`），改动方向冲突 |
| `3033e471` | 统一使用 `MaterialTheme.colorScheme.secondary` | fork 有自研主题系统，统一用 `colorFabActive`（`Theme.kt`），替换会破坏设计一致性 |
| `384553e7` | `Code clean` | 62% 命中；剩余为 import 排序/未用 import 清理，纯噪声 |
| `f63f6494` | `Adjust UI` | `Dialog.kt` 的 padding 改动已存在；剩余为「设置页默认展开」的 UX 偏好，fork 已重组设置分类 |
| `3dc0173b` | `Fix` | 本身就是 `#6105` UI 部分的撤销，无独立价值 |
| `1af8f467` | `Revert "Use ruleset IDs for routing edits"` | 见 P0 节：fork 应修复调用点而非跟随 revert |
| `c73498b0` 之外的 `AGENTS.md` 相关 | — | 见 8.1 |

---

## 十、第二部分：fork 其他功能的进一步稳定性 / 性能增强

以下均为**上游没有对应提交**的 fork 自有问题，按收益排序。

### A. `TrafficStatsManager` 每秒写一次 MMKV（flash 磨损 + 耗电）

`handler/TrafficStatsManager.kt`（fork 从 Bray-Core 移植，214 行）

`pollOnce()` 每 1000ms 执行一次，两条路径都会**每秒落盘一次**：

```kotlin
if (delta > 0L) {
    addToDayWindow(now, delta)      // → decodeSettingsString + split + encodeSettings（写）
} else {
    lastDayBytes.set(readDayBytes()) // → decodeSettingsString + split + encodeSettings（写！）
}
```

而 `addToDayWindow` / `readDayBytes` 每次都**重新从 MMKV 反序列化整串**（`"hour:bytes,hour:bytes,…"` 最多 24 项）、过滤、再 `joinToString` 写回。即：连接期间持续每秒一次「读字符串 → 解析 → 拼字符串 → 写 MMKV」。

**建议**：
1. 内存里维护 `LinkedHashMap<Long, Long>` 作为唯一真源，MMKV 只在「整点跨小时」或「值变化累计 ≥ 阈值」时落盘（例如最多 60s 一次，或仅在小时边界 + `onStop`/`onDestroy` 时落盘）；
2. `delta == 0` 时直接复用内存中的 `lastDayBytes`，不要走 `readDayBytes()`（当前实现会在**空闲时也持续写盘**）；
3. `pollJob` 加 `@Volatile`（`ensurePolling`/`stopIfIdle` 有 `@Synchronized`，但 `pollJob` 的读发生在 `currentDayBytes()` 里，跨线程可见性依赖运气）；
4. `currentDayBytes()` 在 `addDayTrafficListener` 中被**从 composition 线程**调用（`MainHomeScreen.kt:99`），首次会同步读 MMKV。建议改为异步回填（先回调 0 或缓存值，随后在 IO 线程补齐）。

### B. `PrivilegeSelfTest` 主线程执行 root 探测（ANR 风险）

`ui/settings/PrivilegeSelfTest.kt:301-341`

```kotlin
fun runPrivilegeSelfTest(context, scope, onResult) {
    scope.launch {                                   // ← 默认 Dispatcher（rememberCoroutineScope = Main）
        val probeBefore = withContext(Dispatchers.IO) { … }   // 这里是对的
        …
        ui = buildPrivilegeSelfTestUi(…)              // ← 这一步在主线程！
```

`buildPrivilegeSelfTestUi`（第 77 行）内部调用 `PrivilegePortsManager.status(context)`（第 192 行），后者调用 `RootManager.isRootAvailable()` —— `RootManager` 的 KDoc 明确写着「Probing spawns `su` and blocks … must not be called on the main thread the first time」。首次进入自检时会在主线程 fork/exec `su`，直接构成 ANR 风险。

**修复**：把 `buildPrivilegeSelfTestUi(...)` 包进 `withContext(Dispatchers.IO) { … }`。

同类问题（较轻，但都是主线程 IPC / 磁盘写）：

- `SettingsActivity.kt:263 / 850 / 878 / 921`：`moduleStatusSummary(context)` 内部走 `PrivilegeSettingsClient.probe()`（binder 到 system_server），却在 `LaunchedEffect` / 点击回调（主线程）里直接调用；
- `SettingsActivity.kt:889`：`PrivilegePortsManager.applyFromPrefs(...)` 在主线程执行 `RootManager.isRootAvailable()` + `resolveTargetUids` + 通过 root shell 执行 iptables 脚本；
- `SettingsActivity.kt:271`：`runCatching { PrivilegeSettingsClient.sync() }` 在 ActivityResult 回调（主线程）里做 binder IPC + 文件写。

**修复方向**：统一改为 `scope.launch { withContext(Dispatchers.IO) { … } }` 回填 State；`applyFromPrefs` 返回结果后再刷 UI。

### C. `LauncherManager` 主线程 root 探测

`core/LauncherManager.kt:110`：`startContextService()`（由首页开关 / 快捷磁贴在主线程触发）在 root 模式下执行 `RootManager.isRootAvailable()`。只有用户打开过设置页（`SettingsViewModel:34` 会 `RootManager.refresh()`）时缓存才命中；否则首次从首页开关启动 root 模式会在主线程 spawn `su`。

**修复**：`MainViewModel` 初始化时（或 `MainHomeScreen` 首次组合的 `LaunchedEffect` 里）先 `RootManager.refresh()`，让缓存预热；或把该判定改为「异步探测 → 探测完成后再启动服务」。

### D. `SafeMethodHook.ClassicReflectParam` 每次属性访问都做反射查找

`xposed/hooks/SafeMethodHook.kt:118-166`

经典 Xposed 路径下，`ClassicReflectParam` 的每个属性 getter/setter 都现算 `cls.getField("method")` / `cls.getMethod("getResult")` / `cls.getField("result")` 等。这些 `getField`/`getMethod` **没有缓存**，每次调用都要沿类层次查找 + 访问检查。

被 hook 的方法里不乏热路径：`NetworkInterface.getName`、`ConnectivityManager.getActiveNetworkInfo`、`getNetworkCapabilities`、`getLinkProperties`。这些方法被目标 App 高频调用，而每个 hook 回调会触发多次反射查找（`param.thisObject` + `param.result` 至少各一次）。

**修复**：为 `ClassicReflectParam` 增加按 raw class 缓存的 `ConcurrentHashMap<Class<*>, CachedHandles>`，一次解析 `method` / `thisObject` / `result` / `returnEarly` / `args` 的 `Field`/`Method` 句柄后复用；或在 `classicBefore` / `classicAfter` 里一次性解析成快照对象。

### E. `SafeMethodHook.ModernParam` 读取 `args` 会隐式改变调用语义

`xposed/hooks/SafeMethodHook.kt:84-116`

```kotlin
override val args: Array<Any?>
    get() {
        argsOverride = mutableArgs      // ← getter 带副作用
        return mutableArgs
    }
```

`interceptModern` 中 `if (param.argsOverride != null) chain.proceed(param.argsOverride!!) else chain.proceed()`。因此**任何只「读」`param.args` 的 hook** 都会把 `chain.proceed()` 变成 `chain.proceed(mutableArgs)`：多一次数组拷贝（`chain.args.toTypedArray()` 在构造 `ModernParam` 时已经做了一次），且在变长参数/基本类型装箱场景下 `proceed(Array)` 与 `proceed()` 语义可能不同。

**修复**：`args` 的 getter 不应设置 `argsOverride`；只在 `setArg` 里设置。同时把 `mutableArgs` 改为惰性创建，避免每个 hook 调用都无条件拷贝一次数组。

### F. 其他已确认但优先级较低的点

- `CoreTestService.kt:128` 的 `lateinit var worker` 被 lambda 捕获（见 P1 `#6203` 第 5 点）。
- `SubscriptionUpdateService.kt:42` 与 `CoreTestService.kt:31` 相同的 `Collections.synchronizedList` 竞态写法。
- `MainServerPager.kt:287-291 / 322-325` 的合成期磁盘读取（见 P2 `#6107`）。

---

## 十一、建议执行顺序

1. **立即**：修复 `MainScreen.kt:370` 的 `"position"` → `"ruleset_id"`（一行，功能恢复）。
2. **本轮**：`#6105` 的 `CoreServiceManager.kt` 双重广播修复（约 15 行，手改）。
3. **本轮**：`TrafficStatsManager` 的每秒落盘改造（A 项）。
4. **本轮**：`PrivilegeSelfTest` / `SettingsActivity` 的 `withContext(Dispatchers.IO)` 包裹（B 项，改动小、收益直接）。
5. **下一轮**：`#6203` 语义移植（requestId + 作用域取消 + `ConcurrentHashMap`）。
6. **下一轮**：`#6205` 结果合并（依赖上一步，或先做「不删缓存」的最小版本）。
7. **下一轮**：`#6107` 思路移植（先做 `remember` 映射的最小版本）。
8. **随手**：`#6129`（`git cherry-pick` 可直接应用）、7 个语种翻译、`setup-java@v6`。
9. **可选**：D / E 两项 xposed 反射优化。

---

## 附：审计产物

- 候选清单：`docs/upstream-sync-candidates-2026-09-17.txt`（44 个哈希，`git cherry -v` 输出）
- 覆盖率脚本（已参数化 repo，支持 `--repo` / `AUDIT_REPO`）：
  `C:\Users\Admin\.workbuddy-ai\skills\upstream-sync-audit\scripts\coverage.py`
