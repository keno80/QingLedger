# 手动记账闭环验收记录

日期：2026-10-13。范围：T02 / T03 的手动账单业务与基础月度汇总。应用 0.2.0，versionCode 2。

## 实现

domain 模块新增整数金额转换、交易校验、按账本时区的月份边界及收支规则。Room 新增 books、categories、accounts、transactions、transaction_edits 五张表，导出 v1 schema；数据库升级禁止破坏性回退。

初次启动建立默认账本、预置分类和账户，不插入演示交易。保存采用事务校验分类 / 账户关系，旧版本编辑返回冲突。删除使用 deletedAt，10 秒撤销恢复账单与统计；操作记录保留用于后续历史页面。

界面支持新增、编辑、详情、日期与备注、支出 / 收入 / 转账、按天列表、月份切换、商户 / 备注搜索、类型 / 分类 / 账户筛选以及分批加载。统计汇总整个月份的正式账单，避免列表分页或筛选导致月度总额截断。主题增加浅灰底色与分组卡片层次。

## 自动化证据

1. `:core:domain:test`：先以规则接口最小桩运行，8 项测试中 7 项预期失败；实现后 8 项全部通过。最后一次领域测试与 Debug 构建共同执行，结果 BUILD SUCCESSFUL。
2. `:app:assembleDebug`：成功生成 app-debug.apk，最终构建成功，仍有既有 Gradle / Compose 弃用提示。
3. `:core:data:connectedDebugAndroidTest`：首次因测试 APK 安装被系统拒绝，未运行测试；该命令虽返回 BUILD SUCCESSFUL，不据此认定测试通过。
4. 之后正常执行 `adb install -r -t data-debug-androidTest.apk` 返回 Success，再执行 `am instrument -w io.qingledger.data.test/androidx.test.runner.AndroidJUnitRunner`，明确返回 `OK (6 tests)`，耗时 0.641 秒。

8 项领域测试：精确金额、非法金额、上限、上海月边界、夏令时月边界、同账户转账拒绝、收支字段校验、转账排除统计。

6 项数据库测试：删除 / 撤销与汇总、旧版本编辑拒绝、无效引用回滚、转账统计、归档引用与重启初始化不覆盖、文件数据库关闭后重开。测试使用独立测试 APK、内存数据库或独立测试数据库，不清空主应用账本。

## 真机 UI 检查

设备 24053PY09C / chenfeng，Android 17 / API 37，系统 OS4.0.0.15.XPACNXM。

- 更新主应用后成功启动，最终启动一次观测为 856 ms。
- 保存 QL_DEBUG_MANUAL 支出 12.34 元，分类餐饮、账户现金；强制停止后重启，列表与金额仍在，支出 12.34、收入 0、结余 -12.34。
- 编辑为 15.50 元，列表与汇总同步变化；删除并撤销后，金额与账单恢复。
- 保存 QL_DEBUG_INCOME 收入 100 元，分类工资；重启后收入 100、净支出 15.50、结余 84.50。
- 保存 QL_DEBUG_TRANSFER 转账 30 元，从现金转入银行卡；汇总仍为收入 100、支出 15.50、结余 84.50。
- 检查统计页显示当前月完整汇总。
- 仅删除以上三笔调试账单，列表为空，收入 / 支出 / 结余归零。删除采用业务软删除，不清空用户数据库。
- 最终版本输入 5.00 元但未保存，系统返回取消后重新新增，金额为空，未产生账单。
- 当前进程 AndroidRuntime:E 无输出，未观察到致命异常；应用留在账单首页。

一次撤销后立即检查的 UI 自动化读取遇到重绘时序，后续读取和回到列表顶部确认金额为 15.50；未将单次快照失败误判为数据丢失。

截图位于被 .gitignore 排除的 debug-artifacts：manual-home.png、manual-saved.png、manual-edited-restored.png、manual-statistics.png、manual-complete.png、manual-final.png。

## 当前限制

分类 / 账户管理页面、修改历史详情、退款、年度 / 分类图表、自动通知识别、Root / Shizuku 增强、导入备份仍未交付。归档引用机制已有测试，但尚无用户管理入口。200% 字体、读屏、完整旋转恢复和其他机型未验收。

APK 位于 app/build/outputs/apk/debug/app-debug.apk，已安装到当前连接真机。没有提交或推送代码。
