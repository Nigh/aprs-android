# APRS-TX Android — agent guide

Native port of `aprs-pwa`: amateur-radio APRS position/status TX via **APRS-IS Tier2** (not the PWA Cloudflare HTTP gateway).

许可：**GPLv3**（根目录 `LICENSE`）。

## 文档同步（必须）

对项目做出任何编辑时，必须同步更新本文件，使其与代码、构建方式、后台策略保持一致。`build.sh` 与 `build.ps1` 的功能或参数有改动时，必须同步修改另一个脚本及本文件。

## 构建与设备

- **只用 `./build.sh`（Linux/WSL + Docker）或 `.\build.ps1`（Windows + WSLC）** 编译、测试、安装、adb；勿直接使用本机 `adb`/`gradlew`。
- 镜像：`xianii/android-dev:latest`（`ANDROID_DEV_IMAGE` 可覆盖），见 `android-dev-docker`。
- 编译：容器 root + `GRADLE_USER_HOME=/workspace/.gradle-docker`（不挂载宿主 `~/.gradle`）；脚本通过 Gradle Wrapper launcher 启动，兼容 Windows checkout 的 CRLF。
- Linux adb：`build.sh` 使用 `--user` + USB + `~/.android`，安装前会 `adb kill-server` 释放宿主 adb；Windows adb：`build.ps1` 通过 WSLC 容器使用 `%USERPROFILE%\.android`。
- 子命令：`build` / `release` / `test` / `install` / `run` / `adb <args>`；无参 = build+install。
- Release 签名：`./build.sh release` / `.\build.ps1 release` 读 `keystore/release.env`（`APRS_RELEASE_*`）；`/keystore/` 不入库。

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
| `Aprs.kt` | 包格式（`!lat/lon[CSE/SPD`comment）、校验、Haversine 均速；TX 委托 `AprsIs` |
| `AprsIs.kt` | 区域 rotate 选择、login、TCP 发包（短连接） |
| `LocationHelper.kt` | 单次定位；&lt;30s last-known 优先 |
| `BeaconService.kt` | 前台 `location` 服务：间隔信标 + 短时 PARTIAL wake |
| `BeaconRuntime.kt` | 进程内 UI 状态（active/countdown/location/toast） |
| `AppGraph.kt` | 单例 `SettingsStore` / `LogStore`；init 时挂 WiFi 监听 |
| `WifiAutoBeacon.kt` | WiFi 断连延时 auto-start；若监听开始时已连接，需连续断连 100s 才武装连上 auto-stop（进程存活期内） |
| `GeoAutoStop.kt` | 停发地点（最多 16，每区 enabled + 半径 50–5000m）；`geoAutoStopStep` 状态机 |
| `SmartBeacon.kt` | 最小/最大间隔与位移 TX 判定（`shouldBeaconTx`）；任意两次发包 ≥30s |
| `GpsPowerSave.kt` | 连续 GPS 超时退避（3 次 +30s，上限 300s；成功恢复 min；min≥300 不干预）；`BeaconService` 轮询间隔 |
| `Transmitter.kt` | GPS+发包共享逻辑；成功 TX 记 lastTx；全局 30s cooldown |
| `MainActivity.kt` / `Ui.kt` | 主界面（passcode 编辑聚焦时明文、失焦时遮罩，Send once / Start scheduled TX；Settings 右下角）+ Settings（高对比度统一 Switch 配色、min/max interval、位移 TX、Automatic power saving、WiFi、Stop zones〔Add 成功后清空经纬度输入〕、JSON 导入/导出、紧凑底栏 GitHub / made by BA7NTM）+ Logs；Settings/Logs 的系统返回键或手势回主界面；根 `Surface` 用 `WindowInsets.safeDrawing`（targetSdk 35 edge-to-edge） |
| `SettingsStore.kt` | SharedPreferences；`SettingsBackup` JSON 编解码（不含 lastTx/位置） |
| `XianiiTheme.kt` | Compose 主题：[@xianii/design-system](https://github.com/Nigh/xianii-theme) token → Material3（跟系统深/浅） |
| `res/mipmap-anydpi/ic_launcher*.xml` | 自适应 launcher icon（fg 居中缩至 60% / bg 全幅 → `drawable/ic_launcher_{foreground,background}.png`） |
| `docs/icon.png` | README 顶部预览（合成 fg/bg） |
| `build.sh` / `build.ps1` | Linux/WSL Docker 与 Windows WSLC 编译、测试、安装；两者接口保持同步 |
| `README.md` | 用户功能与 Linux/WSL、Windows WSLC 构建说明 |

## 后台与省电（约定）

- 定时发送**必须**走 `BeaconService`（FGS），不要用普通后台线程/WorkManager（&lt;15min 间隔）。
- 任意两次成功发包间隔 ≥30s（手动 Send once 与 scheduled 共用 `lastTxAtMs`）。
- Settings：min interval 30–3600s（默认 60）；max ≤3600 且 ≥ min（默认 300）；位移阈值默认 100m（100–1000）。位移 TX 默认关：只编 min，max 跟随 min；开启后 GPS 按 min 取点，位移 ≥阈值则按 min 发包，否则到 max 强制发包。
- 自动省电（默认开）：Beacon 连续 3 次 GPS 超时（不含 &gt;30s last-known 兜底）则下次 GPS 轮询间隔 +30s，上限 300s；成功取点后恢复 min interval。若 min interval ≥300s 则不干预。运行时状态，不持久化。
- Settings 备份：Export/Import JSON（呼号、passcode、comment/status、间隔与位移、自动省电、WiFi、Stop zones；不含 lastTx/位置）；SAF `CreateDocument`/`OpenDocument`。
- 每轮只做一次单次定位；手动 TX 位置未过期（60s）则复用；scheduled GPS 轮次强制刷新（fallback last-known ≤30s）。
- 禁止连续 `requestLocationUpdates`；禁止 screen wake lock。
- TX 前后 `PARTIAL_WAKE_LOCK` ≤60s，间隔内仅 `delay` 倒计时。
- 通知 channel：`IMPORTANCE_LOW` + silent。
- WiFi 自动启停：`Settings` 两项（断连后等一个 min interval 再 start）；监听开始时已有 WiFi 则 auto-stop 初始未武装，需完全断连并连续保持 100s 才武装，100s 内重连会取消且下次断连从 0 计时，武装后连上才 stop；`ConnectivityManager` NetworkCallback，进程被杀则失效。
- Geo auto-stop：`Settings` 最多 16 个 StopZone（每区 Switch + 半径 50–5000m）；Beacon 每次 GPS 轮次判定；启动时已在启用区内则需离开全部区外并越过迟滞距离再武装（半径 ≤1000m 时 +50m，>1000m 时 +100m）；进入启用区则本轮不发包并 stop。

## 自检

- `./build.sh test` / `.\build.ps1 test` → `AprsTest`（坐标格式、包组装、呼号校验、rotate 选区、login 行、WiFi auto 动作与连续断连武装、geo auto-stop、min/max/位移 TX 判定、Settings JSON 备份往返、GPS 自动省电退避）。
