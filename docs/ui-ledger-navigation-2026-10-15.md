# 底栏与账本样式落地

日期：2026-10-15。当前版本：0.7.1（versionCode 13）。基于已确认的薄荷绿设计稿，本阶段落地底部导航和账本首页。

## 0.7.1 悬浮布局修正

- 0.7.0 错误地把操作栏放在 Scaffold.bottomBar，并给列表应用完整底部 inset，列表可见区域被截短。原验收只检查末行能否露出，遗漏了列表必须从控件背后滚过这一要求。
- 改为内容 Box 内的底部叠层；列表占满可用区域，操作栏周围无整条背景。末尾 132 dp contentPadding 随内容滚动，最后一笔可以完整滚到控件上方。
- 导航保留 miuix FloatingNavigationBar 默认 surfaceContainer；圆形按钮继续使用 miuix FloatingActionButton，调整为与导航同高的 52 dp、同底色、薄荷绿加号。
- miuix 0.9.4 导航使用半径 10 dp、透明度 0.2 的柔和 dropShadow，FAB 默认是 elevation 阴影。按钮关闭默认 elevation，使用相同 dropShadow 参数，避免两套阴影并排不协调。
- Debug 构建通过并覆盖安装；真机截图确认列表从控件背后延伸、末行完整露出、圆形按钮打开新增页、二级页隐藏底栏、统计切换及返回。当前进程无 AndroidRuntime 错误。截图保存在 debug-artifacts/ui-071-*.png。
- 本轮为布局修正，未重跑领域测试；没有新增或修改账单。

## 已完成

- 底栏仅保留账本、统计、我的；使用 miuix FloatingNavigationBar / FloatingNavigationBarItem，不自行绘制导航交互。
- 账本旁的独立记账按钮使用 miuix FloatingActionButton，CircleShape、52 dp，和导航保持 12 dp 间距；只在账本首页出现，二级页隐藏底栏。
- miuix 0.9.4 的导航有外层占满宽度和内部底部留白，使用受限容器及相同基线留白适配，避免按钮被挤出或上下错位。
- 全局 miuix 主色改为薄荷绿；浅深色的支出、收入、强调底和强调文字使用对应语义颜色。
- 账本标题、月份箭头、本月概览、单一搜索框、筛选按钮和按天分组流水。每行优先展示标题、金额、分类、账户、时间与来源；完整备注保留在详情页。
- miuix Card 组合分组圆角，每笔记录仍是独立 LazyColumn 项，避免一个整日卡片一次渲染大量记录。长账户名称允许两行；商户名称可省略，详情页保留全文。
- 搜索采用 miuix TextField、筛选采用 IconButton / OverlayBottomSheet / Button，支持类型、分类、账户选择及重置。没有重复的标题栏搜索入口。
- 修复连续快速输入时数据库流回写覆盖输入的问题：先更新本地输入，再等待相同查询返回，仍能接收外部查询重置。
- 保持真实月度汇总；筛选影响流水，不改变整月概览。日期标题展示当前分组笔数，未将筛选 / 分页后的局部金额当成全天合计。
- 自动记账入口移动为“我的 → 设置 → 自动记账与识别通知”。我的页只增加基础管理与设置入口，完整视觉重整留待后续。

## 组件复用

| 功能 | 使用的 miuix 组件 |
| --- | --- |
| 页面与弹层宿主 | Scaffold |
| 悬浮三项导航 | FloatingNavigationBar、FloatingNavigationBarItem |
| 独立圆形记账按钮 | FloatingActionButton |
| 概览与分组流水 | Card |
| 月份与筛选操作 | IconButton、Icon |
| 搜索 | TextField |
| 筛选面板 | OverlayBottomSheet、Button |
| 字体与主题 | Text、MiuixTheme |

Compose 布局、圆角裁切和间距仅用于页面组织，不替代组件本身的点击、按压或弹层行为。未升级 miuix 依赖，也未引入 Material 组件替代上述组件。

## 验收

- 领域回归测试通过；Debug 构建成功并安装到连接设备，冷启动正常。
- 真机检查浅色 / 深色、三项导航、独立圆形按钮、详情入口、二级页隐藏底栏、记账入口及取消返回。
- 收入筛选后只显示收入流水，整月概览不变；底部筛选弹层可通过系统返回关闭。
- 快速输入 NO_MATCH_070 后内容完整保留，显示无匹配账单；清空输入后恢复列表。
- 上月空账本显示零汇总及空态，回到本月恢复原记录。
- 列表滚动到最后一笔，流水完整显示在底栏上方，不被悬浮操作遮挡。
- 验证“我的 → 设置 → 自动记账”的入口及逐级系统返回。测试未改动账单内容，未开启采集来源或替用户授权。
- 恢复跟随系统主题，并停留本月账本顶部；截图保存在被 Git 忽略的 debug-artifacts/ui-070-*.png。

未完成项：其他页面完整视觉重整、图表与日历业务、分类专属图标体系、200% 字体和读屏全流程验收、其他尺寸实机回归。升级后手机的无障碍授权显示需重新开启，本阶段没有自动授权；采集与来源保持关闭。

参考：[miuix NavigationBar](https://compose-miuix-ui.github.io/miuix/zh_CN/components/navigationbar)、[FloatingActionButton](https://compose-miuix-ui.github.io/miuix/zh_CN/components/floatingactionbutton)、[OverlayBottomSheet](https://compose-miuix-ui.github.io/miuix/zh_CN/components/overlaybottomsheet)。
