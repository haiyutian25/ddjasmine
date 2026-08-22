# 插件化差距分析（Jasmine vs 主项目）

> 依据 2026-08-22 对 `D:\dsh-research\deepseek-harness`（最新主仓）与本目录
>（`rust/` 数据脊柱 + `android/` Jasmine）的全量对照调研。
> 主仓语义基准：`ANDROID-PLAN.md` §1/§2/§4/§6；里程碑口径 M0–M4。
> 配套文档：`RUST-SINKING-ROADMAP.md`（Rust 侧未沉项与状态）。

## 1. 现状盘点

### Jasmine 已有（"数据与持久化"半边）

- **Rust 数据脊柱（M1 完成）**：store / session-log / measure / compose / codec / ffi
  六 crate；116 单测全绿。
- **解释层（P0 全落）**：request-header 折叠代数、surface 严格校验、关系不变量
  状态机、token 计价 + shadow-price 协议 + 三个投影 fold、tool-result 修剪、
  chunk 打包/展开（编码方向）。
- **UniFFI 0.32 双向打通**：官方方式 workspace 内 bindgen、四 ABI `libffi.so`、
  JNA 5.19.1、`SessionLogHandle` 等全 API 面。
- **会话 UI**：`feature-session` 会话列表 + 事件时间线，全部数据真实读写 Rust 日志
  （create/append/derive/closeInterruptedTurns/repairedTornTailBytes 均在用）。
- **品牌**：Jasmine v0.1.0 / `com.lhzkml.jasmine` / 全槽位茉莉色板（无紫、无渐变、
  无湛蓝；动态取色默认关闭）。

这些刻意**不可插件化**——Rust 部件随 APK 发布，是"固定不变量"半边，这正是设计。

### 缺失的半边：Kotlin 行为内核与插件生态

主仓"一切皆插件"依赖三件套：Cordis 内核、组合层、capability seam。Jasmine
目前一个都没有。

## 2. 差距清单（按主仓架构分层）

### A. Kotlin 内核骨架（M0，插件化的地基）——完全缺失，最大差距

| 主仓机制 | 主仓位置 | Jasmine 现状 |
|---|---|---|
| Registry（插件注册/生命周期/disposer） | `vendor/cordis` registry | 无 |
| EventBus 五模式（emit/parallel/serial/bail/waterfall） | `vendor/cordis` events | 无——插件协作的全部语法 |
| Fiber（结构化并发，= 协程作用域树） | `vendor/cordis` fiber | 无 |
| Scope（插件隔离与注入上下文） | `vendor/cordis` scope | 仅有 Hilt，无插件级 scope |
| PluginHost（KSP 编译期插件索引，替代 npm loader） | 规划于 ANDROID-PLAN §5 | 无 |
| "注册即效果"（`ctx.effect()` / `ctx.on()`，register 返回 disposer） | AGENTS.md 约定 | 无 |

没有这层，`feature-plugin` 模块只是空壳名字——真正的插件管理界面背后需要
PluginHost 可查。

### B. 组合层（profile / bundle / patch 装配）

- Rust `compose` crate 已备**纯代数**（三态 PatchValue、insert、逐项告警），
  FFI `compose_yaml_layers` 已导出但**零调用**。
- Kotlin 侧缺：patch YAML 装载入口、bundle/profile 目录约定、preset 组合、
  把合成结果变成"实际挂载哪些插件"的 mount 步骤。

### C. Capability seams（插件提供能力的标准协议）

主仓每种能力 = Service Definition / Provider / Consumer 三角色。Jasmine
一个 seam 都没有：

| Seam | 主仓参照 | 备注 |
|---|---|---|
| LLM（`llm/stream` waterfall、usage 规范形） | `packages/llm` | M2 主体 |
| Tools（注册/调度/有界并行池） | `packages/core/tools` | M2–M3 |
| fs / subprocess(mksh) | `packages/fs`、`packages/subprocess` | M3–M4 |
| web（search/fetch） | `packages/web` | M4 |
| skill | `packages/skill` | M4 |
| compaction | `packages/compaction` | 算法已在 Rust，缺 seam |
| approval / permission | `packages/interaction` | M3 |

### D. AgentLoop（M2）

- 缺：turn/step 状态机、inbox 争用、abort、seedConfig 判定（"header 变才落
  `request/header`"）。
- 有利条件：Rust 已备其全部校验器——`validate_surface`、`SessionTrace`
  （关系不变量）、request-header 折叠/相等（上游五条不变量中的三条半），
  loop 每次请求前调用即可。

### E. 首批插件（M3，对标主仓 base bundle 80 插件裁剪出的清单）

| 插件 | 依赖的已备资产 | 缺口 |
|---|---|---|
| llm-deepseek | `SseFeeder`/`pump_sse_chunks` FFI（就绪零调用） | `mapFinishReason`/`mapUsage` 纯映射 + HTTP 客户端 |
| tool-bash(mksh) | subprocess seam | seam + mksh 适配 |
| 审批/权限 | 无 | 全新（Android 对话框式审批） |
| session-title | title normalize 待沉（P2-1） | 小 |
| todo / plan | logged-state fold 待沉（P2-1） | 中 |
| token-meter | Rust `measure` 全套已备 | 只差 Kotlin 投影壳 |

### F. 会话引擎补全（Rust 侧剩余，详见 RUST-SINKING-ROADMAP）

P1-2 压缩选取、P1-3 zstd、P1-4 BlockAssembler（chunk→消息组装，接 SSE 流
必需）、P2 投影 fold 族 / checkpoint 冷读 / system-prompt 渲染。

### G. 平台基础设施

settings / credentials（P1-6）、guard（loop-hygiene / tool-timeout）、审批 UI、
前台服务保活、遥测、应用沙箱 + 白名单（替代 Landlock）。

### H. 验证面

mock LLM 回放器（对标 `llm-mock-server`）、快照测试、真机 e2e——主仓
"模型可见 ⟺ 可回放"的纪律在 Android 尚无任何测试承载。

## 3. 结论与最短路径

里程碑位置：**M1 ✅ → 当前卡在 M0（内核骨架）**。

1. **M0**：Kotlin 内核五件套（Registry / EventBus 五模式 / Fiber / Scope /
   KSP PluginHost）。纯 Kotlin、无 Rust 依赖、JVM 单测可全覆盖。
2. **M0.5**：用 `compose` crate 做 patch 装配，`feature-plugin` 改写成真实
   插件管理界面（列出/启停插件 = 写 patch 层）——用上预留模块。
3. **M2**：AgentLoop + LLM seam + mock 回放；SseFeeder 与 measure 接通，
   日志回声变真对话。
4. **M3**：llm-deepseek + tool + 审批，真机端到端。
5. **M4**：能力扩展与硬化（web、SQLite、前台服务、基准）。


## M3 供应商连接（2026-08-22）

- `core-agent/DeepSeekLlmService`：真实 OpenAI-compatible `/chat/completions` 与 `/models`，
  OkHttp 5.4.0（官方 5.5.0 AAR 要 compileSdk 37；项目 SDK 36，5.4.0 metadata 明确 minCompileSdk=36），
  4 个 MockWebServer3 测试（请求/模型/HTTP错误脱敏/坏JSON）。
- `ProviderSettingsStore`：API Key 用 Android Keystore AES-GCM 加密落 SharedPreferences；baseURL/model 明文非秘密。
- `ConfigurableLlmService`：有 Key 走真实 DeepSeek，无 Key 走 mock（首次启动仍可聊天）。
- UI：设置→模型与凭证，支持 Key 隐显、baseURL、手输/拉取模型、连接测试、保存；聊天发送即时使用新配置。


## 自定义供应商双协议（2026-08-22）

- 不再预装或默认任何 LLM 厂商：无默认 API 地址、模型、密钥，无生产 mock 回退；未配置时聊天明确报错。
- `CustomLlmService` 支持 `Chat Completions (/chat/completions)` 与 `Responses (/responses)`；
  地址既可为 API 根地址，也可为完整协议端点，内部去重规范化；`/models` 从根地址派生。
- 用户设置：协议、API 地址、密钥（可空，Keystore 加密）、模型、上下文长度、最大输出长度。
- Chat 发送 `max_tokens`；Responses 发送 `max_output_tokens`；上下文按 4 字符/token 的固定密度
  从最旧消息开始裁剪，为输出预算预留窗口。
- 双协议 6 个 MockWebServer3 测试 + 全量测试/release 构建通过。


## 供应商输出默认与 AgentLoop 卡死修复（2026-08-22）

- 最大输出长度改为可选：留空时 `maxOutputTokens=null`，Chat Completions 不发送
  `max_tokens`，Responses 不发送 `max_output_tokens`，由供应商使用其最大值；上下文裁剪
  在此模式下使用完整 contextLength 预算。
- 修复永久“思考中”：Hilt 中的 `ConfigurableLlmService` 曾未注册到 Kernel 的
  `LlmServiceKey`，AgentLoop 永久挂起在 `registry.await`。AgentModule 现在创建单例 loop 时
  将 Hilt provider 注册到 Kernel。
- AgentLoop 增加 120 秒总请求超时；provider 失败/超时仍补写 `step/end` 与 error `turn/end`，
  ViewModel 无论成功失败都刷新 Rust 日志并清除 sending。
- 新增测试：输出留空时双协议均省略限制字段；缺 provider 与 provider 失败均闭合 step/turn。
