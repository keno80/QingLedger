# 真机调试记录

日期：2026-10-13。用户明确要求构建、安装并在已连接真机启动调试。

## 环境

- 设备：24053PY09C / chenfeng。
- Android：17，API 37。
- 系统版本：OS4.0.0.15.XPACNXM。
- SDK：E:/Android/SDK，已安装 platform 37。
- 应用：io.qingledger.app，versionName 0.1.0，Debug APK。

## 构建与安装

执行 `./gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 4m 38s`，92 个任务执行。无编译错误；存在 Compose 依赖访问器和 Gradle 弃用警告，未影响当前构建，升级工具链前需处理。

APK：`app/build/outputs/apk/debug/app-debug.apk`。

ADB install -r 返回 Success；首次 `am start -W` 返回 Status: ok、LaunchState: COLD、TotalTime: 1002 ms。强制停止后的再次冷启动为 717 ms。它们是单次启动观测，不是性能基准。

## 已检查的行为

| 检查 | 结果与证据 |
| --- | --- |
| 首页启动 | 账单页可见，进程保持运行 |
| 四入口切换 | 已通过 ADB 输入操作与 UI hierarchy 确认各页文案 |
| 深色切换 | 设置页深色被选中，页面与状态栏切换，截图已检查 |
| 持久化 | 强制停止后重启，账单页仍为深色，截图已检查 |
| 浅色切换 | 设置页浅色被选中，截图已检查 |
| 系统返回 | 从我的页返回后 UI hierarchy 包含“本月净支出”，确认回到账单页 |
| 崩溃检查 | 当前应用进程的 AndroidRuntime:E 日志没有输出，未观察到致命异常 |
| 当前交付状态 | 已恢复跟随系统，留在账单首页；记账按钮仍禁用，权限功能未接入 |

首次启动日志含厂商图形 / QoS 诊断和 Skipped frames 提示，未出现应用致命异常；当前检查不等于长期稳定性或性能验收。

## 本地调试截图

截图和 UI hierarchy 存放于 `debug-artifacts/`，已加入 .gitignore；包含设备状态栏，仅用于本地调试。

- ledger.png：首次启动浅色首页。
- settings-dark.png：深色设置页。
- ledger-dark-restart.png：重启后深色首页。
- settings-light.png：浅色设置页。
- ledger-final.png：最终账单首页。

尚未验证 200% 字体、旋转 / 滚动恢复、读屏、长期运行、自动化测试、通知采集、Root 或 Shizuku。没有修改业务源代码，没有提交或推送代码。
