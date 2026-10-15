# 实时更新胶囊未显示修复

日期：2026-10-15。版本：0.6.1（versionCode 11）。

## 原因与证据

0.6.0 的识别通知在数据库处理后立即替换为普通结果通知，处理很快，无法保证 SystemUI 来得及显示胶囊。此前的 hasPromotableCharacteristics 测试只证明模板符合提升条件，不能证明用户已经看见胶囊。

本次增加 Debug 自检：保留进度 8 秒，不读取支付页面、不生成账单。真机系统记录包含 ONGOING_EVENT、ONLY_ALERT_ONCE、PROMOTED_ONGOING，截图实际显示状态栏胶囊。因此设备支持提升；本次复现的主要原因是通知状态切换过快。

## 修复

- 识别中的通知继续请求提升；保存成功后立即显示真实“已入账 / 已关联 / 已跳过 / 未完成”结果，绝不人为延迟数据库保存。
- API 36 及以上且系统允许提升时，结果作为短暂过渡胶囊保留 4 秒，再转为普通可清除通知。过渡通知另设 5 秒系统超时兜底；普通结果设置 30 秒超时。
- 定时回调通过版本标记和锁避免覆盖后续交易；新识别、取消会撤销旧回调。若用户已经清除通知，不重新发出普通结果。
- 不支持或未允许提升的系统直接使用普通结果。结果只显示状态，不显示金额、账户和页面原文。
- 官方建议活动结束后使用普通通知；这里的结果胶囊仅用于短暂可见反馈，不作为常驻状态，4 秒后回归普通通知。
- Debug 的 LiveUpdateProbeReceiver 受 android.permission.DUMP 保护，仅供 shell 调试；独立使用通知 ID 4102，不干扰实际识别的 4101。Release 清单不声明此接收器。

## 验收

1. Debug 构建成功、0.6.1 安装成功，启动正常。用户重新开启无障碍后，仅使用支付测试器来源验证。
2. 真机实际交易识别后，系统记录标题“识别完成 · 已入账”、PROMOTED_ONGOING、PT5S，截图实际显示“已入账”胶囊。
3. 同笔刷新显示“已关联”胶囊，系统同样记录 PROMOTED_ONGOING，未增加该笔账单。
4. 过渡后实际记录变为 ONLY_ALERT_ONCE / AUTO_CANCEL、PT30S，不再包含 PROMOTED_ONGOING。
5. 三项 Android 通知模板测试通过；领域测试通过。尚未在低版本 / 其他厂商手机复测。
6. 测试结束已暂停采集、关闭模拟来源，真实支付来源保持关闭。本轮保留两笔 18.88 元模拟支出；活动账单数从 11 增至 13，刷新未额外生成账单。

截图仅保存在 Git 忽略的 debug-artifacts 目录：live-booked-capsule.png、live-result-capsule.png、live-probe-screen.png；不上传设备截图。

参考：[Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)。
