# 识别实时更新通知

此文记录 0.6.0 的初始实现与历史验收。0.6.1 已修复切换过快导致胶囊不可见的问题，完成结果短暂驻留 4 秒后再转普通通知，并已确认实际胶囊与系统提升标记，见 [胶囊显示修复](live-updates-capsule-fix-2026-10-15.md)。

日期：2026-10-15。目标版本：轻账 0.6.0（versionCode 10）。

## 行为

- 接收已授权来源的交易事件后，在后台处理队列中发送“正在识别交易”通知，使用不确定进度，不编造百分比。
- 同一通知 ID 更新识别结果：数据库实际保存后显示“已入账”，会话更新显示“已关联”，无法入账显示“已跳过”；异常显示“识别未完成”，不误报成功。
- Android 16 / API 36 及以上请求 Live Updates；低版本或未允许提升时使用标准通知。最终结果是可清除的普通通知，不将已结束活动持续占用为实时更新。
- 识别中最多保留 15 秒，结果通知 30 秒后自动移除。通知点击打开轻账。单通道静音、不振动，同 ID 更新，重复摘要先查持久化记录再处理；有界进程缓存进一步抑制重复提醒。
- 暂停、切换方式或来源 generation 失效时，旧队列不发送成功结果；通知发送失败不会中断账单保存。
- 不展示金额、银行卡、账户或页面原文；锁屏使用 PRIVATE 可见性。

## 授权与设置

在“自动”页新增“识别通知”：显示通知是否允许、系统是否允许提升为实时更新，提供普通通知授权 / 设置和实时更新设置入口。通知访问（读取其他应用通知）与 POST_NOTIFICATIONS（发送轻账自己的通知）是两种独立授权。

清单声明 POST_NOTIFICATIONS 和 POST_PROMOTED_NOTIFICATIONS。API 33 及以上通知授权由用户通过系统弹窗完成，不使用 ADB 自动授权。实时更新是否展示状态栏胶囊由系统及厂商决定；canPostPromotedNotifications 仅表示允许请求，不等于当前通知已提升。

## 接入位置

NotificationCaptureRuntime 的串行队列负责开始、完成和异常通知；RoomCaptureRepository.capturedState 按会话和摘要读取已提交状态，保持原 capture 布尔接口及账本行为。无障碍与旧通知调试通道共用此流程。Shizuku / Root 仍未接入实际采集后端。

使用系统 Notification.Builder、BigTextStyle、ongoing 和 android.requestPromotedOngoing extras，不引入远程服务、Google Play Services 或自定义 RemoteViews。当前 SDK 未暴露原生 setRequestPromotedOngoing setter，使用官方支持的 extras 请求方式；未通过反射调用隐藏 API。

## 验证

领域测试覆盖已提交状态文案、未知状态不误报成功、有界重复抑制以及财务字段不外露。Android 仪器测试覆盖识别通知 ongoing / 提升请求 / 超时 / 可提升特征，以及完成通知可清除、不继续请求提升和锁屏可见性。

- `:core:domain:test` 通过，包含新增通知文案和重复抑制测试；`:app:assembleDebug` 构建通过。
- 通知模板测试 APK 安装成功，真机 `am instrument` 返回 `OK (2 tests)`；验证平台 `hasPromotableCharacteristics()` 为真。
- 轻账 0.6.0 安装成功、启动正常。用户通过系统设置允许通知；读取到 POST_NOTIFICATIONS 与 POST_PROMOTED_NOTIFICATIONS 均已授予，自动页显示实时更新允许状态。
- 仅启用支付测试器，成功页触发实际数据库保存和通知 4101；同页刷新产生 MERGED，通知变为“识别完成 · 已关联”，账单总数保持 11，不重复入账。完成通知系统记录为 ONLY_ALERT_ONCE / AUTO_CANCEL、PRIVATE、PT30S，通道无声音与振动。
- 多金额页面未进入既有无障碍采集队列，未新增账单。此次未更改该识别规则；跳过 / 失败结果文案已通过领域测试，未伪造设备异常。
- 识别阶段很短，本次没有捕获到实际状态栏胶囊截图，也未以 FLAG_PROMOTED_ONGOING 证明 HyperOS 实际提升；模板可提升和权限允许不等于厂商最终展示已验收。低版本系统尚未做实机回归。
- 测试完成后暂停采集、关闭测试器来源，真实支付来源保持关闭；保留一笔 18.88 元模拟支出供查看。

参考：[Android 官方 Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)。
