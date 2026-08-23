# 插件化框架 Rust 重写方案 —— 基于 ComboLite 代码级审核

> 状态：方案设计 v2（第二轮全面复核后修订，未开始开发）
> 依据：《插件化架构开发方案》《插件化架构-插件的ui解决方案》（见 `插件化架构开发方案/`）
> 参考实现：ComboLite v2.0（本地 `ComboLite/`，Apache-2.0，只读参考，重写不复用其代码）
> 审核范围：comboLite-core 全部 51 个 Kotlin 文件的核心路径 + build-logic + sample-plugin 插件侧写法，两轮审读

---

## 一、目标与结论

**目标**：以 ComboLite 的架构为概念蓝本，结合本项目已有的 Rust 固定层与 Kotlin 插件内核，重写一套命名与结构全新的插件化框架。Rust 承接框架的"大脑"（状态、图、规则、事务），Kotlin 保留"手脚"（ClassLoader、Android 边界、UI）。

**结论**：可行，且是升级而非平移。ComboLite 用 Kotlin 并发原语手写的四个状态机（注册表、双向依赖图、类索引、权限规则）恰好是 Rust 类型系统与事务性模式的强项；审核发现其五个具体缺陷，在本方案中均有明确解法。

**工作量重估**：Rust 侧约 800 行（对应它 ~1200 行核心逻辑），Kotlin 执行壳约 900 行重写（命名全部换新）。现有 UniFFI 计划式交互模式原样适用。

---

## 二、ComboLite 架构审核摘要

### 2.1 框架形态

微核 + 管理器集群，共 51 文件：

```
PluginManager（对外唯一门面/单例，suspend API）
  └─ internal PluginFrameworkContext（依赖中心：共享状态 + 管理器实例）
       ├─ InstallerManager        安装/更新/卸载（磁盘世界）
       ├─ PluginLifecycleManager  加载/实例化/启动/卸载/链式重启（内存世界）
       ├─ DependencyManager       双向依赖图 + 跨插件类查找仲裁
       ├─ PluginResourcesManager  合并式资源
       ├─ ProxyManager            四大组件代理调度
       └─ AuthorizationManager    权限/授权总入口
```

关键状态（全部在 PluginFrameworkContext）：
- `loadedPlugins: StateFlow<Map<id, LoadedPluginInfo>>`
- `pluginInstances: StateFlow<Map<id, IPluginEntryClass>>`
- `classIndex: ConcurrentHashMap<类名, 插件ID>`（全局类索引）
- `initState` / `validationStrategy`

### 2.2 五大核心机制（实现要点）

1. **安装流水线**（InstallerManager:134-230）：checkApiCaller 门禁 → 存在性 → 元数据验证 → 签名策略裁决（Strict / UserGrant / Insecure）→ 版本检查（禁降级）→ 旧目录备份 → 复制 APK + `setReadOnly()`（Android 14+ DCL 合规）→ 解压 native so → dexlib2 扫描生成 class_index → 解析静态 Receiver/Provider → 写 plugins.xml。
2. **跨插件类查找**（PluginClassLoader + DependencyManager）：本地 dex 查找失败 → 委托仲裁 → 查全局类索引 O(1) 定位目标插件 → 依赖图记边（正反两张）→ 目标 ClassLoader 的 `findClassLocally`（不再委托，杜绝递归）→ 都失败抛 `PluginDependencyException`。**插件依赖零配置**：第一次借类即建边。
3. **链式重启**（PluginLifecycleManager:136-153）：更新插件 B 时在反向依赖图 DFS 求受影响闭包 → 逆序卸载 → 正序重载，保证新旧代码不混用。
4. **四大组件代理**（ProxyManager，0 Hook）：Activity 单占坑代理 / **Service 代理池**（宿主预注册 N 个）/ Receiver 中心化代理分发（Manifest 预声明通用 action 集）/ Provider URI 代理。Intent extra 携带插件类名，代理组件实例化插件接口实现并转发生命周期。
5. **安全闭环**：`@RequiresPermission(level, hardFail)` 注解标记敏感 API → `checkApiCaller()` 分析调用堆栈 + 类索引反查调用插件 → 静态规则检查（HOST=与宿主签名一致；SELF=同插件或宿主签名）→ 不通过且非 hardFail 则 `IAuthorizationHandler` 弹授权 UI（宿主可替换）。崩溃侧：全局捕获 → 识别插件类异常 → 插件回调 → 宿主回调 → 熔断（CrashActivity 引导禁用肇事插件并重启）。

### 2.3 宿主集成要点（app 侧）

- 插件 APK 可内置 assets 首发 + 网络版本更新双通道；
- `awaitInitialization()` 挂起等框架就绪；`pluginInstancesFlow` 驱动 UI；
- `IPluginEntryClass` 含 `@Composable fun Content()`——插件 UI 直接挂进宿主 Composition（共享单一 Compose Runtime 的充要条件：插件全部依赖 `compileOnly` + ClassLoader parent 委派宿主）；
- 插件可带 native so（`librarySearchPath` 指向插件目录 `lib/$abi`）。

### 2.4 构建期（build-logic/aar2apk）

插件 = 普通 `com.android.library` 模块（全部 `compileOnly`）→ 开发模式 Run 时自动注入宿主构建（原生级断点调试）；发布模式 AAR 升格为可安装 APK（带签名）。

---

## 三、审核发现的缺陷（重写改进靶点）

| # | 缺陷 | 代码位置 | 本方案对策 |
|---|---|---|---|
| 1 | 卸载插件时索引移除是 O(n) 全表扫描 | PluginLifecycleManager:333-343 | Rust 双向索引（pluginId→类集合）O(1) 移除 |
| 2 | 依赖图 Set 迭代序不确定 → 链式重启计划顺序每次运行可能不同 | DependencyManager:42-50 | 有序集合 + 拓扑排序，计划确定性可复现 |
| 3 | 类索引漂移只 log 不对账（索引指向未加载插件 / 目标 DEX 缺类两处） | DependencyManager:74-80, 89-94 | invariant 式三方对账（索引↔注册表↔已加载集）+ 修复报告 |
| 4 | 安装更新虽有 catch 回滚（删新目录→`backupDir.renameTo` 恢复），但**备份失败仅 warn 后继续安装**，且回滚恢复失败时插件丢失仅 log | InstallerManager:186-190, 236-249 | 真事务：新目录成功后原子切换，任何一步失败自动回滚并上报 ledger（含恢复失败的双错报告） |
| 5 | checkApiCaller 用 `com.combo.core.` 字符串前缀跳框架帧，插件可冒名 | PermissionExt:52 | 框架类白名单清单匹配 |

另记录两个取舍：`onLoad` 异常被吞（初始化崩了仍算加载成功）；写盘 renameTo 前无 fsync（断电丢最后 500ms 延迟写）。

### 2.5 补充审核发现（第二轮查漏）

1. **卸载是事务性的**（InstallerManager.uninstallPlugin）：先重命名插件目录 → 删除成功 → 再更新注册表，注释明确以原子性为目标。我们的 ledger 卸载事务可直接对标此设计并加 fsync。
2. **资源合并的挂载点在 Application 层**（BaseHostApplication 覆写 `getResources()`/`getAssets()`，未初始化时回退 super）——插件资源"全局可见"靠这一层覆写。**本方案取舍**：不覆写 Application 做全局合并（侵入宿主且资源冲突面大），改为**显式注入**（宿主把插件 Resources 经 PluginContext 交给插件），与我们的 Kernel 注入模型一致。
3. **接口查找有宿主回退**（PluginManager.getInterface:227-231）：类索引未命中时先尝试从宿主自身加载——跨插件服务定位的完整顺序是"索引 → 宿主回退"。topology.locate 需保留该语义。
4. **崩溃钩子必须最先初始化**（BaseHostApplication.onCreate 中 `PluginCrashHandler.initialize` 先于 `PluginManager.initialize`）——时序约束写入执行器设计。
5. **跨插件 UI 协作模式**（sample-plugin/home 的 Content()）：共享接口定义在 common 插件（compileOnly）→ Koin `inject()` 取实现 → `CompositionLocalProvider` 注入 Compose 语境。我们等价替换：接口由插件 SDK 定义 + ServiceKey 提供 + CompositionLocal 传递。这是 POC 必须验证的第二条链路（不止"插件出 UI"，还有"插件间 UI 协作"）。
6. **aar2apk 实为两个 Gradle 插件**：`Aar2ApkPlugin`（AarExtractor → ApkPackager → ApkSigner 发布链）与 `AppIntegrationPlugin`（开发期把插件注入宿主构建）。我们照此拆分：`jasmine-plugin-pack` 与 `jasmine-plugin-dev` 两个构建插件。
7. **DEX 扫描带 API 级别语义**：`DexFileFactory.loadDexContainer(apk, Opcodes.forApi(SDK_INT))`——按运行 API 选 opcode 集，扫描器实现需保留此参数。
8. **Service 代理池细节**：实例标识符 `类名:taskN`（前缀匹配恢复运行态）；池耗尽返回 null 无排队。代理池大小的容量规划写入宿主 Manifest 预注册清单。
9. **checkApiCaller 是手写样板侵入**：每个敏感 API 方法体首行都要写 `::method.javaMethod?.checkApiCaller()`——易漏。我们的改进：KSP 编译期自动织入检查（我们已有 KSP 插件索引先例）或统一网关入口。
10. **框架内嵌 Koin**（PluginManager.initialize 里 `startKoin`）：宿主若已有 Koin 会冲突。我们无此依赖（ServiceKey 自研），规避。

---

## 四、重写总体架构

### 4.1 职责划分原则

沿用既有下沉先例（invariants / pruner / surface-fold / token-estimator）的判据：
**纯数据、纯算法、无 Android API、要求崩溃安全与事务性 → Rust；依赖 JVM ClassLoader / Android Framework / Compose → Kotlin。**
跨 FFI 一律**计划式**：Rust 一次调用返回完整决策（计划/裁决/报告），Kotlin 执行，不做细粒度回调。

### 4.2 目标结构

```
Rust 侧（新 crate，与 store/session-log/measure 平级；首版单 crate 三模块）
  jasmine-plugin-core
    ├─ ledger     户口本：已装插件元数据、事务性注册/注销、原子持久化（tmp→fsync→rename→bak 轮换）
    ├─ topology   依赖拓扑：借类建边、受影响闭包、确定性链式重启计划（拓扑序）
    └─ charter    权限章程：L0-L4 等级 × 策略 × 声明权限的规则求值、签名白名单、版本策略（禁降级判定）

Kotlin 侧（现有结构上扩展，命名全新）
  core-kernel（现有 Kernel 之上）
    ├─ DEX 扫描器（dexlib2，loadDexContainer 带 Opcodes.forApi）：产出类清单 → 交 ledger 建索引
    ├─ PluginClassLoader 移植版：parent 委派 + topology 委托仲裁（走 Rust 查询，含宿主回退语义）
    ├─ 生命周期执行器：拿 topology 的重启计划，在现有 Fiber/dispose 机制上按序执行
    │    （崩溃钩子最先初始化，先于框架本体）
    ├─ 安装执行器：按 charter 裁决 + ledger 事务执行文件操作，回滚走 ledger 计划
    ├─ 授权 UI（JasmineBottomSheet 呈现 charter 裁决：允许/需用户授权/拒绝）
    └─ 敏感 API 检查：KSP 编译期自动织入（替代 ComboLite 每方法手写 checkApiCaller 的样板）
  资源模型：不覆写 Application 做全局合并，插件 Resources 经 PluginContext 显式注入
  UI 协作：插件 SDK 定义共享接口 + ServiceKey 提供 + CompositionLocal 传递（替代 Koin）
  事件打通：插件装卸事件进现有 EventBus → session-log
    → 现有 invariant 系统顺势获得"插件生命周期序列校验"能力

构建期（build-logic，对照 aar2apk 拆两个 Gradle 插件）
  ├─ jasmine-plugin-pack：AAR → APK 升格打包 + 签名（发布链）
  └─ jasmine-plugin-dev：开发期把插件模块注入宿主构建（源码级断点调试）
```

### 4.3 关键交互（计划式）

```
安装：Kotlin(文件落盘+DEX扫描+签名摘要) → charter.adjudicate(安装请求) → 裁决
      → ledger.commit(插件记录+类清单) → 事务持久化 → Kotlin 更新内存态
查找：PluginClassLoader.findClass 失败 → topology.locate(类名) → (目标插件, 依赖边)
      → Kotlin 从目标 ClassLoader 加载
更新：charter 检查版本 → topology.restart_plan(插件) → [确定性顺序的卸载/重载清单]
      → Kotlin 按计划执行，结果回报 ledger 对账
对账：定期/装卸后 audit() → 三方一致性报告（漂移即发现即修）
```

---

## 五、与 ComboLite 的映射及命名对照（全部换新，不复用原名）

| ComboLite | 本方案 | 归属 |
|---|---|---|
| XmlManager（plugins.xml） | ledger | Rust |
| DependencyManager（双向图+DFS） | topology | Rust |
| PermissionManager + 策略枚举 | charter（扩展为 L0-L4 五级） | Rust |
| class_index 存储与查询 | ledger 索引（双向+对账） | Rust |
| 版本禁降级判定 | charter | Rust |
| PluginClassLoader / 生命周期执行 / Koin装卸 | core-kernel 执行器 + ServiceKey | Kotlin |
| dexlib2 扫描 / PackageManager 签名 / 堆栈追踪 | core-kernel 适配层 | Kotlin |
| 资源合并（Application 覆写式） | 改为 PluginContext 显式注入 | Kotlin（改设计） |
| 四大组件代理 | core-kernel 代理层（首版可裁剪） | Kotlin |
| 授权/崩溃 UI | core-ui + JasmineBottomSheet | Kotlin |
| aar2apk（Aar2Apk + AppIntegration 两插件） | jasmine-plugin-pack + jasmine-plugin-dev | Kotlin（Gradle） |
| 每方法手写 checkApiCaller | KSP 编译期自动织入 | Kotlin（改进） |

---

## 六、实施阶段

1. **POC（先决验证）**：独立模块编译一个实现插件接口 + `@Composable Content()` 的样例插件（全部 compileOnly）→ 宿主验签 → 只读落盘 → DexClassLoader（parent 委派）→ 注册进现有 Kernel → 设置页渲染插件 UI。**同时验证第二条链路**：跨插件 UI 协作（共享接口 + ServiceKey + CompositionLocal，对照 2.5-5 的模式）。真机跑通即宣告 Compose 动态插件链路成立。
2. **最小闭环**：charter（安装裁决+签名白名单）+ ledger（注册表事务+类索引）+ 执行器，支持 assets 内置插件的安装/加载/卸载。
3. **拓扑与热更新**：topology 建边/闭包/重启计划 + 对账审计；网络更新通道。
4. **生态迁移**：内置能力（Model Provider、AgentLoop、MCP/Skill）逐步改造为动态插件，验证"Everything is a Plugin"替换性。

## 七、风险与边界（承自前次研究，继续有效）

- **Compose/Kotlin ABI 契约**：插件 SDK 锁版本（当前 Kotlin 2.4.10 / Compose BOM 2026.08），加载时校验插件 SDK 版本；
- **同进程安全（L1）**：签名验证必须先于加载；L4 独立进程隔离列为远期；
- **DEX 扫描无 Rust 等价物**：dexlib2 留 Kotlin，本方案已按此切分；
- **Android 边界**：Manifest/权限声明/四大组件均按《插件化架构开发方案》的 Host 代理模型处理，不越界。

---

## 附：ComboLite 关键源码索引（本地审读路径）

- 类加载与委托：`ComboLite/comboLite-core/.../runtime/loader/{PluginClassLoader,DependencyManager,IPluginFinder}.kt`
- 安装与注册表：`.../runtime/installer/{InstallerManager,XmlManager}.kt`
- 生命周期与链式重启：`.../runtime/lifecycle/PluginLifecycleManager.kt`
- 安全三件套：`.../security/{signature/SignatureValidator,permission/*,auth/*}.kt`
- 门面与上下文：`.../runtime/PluginManager.kt`、`.../model/PluginFrameworkContext.kt`
- 官方架构文档：`ComboLite/docs/5_ARCHITECTURE_ZH.md`
