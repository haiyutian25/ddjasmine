# 插件化框架 Rust 重写方案 —— 基于 ComboLite 代码级审核

> 状态：方案设计 v3（第三轮：对 ComboLite 源码逐条核对后勘误，缺陷增至 6 个，新增 12 项遗漏补遗）
> 实施：Rust 侧已落地——`rust/plugin-core`（ledger/topology/charter/dispatch 四模块）+ `store::atomic_write`
> + ffi 导出 `PluginCoreHandle` 计划式 API；147 项工作区测试通过，clippy 0 警告，fmt 干净。
> Kotlin 侧执行壳已落地——`android/core-plugin`（PluginHost 门面 / InstallExecutor / PluginClassLoader /
> LifecycleExecutor / 四大代理 / CrashHook / 资源双路径加载 / 更新通道 / 默认授权 UI / PluginHostApplication），
> 绑定走独立包 `com.lhzkml.jasmine.core.plugin.rust`。宿主接线零成本：代理组件在库 manifest 预注册自动合并。
> 示例插件 `:sample-plugin` 与 app 端到端接线完成（assets 打包 + 首启安装 + 自动加载，KSP 织入已生成
> `HelloHostApiGated` 验证通过）。构建插件 `:build-logic`（jasmine.plugin-pack：AAR→APK 五步链含
> `--package-id 0x80+N` 分区；jasmine.plugin-dev：开发期注入宿主）与 KSP 处理器 `:core-plugin-ksp` 已落地。
> **三处已全部接入 app**：app 应用 `jasmine.plugin-dev`（packModules=:sample-plugin，插件经 pack 管线产出后
> 生成式注入 assets/plugins）；app 挂 `ksp(project(":core-plugin-ksp"))`；更新通道以占位地址
> `https://updates.example.com/jasmine` 在启动就绪后触发检查（失败不致命）。
> `:app:assembleRelease` BUILD SUCCESSFUL，发布包 `app/build/outputs/apk/release/app-release.apk`（占位
> debug 签名，含 `assets/plugins/sample-plugin.apk`）。
> 落地后已做第四轮字段级对照（见 2.7），签名语义/记录字段/依赖链查询三处偏差已修正。
> 依据：《插件化架构开发方案》《插件化架构-插件的ui解决方案》（见 `插件化架构开发方案/`）
> 参考实现：ComboLite v2.0（本地 `ComboLite/`，Apache-2.0，只读参考，重写不复用其代码）
> 审核范围：comboLite-core 全部 51 个 Kotlin 文件的核心路径 + build-logic + sample-plugin 插件侧写法，三轮审读（第三轮为逐条源码核对）

---

## 一、目标与结论

**目标**：以 ComboLite 的架构为概念蓝本，结合本项目已有的 Rust 固定层与 Kotlin 插件内核，重写一套命名与结构全新的插件化框架。Rust 承接框架的"大脑"（状态、图、规则、事务），Kotlin 保留"手脚"（ClassLoader、Android 边界、UI）。

**结论**：可行，且是升级而非平移。ComboLite 用 Kotlin 并发原语手写的四个状态机（注册表、双向依赖图、类索引、权限规则）恰好是 Rust 类型系统与事务性模式的强项；审核发现其六个具体缺陷，在本方案中均有明确解法。

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
- `initState: StateFlow`；注意 `validationStrategy` 只是普通 `var`、非 StateFlow（PluginFrameworkContext:48）

### 2.2 五大核心机制（实现要点）

1. **安装流水线**（InstallerManager:133-253）：checkApiCaller 门禁 → 存在性 → 元数据验证（PackageManager 读 meta-data 的 `plugin.entryClass`）→ 签名策略裁决（Strict / UserGrant / Insecure）→ 版本检查（禁降级，`forceOverwrite` 可跳过）→ 清 dex_opt 缓存（仅 API<26）→ 旧目录备份 → 复制 APK（含长度校验）+ `setReadOnly()`（Android 14+ DCL 合规）→ 按 ABI 解压 native so → dexlib2 扫描生成 class_index → 解析静态 Receiver/Provider → 写 plugins.xml（enabled 状态跨升级保留，InstallerManager:220）。
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

插件 = 普通 `com.android.library` 模块（全部 `compileOnly`）。

- **发布链**（ConvertAarToApkTask:108-200）：AarExtractor → **ResourceProcessor（aapt2，给第 N 个插件模块分配 `--package-id 0x80+N`，宿主保留 0x7f，插件上限 128 个，ID 与模块声明顺序绑定，运行时零重映射——资源 ID 冲突的构建期解法）** → DexProcessor（d8）→ ApkPackager → ApkSigner。
- **开发链**：AppIntegrationPlugin 在 onVariants 中让宿主变体依赖 `buildAll{Debug,Release}PluginApks`，PreparePluginAssetsTask 拷贝后经 `addGeneratedSourceDirectory` 挂进宿主 `assets/plugins`（AppIntegrationPlugin:37-85），Run 即注入宿主构建，原生级断点调试。

---

## 三、审核发现的缺陷（重写改进靶点）

| # | 缺陷 | 代码位置 | 本方案对策 |
|---|---|---|---|
| 1 | 卸载插件时索引移除是 O(n) 全表扫描 | PluginLifecycleManager:333-343 | Rust 双向索引（pluginId→类集合）O(1) 移除 |
| 2 | 依赖图 Set 迭代序不确定 → 链式重启计划顺序每次运行可能不同 | DependencyManager:42-50 | 有序集合 + 拓扑排序，计划确定性可复现 |
| 3 | 类索引漂移只 log 不对账（索引指向未加载插件 / 目标 DEX 缺类两处） | DependencyManager:74-80, 89-94 | invariant 式三方对账（索引↔注册表↔已加载集）+ 修复报告 |
| 4 | 安装更新虽有 catch 回滚（删新目录→`backupDir.renameTo` 恢复），但**备份失败仅 warn 后继续安装**，且回滚恢复失败时插件丢失仅 log | InstallerManager:186-190, 236-249 | 真事务：新目录成功后原子切换，任何一步失败自动回滚并上报 ledger（含恢复失败的双错报告） |
| 5 | checkApiCaller 用 `com.combo.core.` 字符串前缀跳框架帧，插件可冒名；且调用方识别失败（如宿主直接调用）**直接放行** | PermissionExt:56, 52-63 | 框架类白名单清单匹配；"宿主调用放行"语义保留但改为显式分支，不吃识别失败 |
| 6 | Service 代理池前缀匹配无冒号边界：`startsWith(serviceClassName)` 恢复运行实例，类名 `Foo` 会误匹配 `FooBar:task3`，可能把他插件 Service 实例错配给调用方 | ProxyManager:193-197 | 实例标识精确匹配（`id == "$className:$n"`），topology 记录实例归属 |

另记录两个取舍：`onLoad` 异常被吞（初始化崩了仍算加载成功，PluginLifecycleManager:266-275；`onUnload` 277-284 与 Koin 模块加载 286-296 同样吞掉——三处在本方案全部改为失败上报并计入 ledger）；写盘 renameTo 前无 fsync（且 XmlManager 是 HandlerThread 500ms 延迟合写，XmlManager:93,126-145，断电丢最后 500ms 延迟写）。

### 2.5 补充审核发现（第二轮查漏）

1. **卸载是事务性的**（InstallerManager.uninstallPlugin）：先重命名插件目录 → 删除成功 → 再更新注册表，注释明确以原子性为目标。我们的 ledger 卸载事务可直接对标此设计并加 fsync。
2. **资源合并的挂载点在 Application 层**（BaseHostApplication 覆写 `getResources()`/`getAssets()`，未初始化时回退 super）——插件资源"全局可见"靠这一层覆写。**本方案取舍**：不覆写 Application 做全局合并（侵入宿主且资源冲突面大），改为**显式注入**（宿主把插件 Resources 经 PluginContext 交给插件），与我们的 Kernel 注入模型一致。
3. **接口查找有宿主回退**（PluginManager.getInterface:227-231）：类索引未命中时先尝试从宿主自身加载——跨插件服务定位的完整顺序是"索引 → 宿主回退"。topology.locate 需保留该语义。
4. **崩溃钩子必须最先初始化**（BaseHostApplication.onCreate 中 `PluginCrashHandler.initialize` 先于 `PluginManager.initialize`）——时序约束写入执行器设计。
5. **跨插件 UI 协作模式**（sample-plugin/home 的 Content()）：共享接口定义在 common 插件（compileOnly）→ Koin `inject()` 取实现 → `CompositionLocalProvider` 注入 Compose 语境。我们等价替换：接口由插件 SDK 定义 + ServiceKey 提供 + CompositionLocal 传递。这是 POC 必须验证的第二条链路（不止"插件出 UI"，还有"插件间 UI 协作"）。
6. **aar2apk 实为两个 Gradle 插件**：`Aar2ApkPlugin`（发布链，完整五环节见 2.4）与 `AppIntegrationPlugin`（开发期把插件注入宿主构建）。我们照此拆分：`jasmine-plugin-pack` 与 `jasmine-plugin-dev` 两个构建插件。
7. **DEX 扫描带 API 级别语义**：`DexFileFactory.loadDexContainer(apk, Opcodes.forApi(SDK_INT))`——按运行 API 选 opcode 集，扫描器实现需保留此参数。
8. **Service 代理池细节**：实例标识符 `类名:taskN`（前缀匹配恢复运行态，冒号边界缺陷见缺陷表 #6）；池耗尽返回 null 无排队。代理池大小的容量规划写入宿主 Manifest 预注册清单。
9. **checkApiCaller 是手写样板侵入**：每个敏感 API 方法体首行都要写 `::method.javaMethod?.checkApiCaller()`——易漏。我们的改进：KSP 编译期自动织入检查（我们已有 KSP 插件索引先例）或统一网关入口。
10. **框架内嵌 Koin**（PluginManager.initialize 里 `startKoin`，PluginManager:138）：宿主若已有 Koin，GlobalContext 重复注册抛异常致初始化失败。我们无此依赖（ServiceKey 自研），规避。

### 2.6 第三轮核对：源码逐条验证补遗（v3 新增）

第三轮对 51 个 Kotlin 文件逐条核对，前述论断全部属实（个别行号微调已就地修正），另补 12 项遗漏：

1. **资源加载双路径**：Android 11+ 用 `ResourcesProvider.loadFromApk`（PluginResourcesLoader:55-62），11 以下反射 `addAssetPath` 并整体 `new Resources`、卸载时全量重建（PluginResourcesManager:158-190, 250-276）。Kotlin 执行壳需复刻两套。
2. **资源 ID 冲突在构建期解决**：aapt2 `--package-id 0x80+N`（见 2.4）。本方案"显式注入"取舍不变，但**只要插件带资源就必须回答 ID 冲突**：照搬到 jasmine-plugin-pack（构建期 package-id 分区），运行时零重映射。
3. **更新通道无完整性校验**：`updates/plugins.json` 仅 id/versions/downloadUrl/changelog，**无 hash 字段**，完整性全靠安装期签名兜底。charter 强化：网络更新包必须带 SHA-256 摘要，裁决时校验。
4. **XmlManager 持久化细节**：filesDir/plugins.xml + .tmp/.bak，XmlPullParser 序列化，tmp→bak→rename 原子替换但无 fsync；`flushToDisk` 由安装/卸载/setPluginEnabled 显式触发。ledger 决策：**事务点同步写 + fsync，放弃 500ms 延迟合写**换崩溃安全。
5. **授权无缓存**：DefaultAuthorizationHandler:36-70 每次请求都起 AuthorizationActivity，靠 `ACTION_AUTHORIZATION_RESULT` 广播 + 随机 requestCode 恢复挂起续传；hardFail 直接拒绝不进 UI（AuthorizationManager:71-74）。charter 增加**会话级裁决缓存**（同插件同权限缓存结果，卸载即失效）。
6. **native so 无 ABI 回退**：安装期按 `Build.SUPPORTED_ABIS` 匹配解压（InstallerManager:656-677），加载时只取 `SUPPORTED_ABIS[0]` 拼 librarySearchPath（PluginLifecycleManager:214-226）。本方案改为按 ABI 列表序回退。
7. **静态组件解析手段不一**：Receiver 用反射 AssetManager + `openXmlResourceParser` 解析二进制 Manifest（InstallerManager:474-480），Provider 用 `getPackageArchiveInfo(GET_PROVIDERS)`（584-587）；注册/注销挂在 loadPlugin/unloadPlugin（PluginLifecycleManager:206-211, 86-87）。安装执行器需复刻两条解析路径。
8. **广播分发安全语义**：匹配只比 action/categories/scheme；`exported=false` 仅放行 package==宿主包名的内部广播（ProxyManager:245-266）。~~代理层重写时原样保留~~——已下沉 dispatch（2.9）。
9. **Activity 代理具体参数**：全局仅一个 HostActivity，未设 launchMode/process（默认 standard 单进程共栈），Intent 键 `plugin_activity_class_name`（Extensions:44）。首版"单占坑"照此参数实现。
10. **Service 池容量 = 宿主手工规划**：sample 预注册 HostService1..10（Manifest:73-102），无 `android:process`，不支持多进程/多 task。容量规划写入宿主 Manifest 预注册清单（同 2.5-8）。
11. **热更重启范式**（sample 侧，非 core）：`installPlugin(forceOverwrite=true)` + `launchPlugin` 热更；整框架重启用 `makeRestartActivityTask` + `killProcess`（PluginUpdateViewModel:107-150）。执行器设计参考。
12. **类查找委托链完整顺序**（执行器验收基准）：父 ClassLoader（宿主，PluginLifecycleManager:224）→ 自身 dex（PluginClassLoader:68）→ 查 classIndex → 目标插件 `findClassLocally`（防递归，DependencyManager:61-95）→ 抛 `PluginDependencyException`（PluginClassLoader:74-78）。与 2.2-2 一致。

### 2.7 第四轮对照：落地实现 vs 源码的字段级核查（v3 增补）

Rust 落地后对 ComboLite 数据形状（XmlManager 持久化字段、PluginInfo、SignatureValidator、PluginManager API 清单）做了字段级对照，修正三处偏差：

1. **签名是集合子集语义，不是单值比较**：`hostSignatures.containsAll(pluginSignatures)`，多签名 APK 取 `apkContentsSigners`（SignatureValidator:108-161，SHA-256 小写 hex）。Strict 裁决的对象是"插件签名集 ⊆ 宿主信任集"。charter 落地为：`is_host_trusted`（空签名集永不受信）+ 两道独立签名闸（更新连续性：新旧签名集一致；宿主信任：子集覆盖），`force_overwrite` 只跳降级闸、不跳签名闸（InstallerManager:165 vs 703-740）。
   > 第五轮亲读更正：ComboLite 安装时**没有**新旧签名连续性检查（`checkSignatureAndAuthorize` 只对宿主信任集裁决，InstallerManager:703-740）——连续性闸是我们**新增的**加固（对齐 Android 自身的更新规则），不是对其既有行为的复刻。另：ComboLite 不持久化签名（plugins.xml 无此字段），每次权限检查实时读 APK（PermissionManager:90-96）；我们把签名摘要持久化进 ledger（安装期验证过的既定事实，base.apk 只读），免去每次磁盘读取。
2. **持久化记录字段补全**：ComboLite 的 PluginInfo 含 `name/iconResId/entryClass/description/staticReceivers/providers`（PluginInfo:22-35）。其中 `entryClass` 是加载必需品（生命周期执行器实例化入口类时从记录取，不可能加载时再解析 APK）；receivers/providers 是代理注册数据源。PluginRecord 落地为全字段，receivers/providers 以 JSON 字符串携带（ledger 不透明理解，代理层消费）。
3. **依赖链双向可查**：`getPluginDependentsChain`/`getPluginDependenciesChain`（PluginManager:290/299）——topology 落地为 dependents（affected_closure）+ dependencies（dependencies_closure）两个方向，FFI 均导出。

另确认无需修正项：plugins.xml 的 tmp→bak→rename 已有 store::atomic_write 等价覆盖；crash 归因（沿 cause 链逐帧查索引）与 KSP 织入所需数据均已就绪；对外 suspend API 的 HOST/SELF 注解矩阵与 charter 的 AccessRule 一一对应。

### 2.8 第五轮对照：亲读源码 vs 落地实现的运行语义核查（v3 增补）

对 InstallerManager / PluginLifecycleManager / DependencyManager / PluginClassLoader / PermissionExt / PermissionManager / PluginManager / XmlManager / ProxyManager 逐文件亲读后，修正四处运行语义偏差（均已落地并带测试）：

1. **安装时间不随更新重置**：`installTime = existingPlugin?.installTime ?: now`（InstallerManager:221）——ledger `commit_install` 更新时保留原 `installed_at_ms`（此前只保留了 enabled）。
2. **禁用插件的类不可定位**：ComboLite 的类索引是加载期从 `class_index` 文件重建的内存结构（PluginLifecycleManager:310-331），只含已加载插件；`findClass` 对"索引指向未加载插件"直接失败（DependencyManager:73-80）。我们的索引持久化含全部已装插件——`locate_class` 落地为对 `enabled=false` 的记录返回宿主回退（等价于不可达），Kotlin 执行器另查已加载态。
3. **依赖链查询排除自身**：`findDependentsRecursive`/`findDependenciesRecursive` 显式移除起点（DependencyManager:150-166）——`dependents_chain`/`dependencies_chain` 落地为排除自身（restart_plan 保留根）。
4. **访问检查校验调用方在册且启用**：`getPluginInfo(callingPluginId) ?: return false`（PermissionManager:67-68，数据源是 loadedPlugins，PluginManager:265-267）——facade 的 `check_api_access` 落地为：自称未注册/已禁用插件的调用方一律按"未识别"处理（走询问或拒绝，不吃规则误判）。

执行器（Kotlin 侧）必须复刻的运行语义（写入执行器设计备忘）：
- **批量加载是全有或全无**：`loadAndInstantiatePlugins` 并行加载+实例化，任一失败整批回滚卸载（PluginLifecycleManager:155-193）；已加载插件上调 `launchPlugin` 即触发链式重启（:48-53）。
- **receiver/provider 注册过滤组件级 enabled**（:206-211）；广播匹配：action 精确、categories 包含全集、scheme 空表即通配，`exported=false` 仅放行 package==宿主包名（ProxyManager:239-266）。
- **`setPluginEnabled` 只改注册表不卸载运行中插件**（PluginManager:310-327）——禁用生效于下次进程启动，CrashActivity 正是靠重启生效。
- **卸载幂等性差异**：ComboLite 目录与记录都不在时返回 true（InstallerManager:279-287）；我们的 `commit_uninstall` 对未知插件报错（严格语义，Kotlin 执行器先做文件侧判断）。
- **权限检查的签名取自实时 APK** vs 我们取自 ledger 记录——已选定后者（见 2.7-1 更正注）。

### 2.9 第六轮：第二轮下沉——dispatch 模块（已落地）

亲读 ProxyManager / PluginCrashHandler / PluginResourcesManager / PluginResourcesLoader / AuthorizationManager / utils/Extensions 后，将三个纯算法机制下沉为 `plugin-core/src/dispatch.rs`（145 项工作区测试通过，FFI 已导出）：

1. **广播意图过滤器匹配**：下沉语义收窄的 Android filter match——action 精确、categories 子集、scheme 空表通配/非空成员（ProxyManager:245-266）；**修正 2.6-8 的错误描述**：实际比对的是 `categories` 不是 `dataType`（mime type 未参与）。宿主与代理共用一份 Rust 实现，避免两处 Android match 调用漂移。
2. **Provider authority 路由**：authority→类、类→属主双向映射（ProxyManager:275-288），卸载时级联清理。
3. **崩溃归因与分类**：cause 链逐帧查类索引归因（PluginCrashHandler:123-148）+ 分类优先级（Dependency→ClassCast→ResourceNotFound→NoSuchMethod/Field/AbstractMethod→Other，:195-207）；**Dependency 异常无插件归因也分类**（:169-178），`PluginDependencyException` 类本身属 Kotlin，跨界只传罪魁 id + 缺失类名。异常类名匹配用二分名精确匹配（含 `$NotFoundException` 内部类形式），避免裸字符串误判。
4. **ffi 防御性设计**：`unwrap_or_else(throwable = ...)`（Extensions:16-19）在 Rust 无对应物（catch_unwind 跨 JNI 未定义行为），约定 Kotlin 包装层托管。

本轮评估后**明确留 Kotlin**（不移植）的结论：资源合并反射/ResourcesProvider 双路径（纯 Android API，PluginResourcesManager:250-276）、checkApiCaller 堆栈归因（StackWalker 框架前缀匹配是 JVM 绑定，被 KSP 织入替代）、授权 Activity/广播续传（UI 生命周期）、DexClassLoader/PackageParser/dexlib2（平台绑定）。

### 2.10 第七轮：51 文件完整通读收尾（v3 增补）

对 model/api/exception/component/security 接口层/BaseHostApplication/XmlManager 全文/build-logic 逐文件亲读后，一处修正落地、五处备忘入库：

**修正（已落地 + 测试）**：
1. **损坏回退缺失**：XmlManager 的 `.bak` 是**永久保留**的损坏兜底——主文件解析失败时从备份恢复并重写主文件（XmlManager:443-476），此前我们的 atomic_write 成功后删 bak、主文件损坏即报错无回退。已改：atomic_write 保留 bak（last-known-good），新增 `store::read_backup`，`Ledger::open` 主文件解析失败 → 回退 bak → 立即重写主文件；主备皆毁时报错（不静默清空，优于 XmlManager:471-475 的"初始化空缓存"）。

**备忘（Kotlin 执行器/构建插件设计输入）**：
2. **宿主信任集须含密钥轮换历史**：单签名 APK 取 `signingCertificateHistory`（SignatureValidator:142）——Kotlin 侧构造 charter 时传入的宿主签名集必须包含历史证书，否则轮换过密钥的宿主会误判自己的旧签名插件。
3. **Provider URI 重写规则**（BaseHostProvider:55-94, 110-124）：代理 URI 首段是 URL 编码的插件 authority；重写 = 去首段 + 恢复原始 authority + 清 query/fragment；insert 结果 URI 反包装（宿主 authority + `/<插件authority>` 前缀路径）；exported=false 用 `Binder.getCallingUid() != myUid` 拒绝。规则为纯字符串算法但体量小且 Uri API 绑定，**留 Kotlin**，按此规格复刻。
4. **Activity 代理初始化吞异常**：BaseHostActivity.initPluginActivity 失败仅 printStackTrace、pluginActivity=null（宿主 Activity 空白存活）——执行器改为显式失败上报。
5. **构建插件 DSL 备忘**：PackagingOptions 默认最小化（includeDependenciesRes/Dex/Assets/Jni 全 false，Aar2ApkExtension:66-72）；aapt2 link 参数 `--auto-add-overlay --no-version-vectors --no-static-lib-packages` + `.flat` 列表走 response file（ResourceProcessor:158-173）。
6. **XmlManager add/update 语义差异**：addPlugin 重复抛异常、updatePlugin 缺失抛异常（:686-721）；我们的 `commit_install` 统一 upsert，由执行器在安装流程中分流（新装/更新已由存在性检查区分）。

**通读确认无新增下沉项**：api 四接口（IPluginEntryClass/IPluginActivity/IPluginService/IPluginReceiver）与 BasePluginActivity/BasePluginService 是纯生命周期契约；AuthorizationActivity/DefaultAuthorizationHandler 的 requestCode+广播续传是 UI 生命周期；CrashActivity 重启流程已入 2.6-11；ui/ 10 个 Compose 屏幕为纯展示层（宿主可经 IAuthorizationHandler 整体替换，不重写）；ProviderInfo.metaData 由 providers_json 不透明携带。

### 4.1 职责划分原则

沿用既有下沉先例（invariants / pruner / surface-fold / token-estimator）的判据：
**纯数据、纯算法、无 Android API、要求崩溃安全与事务性 → Rust；依赖 JVM ClassLoader / Android Framework / Compose → Kotlin。**
跨 FFI 一律**计划式**：Rust 一次调用返回完整决策（计划/裁决/报告），Kotlin 执行，不做细粒度回调。

### 4.2 目标结构

**工作区整合原则（对照 `rust/` 现状核定）**：现有 7 成员（store / session-log / measure / compose / codec / ffi / uniffi-bindgen）是会话日志数据脊的正交域，**零删除、零改动语义**；插件框架核心作为新域平级加入，完全照搬现有模块化约定——域 crate 纯 Rust 不依赖 uniffi、`ffi` 单窗口导出、多文件模块拆分、Arc+Mutex 所有权纪律、计划式粗调用、JSON 字符串传不透明载荷、thiserror→uniffi::Error。

```
Rust 侧（`rust/` 工作区新增 1 成员，命名随现有短名约定）
  plugin-core（新 crate；纯 Rust + thiserror；模块文件对照 session-log/src/*.rs 模式）
    ├─ ledger.rs    户口本：已装插件元数据、事务性注册/注销、双向类索引与对账、
    │               原子持久化复用 store 的 atomic-write（tmp→fsync→rename→bak 轮换）
    ├─ topology.rs  依赖拓扑：借类建边、受影响闭包、确定性链式重启计划（拓扑序）、
    │               locate 含宿主回退语义、Service 池实例归属记录（精确匹配，缺陷 #6 对策）
    ├─ charter.rs   权限章程：L0-L4 等级 × 策略 × 声明权限的规则求值、签名白名单、版本策略（禁降级判定）、
    │               更新包 SHA-256 摘要校验（补 ComboLite 无 hash 之缺）、会话级授权裁决缓存（卸载即失效）
    └─ dispatch.rs  代理分发纯算法：广播意图过滤器匹配（宿主/代理共用一份）、
                    provider authority 路由、崩溃归因与分类（cause 链逐帧查类索引 + 分类优先级）
  store（既有成员顺势扩展，落地路线图 P2-6 已规划的 atomic-write 小随迁）
    └─ atomic-write 原语：tmp→fsync→rename→bak 轮换，供 ledger 复用（session-log 后续也可用）
  ffi（唯一 UniFFI 窗口，既有模式原样沿用）
    └─ 加 `plugin-core = { path = "../plugin-core" }` 依赖，导出计划式 API：
       FfiPluginRecord 等全为 Record；adjudicate / commit / locate / restart_plan / audit
       一次调用返回完整决策；错误经 thiserror→uniffi::Error 单枚举跨边界。
       Kotlin 仍 import com.lhzkml.jasmine.rust 同一包，core-data 的
       cargo→uniffi-bindgen 生成管线零改动，无新增 .so、无第二套绑定。

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
    （加载双路径：API 30+ ResourcesProvider.loadFromApk / 以下反射 addAssetPath；
    ID 冲突构建期解决：jasmine-plugin-pack 内 aapt2 --package-id 0x80+N 分区，宿主保留 0x7f，上限 128 个）
  UI 协作：插件 SDK 定义共享接口 + ServiceKey 提供 + CompositionLocal 传递（替代 Koin）
  事件打通：插件装卸事件进现有 EventBus → session-log
    → 现有 invariant 系统顺势获得"插件生命周期序列校验"能力

构建期（build-logic，对照 aar2apk 拆两个 Gradle 插件）
  ├─ jasmine-plugin-pack：AAR → APK 升格打包（aapt2 package-id 分区 → d8 → 打包 → 签名，全链对照 2.4）
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
| 资源合并（Application 覆写式）+ aapt2 package-id 分区 | PluginContext 显式注入 + 保留构建期 0x80+N 分区 | Kotlin（改设计）+ Gradle |
| 四大组件代理 | core-kernel 代理层（首版可裁剪） | Kotlin |
| 授权/崩溃 UI | core-ui + JasmineBottomSheet | Kotlin |
| aar2apk（Aar2Apk + AppIntegration 两插件） | jasmine-plugin-pack + jasmine-plugin-dev | Kotlin（Gradle） |
| 每方法手写 checkApiCaller | KSP 编译期自动织入 | Kotlin（改进） |

---

## 六、实施阶段

1. **POC（先决验证）**：独立模块编译一个实现插件接口 + `@Composable Content()` 的样例插件（全部 compileOnly）→ 宿主验签 → 只读落盘 → DexClassLoader（parent 委派）→ 注册进现有 Kernel → 设置页渲染插件 UI。**同时验证第二条链路**：跨插件 UI 协作（共享接口 + ServiceKey + CompositionLocal，对照 2.5-5 的模式）。真机跑通即宣告 Compose 动态插件链路成立。
2. **最小闭环**：`plugin-core` 三模块（charter 安装裁决+签名白名单+摘要校验 / ledger 注册表事务+类索引，复用 store atomic-write）+ ffi 导出 + 执行器，支持 assets 内置插件的安装/加载/卸载。
3. **拓扑与热更新**：topology 建边/闭包/重启计划 + 对账审计；网络更新通道（带 SHA-256 摘要，补 ComboLite 无 hash 之缺）。
4. **生态迁移**：内置能力（Model Provider、AgentLoop、MCP/Skill）逐步改造为动态插件，验证"Everything is a Plugin"替换性。

## 七、风险与边界（承自前次研究，继续有效）

- **Compose/Kotlin ABI 契约**：插件 SDK 锁版本（当前 Kotlin 2.4.10 / Compose BOM 2026.08），加载时校验插件 SDK 版本；
- **同进程安全（L1）**：签名验证必须先于加载；L4 独立进程隔离列为远期；
- **DEX 扫描无 Rust 等价物**：dexlib2 留 Kotlin，本方案已按此切分；
- **Android 边界**：Manifest/权限声明/四大组件均按《插件化架构开发方案》的 Host 代理模型处理，不越界。
- **工作区纪律**（承 RUST-SINKING-ROADMAP"实现纪律"）：`plugin-core` 落地时全量 `cargo test --workspace` + clippy 0 警告 + fmt 干净；完成即更新路线图状态标记与 `rust/` README；FFI 面只传数据不传行为续延（判据同 §4.1）。

---

## 附：ComboLite 关键源码索引（本地审读路径）

- 类加载与委托：`ComboLite/comboLite-core/.../runtime/loader/{PluginClassLoader,DependencyManager,IPluginFinder}.kt`
- 安装与注册表：`.../runtime/installer/{InstallerManager,XmlManager}.kt`
- 生命周期与链式重启：`.../runtime/lifecycle/PluginLifecycleManager.kt`
- 安全三件套：`.../security/{signature/SignatureValidator,permission/*,auth/*}.kt`
- 门面与上下文：`.../runtime/PluginManager.kt`、`.../model/PluginFrameworkContext.kt`
- 资源加载：`.../runtime/resource/{PluginResourcesManager,PluginResourcesLoader}.kt`
- 代理与分发：`.../runtime/proxy/ProxyManager.kt`、`.../component/receiver/BaseHostReceiver.kt`、`.../component/provider/BaseHostProvider.kt`
- 崩溃熔断：`.../crash/{PluginCrashHandler,CrashActivity}.kt`（归因=堆栈帧查 classIndex，PluginCrashHandler:195-207；熔断=killProcess+exitProcess(10)，182-193；CrashActivity:76-90 重启前 setPluginEnabled(false) 禁用肇事插件）
- 发布链：`ComboLite/build-logic/.../tasks/ConvertAarToApkTask.kt`、`.../AppIntegrationPlugin.kt`
- 更新通道样例：`ComboLite/updates/plugins.json`、`sample-plugin/common/.../update/UpdateManager.kt`
- 跨插件 UI 协作样例：`sample-plugin/home/.../PluginEntryClass.kt:47-55`（Koin inject + CompositionLocalProvider）
- 官方架构文档：`ComboLite/docs/5_ARCHITECTURE_ZH.md`
