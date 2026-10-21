# 统计页第二版实现计划

目标：按已确认 HTML 稿实现日 / 周 / 年 / 全部、纯图标图表切换及收支排行，使用真实数据。

架构：独立 StatisticsViewModel 持有时间筛选，Repository 按可空范围读取未删除账单；领域层负责时间范围、趋势分桶与分类排行。Compose 使用 miuix 卡片、分段按钮及 Canvas 图表，不改变数据库版本。

技术栈：Kotlin、Room Flow、Compose、miuix。

- [x] 领域测试先失败：自然周 / DST、跨年、六个日时段、月 / 年分桶、退款负净额、混合排行。
- [x] 实现 StatisticsOverview.kt 的时间范围、趋势与排行，补独立范围查询及真机数据库测试。
- [x] StatisticsViewModel.kt 独立观察范围、重新恢复；MainActivity / AppNavHost 注入。
- [x] StatisticsScreen.kt / StatisticsCharts.kt 按原稿实现，图标切换复用 miuix Button；排行可查看分类流水。
- [x] 构建、安装、领域与数据库测试，真机切换、空数据、详情返回、深浅色和数据指纹验证。
- [x] 更新设计确认状态与验收记录，暂存全部改动、中文 Conventional Commit 并推送。
