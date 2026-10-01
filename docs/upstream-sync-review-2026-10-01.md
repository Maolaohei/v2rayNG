# 上游同步审计 — 2026-10-01

本轮同时处理了 CI 的 libv2ray 取版策略、上游 2.3.10 之后的改动、以及 fork 自身遗留的停止路径缺陷。
所有改动都在本地跑过真实构建，证据见文末「验证」。

## CI：不再锁定 AndroidLibXrayLite tag

`build.yml` 原先在子模块里 `git describe --tags` 取 pin 的 tag 再下载对应 release：意味着 APK 里的
xray-core 版本要等有人 bump 子模块指针才能前进。改成用 `gh api repos/2dust/AndroidLibXrayLite/releases/latest`
解析最新 release tag（`github.token` 鉴权，避开限流），再按该 tag 下载 `libv2ray.aar`。
子模块 checkout 保留作源码参考，最新版与 pin 的差异写入 job summary，APK 里的 libv2ray 版本仍可追溯。

兼容性核实：`d0c6c4ae`(v26.9.9) → `ea96a7f`(v26.9.30) 之间 `AndroidLibXrayLite` 的 `.go` 文件零 diff
（只有 go.mod/go.sum 变化），gomobile 导出面 `Libv2ray`/`CoreController`/`CoreCallbackHandler`/`ProcessFinder`
不变，所以「永远拉最新」当前不会编译失败。**这是长期风险**：上游一旦改绑定签名，CI 会直接红而不是静默降级。

## 已合并（按优先级）

| 提交 | 上游 | fork 侧核实 |
|---|---|---|
| hev-socks5-tunnel 2.18.0 | `04d407f5` | pin `64cc609` = 上游 bump 前同一提交；16 commits 中 UDP 地址类型/空指针、lwIP `e22c9d2` 的 PRETEND IPv6 校验、tunnel stop-event 断言是本 app 实际路径。`src/` 无新增文件、`Android.mk`/`build.mk`/JNI 契约未变，`build.mk` 用 rwildcard 收源文件 → `compile-hevtun.sh` 无需改 |
| mockito-kotlin 6.4.0、去掉 mockito-inline | `c63e18b7` | fork 同款缺陷：`libs.versions.toml` 里 `org-mockito-mockito-inline` 与 `mockito-kotlin` 共用 `mockitoMockitoInline = "5.2.0"`，mockito-kotlin 被钉在 5.2.0，且测试类路径是已停更的 mockito-inline |
| JUnit 4 → JUnit 6 | `5029d754` | fork 仍 `junit = "4.13.2"`（2021 停更）；18/19 个测试文件与上游迁移前逐字相同，直接取上游迁移后版本，`MainTestRequestsTest.kt` 手工改 import |
| 导航栏主题 API 分层 | `89f53233` | fork `values*/themes.xml` 就是上游修复前的写法（`windowLightNavigationBar` 无版本限定、无 `values-v27*` 目录），4 文件逐字等价 |
| 服务器行文字重叠 | `504f22d7` | fork `MainServerPager.kt` 的 `typeDescription`/`testResult` 行与上游修复前同构，长协议名/长延迟会重叠；按 fork 的参数化签名适配 |
| coreKtx 1.19.1 / mmkv-static 1.3.17 / work-runtime 2.12.0 | `ab09f816` | fork 三项全在升级前版本 |
| NDK r29 → r30 | `e151890c` | fork pin `29.0.14206865`（已非 LTS）；NDK 路径在 hev 缓存 key 里，缓存自然失效重建 |
| Gradle 9.8.0 / AGP 9.4.1 / Kotlin 2.4.20 / license-plugin 0.9.91 / appcompat 1.8.0 / okhttp 5.5.0 / CameraX 1.6.2 / Compose BOM 2026.09.00 | `cb39a10a`、`85b1f694` | fork 全在升级前版本（含 wrapper 9.5.1） |
| Coil 2.7.0 → Coil 3.6.3 | `08c2a4de` | fork 只有两个 Coil 触点，`Components.kt` 仅 import 变，`AppIconFetcher` 换 `ImageFetchResult` + `drawable.asImage()`；无自定义 ImageLoader 单例，`fetcherFactory` 逐请求传入，因此不需要 network artifact |
| 子模块 pin `d0c6c4ae` → `ea96a7f` | `996b039b` | CI 已改为动态拉最新，pin 只剩源码参考/local 构建一致性意义 |

顺带的 CI 卫生：`actions/cache/restore|save` 由 v5 升到 v6（上游已 v6，fork 落后）。

## 跳过

- `a4dd3d08` 版本号、`be1f2cf0` 翻译等无行为改动。
- 设计冲突类（fork 自研 UI）：FAB 动画、toast 透明度、色系等。
- 上游新增的 keystore+GPG 签名发布 job：fork 刻意只出 debug 包，不引 secrets。
- 上次审计已判定「暂缓」的 UX 项（删除确认显示条目名、表单内联校验）仍未动。

## fork 自身缺陷（本轮修复）

1. `CoreServiceManager` 停止核心是 `CoroutineScope(Dispatchers.IO).launch` 裸协程：不可取消、不可等待、无法观测。
   现在改由受管 `coreOpScope` 跟踪为 `stopJob`，新增 `awaitCoreStopped()`（挂起，join + 轮询 Go 侧，超时上限）
   与 `awaitCoreStoppedBlocking()`（服务销毁路径用的有界阻塞版）。
2. 重启广播路径 `stopService()` → `delay(500L)` → 重启：固定睡眠既在慢机上竞态、又在快机上白等，
   改为 `awaitCoreStopped()`，超时后照旧往下走并打日志。
3. `CoreVpnService.stopAllService()` 的 `Thread.sleep(100)`（VPN 图标残留的元凶）：同一处竞态，
   改为 `awaitCoreStoppedBlocking()`。
4. 删除 `ProcessService`：全仓（含 Manifest/proguard）零调用点，其 `Thread.sleep(50)` + 无界 `waitFor()`
   每次调用泄一个协程并长期占用 IO 线程。
5. 单测从不在 CI 跑（19 个文件、含 RootProcessRunner/DialerWebviewService/RealPingExecutionLimiter 回归测试）：
   `build.yml` 新增 `./gradlew testFdroidDebugUnitTest`，排在打包之前。

## 仍未处理

- `MainServerPager` 的 servers/complex 列表仍是 fork 重写版，上游 `e2dc37ba`（行移出 composition）未评估是否同款重组热点。
- xposed/root 相关路径的其余裸 scope 未系统清理（本轮只动了停止路径）。
- Coil 3 已无自定义 ImageLoader 单例，若后续要缓存 appicon 图标，需要引入 `SingletonImageLoader` 配置。

## 验证

环境：Windows 本机，JDK 21.0.10，Gradle 9.8.0（独立发行版），AGP 9.4.1，Kotlin 2.4.20，
SDK `platforms;android-37.0` + `build-tools;36.0.0`，NDK r30（30.0.16248370），hev 子模块 2.18.0（含嵌套子模块）。
依赖经 Aliyun 镜像（`dl.google.com` / `services.gradle.org` 在本机不可达；CI 不受影响）。

```
gradle :app:compileFdroidDebugKotlin      → BUILD SUCCESSFUL
gradle :app:testFdroidDebugUnitTest       → BUILD SUCCESSFUL，19 suites / 92 tests / 0 failures / 0 errors
NDK_HOME=<ndk-r30> bash compile-hevtun.sh → 4 ABI 全部产出 libhev-socks5-tunnel.so + libhevsockstun.so（ELF 架构校验通过）
gradle :app:assembleFdroidDebug           → 5 个 APK；arm64-v8a 内 libgojni.so / libhev-socks5-tunnel.so / libhevsockstun.so 均在位
```

工件：`V2rayNG/app/build/outputs/apk/fdroid/debug/*.apk`、
`V2rayNG/app/build/test-results/testFdroidDebugUnitTest/TEST-*.xml`、仓库根 `libs/<abi>/*.so`。

本机环境两个坑（与仓库内容无关，CI 的 Linux runner 不受影响）：

- Git for Windows 无 symlink 权限，`core.symlinks=false` 会把 hev 子模块里 31 个符号链接 checkout 成
  「内容是链接目标的普通文件」，`<hev-object-atomic.h>` 因此编译失败。已就地物化为真实文件副本
  （子模块工作区因此显示 dirty，指针未变）。
- `platforms/android-37.0/android.jar` 被上一轮工作换成 234MB 的合成 jar，AGP 的 mockable-jar
  transform 会以 `Cannot read field "outgoingEdges" because "handlerRangeBlock" is null` 失败
  （AGP 9.3.2/9.4.1 同样失败，故与本次升级无关）。已换回官方 43MB 包（`platform-37.0_r01.zip`），
  单测随即通过。备份：`D:/Android/android-37.0-android.jar.bak`。
