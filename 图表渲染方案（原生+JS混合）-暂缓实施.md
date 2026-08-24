# 图表渲染方案（原生 + JS 混合）—— 暂缓实施

> 记录时间：2026-08-25
> 状态：**方案已定，暂不实施**。待聊天界面图表需求实际出现时按本文档执行。
> 关联文档：`Markdown渲染.md`（Markwon 生态清单）、`插件化框架能力盘点与缺口分析.md`

---

## 一、背景

聊天界面 Markdown 渲染已于 2026-08-24 从 `com.mikepenz:multiplatform-markdown-renderer`
整体替换为 **Markwon 4.6.2 生态**（GFM 表格/删除线、LaTeX、Prism4j 代码高亮、
linkify、HTML、图片），封装为 `feature-session/.../ui/MarkdownText.kt`
（`AndroidView` + `TextView`）。

Markwon 无法覆盖的唯一内容形态是**图表**（流程图/时序图/数据图表）。本文档记录
"原生 + JS 混合渲染"的预留方案：Markwon 继续渲染全部文本，**WebView 只渲染图表块**。

## 二、方案要点（结论先行）

| 项 | 结论 |
|---|---|
| 渲染主体 | Markwon 不变，一行不用改 |
| 图表渲染 | WebView + 本地 JS 图表库（assets 离线加载） |
| **marked.js 不引入** | 它只是 Markdown→HTML 解析器，与 Markwon 职能重复；画图靠的是 mermaid.js / ECharts |
| 首选图表库 | **mermaid.js**（AI 回复事实标准，` ```mermaid ` 围栏代码块，约 1.5~2MB） |
| 次选图表库 | ECharts（数据图表，约定 ` ```echarts ` 围栏放 JSON option，约 1MB） |
| 实施时机 | 暂缓。等模型输出中图表需求真实出现再做 |

## 三、架构：拆块路由（核心设计）

**关键约束**：WebView 不能嵌进 TextView（Markwon 输出是 Spanned，span 里放不下
View）。因此图表块必须在 Markdown 进入 Markwon **之前**拆出：

```
模型回复（含 ```mermaid 围栏代码块）
   │
   ├─ 预拆分：按围栏代码块把文本切成若干段（几十行 Kotlin，围栏正则）
   │     普通段          ──→ MarkdownText（Markwon，现状）
   │     mermaid/echarts 段 ─→ ChartView（WebView + 对应 JS 库）
   │
   └─ 消息内按段顺序纵向拼接（Compose Column）
```

该设计规避了 WebView 嵌入聊天列表的全部已知坑：

| WebView + LazyColumn 经典坑 | 拆块后为何不存在 |
|---|---|
| 每行一个 WebView，内存爆炸 | WebView 数量 = 图表数量（通常一条消息 0~1 个） |
| 高度测量抖动、滚动冲突 | 图表块**定高**（如 300dp，内部缩放/拖动），不撑高消息行 |
| 流式输出每 delta 重载 | 流式期间围栏未闭合时按普通代码块渲染（Markwon 高亮），**闭合后**才切换为图表，一次渲染 |
| 不可信内容开 JS 的安全面 | 只加载本地 assets 的 JS 库；mermaid/ECharts 输入是纯文本 DSL，无网络请求 |
| 破坏 followTail 跟随滚动 | 图表块定高，行高在渲染前就确定 |

## 四、实施清单（备用）

实施时按此清单执行，预计 1~2 个工作日：

1. **资产**：`mermaid.min.js`（+ 可选 `echarts.min.js`）放入 `feature-session/src/main/assets/charts/`；
   配套一个模板 HTML（含 mermaid 初始化、主题变量注入点、错误回显）
2. **拆分器**：`MarkdownSegmenter.kt`——把回复文本按围栏代码块切成
   `sealed class Segment { Text, Chart(type, source) }`；识别 `mermaid`/`echarts` 语言标记
3. **ChartView**：WebView 封装 composable，定高、禁用外链、`javascriptEnabled` 仅限本地、
   通过 `evaluateJavascript` 注入图表源码渲染；渲染失败回退为代码块展示
4. **接线**：`ChatScreen` 的 `MessageBlock`/`StreamingAssistantBlock` 里先跑拆分器，
   单段纯文本走现有 `MarkdownText` 快路径（零回归），含图表段才走 Column 拼接
5. **流式契约**：`state.streamingText` 中围栏未闭合的图表块按代码段渲染；
   闭合的下一帧切换为 ChartView
6. **主题**：mermaid 初始化配置跟随 MaterialTheme 深浅色（注入 `themeVariables`）

## 五、明确不做的

- ❌ 不引入 marked.js（与 Markwon 职能重复）
- ❌ 不做整消息的 WebView 渲染（流式性能、滚动、安全面全面劣化，见 2026-08-25 分析）
- ❌ 不改 Markwon 现有集成与 `MarkdownText.kt`
- ❌ 不做图表编辑/交互导出（首期只读渲染；导出 PNG/SVG 后续再议）

## 六、触发条件（何时启动实施）

满足任一即可启动：

1. 模型输出中出现 mermaid 代码块且用户反馈"显示为代码而不是图"
2. UI-FEATURES.md 会话详情页面板族（4.x）开发到需要图表可视化的项
3. 数据图表类插件（如统计/报表）立项
