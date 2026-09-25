# 上游同步审计 — 2026-09-26

## 已合并（同日执行，6 个提交）

| 提交 | 上游 | 方式 |
|---|---|---|
| `8ed0b1f0` Preserve groups when replacing profiles | `44721626` | 干净 cherry-pick |
| `475384a4` Localize notification-channel names | `073376e4` | 干净 cherry-pick（引入 NotificationChannelType 枚举） |
| `670cee2d` feat(wireguard): add remoteDNS field | `c48fbd3f` | 干净 cherry-pick（7 个 kt + 9 个语言文件） |
| `1fbfe366` Show service start failure details | `4c7c964e` | 干净 cherry-pick——fork 事件链与上游同构，sendMsg 本就写 "content" extra，无需适配 |
| `89ffa5bc` fix(root): enforce command deadlines | `60ab1af3` | **手工移植**：新增 `RootProcessRunner.kt`，重接 `RootShell.exec()` 与 `RootManager.probe()` |
| `7d3c1299` Fix WebView Browser Dialer recovery (#6229) | `b9889521` | 干净 cherry-pick（fork 文件与上游修复前逐字相同，含 206 行 JVM 回归测试） |

⚠️ 沙箱内 Gradle 无法解析 AGP 插件（网络受限），**以上改动未经编译验证**，需本地跑 `./gradlew :app:compileDebugKotlin` 确认。

## 暂缓（P2/P3 评估结论）

- `a1cfa2aa` 删除确认显示条目名：77 行、9 语言目录、涉及多个 fork 重写过的编辑器与 saver，冲突于 MainServerPager.kt——收益为 UX，成本高，暂缓。
- `1ad5f30e`(+`e4c0b09e`) 表单内联校验：功能型，依赖上游表单结构，暂缓。
- `08c2a4de` Coil 2→3、`cb39a10a` Gradle/AGP 9.4.1：依赖升级专项，建议单独一个会话整体做（两者有联动）。
- `e2dc37ba` 服务器行移出 composition：fork UI 已重写，需先分析 fork 列表实现是否存在同款重组热点再决定。

---


- merge-base: `9be547c3`（up 2.3.3 基线）
- 上游 tip: `bd21bbc3`（2.3.9，749）
- fork HEAD: `dca925af`（2.3.10，750）
- 候选（`git cherry` 标 +）：62，其中命中率核验后**已包含/等价**约 20 个，真缺失约 25 个（其余为版本号/CI/翻译等可跳过项）

## 建议合并（按优先级）

| 优先级 | 提交 | 主题 | 冲突 | 理由（fork 核实） |
|---|---|---|---|---|
| P1 | `44721626` | 替换订阅时保留分组 | 无冲突 | fork `MmkvManager.kt:349-353` 仍是修复前写法（`mutableListOf()`），非 append 替换会丢弃分组型条目；fork 已有 `isGroupType()` 扩展（`_Ext.kt:68`），可直接 cherry-pick |
| P1 | `60ab1af3` | root 命令强制超时+输出上限 (#6241) | 无冲突 | fork `RootShell.kt:38` 先 `readText()` 阻塞读满 EOF 才 `waitFor(30s)`——su 挂起或 helper 持有 stdout 时超时永远不触发。需适配：上游改的是 `RootManager.kt`+新增 `RootProcessRunner.kt`，fork 对应 `RootShell.kt` + probe |
| P1 | `4c7c964e` | 显示服务启动失败详情 | 无冲突（需适配） | fork `CoreServiceManager.kt:111,279` 已发送失败 message，但 `MainRepository.kt:55` → `MainServiceEvent.kt:10`（无参 data object）丢弃内容，`MainViewModel.kt:121` 只弹通用文案。需按 fork 自研事件链携带 message |
| P2 | `073376e4` | 本地化通知渠道名 (#6195) | 无冲突 | fork `NotificationManager.kt:160` 渠道名仍是硬编码 `AppConfig.RAY_NG_CHANNEL_NAME` 常量 |
| P2 | `b9889521` | WebView 拨号器网络切换后恢复 (#6229) | 未测 | fork 有 `DialerWebviewService.kt`（已有主线程 Handler 但生命周期限定不全），需人工比对 112 行改动 |
| P2 | `c48fbd3f` | WireGuard remoteDNS 字段 (#6230) | 无冲突 | fork 无 remoteDNS（grep 0 命中），属功能补齐 |
| P2 | `a1cfa2aa` | 删除确认框显示条目名 (#6202) | MainServerPager.kt 冲突 | UX 改进，fork Dialog.kt 重写过，需手工适配 |
| P3 | `1ad5f30e`（含 `e4c0b09e` 前置） | 表单内联校验错误 | 未测 | UX 功能，改动较大（43+93 行），可选 |
| P3 | `08c2a4de` | Coil 2→3 迁移 (#6248) | 未测 | fork 还在 coil 2.7.0；建议与 `cb39a10a`（Gradle/AGP 9.4.1）一起做专项升级 |
| P3 | `e2dc37ba` | 服务器行移出 composition 组装 (#6107) | 未测 | 性能改进（157 行）；fork UI 已重写，需按概念移植到 fork 的列表实现 |

## 明确跳过

- **版本号/子模块/CI**：`up 2.3.x` 系列（fork 已 2.3.10/750）、`53c65063`/`d4fc9150`（AndroidLibXrayLite pin 与上游一致 `d0c6c4ae`）、`fdacd58e`/`53fce9a8`（setup-java）、`3c9336f6`（AGP 已含）。
- **设计冲突（fork 自研 UI，勿跟）**：`3033e471`（secondary 色系）、`1f7cef32`（FAB 旋转动画）、`3dc0173b`（MainGroupTab 修复）、`f63f6494`、`7e68e2ea`（toast 透明度）、`8dcff423`（已含 87%）。
- **反向陷阱——不采纳上游 revert**：`1af8f467` 撤销"用 ruleset ID 做路由编辑"。fork 已全线统一 `ruleset_id`（`MainScreen.kt:370` ↔ `RoutingEditActivity.kt:60` 参数一致，历史 position/ruleset_id 错位 bug 已修），采纳 revert 属倒退。
- **其他**：`26099c19`（#6105；CoreServiceManager 部分 fork 已自有实现，MainGroupTab 部分设计冲突）、`0e80f6b4`（toast 调度重构，fork 自有 toast 体系）、`384553e7`/`b9c2decb` 等 cosmetic、`0f65b1d3`（俄语翻译，可顺手带）、`c73498b0`（文档）。

## fork 自身稳定性/性能问题（上游无对应提交）

1. **`CoreServiceManager.kt:205`** — 裸 `CoroutineScope(Dispatchers.IO).launch { coreController.stopLoop() }`，无 Job 跟踪：服务停止路径的异步清理不可取消、不可等待，异常仅打日志。改法：用服务级受管 scope 或存 Job 并 join。
2. **`CoreServiceManager.kt:495`** — goAsync + 裸 scope 里 `stopService()` 后 `delay(500)` 再重启：500ms 魔数竞态窗口，建议改为显式等待停止完成的回调/轮询（isRunning 翻转）。
3. **`ProcessService.kt:28-33`** — 裸 scope 内 `Thread.sleep(50)` + 无界 `process.waitFor()`：每次启动泄漏一个协程，且阻塞 IO 线程直到进程退出。改法：存 Job、用 `withTimeout` 包裹 waitFor。
4. **`RootShell.kt:38`** — 见上表 `60ab1af3`（fork 侧同款缺陷）。
5. **`NotificationManager.kt:160`** — 渠道名未本地化（同 `073376e4`）。
6. **正面确认（无需动）**：`TrafficStatsManager` 落盘已节流至 60s（`PERSIST_INTERVAL_MS`）；xposed 反射查找均已 lazy 缓存；无 `synchronizedList` 复合判断模式。

## 建议 cherry-pick 顺序

`44721626` → `073376e4` → `c48fbd3f` → `4c7c964e`（适配事件链）→ `60ab1af3`（适配 RootShell）→ 其余 P2/P3 逐个评估。
