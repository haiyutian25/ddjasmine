# 主仓插件生态盘点（Jasmine 移植基准）

> 依据 2026-08-22 对 `D:\deepseek-harness-master` 的逐行调研：
> `packages/bundle/base/cordis.patch.yml`（78 行）+ `packages/bundle/{headless,web-app}/cordis.patch.yml`
> + `packages/**` 实现 + `examples/mcp-memory`。
> 配套：`GAP-ANALYSIS.md`（里程碑差距）、`RUST-SINKING-ROADMAP.md`（Rust 侧）、`ANDROID-PLAN.md`。

## 1. 补丁语义（移植必须保持的规则）

- `- insert:` 数组追加插件行；后续 layer 按 `id` 定位整行，**config 整体替换**（浅覆盖不合并）；
- `disabled` 关行；**行顺序无加载语义**（激活由服务可用性驱动）；
- Jasmine 的 Rust `compose` crate 已对齐该语义；`PatchOverlay` 实现 home 覆盖层开关。

## 2. base bundle 全部 78 行（id · config 要点 · disabled）

### 基础设施
1. `timer` — 定时服务（timeout/interval/throttle/debounce）
2. `hmr` — Node 模块热替换；headless/web 层 disabled
3. `typert` — 类型化 RPC schema 注册表
4. `typert-loader` — schema 从包加载
5. `typert-gateway` — 跨进程 RPC 网关

### LLM 域
6. `llm` — LLM seam（Service Definition）
7. `llm-deepseek` — 每请求解析 settings `llm-deepseek:` 节 + credentials 引用
8. `llm-pi-ai` — 挂载即休眠，settings 有配置才注册路由
9. `llm-retry` — 重试策略
10. `agent-default-model` — `provider: deepseek-official, model: deepseek-v4-flash`

### 会话域
11. `session` — 会话领域核心（事件/append/投影）
12. `session-persistence-jsonl` — `root: !!js dshHomePath('sessions')`
13. `session-query-sqlite` — `path: ':memory:', openAt: never`
14. `session-projection` — 共享投影注册表
15. `session-telemetry-otel` — OTLP 遥测，默认 DISABLED
16. `session-checkpoint-policy` — 模型请求/顶层分发前落检查点
17. `session-title` — 启发式标题（fallbackMaxWords 5 / bytes 40 / 80）
18. `session-title-llm` — 首条 prompt LLM 起标题（targetWords 5）

### 存储/凭证/附件
19. `settings` — `$DSH_HOME/settings.yaml`，watch + 热加载 + 跨进程锁
20. `credentials` — 分层凭证（继承环境 > 托管文件 > 项目 .env > 用户 .env）
21. `attachment-local` — 附件字节库（内容寻址，日志只存引用）

### 平台/provider
22. `subprocess` — node-pty 子进程 seam 本地实现
23. `sandbox` — 文件效应边界 seam（bwrap/Landlock/ACL）
24. `sandbox-policy` — `mode: workspace-write`（DSH_PERMISSION_MODE）
25. `bash-sandbox` — disabled: win32，timeoutMs 60000
26. `pwsh-sandbox` — disabled: 非 win32
27. `shell-env` — shell 环境变量发布
28. `jobs` — 后台作业注册表（按 owning agent 键控）
29. `spill-local` — 大内容外溢磁盘
30. `fs-sandbox` — 沙箱化 fs provider
31. `web` — web capability seam，searchProvider: deepseek-official
32. `web-search-deepseek` — 搜索 provider，apiKeyEnv: DEEPSEEK_API_KEY

### 策略/守卫
33. `approval` — 审批 seam（waterfall answerer 链、fail-closed、审计）
34. `permission` — 三档预设 read-only / workspace-write / danger-full-access
35. `timeout-policy` — 工具调用超时
36. `repeat-tool-reminder` — thresholds [3,5,8]
37. `fs-observation-policy` — 文件观测策略
38. `spill-policy` — maxInlineBytes 50000
39. `compaction-basic` — 自动压缩后端
40. `tool-result-pruner` — 8192/4096/1024（Rust measure 已镜像）
41. `token-meter` — 上下文计价/压力投影

### 核心 agent 面
42. `agent` — Agent 核心类型
43. `agent-loop` — `agents: []`；turn/step 状态机（固定核心）
44. `tools` — 工具注册表 + 执行管线（pre/guard/around/post/result）
45. `system-prompt` — `persona: ''` 提示段合成

### 工具类（tool-*）
46. `tool-bash` — bash 执行工具（disabled: win32）
47. `tool-pwsh` — PowerShell（disabled: 非 win32）
48. `tool-jobs` — job_* 控制工具
49. `tool-fs` — 文件读写工具（fs seam + 沙箱）
50. `tool-fs-search` — glob/grep（sampleOverCapGlobResults: false）
51. `tool-str-replace-editor` — maxOutputChars 16000
52. `tool-web` — fetch: false, searchTimeoutMs 60000（防 SSRF）
53. `tool-todo` — allowParallelInProgress: true
54. `tool-goal` — 会话持久 goal
55. `tool-ralph` — subagentProvider: spawn, maxRounds: 64
56. `tool-workflow` — 持久步骤生命周期
57. `tool-skill` — skill 目录/加载

### Skill 域
58. `skill` — skill 注册表 seam（host+per-scope 分层）
59. `skill-filesystem` — 文件系统发现
60. `skill-badge` — disabled: true

### 命令/交互
61. `commands` — 人类斜杠命令注册表（command/run+done 日志事件）
62. `command-feedback` — 命令结果反馈
63. `command-compact` — /compact
64. `command-goal` — /goal
65. `goal` — goal 领域服务
66. `goal-round-driver` — goal 轮次驱动
67. `user-questions` — 向人类提问 seam（UI provider 挂起等待）
68. `plan-mode` — section 长提示词 + /plan + exit_plan_mode
69. `agent-instructions` — AGENTS.md 类指令（maxBytes 65536）

### Subagent 域
70. `subagent` — 子代理注册表单例
71. `subagent-spawn-in-process` — providerName: spawn
72. `subagent-fork-in-process` — providerName: fork
73. `tool-subagent` — spawn/continuable 委托工具
74. `tool-subagent-fork` — fork/one-shot 委托工具
75. `tool-subagent-control` — 后台子代理控制
76. `tool-subagent-list-agents` — list_agents 工具
77. `tool-subagent-report` — report 返回通道
78. `workflow-worker-thread` — provider: spawn

## 3. MCP 专查结论

- 唯一实现 `dsh-mcp-client`（`packages/mcp/mcp-client`），依赖 `@modelcontextprotocol/sdk`；
- **纯 client 桥接器**：一个插件实例连一个 server，discover 工具注册进 `ctx.tools`，
  命名 `mcp__<serverName>__<rawName>`（64 字符规范化 + 冲突加 12 位哈希）；
- 配置：`transport: stdio|streamable-http`、serverName(1-32)、
  stdio: command/args/env/cwd；http: url/headers；toolCallTimeoutMs 60000、
  failOnStartupError false、reconnect 参数组；崩溃后不自动重连；
- **不进任何出厂 bundle**，纯用户 patch 行接入；示例 `examples/mcp-memory/` 三个
  stdio 记忆服务（memorix / mcp-reference-memory / engram），全部默认关闭；
- **UI：无**（连 Web 版都没有管理面，手写 YAML）；
- Jasmine：stdio 不可用（Android 不能 spawn 任意二进制），Streamable HTTP 可移植；
  **MCP 服务器管理页需全新建**（无对标）。

## 4. 主仓 UI/设置面板机制

- CLI 无 UI；Web 有完整 roster（host 层 + 30+ ui-* 客户端插件）。
- 设置数据面：`settings-file` 热加载 YAML，插件经 `settingsNamespace()` 注册，
  现存 7 个命名空间：llm-deepseek / llm-pi-ai / agent-default-model /
  agent-loop / permission / shell / web-search-deepseek。
- 设置 UI 面：ui-settings 壳 + general + **models**（写 settings 节+credentials）+
  **plugin-inventory**（插件清单）+ **plugins**（把各插件配置节渲染成可编辑卡片）+
  permission-presets + agent-preset。
- 运行期交互：user-questions（ask_user 对话框）、approval（工具调用前审批）。
- 会话内控制：/compact /goal /permission /plan /model /export 等命令。
- web-app 层把大量 tool-* 行 disabled 并改由 agent-presets 按会话挂载——
  "宿主面 vs 会话面"两层归属模型，Jasmine 单会话形态暂不做，边界保留。

## 5. Jasmine 移植映射总表

- **A. 内置固定**：会话引擎/持久化/合成/SSE/计价（Rust 已交付 M1）、timer、
  session、agent、agent-loop、tools、system-prompt、llm seam（Kotlin 内核常驻，
  保留插件形式但不暴露启停）。
- **B. 内置可配置**：llm-deepseek、llm-retry、agent-default-model、approval、
  permission、timeout-policy、repeat-tool-reminder、compaction-basic、
  session-title(+llm)、plan-mode、commands、user-questions、web+search+tool-web(仅 search)、
  tool-bash(mksh)、tool-fs(+search)、str-replace-editor、tool-todo、
  settings(→DataStore+YAML 导入)、credentials(→Keystore)、attachment、subprocess(重写)。
- **C. 目录/文件驱动（需管理 UI）**：skill 系、agent-instructions、用户命令目录。
- **D. 远期**：subagent 全套、workflow、jobs、goal、session-projection/stats/cache、
  telemetry、llm-pi-ai、token-meter Kotlin 壳、**MCP client（HTTP）**。
- **E. 不适用**：hmr、typert 传输层、bwrap/Landlock、pwsh、web HTTP 栈。

## 6. Jasmine 需新建的 UI 管理面

模型与凭证编辑页 · **MCP 服务器管理页（全新建，无对标）** · 权限预设+审批对话框 ·
skill 目录管理 · 插件清单/启停（已有雏形）· 命令触发面 · plan/goal/todo 席位 ·
后台作业列表 · token/上下文压力显示 · 遥测开关。

## 7. 核心结论

主仓 90% 的"插件"是**配置行**而非代码；用户侧需求 = 配置化 + UI 管理化。
真正"用户开发"的长尾由脚本插件承载；代码包（dex）列为远期。
Jasmine 路线：配置化大功能+管理 UI（90% 场景）→ 脚本插件（长尾）→ 代码包（远期）。

## 8. 外部插件生态研究（2026-08-22，互联网检索 + 本地克隆 4 仓库）

来源（已克隆至 `D:\dsh-research\plugins\`）：
- `dsh-agent-teams`（真实插件，779★，含 `docs/developing-dsh-plugins.md` 与 `skills/dsh-plugin-development/SKILL.md`）
- `awesome-dsh-plugin`（1837 条插件索引，一插件一 YAML）
- `awesome-deepseek-harness-plugins`（3100+ 插件市场：目录 API + 商店插件 dsh1024 + 安装器 CLI）
- `open-design`（90k★，独立产品，把 dsh 当 stdio 协议的外部 runtime）

### 8.1 第三方插件开发 recipe（以 dsh-agent-teams 为准）

1. npm 包骨架：`package.json` 含 `dsh.bundle.patch`、`dsh.client{platform, inject}`、
   `exports["./client"]`（缺则名册扫描拒绝）、16 个 `@deepseek-ai/*` 全 peerDependencies optional
2. `cordis.patch.yml`：`- insert: [- id, name(==包名), config?]`——稳定行身份
3. host 入口四要素：`export name / inject / Config(z.object) / apply(ctx, config)`；
   apply 里 `ctx.systemPrompt.section()`、`ctx.tools.register(defineTool({...}))`、
   惰性 `ctx.inject(['commands'])`、可选 HTTP 路由（`ctx.effect` + `internal/service` 补注册）
4. 事件面：`declare module` 合并 `SessionEventMap`（零 import 文件），`session.append` 写入
5. client 面：`ctx.slots.inject(key, () => ctx.slots.register({name,id,order,label}, Component))`
   ——声明/认领/注册三步；`ctx.conversationEvents.register(definition)` 事件确定性重放卡片
6. 构建：tsc×2（host/client 双 program）→ tsdown closure-factory；purity gate 禁插件间值导入
7. 自检：verify.mjs 八节（打包契约/纯逻辑/依赖门控/磁盘往返/投影/生命周期/压力）+ `--dump-config`
8. 发布安装：npm publish → `dsh plugin --profile X add <spec>` → **重启 profile 生效**

### 8.2 市场/安装安全模型（可整体借鉴）

- 目录 schema：`$schema/id/name/repository/category/description{en,zh}/added`；
  API 层加 `install`（现成命令）/`target`/`allowBuild`/`stars`/`installCount`
- 收录门槛：**静态校验绝不执行**——`package.json.dsh.bundle` + patch 同树存在
- 安装器纪律：目录只给结构化 spec，受信宿主校验执行，目标白名单，绝不 shell 拼串
- open-design 借鉴：插件声明 `capabilities`，安装时**逐项能力授权**（契合 Android 权限模型）

### 8.3 对 Jasmine 的直接启示

1. **manifest + patch 双层模型**：声明（dsh 元数据）与组合行分离——Jasmine 对应
   "插件描述文件 + 安装时注册表条目"，`id` 稳定身份 + config 整段替换照搬
2. **插件 API 面**：`name/inject/apply` + `defineTool` 工具契约 + `SessionEventMap`
   确定性事件流 = Jasmine 插件 SDK 的核心形态（我们已有 ServiceKey/EventBus/waterfall 对应物）
3. **UI 扩展点**：slot 三步仪式 → Jasmine 定义自己的扩展点契约（面板注册/工具菜单/会话卡片）
4. **必须差异**：无 npm/Web → 自定插件包格式（zip+manifest.json）；安装=目录+注册表+沙盒数据目录；
   重启语义显式建模；强制插件只经宿主总线通信（purity gate 思想）
