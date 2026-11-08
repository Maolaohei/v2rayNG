# 上游同步审计 - 2026-11-08

上游 tip：`57907cf3`（up 2.3.15，2026-11-02）。同步窗口为 `2026-10-30` 审计
（基线 `aa4b0550`）之后，上游新增 6 个提交（`2026-11-01` 至 `2026-11-02`），
本次合并 4 个、跳过 2 个，全部按 `cherry-pick -x` 线性落在 `master` 上。

同步前基线：`fec874f9`（含 fork 自有的 UID 组修复与 2.3.16/2.3.17 两次发版），
已留备份分支 `backup/pre-sync-2026-11-08`。

## 已合并

| 上游 | fork | 主题 | 核实 |
|---|---|---|---|
| `fa30dc1a` | `507671cf` | 更新 `AndroidLibXrayLite` | 仅更新子模块 gitlink；`76a6e217`（`v26.10.21`）-> `2ffe5f79`（`v26.10.31`），对应 xray-core `a4a3dff5b20c2faae567b742c172c7b95e75c083`。干净 cherry-pick，子模块工作区已同步 |
| `35f5c4e5` | `881657fa` | Try fix dns (#6376) | 干净（12+/7-）；把 `remoteDns` 追加移到路由规则构造之后，路由规则里的远端 DNS 展开为全部条目并给末尾条目加 `finalQuery`，`V2rayConfig.DnsBean.ServersBean` 新增 `finalQuery` 字段 |
| `fc926f52` | `15667199` | WireGuard remoteDNS 输入提示 + 多语言 (#6381) | 冲突适配，见下 |
| `f94e9978` | `7799bab4` | 信息卡片：无到期时间显示 ∞，RTL 下流量保持 `used / total` 从左到右 (#6356) | 干净；`MainSubscriptionInfo.kt` 与上一轮合入的上游版本完全一致 |

## 适配合并细节：`fc926f52`

`values-bqi-rIR/strings.xml` 冲突：fork 早前（`775546ce` 等）自行重译了
`server_lab_local_address` / `server_lab_local_mtu` / `server_lab_remote_dns` 等行，
上游同段文案措辞不同，且其 `server_lab_remote_dns` 仍提到 `local` 取值——fork 的
`CoreOutboundBuilder` 已在新 core 下剔除 `local`/主机名这类非字面地址，该措辞会误导。

处置：保留 fork 的四行译文，只把新增的 `server_lab_remote_dns_hint` 按上游译文插入
`server_lab_remote_dns` 之后。其余 10 个 `values-*` 语言的同一行均干净合入。

`WireguardFmt.kt` 把 `remoteDNS` 的缺省值由 `AppConfig.WIREGUARD_LOCAL_REMOTE_DNS`
改为空串，与 fork 既有的 `defaultRemoteDns()` 回退逻辑一致：空值即走默认列表并按
IPv6 开关过滤，因此解析/编辑界面留空不再写入一段逗号串。

## 跳过

- `5cb002f6` 修复 WireGuard 空 remoteDNS 崩溃 (#6379)：fork 已有更强的等价实现——
  `CoreOutboundBuilder` 的 `defaultRemoteDns()` 负责拆分默认列表，并用
  `Utils.isPureIpAddress` 过滤 `local`、主机名等非字面地址，随后按 IPv6 开关回退；
  上游改动合并进去只会退化，故跳过（pick 时在 `CoreOutboundBuilder.kt` 冲突，已 abort）。
- `57907cf3` up 2.3.15：仅上游版本号，fork 使用自己的发版序列。

## 子模块

```
AndroidLibXrayLite  2ffe5f794a32ba15b122e177b5acffc6f11c4959 (v26.10.31)
hev-socks5-tunnel   c5584feca8f6df70ee53eb2879e034b849a3d15a (2.19.3)
```

上游 `v26.10.31` 发布资产 `libv2ray.aar`：60,031,471 字节，
`sha256:2efdf731b1ed624eb1cb0d5a4d0aa95c0672e89f9702226d1ba2dc90308630b6`。
`V2rayNG/app/libs/` 被 gitignore，本地 AAR 不随提交变更；仓库 CI 的 `build.yml`
按子模块 pin 下载对应 release，因此发版流水线会用 `v26.10.31` 执行实际构建门禁。

Windows 下子模块把符号链接落成普通文件造成的脏状态是既有现象，未纳入任何提交。

## 验证

环境同前几轮：JDK 21.0.10、独立 Gradle 9.8.0 发行版 +
`D:\Android\aliyun-mirror.init.gradle`、SDK android-37。

```
gradle :app:testFdroidDebugUnitTest
  -> BUILD SUCCESSFUL
  -> 26 suites / 141 tests / 0 failures / 0 errors / 0 skipped
     （上一轮：25 suites / 124 tests；本轮基数含 fork 新增的 UidGroupsTest 17 例）

gradle :app:assembleFdroidDebug
  -> BUILD SUCCESSFUL in 31s
  -> 5 个 fdroid debug APK（arm64-v8a / armeabi-v7a / universal / x86 / x86_64）
```
