# 上游同步审计 - 2026-11-26

上游 tip：`8b4e966a`（2.4.2-1，2026-11-26）。同步窗口为 `2026-11-18` 审计
（基线 `ef06bac9`）之后，上游新增 38 个提交（2026-11-16 至 2026-11-26），
本次合并 20 个、跳过 18 个，全部按 `cherry-pick -x` 线性落在 `master` 上。

同步前基线：`7b509db4`（2.4.0/760），已留备份分支 `backup/pre-sync-2026-11-26`。
同步后版本：`2.4.2 (762)`，发布标签计划 `2026.11.26`。

## 已合并

| 上游 | fork | 主题 |
|---|---|---|
| `740f0bac` | `344762f5` | build(deps): bump actions/setup-java from 6.2.0 to 6.3.0 (#6469) |
| `96333136` | `b6cbe669` | fix(http): prevent subscription header leaks across redirects (#6437) |
| `35976757` | `ae5ef586` | fix(import): refresh Main after external imports complete (#6440) |
| `904c9ace` | `6196cecc` | Find imported profiles in the Default group (#6443) |
| `9c71c260` | `8a3501be` | Bug fix |
| `ecfc3891` | `4ec23581` | fix(sub): skip unsupported v2rayn:// entries instead of the whole batch (#6463) |
| `5f7031fa` | `297c82fa` | fix(root): stop hijacking hotspot clients' DNS when LAN sharing is off (#6484) |
| `f0957d51` | `bc9aa7bc` | build(deps): update Compose BOM to 2026.11.01 (#6493) |
| `d3d4c1ec` | `d9dc7c62` | fix(sub): show proxy chain members for v2rayN subscription imports (#6500) |
| `a5e0be0c` | `8bc97fe1` | fix: restore profile list position after activity recreation (#6444) |
| `a6f3ec05` | `3822b59d` | Resume server picker search after activity recreation (#6450) |
| `69437bf4` | `8b1eba72` | Fix outbound editor focus and keyboard after activity recreation (#6448) |
| `bef99868` | `3189e7b7` | Restore custom profile editing after activity recreation (#6453) |
| `9d1cfada` | `bca46a0d` | Fix system language fallback after cold start (#6511) |
| `92fe7e98` | `183ccd1d` | lang: Update Azerbaijani localization (#6528) |
| `71631de1` | `c3107d8b` | Keep routing rule IDs unique after import (#6530) |
| `2f0eff6c` | `bb4f2977` | Preserve pending deletion in group editors across recreation (#6526) |
| `d4720e23` | `3139d092` | build(deps): update Gradle wrapper to 9.9.2 (#6445) |
| `9d580255` | `31e581dc` | build(deps): update hev-socks5-tunnel to 2.20.0 (#6446) |
| `bfb299a4` | `07bd5b1b` | Update AndroidLibXrayLite |

## 适配细节

- `a5e0be0c`：`MainScreen.kt` / `MainServerPager.kt` 冲突。保留 fork 现有布局，
  删除上游新增的 `lazyListStates` / `lazyGridStates` 映射，改用
  `rememberLazyListState()` / `rememberLazyGridState()`（4 insertions / 26 deletions）。
- `740f0bac`：`.github/workflows/build.yml` 冲突。保留 fork 的 `Run unit tests`
  步骤，仅把顶部 `actions/setup-java` 从 `6.2.0` 更新到 `6.3.0`。
- `f0957d51`：`V2rayNG/gradle/libs.versions.toml` 冲突。保留 fork 的
  `composeBom = "2026.11.01"`，未引入 `tvMaterial = "1.1.0"`（未跟进 TV UI）。
- `92fe7e98`：`values-az/strings.xml` 冲突。保留上游新增的 9 条 DNS 翻译；
  默认资源暂无对应 key，打包时会被丢弃，待 `5fa043e9` 跟进后生效。
- `71631de1`：`RoutingSettingsViewModel.kt` 保持 fork 的
  `kotlinx.coroutines.withContext`，移除 `java.util.UUID`，引入
  `ensureUniqueIds()` 修复导入后的重复 ruleset id。
- `2f0eff6c`：`ServerGroupActivity.kt` 的 `showDeleteConfirm` 改为
  `rememberSaveable`；fork 无 `showFallbackDialog`，未引入。
- 其余提交为干净 cherry-pick；未产生 merge commit。

## 跳过

| 上游 | 主题 | 原因 |
|---|---|---|
| `4a17f084` | Replace snackbars with in-app notifications | 通知交互改版，未纳入本轮范围 |
| `d4c3c796` | Fix (#6483) | 核心配置单行修正，随核心协议组暂缓 |
| `c84896f7` | TV UI, part 1: Add a dedicated D-pad main screen for Android TV (#6441) | TV 专用界面，fork 不跟进 |
| `93f2d225` | Add a dedicated Settings screen for Android TV (#6447) | TV 专用界面，fork 不跟进 |
| `ddcb9a40` | build(deps): update AndroidX Lifecycle to 2.11.1 (#6491) | 依赖升级未选，避免扩大验证面 |
| `a335ffe5` | up 2.4.1 | 仅版本号，fork 手动同步为 `2.4.2 (762)` |
| `43c8b43e` | feat(routing): support importing rulesets from network URL in clipboard and QR code | 路由功能新增，本轮未选 |
| `0a55a90f` | feat(userasset): add downloading indicator for geo files | 用户资产 UI 改动，未选 |
| `5fa043e9` | Fix DNS and add features (#6522) | DNS 核心重构，冲突面大，暂缓 |
| `3a2af091` | feat: add global port hopping interval setting | 端口跳跃功能，暂缓 |
| `d14cbbdc` | Create test.yml | CI 测试工作流，未纳入 |
| `9e94b1fc` | Revert "Create test.yml" | 同一 CI 工作流回滚，未纳入 |
| `651b5b30` | Update test.yml | 同一 CI 工作流更新，未纳入 |
| `9a60ca10` | Add xdrive transport support (#6464) | 新增传输协议，暂缓 |
| `591cc251` | refactor(ui): remove subscription info card from main screen | 主界面 UI 决策项，未选 |
| `6e9dfe2a` | feat(subscription): display traffic info and enhance update flow in SubSettingActivity | 订阅页 UI 重构，未选 |
| `99a9106b` | up 2.4.2 | 仅版本号，fork 手动同步为 `2.4.2 (762)` |
| `8b4e966a` | Fix (#6532) | 核心 DNS 修复，随 DNS 组暂缓 |

## 子模块

```
AndroidLibXrayLite  e23aca42e775ea9c31dc4e37a3472927caa3b9cf  (v26.11.26)
hev-socks5-tunnel   75dbc1e9ace6c70d3d1b94df7c283efe1de7effc  (2.20.0)
```

`V2rayNG/app/libs/` 被 gitignore，本地 `libv2ray.aar` 不随提交变更；CI 的
`build.yml` 会解析 AndroidLibXrayLite 最新 release 并下载实际 AAR。Windows 下
`hev-socks5-tunnel` 子模块工作区仍有本地生成文件与 2.19.3 checkout，未纳入提交。

## 验证

- `:app:assembleDebug`：BUILD SUCCESSFUL in 5m 16s，85 actionable tasks；fdroid 与
  playstore 各生成 5 个 APK，fdroid universal 86,537,005 字节。
- `:app:testFdroidDebugUnitTest`：BUILD SUCCESSFUL in 49s；30 个 testsuite XML，
  172 tests，0 failures，0 errors，1 skipped。跳过的是
  `RootProcessRunnerTest`：Windows 的 Git Bash 会等待后台子进程退出，与 POSIX sh
  语义不同；其余用例全部通过。
- 构建警告：`values-az` 中 6 个 DNS 字符串无默认值，被资源打包阶段丢弃；
  因为未跟进 `5fa043e9`，不影响构建。
- `git diff --check` 干净，源码无冲突标记。

## 发版

- 版本：`2.4.2 (762)`，标签 `2026.11.26`。
- 推送与 CI：（待回填）。
