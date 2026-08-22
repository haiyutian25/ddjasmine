# Rust 下沉路线图（未沉"固定部分"清单）

> 依据 2026-08-22 对 `D:\dsh-research\deepseek-harness`（最新版主仓）的全面调研。
> 判据：行为由规范/格式/代数固定；插件只消费不替换；跨 FFI 只传数据不传行为续延；多在热路径。
> 现状基线：`rust/` 已下沉"存储与传输层"（store / session-log / compose / codec / ffi），
> 尚未下沉的是**解释层**——同一份会话日志的解释器族。

状态标记：`[ ]` 未开始 · `[~]` 进行中 · `[x]` 已完成（附 crate/模块）· `[f]` 有意推迟（附理由）

## P0 — 日志格式的"另一半"

- [x] **P0-1 request-header 折叠代数** → `session-log/src/request_header.rs`（含 FFI `request_header_json`/`request_context_json`/`canonicalize_header_json`/`epoch_header_equals_json`）
  - 来源：`packages/core/session/src/request-header.ts`
  - `canonicalHeader`（空 system/空 tools 归一为缺席；adapterDefaults 仅标记存在时保留）、
    `headerEquals`（config 逐字段 + callConfig + tools 按序比较）、
    `foldRequestHeader(events, from)`（last-wins + 增量续折叠）。
  - 同批：`requestContext()` 折叠（`request/context` 三元组 last-wins，
    `packages/core/session/src/index.ts`）。
  - 消费方：agent-loop seedConfig 判定、token-meter、compaction、不变量 (5)(7)。

- [x] **P0-2 surface 严格校验代数** → `session-log/src/surface_validate.rs`（`validate_surface`；含 payload 形态适配说明）
  - 来源：`packages/core/session/src/surface.ts`
  - Android `derive_messages` 目前是**宽容投影**；需补 validate API：
    非 surface 事件禁带 marker / surface 事件必带、`assertProvenance`
    （sourceEventSeqs 早于自身、无重复、覆盖全部被遮蔽节点）、
    `replacementRange`（start/end 在 surface 中且有序）、
    `assertToolResultRewrite`（tool/result 替换只许改 content，其余深度相等）。
  - 形态建议：`validate_surface(events) -> Result<(), SurfaceError>`，
    写路径（Kotlin append 前）调用；投影保持宽容。

- [x] **P0-3 事件信封/关系不变量** → `session-log/src/invariant.rs`（`SessionTrace::validate_event` 纯状态机 + `validate_log`；含 legacy 拒绝与 payload 形态适配说明）
  - 来源：`packages/core/session/src/invariant.ts` + `index.ts` 的 assert* 族
  - seq 严格递增（已有）、turn/step 编号连续、事件必须 turn 包围
    （`request/header`、`request/context`、`todo/write`）、
    step 内 tool/result 必须有前驱 tool/call（合成 NOT_STARTED 豁免）、
    替换型 tool/result 必须在 open turn 内；
    信封键白名单（type/seq/time/data/surfaceOp/sourceEventSeqs/ignorable）、
    消息形态校验、legacy `request/header-delta` 与 `reason:'fallback'` 拒绝。
  - 注意：frozen 检查是 JS 对象模型特有，Rust 天然满足，跳过。

- [x] **P0-4 token 估价器 + fold** → 新 crate `measure`（estimate / surface_fold / surface_projection / usage_projection 四模块；shadow-price claim 适配为 seq 集合）
  - 来源：`packages/llm/token-meter/src/{estimate,surface-fold,surface-projection,usage-projection,breakdown-projection}.ts`
  - 常量：`CHARS_PER_TOKEN=4`、`BLOCK_OVERHEAD=4`、`ROLE_OVERHEAD=4`（无配置、拒绝任何键）；
    估价族（content/message/system/tools/header）+ shadow-price 协议
    （`compaction/summary|prune` 布防 claim，紧邻 replace 消费，O(1) 状态）+
    usage/context-pressure/breakdown 三个投影 fold。
  - 关键约束：shadow-price 事件数值必须与估价器逐字节一致，双实现漂移会静默破坏重放。

- [x] **P0-5 tool-result-pruner 纯核** → `measure/src/prune.rs`（resolve_config / measure_content / prune_content / plan_prunes，含 shadow-price 事件对规划）
  - 来源：`packages/compaction/compaction-tool-result-pruner/src/{config,index}.ts`
  - `DEFAULTS` 8192 阈值/4096 头/1024 尾、`PRUNE_MARKER` 固定文案、
    `resolveConfig`（键白名单 + head+marker+tail≤threshold 不变式）、
    `codePointLength`/`measureContent`/`pruneContent`（码点切片、跨块保留头尾、
    单 marker、事后自检）。
  - 修剪结果以 `surfaceOp: replace` 回写日志——两侧必须共享同一实现。

- [f] **P0-6 SQLite 持久化整包** → `store` phase-2
  - 来源：`packages/session/session-persistence-sqlite`（SCHEMA_VERSION=17、
    APPLICATION_ID=0x44534850、STRICT 三表、34 条封闭 SQL、
    frozen codec：MIN 3 / MAX 1024 成员 / 1MiB 上限、zstd level 3 + 阈值 4096B、
    zigzag varint sourceEventSeqs、`ignorable=0` sentinel）
  - 必须用 `libsqlite3-sys` bundled（Android 系统 SQLite 版本不可控，STRICT 需 ≥3.37）。
  - [f] 列为 phase-2：依赖引入重、UI 尚未消费；JSONL 已满足 M1–M2。

## P1 — 压缩选取与编码对称性

- [x] **P1-1 chunk 打包器（编码方向）** → `session-log/src/chunk_rows.rs`（`pack_chunk_runs` / `expand_chunk_row` 纯函数往返；尚未接入 append 写路径——接入时需连 `load` 的解码一起改，属格式变更决策）
  - 来源：`packages/core/session/src/chunk-rows.ts`
  - Rust 目前只有解码（import_bridge 展开）；pack 的二分受限算法、
    白名单 `classify`（exact-keys）、`MIN_RUN=3`、同 run 约束（seq 连续、dt 安全整数、
    同 turn/step/index、tool-call id/name 一致）、`dt` 可为负（时钟回拨）需逐字移植。

- [ ] **P1-2 压缩选取纯核** → `measure`
  - 来源：`packages/compaction/compaction-basic/src/{region,config}.ts` +
    `packages/compaction/compaction/src/tool-pairing.ts`
  - `selectCompactableRange`（尾部累计预算→tool-pairing 平衡切点→头锚定）、
    `inspectCompactionEntryState`（单遍逆向）、config 代数（thresholdRatio×contextWindow）。
  - summarizer（LLM 调用）留 Kotlin。

- [ ] **P1-3 zstd 帧扫描/帧常量** → `store`
  - 来源：`packages/session/session-persistence-jsonl/src/zstd.ts`
  - `scanZstdFrames`（不解压的结构级扫描：magic 0xFD2FB528、FHD 位域、block header、
    tornStart）+ 独立帧压缩（checksum flag=1）+ 撕裂前缀恢复。

- [ ] **P1-4 BlockAssembler** → `session-log` 或 `measure`
  - 来源：`packages/llm/llm/src/assembler.ts`
  - chunk→block→message 增量状态机（block-end 先到先冻结、迟到 delta 忽略、
    max-tokens 丢弃 tool-call、interruptedBlocks 前缀规则）——衔接 `ffi::pump_sse_chunks`。

- [ ] **P1-5 hooks wire 协议三件** → 新 crate `hook-protocol`
  - 来源：`packages/hooks/hook-protocol/src/{codec,matcher,merge}.ts`
  - `parseHookOutput`（exit 2=block、JSON 对象解析容忍、permissionDecision 覆盖）、
    `matchesMatcher`（缺席/空/`*` 全匹配、方言 regex）、`mergeHookOutputs`
    （deny>ask>allow>none 格、continue sticky）。
  - 注意：JS regex 与 Rust regex 方言差异，需 `fancy-regex` 或文档化。

- [ ] **P1-6 settings 数据层 + credentials 解析层** → 新 crate `conf-core`
  - settings：`mergeLayers`/`cloneJsonShaped`/`applyPathOp`/`deepEqualJson`/`redactSecrets`；
    schema 校验与 YAML 注释保留渲染留宿主。
  - credentials：`credentialRef` 正则、严格 YAML 文档解析（错误不带值——保密语义）、
    分层序（inherited env > $DSH_HOME/.credentials.yaml > cwd/.env > $DSH_HOME/.env）、
    `changedRefs` diff、launch-environment 快照。

## P2 — logged-state 投影族与杂项纯函数

- [ ] **P2-1 投影 fold 族** → `session-log`
  - session-stats（step/end 计步、首 token 判定、callId 配对，墙钟全来自事件 time）、
    title normalize（OSC/CSI/控制字符剥离 + UTF-8 字节截断 + 词数/字节双限）、
    plan-mode fold（`plan/mode` last-wins + wanted 状态机）、
    todo fold（`todo/write` last-wins + `turn/start` 清空 + toTodoList 校验）。
- [ ] **P2-2 投影 checkpoint 冷读代数** → `session-log`
  - `restore`/`restoreFloor`/`viewCheckpoint`/`checkpoint`/`buildCell`
    （(key,ver,seq,val) 行 + 前向尾重放 + 版本失效）。
- [ ] **P2-3 system-prompt 渲染** → `session-log` 或独立
  - `interpolate`（严格 `{{var}}`：malformed/未知名/undefined 三错误路径、
    孤立 `{{` 视为字面）、`orderTools`（code-unit 字典序 + `<unlisted-tools>` 槽）、
    `validateAssembly`。
- [ ] **P2-4 JSON-RPC 分帧纯函数** → 新 crate `jsonrpc-framing`
  - 行→帧分类（id+method/method/id）、params 归一、错误码（-32601/-32603）、
    响应配对——TS/Python 已双实现，Rust 做第三方共享规范。
- [ ] **P2-5 web policy 四件**：`validateFetchUrl`/`isSameOrigin`/
  `classifyContentType`/`parseCharset`。
- [ ] **P2-6 小随迁**：atomic-write/withFileLock、home-paths、identity UUID v4
  协议、`renderSkillContent` wire 形状 + frontmatter 解析、file-reference 语法、
  session-reference URI、attachment `sha256:` 校验/base64 canonical/displayName。

## 明确不沉（记录结论，防止重复评估）

| 项 | 理由 |
|---|---|
| ReactLoopAgent / tool-calls 调度池 / SystemPrompt.assemble / timeout-policy / telemetry coordinator | 行为续延（waterfall/abort/并发），过不了 FFI 判据 |
| cordis 本体 / typert generator+loader / vendor hmr+loader / Landlock / Node 私有 zstd decoder / brand / timeout util | Node/构建期专属或编译期类型 |
| llm-deepseek wire translate | 现有哲学：provider 语义留 Kotlin 插件 |
| schemastery 校验 / YAML 注释保留渲染 | 生态缺口；Android 支持 JSON 或留宿主 |
| time-context Intl 时区 | ICU 行为，留 Kotlin |

## 实现纪律

1. 每项落地前先读 TS 源码逐函数对照，语义以最新主仓为准；
2. 常量/文案/错误消息逐字移植，禁止"顺手改进"；
3. 每项带对应用例（含上游 invariant 测试的等价断言）；
4. 完成即更新本文件状态标记与 `rust/` README；
5. 全量 `cargo test --workspace` + clippy 0 警告 + fmt 干净。
