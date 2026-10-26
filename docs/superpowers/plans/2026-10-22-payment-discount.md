# 付款优惠记录实现计划

目标：保留明确的原价和优惠，支出、余额、统计继续只使用实付。按已批准方案在当前工作区顺序执行，不调度代理。

- [x] 新增领域校验与 OCR 回归，先运行失败测试，再实现可空字段及保守识别。
  - 文件：`core/domain/.../model/LedgerModels.kt`、`PaymentDiscountRules.kt`、`OcrPaymentPageRules.kt`、`OcrPaymentContextCache.kt`。
  - 断言：原价 800、优惠 40、实付 760 合法；800 / 50 / 760 冲突；优惠零合法；非支出拒绝。
- [x] 贯通缓存、无障碍服务、通知队列与 Room，新增 4→5 可空列迁移。
  - 文件：`automation/.../LedgerAccessibilityService.kt`、`LocalReceiptOcr.kt`、`NotificationCaptureRuntime.kt`、`core/data/.../RoomLedgerRepository.kt`、`RoomCaptureRepository.kt`、`LedgerDatabase.kt`。
  - 运行：`gradlew.bat :core:domain:test :core:data:assembleDebugAndroidTest :automation:assembleDebugAndroidTest`。
- [x] 备份兼容 3 / 4 / 5，CSV v4 往返，旧 CSV 13 / 14 / 15 列补 null；新增独立数据库回归。
  - 文件：`BackupCodec.kt`、`RoomBackupRepository.kt`、`LedgerCsv.kt`、`CsvImportParser.kt`、`RoomCsvImportRepository.kt`、`RoomCsvExportRepository.kt`。
- [x] 按原设计扩展详情及可编辑优惠面板，同步 `docs/qingledger-ui-preview.html`。
  - 文件：`app/.../ledger/TransactionDetailScreen.kt`、`TransactionEditor.kt`。取消面板不改变草稿，取消编辑不写账。
- [x] 构建 Debug、安装现有设备，运行领域 / 独立数据库 / Android 测试，核对迁移后原字段及真机入口。
- [x] 更新版本与验收文档，`git diff --check`；暂存全部改动，中文 Conventional Commit 并直接推送。

2026-10-26 收尾：134 项领域、66 项数据库、20 项 Android 测试通过，0.7.33 已构建安装；迁移前后原八表既有字段指纹一致，未保存真机测试草稿。新一笔真实支付仍需用户正常付款时验证，未执行支付操作。见 [验收记录](../../payment-discount-2026-10-26.md)。
