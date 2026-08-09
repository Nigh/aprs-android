# APRS-TX Android — agent guide

Native port of `aprs-pwa`: amateur-radio APRS position/status TX via **APRS-IS Tier2** (not the PWA Cloudflare HTTP gateway).

## 文档同步（必须）

对项目做出任何编辑时，必须同步更新本文件，使其与代码、构建方式、后台策略保持一致。

## 构建与设备

- **只用 `./build.sh`** 编译、测试、安装、adb；勿用本机 `adb`/`gradlew`。
- 镜像：`xianii/android-dev:latest`（`ANDROID_DEV_IMAGE` 可覆盖），见 `android-dev-docker`。
- 编译：容器 root + `GRADLE_USER_HOME=/workspace/.gradle-docker`（不挂载宿主 `~/.gradle`）。
- adb：`--user` + USB + `~/.android`；安装前会 `adb kill-server` 释放宿主 adb。
- 子命令：`build` / `test` / `install` / `run` / `adb <args>`；无参 = build+install。

## 技术栈

- Kotlin + Jetpack Compose，无 ViewModel / DI
- SDK 35，minSdk 28，Java/Kotlin 21
- 包名 `com.nigh.aprstx`，入口 `MainActivity`
- 定位：平台 `LocationManager`（无 Play Services / FusedLocation）
- 传输：TCP APRS-IS **port 14580** → 区域 rotate（`AprsIs.selectRotateHost`），无 GPS 时用 `rotate.aprs2.net`
- 不用 PWA 的 `aprs-api.tecnico.cc`（浏览器同源/CORS 限制才需要 CF 中转）

## 代码布局

| 文件 | 职责 |
|------|------|
| `Aprs.kt` | 包格式、校验、Haversine 均速；TX 委托 `AprsIs` |
| `AprsIs.kt` | 区域 rotate 选择、login、TCP 发包（短连接） |
| `LocationHelper.kt` | 单次定位；&lt;30s last-known 优先 |
| `BeaconService.kt` | 前台 `location` 服务：间隔信标 + 短时 PARTIAL wake |
| `BeaconRuntime.kt` | 进程内 UI 状态（active/countdown/location/toast） |
| `AppGraph.kt` | 单例 `SettingsStore` / `LogStore` |
| `Transmitter.kt` | GPS+发包共享逻辑 |
| `MainActivity.kt` / `Ui.kt` | 主界面 + Logs |
| `build.sh` | Docker 编译/测试/安装 |

## 后台与省电（约定）

- 定时发送**必须**走 `BeaconService`（FGS），不要用普通后台线程/WorkManager（&lt;15min 间隔）。
- 每轮只做一次单次定位；位置未过期（60s）则复用。
- 禁止连续 `requestLocationUpdates`；禁止 screen wake lock。
- TX 前后 `PARTIAL_WAKE_LOCK` ≤60s，间隔内仅 `delay` 倒计时。
- 通知 channel：`IMPORTANCE_LOW` + silent。

## 自检

- `./build.sh test` → `AprsTest`（坐标格式、包组装、呼号校验、rotate 选区、login 行）。
