# 自动记账新增选项实现计划

> 使用 subagent-driven-development 分别实现独立后端、通知和数据任务；主任务负责确认 UI、运行时集成及最终验证。工作区共同编辑，按文件职责划分，不并发修改同一文件；统一提交与推送。

**目标：** 实现用户已确认的今日收支常驻通知、可选确认后入账和 Shizuku 自动采集，保留直接入账。

**架构：** 采集后端统一进入既有队列。数据库决定 DIRECT / CONFIRM；待确认内容先落库，确认窗口只处理候选 ID。普通汇总通知与 Live Updates 分离；Shizuku 使用 UserService 只读采集并复用 OCR、短时上下文及持久化去重。

**技术栈：** Kotlin、Room、Compose miuix、Android 通知 / WindowManager、Shizuku API / UserService。

## 任务 1：数据与确认状态

文件：`core/domain/.../CaptureModels.kt`、`CaptureRepository.kt`、`core/data/.../CaptureEntities.kt`、`CaptureDao.kt`、`RoomCaptureRepository.kt`、数据库迁移 / 备份编解码及相应测试。

- [x] 编写行为测试：DIRECT 默认继续入账；CONFIRM 保存候选但不改账本；重复回调合并候选；忽略后不再入账；确认并发仅一次，金额 / 分类 / 账户修改生效。
- [x] 新契约：`CaptureBookingMode { DIRECT, CONFIRM }`；CaptureSettings 增加 `bookingMode` 和 `dailySummaryEnabled`；PendingCandidate 增加可空原价 / 优惠。仓库增加 `setBookingMode(mode)`、`setDailySummaryEnabled(enabled)`、`candidate(id)` Flow。
- [x] Room 5→6 保留数据，候选增加结构化商户 / 账户 / 原价 / 优惠字段；同会话 PENDING 更新原候选，已忽略会话只保留去重，不再次弹窗。模式切换不提交已有候选。
- [x] 扩展备份到 6，兼容 3 / 4 / 5；恢复后暂停采集，校验新增配置和候选。
- [x] 测试样例：`setBookingMode(CONFIRM); capture(event, proposal); assertEquals(0, ledgerCount); confirm(id, edited.copy(occurredAt = now)); assertEquals(1, ledgerCount)`。忽略后再次 capture 必须保持零正式账单。

## 任务 2：常驻通知

文件：新增 `automation/.../summary/` 通知协调器，必要纯领域汇总 / 日期测试；主任务负责 Application 和 manifest 接入。

- [x] 从 LedgerRepository.summary 和 CaptureRepository.pendingCount 观察完整当日收入 / 支出，账本时区跨日重建订阅；响应时间变化和数据库修改。
- [x] `DailySummaryController(context, ledger, capture)`：`start()` 开始观察，`refresh()` 重算日期范围，`close()` 释放。配置 dailySummaryEnabled 控制独立通知；权限未授予不崩溃。通知 pending intent 打开主 Activity 的今日账本。
- [x] 汇总通知使用独立低打扰频道、ID、publicVersion 隐藏金额、ongoing 和 onlyAlertOnce。长时 Shizuku 前台运行另有必要通知，开关互不误取消。
- [x] 测试退款冲减、转账排除、跨日范围、开关关闭撤销以及 Live Updates ID 不冲突。

## 任务 3：Shizuku 只读后端

文件：新增 `automation/.../shizuku/`、AIDL、自动化模块 Gradle 依赖及能力规则测试；主任务负责 manifest / UI / runtime 接线。

- [x] 验证官方依赖和 UserService API；实现未安装、未启动、未授权、连接中、已连接、断开、能力不支持的真实状态。
- [x] `ShizukuCaptureController(context, onFrame)` 暴露 status StateFlow、requestPermission、connect、start(sources,generation)、stop、close；受控回调帧提供 source、activity、display、bitmap、generation。不得依赖轻账无障碍服务。
- [x] UserService 只执行固定的窗口 / 屏幕探测和截图，图像通过文件描述符有界传输，禁止任意命令参数和持久图像；前后窗口一致，锁屏 / 灭屏 / 非来源 / 多屏歧义拒绝。最多两秒一帧，Binder 断开停止。
- [x] 主任务将帧接到 LocalReceiptOcr，复用原会话 / 账户上下文，在 Shizuku 模式支付宝使用独立通知监听。
- [x] 验证断开 / 撤权 / 错窗口 / 大图 / 超时与资源释放；设备未连接时不声称真机后端可用。

## 任务 4：确认弹窗与设置

文件：新增 `app/.../captureconfirmation/`；修改 `CaptureViewModel`、自动记账与权限 / 记录页面、AppContainer / Application、Manifest 和运行时。

- [x] 按已确认提案实现 miuix 确认内容：标题后直接金额；信息卡首行为“入账类型”加紧凑支出 / 收入 / 退款分段；无来源提示、无日期 / 时间入口；底部等宽取消 / 确认入账。
- [x] 金额、分类、账户、商户 / 对方、原价 / 优惠和备注可改。确认时取 System.currentTimeMillis；所有用户关闭路径调用 ignore，并保留去重。异步失败不偷偷入账或丢弃未保存输入。
- [x] 只以候选 ID 展示队列；前台应用内显示，后台已授权悬浮时显示 Compose miuix overlay；锁屏或权限缺失发待确认通知，点击进入专用确认 Activity。使用 Lifecycle / SavedState owner 支持 ComposeView，不自行启用系统权限。
- [x] 自动记账页新增独立入账方式、今日收支通知与 Shizuku 状态；现有 DIRECT 不迁移为 CONFIRM。记录页增加待确认筛选及继续处理入口。
- [x] 检验同候选不会并行弹窗、编辑时采集更新不覆盖草稿、关闭保留忽略墓碑、点击两次确认只保存一笔。

## 任务 5：集成验证与交付

- [x] 将新选项同步 `docs/qingledger-ui-preview.html`，保持用户确认的提案；规格状态改为已确认，更新版本及进度文档。
- [x] 运行 `gradlew :core:domain:test :app:assembleDebug :core:data:assembleDebugAndroidTest :automation:assembleDebugAndroidTest`，确认退出码和报告。
- [ ] 此前真机安装、数据库 87 项与 Android 报告 42 项已执行（其中 2 项 Shizuku 未授权跳过），原生确认弹窗对齐已核对。最新源码已构建，设备已断开；最终重新安装、新增 2 项 Android 测试、Shizuku 授权采集与跨应用悬浮窗仍需补验，详见验收记录。
- [x] 两阶段审查：规格覆盖 / 兼容，再检查并发、资源释放、后台权限及数据安全。修复发现的问题，运行受影响验证。
- [x] `git diff --check` 后暂存全部改动，中文 Conventional Commit，直接推送 main；仅在验证成功范围内声明完成。
