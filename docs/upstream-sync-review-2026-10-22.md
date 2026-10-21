# 上游同步审计 - 2026-10-22

上游 tip：`118cf5bc`（up 2.3.12，2026-10-18）。同步窗口为
`2026-10-13` 审计之后，上游仅新增两个提交，本次合并 1 个、跳过 1 个。

## 已合并

| 上游 | fork | 主题 | 核实 |
|---|---|---|---|
| `58ca4cfe` | `b1f9c5fb` | 更新 `AndroidLibXrayLite` | 仅更新子模块 gitlink；`1f3e2c5d` -> `1be1e621`（`v26.10.17`），对应 xray-core `92ccc939aaf4f8965f369cf0b142e005aeaa11d5`。上游改动前 pin 与 fork 一致，干净 cherry-pick |

子模块工作区已同步到 `1be1e621`，`git submodule status AndroidLibXrayLite`
显示 `(v26.10.17)`。上游发布资产为 `libv2ray.aar`，大小 59,788,467 字节，
GitHub digest 为
`sha256:fd6cb61fed873e465c3064327dffbcb22dc3fd78260d162ec9e868db2582495f`。

## 跳过

- `118cf5bc`：仅把上游版本号从 2.3.11/751 更新为 2.3.12/752；fork 使用自己的发版序列，不跟进该版本号提交。

`git cherry` 中更早的 `+` 项均为前几轮审计已处置（适配合并、设计冲突、
版本或翻译刷新）。本轮窗口内没有其他待处置提交。

## 验证

环境同前几轮：JDK 21.0.10、独立 Gradle 9.8.0 发行版 +
`D:\Android\aliyun-mirror.init.gradle`、SDK android-37。

```
gradle :app:testFdroidDebugUnitTest :app:assembleFdroidDebug
  -> BUILD SUCCESSFUL in 32s
  -> 22 suites / 99 tests / 0 failures / 0 errors / 0 skipped
  -> 5 个 fdroid debug APK
```

本地 Gradle 使用 `V2rayNG/app/libs/libv2ray.aar`（该目录被 gitignore）；
当前网络下 `v26.10.17` 发布资产下载速度约 15 KB/s，未在本地替换该 AAR。
仓库 CI 的 `build.yml` 会从 `2dust/AndroidLibXrayLite` 最新 release 下载
`libv2ray.aar`，因此最终发版流水线会以 `v26.10.17` 执行新 core 的实际构建门禁。

## 发版

- `chore(release): 2.3.15 (755)` + 轻量 tag `2026.10.22`；
- push 后另行触发 `Build APK` workflow 发布预发布版本，并核验 10 个 APK 资产。
