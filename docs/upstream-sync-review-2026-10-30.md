# 上游同步审计 - 2026-10-30

上游 tip：`aa4b0550`（up 2.3.14，2026-10-28）。同步窗口为 `2026-10-22` 审计
（基线 `118cf5bc`）之后，上游共新增 16 个提交（`2026-10-22` 至 `2026-10-28`），
本次合并 10 个、跳过 6 个，全部按 `cherry-pick -x` 线性落在 `master` 上。

同步前基线：`9c759de5`，已留备份分支 `backup/pre-sync-2026-10-30`。

## 已合并

| 上游 | fork | 主题 | 核实 |
|---|---|---|---|
| `c8f373bd` | `6f3b2bb8` | 更新 `AndroidLibXrayLite` | 仅更新子模块 gitlink；`1be1e621`（`v26.10.17`）-> `76a6e217`（`v26.10.21`），对应 xray-core `1b1647cd9ddf0abc34b981d4611fc03bcac1ca74`。干净 cherry-pick，子模块工作区已同步 |
| `bb59a71c` | `fdeca1ce` | 订阅更新后刷新主列表 (#6329) | 干净；1 个文件 +3 行 |
| `700740a1` | `6c38aac8` | `actions/setup-java` 6.0.1 -> 6.1.0 (#6334) | 冲突适配，见下 |
| `59ff8760` | `b8667278` | 阿塞拜疆语符号修正 (#6341) | 干净 |
| `3165bcf4` | `9eb58d45` | Gradle wrapper 9.8.2 (#6343) | 干净（本地验证仍用独立 Gradle 9.8.0 发行版） |
| `b8d13bf5` | `786d316c` | 订阅组流量/到期信息卡片 (#6333) | 适配合并，见下；新增 `SubscriptionInfoParserTest`、`MainSubscriptionInfoTest` |
| `0f961c54` | `07cf4d2e` | `hev-socks5-tunnel` 2.19.3 + `RootProxyManager` 局域网 offload 保护 (#6357) | 干净；子模块 `d9dca26c`（2.18.0）-> `c5584fec`（2.19.3） |
| `40b8706c` | `182e19ca` | 阿塞拜疆语订阅页文案 (#6349) | 依赖 `b8d13bf5` 引入的 `sub_info_*` 文案，必须按序重放；先单独 pick 会因缺上下文冲突 |
| `0d1c5700` | `654bb35e` | OkHttp 5.5.0 -> 5.5.1 (#6351) | 干净 |
| `7179f083` | `e8a8aa63` | Hysteria2 无端口链接使用默认端口 (#6358) | 干净；新增 `Hysteria2FmtTest` |

## 适配合并细节

### `700740a1` CI setup-java

上游该提交只把 `actions/setup-java@v6.0.1` 改成 `v6.1.0`，但 fork 的
`.github/workflows/build.yml` 已重排：`Setup Java` 提前到 `Checkout code` 之后，
并删掉了上游在构建段重复的 `Setup Java` / `Decode Keystore` 两步、换成本地的
`Run unit tests` 步骤。

冲突处置：保留 fork 的 `Run unit tests` 步骤与整体结构，只把顶部
`actions/setup-java@v6.0.1` 改为 `@v6.1.0`；该步骤在 fork 里只有一处，最终提交净
1 行改动、无冲突残留。

### `b8d13bf5` 订阅信息卡片

功能性合并，冲突集中在 fork 自研的订阅页布局：

- `MainScreen.kt`：保留 fork 的订阅页结构（节点计数行 + `Test all` +
  `GroupTabBar` + `HorizontalPager`），丢弃上游 `if (groups.isNotEmpty())` 旧结构；
  只给 `GroupPagerPage` 调用补三个新参数：`usageInfo = group.usageInfo`、
  `groupTitle = group.remarks`、
  `onOpenLink = { url -> onAction(MainAction.OpenSubscriptionLink(url)) }`。
- `MainServerPager.kt`：`GroupPagerPage` 签名直接拿到 `usageInfo`/`groupTitle`/
  `onOpenLink` 三个参数；函数体保留 fork 的 `ServerListPage(servers = …,
  subscriptionId = groupId, confirmRemove = confirmRemove, …)` 调用（fork 仍是
  `ServersCache` + 确认删除的实现，未采用上游 `groupState.rows` + `ServerRowActions`
  重构），外面套上游的 `Box` + `listPadding` 逻辑：`infoHeightPx` 用
  `onSizeChanged` 测量卡片高度，`usageInfo == null` 时不动 `contentPadding`，否则
  顶部补齐卡片高度；卡片 `onRefresh` 派发
  `MainAction.UpdateGroupSubscription(groupId)`。import 取并集，补
  `com.v2ray.ang.dto.entities.SubscriptionUsageInfo`，其余 Compose 依赖
  （`LocalDensity`、`LocalLayoutDirection`、`mutableIntStateOf`、`onSizeChanged`、
  `calculateStartPadding`/`calculateEndPadding`、`Box`、`Alignment`）在 fork 文件中已存在。
- `values/strings.xml`：在 fork 自有尾部文案之后追加 4 条 `sub_info_open_support`、
  `sub_info_open_page`、`sub_info_traffic`（`translatable="false"`，`%1$s / %2$s`）、
  `sub_info_unlimited`。
- `values-bqi-rIR/strings.xml`：保留 fork 自己的 `value_unknown`（نا مؽخص，来自
  `775546ce`，上游该行是不同译法），追加 3 条 `sub_info_*`。
- 干净合入的部分：`MainViewModel.kt`、`MainActivity.kt`、`MainContract.kt`、
  `GroupMapItem.kt`、`SubscriptionItem.kt`、`AngConfigManager.kt`、`HttpUtil.kt`，
  以及新文件 `SubscriptionUsageInfo.kt`、`SubscriptionInfoParser.kt`、
  `MainSubscriptionInfo.kt`、`ic_refresh_24dp.xml` 与 az/ru/zh-rTW 等译文。

## 跳过

- `552cc242`（hev 2.19.1）与 `1b78a510`（Revert 2.19.1）：上游自行回滚了 2.19.1，
  随后的 `0f961c54` 直接升到 2.19.3，成对跳过。
- `eedb287c` 订阅组标签改为浮动 pill 栏 (#6336)：重写 `MainGroupTab.kt` 并移除了 fork
  依赖的 `homeTab: Boolean` 参数；fork 订阅页标签栏是自研 Material3
  `PrimaryScrollableTabRow`（含 Home 页与计数徽标），属设计冲突，沿用既有处置。
- `6214bc19` ci: update actions/setup-java to v6.1.0 (#6327)：空提交，无文件变更，
  内容已由 `700740a1` 覆盖。
- `01b4630f` up 2.3.13、`aa4b0550` up 2.3.14：仅上游版本号，fork 使用自己的发版序列。

## 子模块

```
AndroidLibXrayLite  76a6e2172210613fcb4aa32b6de55bcd9452ce43 (v26.10.21)
hev-socks5-tunnel   c5584feca8f6df70ee53eb2879e034b849a3d15a (2.19.3)
```

上游 `v26.10.21` 发布资产 `libv2ray.aar`：59,954,240 字节，
`sha256:e56ec3b06485fe7b37af26948673951edf719c50d6bbc618b8539045952adde4`。
`V2rayNG/app/libs/` 被 gitignore，本地 AAR 不随提交变更。本轮尝试拉取该资产，实测
下载速率约 25 KB/s（1 分钟 1.5 MB），与上一轮记录的 15 KB/s 同级，遂放弃本地替换：
`V2rayNG/app/libs/libv2ray.aar` 仍是 2026-09-05 的旧包。仓库 CI 的 `build.yml`
按子模块 pin 下载对应 release，因此发版流水线会用 `v26.10.21` 执行实际构建门禁。

Windows 下子模块把符号链接落成普通文件（`include/hev-socks5-tunnel.h` 等），
该脏状态是既有现象，未纳入任何提交。

## 验证

环境同前几轮：JDK 21.0.10、独立 Gradle 9.8.0 发行版 +
`D:\Android\aliyun-mirror.init.gradle`、SDK android-37。

```
gradle :app:compileFdroidDebugKotlin
  -> BUILD SUCCESSFUL in 51s

gradle :app:testFdroidDebugUnitTest
  -> BUILD SUCCESSFUL in 15s
  -> 25 suites / 124 tests / 0 failures / 0 errors / 0 skipped
     （同步前基线：22 suites / 99 tests，新增 +3 套件 / +25 用例：
      Hysteria2FmtTest、SubscriptionInfoParserTest、MainSubscriptionInfoTest、
      RootProxyManagerTest）

gradle :app:assembleFdroidDebug
  -> BUILD SUCCESSFUL in 23s
  -> 5 个 fdroid debug APK（arm64-v8a / armeabi-v7a / universal / x86 / x86_64）
```
