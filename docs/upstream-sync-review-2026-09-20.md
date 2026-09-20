# 上游同步 + 稳定性/性能整改 — 2026-09-20

- **fork**: `Maolaohei/v2rayNG` @ `master`（本次起点 `b44c6551`）
- **upstream**: `2dust/v2rayNG` @ `master` = `53fce9a8`
- **merge-base**: `9be547c3`（`up 2.3.3`）；`HEAD...upstream` = `104 / 77`
- **上一轮审计**: `docs/upstream-sync-review-2026-09-17.md`（基线 `a9b12426`）

## 一、上一轮结论已落地情况

| 项 | 状态 |
|---|---|
| P0 路由规则编辑入口 key 不匹配 | 已修（`c28b7589`） |
| `#6203` 探测请求与生命周期绑定 | 已移植（`bdec36d4`，含 `MainTestRequests` 单测） |
| `#6205` 批量探测结果合并 | 已移植（同上，servers-only 版，行模型留待 `#6107`） |
| `#6105` 延迟测量双重广播 | 上游修复已在（单次 `ConnectionTestResult` + `isRunning` 门控 + `connectionTestScope`） |

## 二、本轮上游新增（`a9b12426..53fce9a8`，共 3 个）

| 提交 | 内容 | 处置 |
|---|---|---|
| `c1c93b62` `#6242` | Wireguard `finalMask` 解析（URI / conf 双向） | **已合并**：`WireguardFmt.kt` 3 处 |
| `b9c2decb` `#6216` | 清理冗余字段 | **部分合并**：仅 `wireguard.port = null`（两份 `v2ray_config.json` 的大 diff 纯格式化，跳过） |
| `cb39a10a` | Gradle 9.6.0 / AGP 9.4.1 / Kotlin 2.4.20 / appcompat 1.8.0 / okhttp 5.5.0 / camerax 1.6.2 / composeBom 2026.09.00 | **本轮跳过**（本地无网络，无法构建验证；需单独分支做完整构建+冒烟后再合） |
| `53fce9a8` `#6232` | `actions/setup-java` 6.0.0 → 6.0.1 | **已合并**：本地原为 `v5`，直接对齐到 `v6.0.1` |

`git cherry` 仍显示 46 个 `+`，其中绝大多数是 fork 早期 cherry-pick 后适配改写导致 patch-id 失效（语义已实现），与上一轮结论一致。

## 三、本轮 fork 自有稳定性 / 性能整改

### 1. `TrafficStatsManager`：内存为真源 + 落盘节流（性能 / flash 磨损 / 耗电）

改前：`pollOnce()` 每 1000ms 一次，有流量走 `addToDayWindow`（读串→解析→拼串→写），空闲走 `readDayBytes`（**同样写盘**）；即连接期间持续每秒一次 MMKV 读+写。

改后：
- 新增 `daySamples: LinkedHashMap<Long, Long>` 作为 24h 窗口唯一真源，MMKV 只在启动时恢复一次、之后作为备份；
- `persistLocked()` 按 `PERSIST_INTERVAL_MS = 60s` 节流，仅在「脏」时写；`stopIfIdle()` 与新增 `flushDaySamples()` 强制落盘；
- 空闲 tick 走 `pruneIdle()`，只做内存裁剪，**零磁盘 I/O**；
- `ensurePolling()` 的恢复读取移入 IO 协程（`hydrate()` + `notifyDay()`），`addDayTrafficListener()` 先回调缓存值再异步补齐 —— **组合线程不再读 MMKV**；
- `pollJob` 标 `@Volatile`。

效果：稳态下 MMKV 写入从「~1 次/秒」降到「≤1 次/分钟 + 停止时 1 次」，空闲时为 0。

### 2. `SettingsActivity` / `LauncherManager`：主线程 IPC 与 root 探测（ANR）

- `SettingsActivity`：`moduleStatusSummary()`（内部 `PrivilegeSettingsClient.probe()` → binder 到 system_server）与 `PrivilegeSettingsClient.sync()` 原先在 `LaunchedEffect`、开关回调、ActivityResult 回调、`onClick` 中直接执行；现统一改为 `scope.launch { withContext(Dispatchers.IO) { … } }` 后回填 State，新增 `refreshModuleSummary()` 统一入口。
- `LauncherManager.prewarmRootCache()`：在主线程首次启动 root 模式时 `RootManager.isRootAvailable()` 会 fork/exec `su`；新函数在 IO 线程预热缓存，`MainHomeScreen` 首次组合时调用。

### 3. 探测 worker 注册表并发安全 + 去掉 `lateinit` 捕获

- `SubscriptionUpdateService`：`Collections.synchronizedList` → `ConcurrentHashMap.newKeySet()`（`remove` / `isEmpty` 原子，消除 `stopSelf()` 误杀新批次）；
- `SubscriptionUpdateService` / `CoreTestService`：`lateinit var worker` 被构造期 lambda 捕获，改为 `AtomicReference` 持有，杜绝构造期同步回调触发 `UninitializedPropertyAccessException`。

### 4. `MainServerPager`：合成期磁盘读（列表卡顿）

`ServerItemRow` / `ServerItemColumn` 原先每次重组、每行都执行 `MmkvManager.decodeSubscription()` 与 `AngConfigManager.generateDescription()`；现分别用 `remember(profile.subscriptionId, subscriptionId)` / `remember(profile)` 记忆化，测试结果刷新导致的重组不再重复读盘。完整版（`#6107` 行模型搬进 ViewModel）仍待后续。

### 5. `SafeMethodHook`：Xposed 热路径反射

- `ClassicReflectParam`：原先每个属性访问都做 `cls.getField/getMethod` 查找（`getName` / `getActiveNetworkInfo` / `getNetworkCapabilities` 等高频 hook，每次调用多次查找）；现按 `raw.javaClass` 用 `ClassicHandles`（`ConcurrentHashMap` 缓存）解析一次后复用。
- `ModernParam.mutableArgs`：由无条件下 `chain.args.toTypedArray()` 改为 `by lazy`，不读 `args` 的 hook 不再付数组拷贝代价（读 `args` 的写回语义不变）。

### 6. 其他

- 7 个语种（`ar` / `bn` / `bqi-rIR` / `fa` / `ru` / `vi` / `zh-rTW`）补齐 `title_pref_dynamic_color` / `summary_pref_dynamic_color` 译文。
- `.github/workflows/build.yml`：`setup-java` → `v6.0.1`。
- 版本：`versionCode 748 → 749`，`versionName 2.3.8 → 2.3.9`。

## 四、仍未处理（下一轮）

1. 依赖/工具链升级（`cb39a10a`）：需本地或 CI 可构建环境下单独验证。
2. `#6107` 完整版行模型（本次只做最小 `remember` 版）。
3. `#6129` 上游 agent 指南（根 `AGENTS.md` + service/ui 作用域指南）——文档量大，需评估与 fork 现状的取舍。
4. `CoreVpnService` / 其他 service 的生命周期内存回收暂无进一步项。

## 验证

- 本地无网络（Gradle 依赖与插件无法解析），**未做本地构建**；验证路径为 GitHub Actions `Build APK`（push 触发）。
- 静态复核：Kotlin 无 `allWarningsAsErrors`；改动均为局部替换，无 API 签名变化（`TrafficStatsManager.currentDayBytes()` 语义由「必要时读盘」变为「仅返回缓存」，调用方只有 UI 监听器）。
