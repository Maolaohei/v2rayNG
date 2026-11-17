# 上游同步审计 - 2026-11-18

上游 tip：`ef06bac9`（2.4.0-3，2026-11-16）。同步窗口为 `2026-11-08` 审计
（基线 `57907cf3`）之后，上游新增 27 个提交（2026-11-08 至 2026-11-16），
本次合并 22 个、跳过 5 个，全部按 `cherry-pick -x` 线性落在 `master` 上。

同步前基线：`4e9c7adb`（2.3.18/758），已留备份分支 `backup/pre-sync-2026-11-18`。
同步后版本：`2.4.0 (760)`，发布标签计划 `2026.11.18`。

## 已合并

| 上游 | fork | 主题 |
|---|---|---|
| `e10fafe6` | `88ef146e` | Fix udphop (#6417) |
| `e75dd93e` | `dd0e2e19` | ui(wireguard): clearer hint for the local address field + locales (#6391) |
| `6e3945ec` | `391f25f7` | Filter editable dropdown options by typed text (#6390) |
| `ae441e51` | `ed02f27c` | Support gecko obfs for Hysteria 2 (#6406) |
| `4c76cc53` | `b8df3e69` | fix(fmt): split share link query pairs on the first '=' only (#6413) |
| `1828b827` | `9d5c9034` | fix(root): fix the device dns DNAT from #6298 (#6415) |
| `6dd0cb2d` | `41426440` | Bump actions/setup-java from 6.1.0 to 6.2.0 (#6424) |
| `dedecf31` | `43f9ac47` | Fix geo asset replacement reporting success on rename failure (#6419) |
| `9a7df0d0` | `965dba9b` | build: bump Android Gradle Plugin to 9.5.0 (#6375) |
| `0975991b` | `5e588bde` | Update AndroidLibXrayLite |
| `a66365cf` | `68b89994` | chore(deps): update Gson to 2.15.0 (#6355) |
| `33737a3b` | `5a369035` | Add certificate pin fetching for Hysteria2 |
| `0b1ae2ae` | `0cdfbeec` | Show connection delay before IP lookup |
| `bec228bf` | `0a22710b` | Update libs.versions.toml |
| `1b45020c` | `a849c5b7` | add support for importing custom outbound configurations from JSON and editing them (#6401) |
| `b17fd69d` | `1901ea65` | feat(profile): add MASQUE (+WARP) protocol support (#6402) |
| `3d1063ac` | `c7a0fd97` | Adjust UI |
| `25e69438` | `b05a799f` | Add searchable proxy chain member selection |
| `b6ef9be4` | `59eda9bb` | fix(sub): stop duplicating subscription policy groups on every update (#6426) |
| `0969f2ad` | `64b09172` | feat: synchronize V2rayConfig with Xray-core (#6434) |
| `141059d9` | `54b129e4` | fix(chain): store proxy chain members as a list (#6438) |
| `ef06bac9` | `92c17a83` | Fix dns (#6462) |

## 适配细节

- `e75dd93e`：`values-bqi-rIR/strings.xml` 冲突。保留 fork 已有的
  `server_lab_local_address` / `server_lab_local_mtu` / `server_lab_remote_dns`
  译文，仅插入新增的 `server_lab_local_address_hint`。
- `ae441e51`：`values-bqi-rIR/strings.xml` 冲突。保留 fork 的
  `server_lab_port_hop` / `server_lab_port_hop_interval` 译文，补入
  `server_obfs_type`、`server_lab_min_packet_size`、`server_lab_max_packet_size`
  三条 Hysteria2 混淆字符串。
- `b17fd69d`：`values-bqi-rIR/strings.xml` 冲突。保留 fork 的
  `local_mtu` / `remote_dns` 译文，补入 `server_lab_masque_warp_settings`、
  `server_lab_masque_warp_private_key`、`server_lab_masque_warp_public_key` 三条字符串。
- `6dd0cb2d`：`.github/workflows/build.yml` 冲突。保留 fork 的
  `Run unit tests` 步骤及本地结构，只把顶部 `actions/setup-java@v6.1.0`
  改为 `@v6.2.0`。
- 其余提交均为干净 cherry-pick；未产生 merge commit。

## 跳过

| 上游 | 主题 | 原因 |
|---|---|---|
| `adabaa84` | Draw the list divider below each row instead of over the top of the reordered row (#6410) | 列表分隔线绘制调整，fork 的 `MainServerPager.kt` 与上游结构不同，未纳入 |
| `89f0f3a1` | Redesign the connection bar: floating rounded track with a round connect knob that slides on connect (#6374) | 底部连接栏整体重设计，视觉与交互取向不同，未纳入 |
| `4ea9bd95` | Use dialogs to select outbound profiles | outbound profile 选择对话框属于交互改版，fork 已有自己的编辑页选择逻辑 |
| `cc074f0b` | Cap the connection bar width and center it on wide screens (#6427) | 依赖 `89f0f3a1` 的连接栏布局，未纳入 |
| `1fa257fa` | up 2.4.0 | 仅上游版本号；fork 手动同步为 `2.4.0 (760)` |

## 子模块

```
AndroidLibXrayLite  b5a377087e74eaa34e86718710b04c50f4b767f5  (v26.11.9)
hev-socks5-tunnel   c5584feca8f6df70ee53eb2879e034b849a3d15a  (2.19.3)
```

`V2rayNG/app/libs/` 被 gitignore，本地 `libv2ray.aar` 不随提交变更；CI 的
`build.yml` 会解析 AndroidLibXrayLite 最新 release 并下载实际 AAR。Windows 下
子模块符号链接落成普通文件的既有脏状态未纳入提交。

## 验证

- `:app:compileFdroidDebugKotlin`：BUILD SUCCESSFUL（本轮首次全量编译 32m 11s）。
- `:app:testFdroidDebugUnitTest`：BUILD SUCCESSFUL in 1m 1s；29 suites / 165 tests /
  164 passed / 1 skipped / 0 failures / 0 errors。跳过的是
  `RootProcessRunnerTest.inherited stdout cannot hold a completed command open`：
  Windows 的 Git Bash 会等待后台子进程退出，与 POSIX sh 语义不同，用例在 Windows
  本地跳过；其余用例全部通过。
- `:app:assembleFdroidDebug`：BUILD SUCCESSFUL in 16m 1s，生成 5 个 fdroid debug APK
  （arm64-v8a / armeabi-v7a / universal / x86 / x86_64），universal 85,197,485 字节。
- `git diff --check`：清理 `ServerHysteria2Activity.kt` 文件末尾多余空行后无告警。

## 发版

- 版本：`2.4.0 (760)`，提交 `62a55855`，标签 `2026.11.18`，已推送
  `4e9c7adb..62a55855`。
- push 触发 `Build APK`：run `44182840217` success；`Validate Fastlane metadata`：
  run `44182840208` success。
- `workflow_dispatch -f release_tag=2026.11.18`：run `44183106062` success。
- GitHub Release `2026.11.18`（pre-release）：
  https://github.com/Maolaohei/v2rayNG/releases/tag/2026.11.18
  挂载 10 个 APK：fdroid 与 playstore 各 arm64-v8a / armeabi-v7a / universal /
  x86 / x86_64，universal 84,696,883 字节。
