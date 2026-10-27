# 兼容与依赖记录

日期：2026-10-13。

2026-10-27：0.8.1 修正桌面 Java 与 Android ICU 对正则字面量右花括号处理不一致的问题，新增实际 Android 运行时回归。正式应用的 Shizuku UserService 已在 Android 17 完成窗口 / 显示屏能力连接；同机管理器仍显示授权应用为 0，不能以列表计数替代实时服务端检查。没有新增 schema 变更。见 [连接验收](shizuku-connection-2026-10-27.md)。

2026-10-27：0.8.0 引入 Shizuku API / Provider 13.1.5 与独立只读 UserService，增加 specialUse 前台服务承载今日收支和 Shizuku 采集；系统授权仍由用户操作。Room 5→6，备份读取兼容 3 / 4 / 5 / 6；旧配置保持直接入账。已构建，曾安装验证原生弹窗和迁移，最新交付构建设备已断开未再次安装，Shizuku 真正授权后的图像采集尚未验收。详细通过数、跳过项和设备边界见 [验收记录](automation-options-2026-10-27.md)。下文按历史阶段保留。

2026-10-14：0.5.3 / 测试器 0.3.0 已构建安装，32 项领域测试及模拟跨页面回归通过。数据库测试 APK 安装出现 INSTALL_FAILED_USER_RESTRICTED，本轮 instrumentation 未重跑；不将历史 21 项结果计算为本轮通过数量。无 schema 或依赖变更，见 [重新验收](cross-page-payment-2026-10-14.md)。

2026-10-14：轻账 0.5.2、测试器 0.2.0 已更新安装，26 项领域和 21 项真机数据库 / 迁移测试通过，无 schema 变更。资金账户字段仅在模拟成功页完成回调验收，真实微信付款方式控件仍待适配，见 [付款账户验收](payment-account-2026-10-14.md)。

2026-10-14：0.5.1 与测试器 0.1.0 已在原设备构建安装，22 项领域测试通过；用户授权下无障碍成功页真实回调及重复刷新回归通过。模拟包仅在 Debug 白名单开放。UIAutomation 默认抑制服务会使层级抓取干扰测试，使用截图 / 输入完成本次验收。见 [测试器验收](payment-simulator-2026-10-14.md)。

2026-10-14 更新：0.5.0 新增 AccessibilityService 与 XML 配置，最低 API 30 和依赖版本不变。Debug 构建、20 项领域测试、原设备更新安装和启动 / 方式入口检查通过；系统无障碍授权与实际页面节点未验证。通知仅保留 Debug 回归方式；三种主方式中 Shizuku + ADB 与 Root 后端尚未实现。见 [接入说明](accessibility-2026-10-14.md)。

0.4.1 改为通知直接入账，沿用 schema v2 与依赖版本。用户要求构建测试后，Debug 构建、16 项领域测试和 18 项真机数据库 / 迁移测试通过，已在原设备更新安装。见 [直接入账变更](direct-booking-2026-10-13.md)。真实支付覆盖仍待验证。

| 项目 | 固定值 | 依据与验证状态 |
| --- | --- | --- |
| AGP | 9.4.1 | 与 miuix 0.9.4 官方版本目录一致；本工程未构建验证 |
| Gradle | 9.7.1 | miuix 0.9.4 官方发布说明记录；本工程未执行 |
| Kotlin / Compose Compiler | 2.4.20 | 与 miuix 0.9.4 官方版本目录一致 |
| Compose | 1.12.0 | 与 miuix 0.9.4 官方版本目录一致；使用 Android 依赖变体 |
| miuix | 0.9.4 | 正式版；已按该标签核对 Scaffold / NavigationBarItem / Card / Button / Theme 参数 |
| Activity Compose | 1.13.0 | miuix 官方版本目录 |
| Lifecycle Compose | 2.11.0 | JetBrains AndroidX 坐标，与 miuix 官方版本目录一致 |
| DataStore | 1.1.7 | 固定版本；本工程未进行运行验证 |
| Coroutines | 1.10.2 | 固定版本，实际依赖解析结果未验证 |
| SDK | min 30 / compile 37 / target 37 | 不使用要求 API 33 的 miuix-blur |

AGP 9 使用内置 Kotlin 支持，Android 模块不再应用 kotlin-android 插件。纯 domain 模块使用 kotlin-jvm。组件库的构建组合仅作为版本选择依据，不等于本工程已验证兼容。

0.4.0 已声明由 BIND_NOTIFICATION_LISTENER_SERVICE 保护的通知监听服务，需用户在系统设置授权。Manifest 没有 INTERNET，系统备份关闭；未接入 Shizuku Provider、Root 命令或支付应用私有数据访问。

## 真机支持矩阵

2026-10-13：设备 24053PY09C（chenfeng）、Android 17 / API 37、系统 OS4.0.0.15.XPACNXM，已通过当前界面基础版的安装、启动、四入口切换、浅深主题、主题持久化和返回导航检查。未探测 Root 状态，未验证通知来源和增强权限，不能由此推断交易采集支持。

其他设备及 Android 11、AOSP 类系统组合仍未验证。实际来源应用 versionCode 与授权能力结果将在 T04 / T06 写入。

本工程的固定依赖组合已成功完成 `:app:assembleDebug`；上方表格中的“未执行 / 未构建”描述为最初选型状态，本次编译证据以 [真机调试记录](device-debug-2026-10-13.md) 为准。

0.2.0 增加 Room 2.8.5、KSP 2.3.12、JUnit 4.13.2、AndroidX Test JUnit 1.3.0 / Runner 1.7.0、Lifecycle ViewModel Compose 2.11.0。Debug 构建、8 项领域测试和 6 项真机数据库测试已通过；详见 [手动记账验收记录](manual-ledger-2026-10-13.md)。数据库 schema v1 随源代码保存，不使用 destructive migration。

0.3.0 沿用以上依赖和 schema v1，未修改 Entity 字段或清空数据库。累计 10 项领域测试、9 项真机数据库测试通过；同一设备完成管理与修改历史 UI 验收，见 [管理与历史验收记录](reference-management-2026-10-13.md)。通知来源和增强权限仍未验证。

## 官方依据

0.4.0 沿用固定依赖，Debug 构建及 16 项领域、17 项真机数据库 / 迁移测试通过。schema v2 使用显式 MIGRATION_1_2，保留 v1 账单并默认关闭采集。用户在上述设备的系统界面授权后，监听已连接，ADB 非支付样例闭环已验证。支付宝在当前用户空间可见，微信安装查询返回未安装；真实支付通知及两者版本覆盖均未验证，不能由安装状态推断支持。见 [通知验收记录](notification-capture-2026-10-13.md)。

- [miuix 0.9.4 发布说明](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.4)
- [miuix 0.9.4 版本目录](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/gradle/libs.versions.toml)
- [Android 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
