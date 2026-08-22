# 上游-android

DeepSeek Harness 的 Android 原生重实现（独立产品，仅借鉴设计，不追求格式兼容）。
总体方案见仓库根目录 [`ANDROID-PLAN.md`](../ANDROID-PLAN.md)。

## rust/ — 数据脊柱（M1 交付物）

Cargo workspace，五个 crate（对应 ANDROID-PLAN §4.1）：

| crate | 职责 | 关键契约 |
|---|---|---|
| `store` | append-only JSONL 后端（SQLite 二期） | `JsonlStore::append_line` / `flush`（checkpoint fsync）/ `read_all` |
| `session-log` | 会话日志引擎 | 版本头（`FORMAT_VERSION = 0`，不符拒载）、seq 连续性校验、`derive_messages`（封闭核心 + 自描述扩展两层投影，沿有序 surface 走，支持 `surfaceOp: replace` 位置替换）、`close_interrupted_turns`（LIFO 收殓、per-turn 归属、双错误码 `TOOL_OUTCOME_UNKNOWN`/`TOOL_NOT_STARTED`、复用最后真实事件时间戳，幂等）、`import_jsonl`（上游 fixture 单向导入桥，见下）；**解释层**：`request_header`（折叠/规范化/相等——`request/header`/`request/context` 的解释器）、`surface_validate`（append 期严格校验：资格/provenance/引用覆盖/tool-result 改写限定）、`invariant`（turn/step/tool-call 关系不变量纯状态机 + legacy 拒绝）、`chunk_rows`（chunk 打包/展开编码方向） |
| `measure` | token 计价与表面折叠 | 固定密度估价（`CHARS_PER_TOKEN=4` 等，常量即规范）、位置性计价 surface 折叠、O(1) shadow-price 协议（claim 适配为 seq 集合）、usage/context-pressure/breakdown 三个投影 fold、确定性 tool-result 修剪（8192/4096/1024 + 码点切割 + `plan_prunes` 含 shadow-price 事件对） |
| `compose` | patch 合成器 | 按 id 定位、按键浅层覆盖（config 整体替换）、显式 null 清除键（三态 `PatchValue`）、`insert` 行数组追加、逐项告警跳过——语义经源码审计与 `vendor/include` 对齐 |
| `codec` | SSE 增量解析 + 附件寻址 | `feed(&[u8]) -> Vec<SseEvent>`：UTF-8 跨块 / CRLF / BOM；`[DONE]` 哨兵为构造参数（协议语义留 Kotlin 插件）；`sha256:` 内容寻址 |
| `ffi` | UniFFI 接口层（唯一对外窗口） | 全部数据穿越（record + JSON 字符串）；唯一回调形态 `SseSink`（`with_foreign`）；`SessionLogHandle`（含 `event_count` 进度查询、`repaired_torn_tail_bytes` 崩溃修复可见性）/ `SseFeeder` / `compose_yaml_layers`；`import_jsonl` 有意不导出（验证面非产品面） |

### 构建与测试

```sh
cd rust
cargo test --workspace     # 116 个单测，host target 全绿
cargo build --release      # 产出 ffi cdylib（.so/.dll）
```

### 生成 Kotlin 绑定（后续步骤）

```sh
cargo install uniffi-bindgen-cli --version 0.29.5
uniffi-bindgen generate --library target/release/ffi.dll --language kotlin --out-dir <android-app>/app/src/main/java
```

（交叉编译各 ABI：rustup target add aarch64-linux-android 等 + NDK linker 配置，属于 M3 集成工作。）

### 已落实的边界纪律（ANDROID-PLAN §4.0/§4.2）

- Rust 侧无线程安全外包：`SessionLog`/`SseFeeder` 内部可变性一律 Mutex 保护，不依赖 Kotlin 单 dispatcher 纪律。
- panic 不过 FFI：UniFFI scaffolding 自带 catch_unwind。
- 无 tokio 等常驻 runtime crate。
- `derive_messages` 两层投影：核心事件固定规则；插件扩展事件经 payload `model_message` 自描述进入历史，新增事件类型不需要 Rust 发版。

### 与 上游 源码的保真度（经逐项源码审计）

已对齐：patch 合成代数（含 insert 行数组形态、三态 null 清除、逐项告警粒度）、torn tail 崩溃容忍与截断修复（上游 commitRepair 等价）、版本头先于结构校验、收殓补全悬空 tool/result 与 step/end（上游 `interruptedTurnClosers` 等价，双错误码区分"结果未落盘"与"未及启动"，合成事件复用最后真实事件时间戳保证确定性）、surface/replace 有损快照投影（payload 携带 `surfaceOp: replace` + `sourceEventSeqs`，引用节点被遮蔽、替换占据首个引用位置——上游 压缩摘要与工具结果裁剪的机制，rc.8 已成承重能力）、SSE 分帧规范（CR/CRLF/LF、BOM 跨块、无 data 块不派发、id NUL 过滤、event 默认 `message`）、`time_ms` 事件包络。

有意更严（已在代码文档注明）：invalid UTF-8 报错而非 U+FFFD 替换；任何 seq 不连续拒载（上游 持久化层对部分崩溃尾形态容忍）；`finish()` 未终止尾部报 `Truncated`（上游 该规则在 sse.ts 协议层，不在分帧库）；收殓的 per-turn frame 归属强于 上游 单槽尾部修复（多孤儿 turn 各自收殓，已闭合 turn 的遗留获得会话级修复而非丢弃）；surface 元数据随 payload 而非事件包络（本日志不承诺与 上游 磁盘格式兼容）。

### 上游 fixture 导入桥（`import_jsonl`，验证面非产品面）

说明：该桥仅经历了去前缀改名（原 `import_dsh_jsonl`），能力完整保留——仍读取仓库真实 snapshot fixture（packed-chunks / compaction-recovery / cancel）做等价验证，7 个测试锚点不变；产品 FFI 面继续有意不导出它。

`session-log` 提供单向导入：把 上游 真实会话日志（JSONL）译成引擎规范形并装入内存日志，用仓库 snapshot fixture 验证投影与收殓（§0 的不兼容承诺不变——不进 FFI 产品面、不接持久化路径）。翻译规则：

- **packed chunk rows 展开**：`text-chunks`/`reasoning-chunks`/`tool-call-chunks` 存储行展开回逐个 `assistant/chunk` 事件（成员 k 的 seq = `seq0+k`、time = `time0+前 k 个 dt`），seq 连续性在展开后校验；
- **信封 surface 元数据折叠进 payload**：`surfaceOp:"append"` 丢弃（本引擎默认即追加）；`{op:"replace",start,end}` 经导入期 surface 折叠（镜像 上游 `foldSurface` 的 splice，含非连续 seq 的位置区间）译为 payload `surfaceOp:"replace"` + `sourceEventSeqs`（恰为被遮蔽的现行 surface 节点集）；
- **payload 形状翻译**：上游 嵌套 message / 内容块数组 / `callId` 译为规范形（text 块拼接为 `content`、tool-call 块收集为 `tool_calls`、调用 id 归一 `tool_call_id`）；空内容块数组的 assistant/message（仅承载 usage）不携带 `content` 键，投影跳过与 上游 `deriveEventMessage` 一致；其余事件类型 payload 原样透传；
- 已用 `packed-chunks`（packed rows 展开 + 完整投影 + 5 条消息）、`compaction-recovery`（replace 语义 + upto 边界）、`cancel`（interrupted 前缀标记 + 收殓幂等 no-op）三个 fixture 回放验证；`ignorable` 标记的拒读语义不在桥上强制（未知类型按本引擎规则惰性透传）。

二期缺口（均告警而非静默）：组内 patch 操作、`!!js` 表达式、任意扩展键、SSE 注释回调通道；replace 的接受期校验（引用覆盖完整性、tool/result 替换限单一现行结果）留在 Kotlin 写入方——Rust 投影对无效引用惰性忽略，绝不静默丢弃模型可见内容。

## 目录结构（双主项目）

```
dsh-android/
  rust/          Rust 数据脊柱（M1，已完成）：store / session-log / measure / compose / codec / ffi
                 + uniffi-bindgen（workspace 内官方方式，与运行时同版本）
  android/       Gradle 工程「Jasmine」（包名 com.lhzkml.jasmine，minSdk 26，v0.1.0）：
                 :app / :core-data（含 com.lhzkml.jasmine.rust 绑定 + Rust SessionRepository）/
                 :core-database / :core-kernel（M0 五件套：Registry/EventBus/Fiber/Scope/PluginHost，
                 纯 Kotlin JVM 可测，30 测试全绿）/ :core-testing / :core-ui /
                 :feature-plugin(-navigation)（M0.5/M0.6 真实插件管理：开关 = home 覆盖层经 compose FFI 合成，运行时经 PluginRuntime 桥挂载真实插件 fiber）/
                 :feature-session（聊天页：气泡列表 + 底部输入条，IME 正确收放，经 AgentLoop 直达 Rust 日志）/
                 :test-app —— jniLibs 四 ABI libffi.so（uniffi 0.32 + JNA 5.19.1），茉莉暖白配色
```

模板原带的 Room 持久化与 Hilt UI 仅作骨架示例；产品持久化走 Rust `store`（SQLite 为
Rust 侧二期），后续按 ANDROID-PLAN §5 增设 `:core:kernel` / `:core:agent` /
`:core:contracts` / `:rust:dsh-ffi` 并经 UniFFI 接入 Rust 核心。

