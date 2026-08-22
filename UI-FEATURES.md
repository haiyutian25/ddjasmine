# Jasmine 内置 UI 功能清单

> 独立文档：Jasmine（Android）需要**做成内置硬功能**的全部 UI 面。
> 来源：主仓 base bundle 78 行插件、web-app 层 30+ 个 `ui-*` 客户端插件、
> 外部生态 1837 条插件分类、Android 平台特有需求。
> 原则：**需要 UI 的功能直接内置**（不做运行时 UI 插件）；纯逻辑功能后台运行不给 UI。
> 配套：`PLUGIN-INVENTORY.md`（主仓插件盘点）、`GAP-ANALYSIS.md`（里程碑）。

## 1. 应用骨架与导航（Android 特有 + 对标 ui-layout/sidebar/workspace）

| # | 功能 | 对标 | Android 形态 | 优先级 |
|---|---|---|---|---|
| 1.1 | 主导航骨架 | ui-layout / ui-sidebar | 底部导航（会话 / 插件 / 设置）+ 顶栏 | P0 |
| 1.2 | 会话列表侧栏语义 | ui-sidebar | 会话列表页（已有雏形，补标题/搜索/归档） | P0 |
| 1.3 | 工作区/项目选择 | ui-workspace | 工作目录选择（SAF 授权）与显示 | P1 |
| 1.4 | 应用级深色/主题切换 | ui-theme | 设置项（茉莉色板默认） | P1 |
| 1.5 | 首次启动引导 | 无（生态 on-boarding 类） | 权限说明 + API Key 引导 | P1 |

## 2. 设置页（对标 ui-settings 壳 + 各 settings 命名空间）

| # | 功能 | 对标 | UI 内容 | 优先级 |
|---|---|---|---|---|
| 2.1 | 模型管理 | ui-settings-models / llm-deepseek / llm-pi-ai / agent-default-model | API Key（Keystore）、baseURL、模型列表增删、默认模型、推理强度 | P0 |
| 2.2 | 凭证管理 | credentials | 托管凭证查看/编辑 | P0 |
| 2.3 | MCP 服务器管理 | dsh-mcp-client（无对标，全新建） | 增删改服务器（名称/URL/headers/超时/重连）、连接状态、`mcp__*` 工具清单 | P0 |
| 2.4 | 权限预设 | ui-permission-presets / permission | 三档选择 + 各档说明 | P0 |
| 2.5 | 通用设置 | ui-settings-general / settings | 结构化表单；各插件 settings 命名空间卡片 | P1 |
| 2.6 | Agent 预设 | ui-agent-preset / system-prompt / agent-loop | persona 文案、agents 配置、系统提示段查看 | P1 |
| 2.7 | 遥测开关 | session-telemetry-otel | 开/关 + OTLP 地址 | P3 |
| 2.8 | 通知设置 | 生态 notify 类 | 通知渠道、后台完成提醒开关 | P2 |

## 3. 会话列表页

| # | 功能 | 对标 | UI 内容 | 优先级 |
|---|---|---|---|---|
| 3.1 | 会话标题 | session-title(+llm) | 标题显示（启发式/LLM）、重命名 | P1 |
| 3.2 | 会话搜索/归档 | 生态 session 类 | 搜索、归档、批量删除 | P2 |
| 3.3 | 附件总览 | ui-attachment / attachment-local | 附件列表、大小、清理 | P3 |

## 4. 会话详情页（时间线与会话内面板）

| # | 功能 | 对标 | UI 内容 | 优先级 |
|---|---|---|---|---|
| 4.1 | 流式回复气泡 | llm-deepseek 流式 + ui-conversation | 逐字流式渲染（M3） | P0 |
| 4.2 | 工具调用卡片 | ui-tool / ui-renderer | tool/call、tool/result 事件卡片（参数/结果/耗时/状态） | P0 |
| 4.3 | 工具结果裁剪折叠 | tool-result-pruner | "已裁剪"折叠卡片可展开 | P1 |
| 4.4 | 文件引用跳转 | ui-reference / tool-fs | 回复内文件路径可点击/预览 | P2 |
| 4.5 | 交付物面板 | ui-deliverables | 会话产出文件列表 | P3 |
| 4.6 | 轨迹视图 | ui-trajectory | 每个回合的思考/工具轨迹时间线 | P2 |
| 4.7 | 消息反馈 | ui-message-feedback | 👍/👎 反馈、重发 | P2 |
| 4.8 | 用户提问对话框 | ui-user-questions / user-questions | ask_user 输入对话框 | P0 |
| 4.9 | 计划模式面板 | ui-plan / plan-mode | active/pending 状态、进入/退出 | P1 |
| 4.10 | Goal 面板 | ui-goal / goal / tool-goal | 当前 goal、历史、编辑 | P2 |
| 4.11 | Todo 面板 | tool-todo | 任务列表、勾选状态 | P1 |
| 4.12 | 后台作业面板 | ui-jobs / jobs / tool-jobs | 作业列表、状态、取消、重试 | P2 |
| 4.13 | Subagent 面板 | ui-subagent / subagent 全套 | 子代理花名册、任务状态 | P3 |
| 4.14 | Workflow 运行视图 | ui-workflow-run / tool-workflow | 步骤生命周期可视化 | P3 |
| 4.15 | token/上下文指示 | token-meter | 上下文用量条、token 统计 | P1 |
| 4.16 | 压缩状态与手动压缩 | compaction-basic / command-compact | 压缩中提示、/compact 按钮 | P2 |
| 4.17 | 重复调用提醒 | repeat-tool-reminder | 连续重复调用横幅 | P2 |
| 4.18 | 命令触发面 | ui-input-trigger / commands | 斜杠命令面板（/plan /goal /permission /model /export…） | P1 |
| 4.19 | 输入框扩展位 | ui-commands 周边 | 附件、语音、发送选项 | P2 |

## 5. 能力管理页（插件/技能/指令）

| # | 功能 | 对标 | UI 内容 | 优先级 |
|---|---|---|---|---|
| 5.1 | 插件清单/启停 | ui-settings-plugin-inventory | ✅ 已有雏形；补详情跳转 | P0 |
| 5.2 | 插件配置卡片 | ui-settings-plugins | 每插件 config 按 schema 渲染表单 | P1 |
| 5.3 | 插件安装（配置包） | dsh plugin add / 市场 | 导入 YAML 包、URL 下载、校验告警 | P0 |
| 5.4 | Skill 管理 | ui-skill / skill / skill-filesystem / tool-skill | skill 列表、详情、启停、导入 | P1 |
| 5.5 | AGENTS.md 指令 | agent-instructions | 查看/编辑项目指令 | P2 |
| 5.6 | 用户命令目录 | commands 用户目录 | 自定义斜杠命令管理 | P3 |

## 6. 运行时交互（对话框/横幅/通知）

| # | 功能 | 对标 | UI 内容 | 优先级 |
|---|---|---|---|---|
| 6.1 | 工具执行审批 | approval | 执行前确认（允许一次/总是/拒绝）+ 参数预览 + 审计记录查看 | P0 |
| 6.2 | 超时/重试提示 | timeout-policy / llm-retry | toast/横幅 | P2 |
| 6.3 | 后台完成通知 | 生态 notify 类 | Android 通知（渠道+权限） | P2 |
| 6.4 | 前台服务状态 | 无（Android 特有） | 长任务前台服务通知 | P3 |

## 7. Android 平台特有（主仓无对标）

| # | 功能 | UI 内容 | 优先级 |
|---|---|---|---|
| 7.1 | 权限请求页 | 存储(SAF)/通知/麦克风权限引导与说明 | P0 |
| 7.2 | 工作目录授权 | SAF 目录选择、授权状态显示 | P0 |
| 7.3 | 语音输入 | Android 语音识别接入输入框 | P3 |
| 7.4 | 分享/深链 | 接收系统分享进会话 | P3 |
| 7.5 | 存储占用管理 | 会话/附件/插件占用统计与清理 | P2 |

## 8. 明确无 UI（对照表，防漏判）

纯逻辑后台运行：llm-retry、timeout-policy 执行面、spill(+policy)、fs-observation-policy、
session-checkpoint-policy、compaction 算法面、pruner 算法面（UI 见 4.3）、token-meter 计算面（UI 见 4.15）、
session-projection/stats/cache、web-search-deepseek 数据面、attachment 存储面、subprocess 执行面、
tool-* 执行本体、hmr（不适用）、typert（不适用）、沙箱策略执行面。

## 9. 统计与里程碑建议

- **需内置 UI 功能合计 39 项**：骨架 5 + 设置 8 + 会话列表 3 + 会话详情 19（含流式）+ 能力管理 6 + 运行时 4 + 平台特有 5（跨节编号去重后）。
- **P0（先行）**：1.1/1.2 导航骨架、2.1 模型、2.2 凭证、2.3 MCP、2.4 权限预设、4.1 流式、4.2 工具卡片、4.8 提问、5.1/5.3 插件管理、6.1 审批、7.1/7.2 权限与目录。
- 建议顺序：**设置页骨架 + 模型/凭证 → MCP 管理 → 权限预设 + 审批 → 工具卡片 + 流式 → 会话内面板族 → 其余 P2/P3**。
