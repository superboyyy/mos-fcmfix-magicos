# MOS推送修复（mos-fcmfix-magicos）

一个专为国行荣耀 **MagicOS** 编写的 LSPosed 模块，用于修复系统省电与清理机制对 Google FCM 推送的限制。

模块无任何联网权限，自带轻量化 Material You 管理界面（支持查看运行状态、切换功能开关、管理应用排除名单与实时日志）。

---

## 适用环境

- **设备系统**：基于国行 **MagicOS 11（Android 16 / 17）** 反编译分析并实测通过。
- **前置要求**：已获取 Root 权限（KernelSU / Magisk / APatch 均可），并已安装 **LSPosed**。
- **版本兼容性说明**：
  - 所有 Hook 均已捕获异常，找不到对应类或方法时会自动跳过，不会导致系统崩溃或卡开机。
  - 部分针对荣耀省电精灵（`com.hihonor.powergenie`）的 Hook 以及 `NotificationManagerService.cancelAllNotificationsInt` 依赖特定固件版本的类名/混淆签名或参数个数；在其他 MagicOS 版本或系统 OTA 后，部分 Hook 可能会被自动跳过（可在管理界面「状态」页或日志中查看挂载情况）。

---

## 核心功能

| 功能开关 | 对应配置键 | 说明 |
| --- | --- | --- |
| 阻止锁屏 / 夜间断网 | `block_night_firewall` | 拦截外部进程（PowerGenie）开启 `POWERSAVE` 网络防火墙链，保留 `system_server` 内部原生省电模式调用与关闭防火墙请求 |
| 推送拉起已停止的应用 | `push_wake` | 为真实 GMS 发出的 `com.google.android.c2dm.intent.RECEIVE` 广播追加 `FLAG_INCLUDE_STOPPED_PACKAGES`，使 `stopped` 状态的应用也能被拉起 |
| 推送后 30 秒放行 | `delivery_window` | 收到 FCM 后 30 秒内放行目标应用的 Service/Job（解除 `isServiceProxy`、`HN_USER_EXPERIENCE`、`HnJobResourceController` 限制），推迟 PowerGenie/iAware 冻结、唤醒锁代理、强制释放唤醒锁、PowerGenie 小憩转休眠及休眠断网/掐连接；若有系统同步（`SyncManager`）正在运行则自动延长窗口（最长 3 分钟，结束后 3 秒收尾） |
| Wi-Fi / 数据切换时检查推送 | `reconnect` | 当非默认网络出现或丢失时，向 GMS 发送 `com.google.android.intent.action.MCS_HEARTBEAT` 主动触发心跳检查（5 分钟限频一次），避免假在线 |
| 应用被停时保留通知 | `keep_notification` | 应用被强行停止（`REASON_PACKAGE_CHANGED`，真卸载除外）或被 iAware 快速清理进程时，保留非静音/非常驻的已有通知 |

所有开关默认开启，在 App 内修改后约 **10 秒内生效，无需重启**。

---

## 安装与启用

1. 安装构建好的 APK（包名：`com.superboyyy.mosfcmfix`）。
2. 打开 **LSPosed**，启用「MOS推送修复」，确认勾选以下**两个作用域**：
   - **系统框架**（`android`）
   - **荣耀省电精灵**（`com.hihonor.powergenie`）
3. **重启手机**。
4. 打开「MOS推送修复」App，并授予 Root 权限，即可在界面中查看挂载状态、配置开关或排除指定应用。

---

## 特殊机制与透明说明

为避免预期外行为，以下设计细节与默认硬编码特例在此透明列出：

1. **Gmail 专项保护（仅受总开关控制）**：
   - 由于 Gmail 收到 FCM 仅作为 tickle 信号、且在 TCP 连接被系统强行销毁或休眠禁网后极易出现同步卡死（`ERR_CONNECTION_ABORTED`），模块对 `com.google.android.gm` 做了专项保护：
     - 全天候跳过 `ConnectivityService`（`InetDiagMessage.destroyLiveTcpSockets*`）的 TCP 销毁；
     - 全天候跳过 PowerGenie 的 `cSockets` 断连与 `ASH_DENY_FLAG` 休眠禁网；
     - 收到 FCM 后始终开启 30 秒放行窗口。
   - 上述针对 Gmail 的保护**不受单个功能开关和「应用」页排除名单影响**，仅在关闭「总开关」后停止。
2. **窗口内系统同步失败自动重试**：
   - 在推送放行窗口内发起的系统同步（`SyncManager`）若以 `databaseError` 结束，模块会以系统身份自动重新触发 `ContentResolver.requestSync`（忽略 backoff），按 3 秒 / 5 秒 / 10 秒最多重试 3 次，无独立开关。
3. **启动去优化（Deoptimize）**：
   - `system_server` 加载模块时会对 `JobSchedulerService.isReadyToBeExecutedLocked` 与 `JobConcurrencyManager.stopNonReadyActiveJobsLocked` 调用 `XposedBridge.deoptimizeMethod`，防止 ART 内联导致 Hook 失效。
4. **本地文件与系统状态**：
   - 配置文件：`/data/system/fcmfix-magicos/config.properties`
   - 应用排除名单：`/data/system/fcmfix-magicos/push_exclude.txt`
   - 运行日志：`/data/system/fcmfix-magicos.log`（超过 1 MB 自动轮转为 `.old`）
   - 跨进程窗口同步：写入 `Settings.Global` 键 `mosfcmfix_push_windows`，供 PowerGenie 进程读取窗口截止时间。
   - 注：配置目录权限为 `system:system`（`0700` / `0600`）；若个别固件上 `com.hihonor.powergenie` 无权限读取配置文件，PowerGenie 侧开关会按默认开启处理。

---

## 应急总开关（无需重启）

除在 App「状态」页点击总开关外，也可以通过 Root Shell 立即暂停或恢复全部 Hook（5 秒内生效）：

```bash
# 暂停所有功能
su -c "resetprop persist.mosfcmfix.off 1"

# 恢复运行
su -c "resetprop persist.mosfcmfix.off 0"
```

---

## 从源码构建

本项目无需 Gradle 或下载外部依赖，仅需 **JDK** 和 **Android SDK（build-tools + API ≥ 31 平台包）**，直接运行根目录脚本即可打包：

```bash
./build.sh
```

默认输出路径为 `build/mos-fcmfix-magicos.apk`。未指定签名时会自动在 `build/debug.jks` 生成临时调试签名；正式发布请通过环境变量指定自有密钥：

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
export KEYSTORE=/path/to/release.jks
export KEY_ALIAS=your_alias
export KEYSTORE_PASS=your_store_pass
export KEY_PASS=your_key_pass
./build.sh
```

支持的全部环境变量见 [build.sh](build.sh) 顶部注释。

---

## 致谢与许可

- 核心思路参考自 [kooritea/fcmfix](https://github.com/kooritea/fcmfix) 与 [ByteJuno/fcmfix-coloros](https://github.com/ByteJuno/fcmfix-coloros)（MIT License）。
- 应用图标基于 Google Material Icons（Apache License 2.0）。
- 本项目源码采用 [MIT License](LICENSE) 开源。
