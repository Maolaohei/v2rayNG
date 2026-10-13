# 上游同步审计 — 2026-10-13

上游 tip：`e1b73f2f`（up 2.3.11，2026-10-11）。同步窗口：2.3.10（`a4dd3d08`）之后的 12 个提交，
其中 3 个此前已吸收（`fcacb9cf`→`2ab5cd0ad`、`6fe3893bf`→`66f7115b8`、`ffc96144`→`b8f663bd1`），
本次合并 5 个、移植 1 个、跳过 2 个。

## 已合并

| 上游 | fork | 主题 | 核实 |
|---|---|---|---|
| `4a1510eb` | `693f34185` | 刷新开关小组件样式与图标 | `widget_switch.xml`、`ic_rounded_corner_active/inactive.xml` 三个文件与上游改动前逐字相同，干净 cherry-pick；新样式（46dp 圆角底 + 播放图标 + 阴影标题）与 fork 主界面无设计冲突 |
| `4c9c9afd` | `14c0559fd` | root 模式把发往 LAN DNS 的查询 DNAT 到公网 IP | fork `RootProxyManager.kt`/`AppConfig.kt` 与上游改动前逐字相同，干净 cherry-pick。修复的是真实缺陷：`geoip:private → direct` 规则会抢在 `dns-out` 前把 53 端口查询送回 LAN 解析器（ISP DNS 污染、CDN 误解析）；同时补齐 `CORE_DNS_OUT` 链的清理段 |
| `e1b190d8` | `c3745ca48` | 阿塞拜疆语支持（#6302，重新落地版） | fork 缺 `values-az`；`Language.kt`/`arrays.xml`/`build.gradle.kts` 目标区域与上游改动前一致（fork 仅多一处空行与 `xposed_scope` 数组），干净 cherry-pick；`generateLocaleConfig` 自动把 az 纳入 locale 配置（已在构建产物核实） |
| `a258ba8b` | `c0f38230` | AndroidLibXrayLite pin 更新 | fork pin 停在 `d0c6c4ae`（v26.9.9；此前审计记录的 `ea96a7f` 实际未落到 gitlink），对齐上游 `1f3e2c5d`（v26.10.10）。CI 本就动态拉最新 release，本次让 pin 与 CI/上游一致 |
| `143ab036` | `b0ca8c92` | Gradle wrapper 9.8.1、Kotlin 2.4.21 | Gradle 9.0.0–9.8.0 受影响于三个高危 advisory（GHSA-mvvg-497x-hmj8 等），fork 原为 9.8.0；Kotlin 为 2.4.20→2.4.21 bugfix（上游核实编译器输出逐字节一致） |

## 测试移植

`61c4e40e`（上游 RootProcessRunner 测试防抖修复）对应的测试文件在 fork 不存在——
上一轮手工移植 `RootProcessRunner`（`89ffa5bc`）时没带测试。本次直接移植上游该文件
（含 250ms→3000ms 的防抖修复）：

- `ece8c06f`：移植四个用例——静默阻塞命令按时截止、后台进程占用 stdout 不能拖住已完成的命令、
  大输出被有界截断、失败码与诊断输出保留；
- `7636bf95`：Windows 适配。上游用裸 `sh`，Git for Windows 不在 PATH 时四个用例全部
  `IOException`（本机实测）。改为先试 `sh`，再回退 `C:\Program Files\Git\bin\sh.exe`，
  都不可用时 JUnit assumption 跳过；Linux CI 仍全量执行。

## 已包含，无需动作

- `ffc96144`（wireguard 新 core 特性）已由 `b8f663bd1` 提前吸收并加固（过滤非字面量 DNS、
  拆分逗号默认值），当时还顺带更新了 9 个 locale 提示文案；
- `fcacb9cf`→`2ab5cd0ad`、`6fe3893bf`→`66f7115b8` 为 10-08 那轮已合并项。

## 跳过

- `36874367` + `44a56f56`：阿塞拜疆语 #6286 与其 revert，净效果为零，最终版由 `e1b190d8` 取代；
- `e1b73f2f`：版本号 2.3.11/751——fork 已在自己的发版节奏上到 2.3.13/753，不回退。

`git cherry` 中更早的 `+` 项均为前几轮审计已处置（已适配合并、设计冲突或版本/翻译刷新），
本轮窗口内不存在其他未处置的上游提交。

## 验证

环境同前几轮：JDK 21.0.10、独立 Gradle 9.8.0 发行版 + `D:\Android\aliyun-mirror.init.gradle`
（wrapper 已随上游指向 9.8.1，CI 走 wrapper）、SDK android-37。

```
gradle :app:testFdroidDebugUnitTest  → BUILD SUCCESSFUL，22 suites / 99 tests / 0 failures
                                       （新增 RootProcessRunnerTest 4/4 实跑，无 skip；
                                        修复前本机因缺 sh 4 个全挂）
gradle :app:assembleFdroidDebug      → BUILD SUCCESSFUL，5 个 APK（2.3.13-fdroid，5 ABI）
```

产物核实：`merged_res/.../values-az_values-az.arsc.flat` 已生成；
`generateFdroidDebugLocaleConfig` 的临时工程枚举出 `values-az`（locale 配置包含 az）；
widget 三个资源文件与上游 master 逐字节一致（`git diff upstream/master -- <paths>` 为空，
仅剩 fork 自有的 AppConfig 常量、`xposed_scope`、Language.kt 空行等既有差异）。

## 发版

- `chore(release): 2.3.14 (754)` + 轻量 tag `2026.10.13`（沿用 `2026.10.8` 的日期 tag 惯例）；
  push 到 master 后 CI 自动出 debug 包。
- 上游更早窗口里已判定跳过/暂缓的项（Monet 色系、FAB 动画、toast 透明度、纯翻译刷新等）不重开。
