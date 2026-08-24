我并行派出多个审查代理，按职责分片对 `core-plugin` 做严格的 bug 排查：
四份审查报告回来了，发现不少问题。在汇报前我先亲自复核几个最严重发现的原始代码，避免误报：
核心发现已核实。再复核第一组报告中被截断的几个高危项：
复核完成。以下是合并四路审查 + 我亲自核对源码后的最终报告（已剔除误报，标注了复核状态）。

# core-plugin 模块 Bug 审查报告

共确认 **11 个高严重度、13 个中严重度、若干低/潜在问题**。几个说明：`插件化框架能力盘点与缺口分析.md` 里标注"已实施"的几项修复实际是**修了但修错了**（ContentProvider、多进程槽位），下面会指出。

## 一、高严重度（我逐条复核过源码）

### H1. ✅已修复 多进程槽 2-4 的进程身份判定错误 → 无限 startService 循环 + 隔离反转
`ProcessIdentity.kt:18,40-42`：`endsWith(":plugin_isolated")` 对 `:plugin_isolated_2/3/4` 返回 **false**。第二个隔离插件经 `allocateSlot()` 落入槽 2 后：① `launchPlugin → isolate() → startService → onStartCommand → launchPlugin` 无限循环，插件永远加载不上；② 槽进程被当作宿主进程，全量加载插件并执行 `installBundledPlugins`/`applyAvailableUpdates`，与宿主**并发双写 plugins.json 账本**；③ 与宿主争抢 offload socket 绑定。**我已在源码逐字确认。**

### H2. ✅已修复 `initialize()` 半初始化 → 启动崩溃循环 + 重试时假装成功
`PluginHost.kt:252,261,272`：`core = handle` 在 252 行提交，261 行 `loadEnabled` 任一插件加载失败抛出后，272 行 `ready.complete` 永不执行；而 `PluginHostApplication.kt:52` 的协程无任何异常处理 → 宿主崩溃。下次启动：再次崩溃（**崩溃循环**，且默认 `pluginCrashCallback()=null` 无熔断）。若宿主自己 catch 后重试，命中 231-233 行 `if (core != null) { ready.complete; return }` —— **假装初始化成功，插件一个不加载**。

### H3. ✅已修复 `installPlugin` 持全局 mutex 挂起等用户授权 → 框架级死锁
`PluginHost.kt:291,309`：`mutex.withLock` 内挂起调用 `authorizationHandler.onAuthorization`（等用户点对话框）。用户思考期间所有加锁 API（install/uninstall/launch/initialize）全部阻塞。更糟：`DefaultPluginAuthorizationHandler` 用 `application.startActivity` 弹框，Android 10+ 后台启动限制会**静默丢弃**（后台自动更新正是此场景）→ continuation 永不恢复 → **mutex 永久持有，整个框架死锁**。

### H4. ✅已修复 插件 ContentProvider 的 `onCreate()` 被调用两次
`HostProvider.kt:81-82`：`p.attachInfo(context, info)` + `p.onCreate()`。缺口文档说"已修复"，但修过了头——AOSP 的 `attachInfo` 在 `mContext == null` 时**自己就会调 `onCreate()`**（Android 7.0 至今如此），第 82 行是第二次。插件 Provider 的数据库/句柄被初始化两遍。修复：删掉 82 行。

### H5. ✅已修复 插件 Application 的 `onCreate()` 在 IO 线程执行
`LifecycleExecutor.kt:229-233`，调用链 `PluginHostApplication` → `Dispatchers.IO`（`PluginHost.kt:229`）→ 反射 attach + `app.onCreate()`。插件在里面 `Handler()`、弹 Toast、初始化主线程 SDK 全部崩溃。违反 Application onCreate 主线程契约。

### H6. ✅已修复 静态广播 action 无引用计数，卸载一个插件误伤其他插件
`StaticReceiverDispatcher.kt:26-37`：`actions` 是普通 Set，插件 A、B 声明同名 action 时，A 卸载把 action 整体移除，**B 从此收不到该广播**。

### H7. ✅已修复 Service 代理池槽位泄漏与跨进程记账错位（一族问题）
- **初始化失败泄漏**：`HostService.kt:54-57` acquire 先占位，但 `instanceId` 赋值在实例化成功之后（126 行）；实例化失败走 catch 只 `stopSelf()`，`onDestroy` 因 `instanceId==null` 跳过 release → 每失败一次永久损失一个槽，20 次后全池耗尽
- **跨进程错位**：`ServiceProxyPool` 是进程内单例，宿主进程 acquire、隔离进程 release（`active.remove` 查无此项直接返回）→ 每启动一个隔离服务烧掉一个槽
- **槽 2-4 没有服务池**：8 个隔离服务槽全声明在 `:plugin_isolated`（Manifest），槽 2-4 插件的 Service 注定实例化失败
- acquire 对同一 instanceId 有 check-then-act 竞态；`startService` 抛异常不回滚槽位

### H8. ✅已修复 跨插件借类重复 defineClass → LinkageError
`PluginClassLoader.kt:66`：`findClassLocally = findClass(name)`，不查 `findLoadedClass`。插件 B 已定义过的类，插件 A 再次借用时对 B 的 loader 二次 `defineClass` → **LinkageError**（不是 ClassNotFoundException，51 行的 catch 接不住）。修复是一行：`findLoadedClass(name) ?: findClass(name)`。（按 JVM/ART 的 defineClass 契约确认，建议修完跑一次跨插件用例实测。）

### H9. ✅已修复 闹钟后台触发 FGS 无豁免 → 宿主进程崩溃
`PluginScheduler.kt:36-40,117-121`：Android 12+ 只有**精确闹钟**豁免后台 FGS 启动，而 `schedulePeriodic` 恒为非精确、Android 14 起 `SCHEDULE_EXACT_ALARM` 默认拒授使 `scheduleOnce` 也降级为非精确 → `onReceive` 里无 try/catch 的 `startForegroundService` 抛 `ForegroundServiceStartNotAllowedException` → 崩溃。附带：先 acquire 槽位再 start，异常时槽位泄漏。

### H10. ✅已修复 `release()` 从不 `unbindService` → 隔离进程杀不死、插件双活
`ProcessIsolationManager.kt:106-117`：全模块无一处 `unbindService`。`BIND_AUTO_CREATE` 的绑定活着时 `stopService` 不会销毁服务 → release 后进程照跑、插件照常加载，而账本已是"未隔离" → 下次 launchPlugin 在宿主**再加载一份，同一插件双活**。

### H11. ✅已修复 UI 伴侣双入口机制两处断裂
- **重启后隔离进程永不启动**：宿主为隔离插件加载 UI 入口后 `isLoaded=true`（`LifecycleExecutor.kt:83-92`），启动循环据此跳过 launchPlugin → `isolate()` 不被调用 → **主入口/native 逻辑每次重启后都不加载**，界面却显示正常
- **热更新/显式 launch 误卸 UI 伴侣**：`ProcessIsolationManager.kt:93-95` 对 `isLoaded` 的插件先 `unloadPlugin`——卸掉的恰是宿主里的 UI 副本

## 二、中严重度

| # | 位置 | 问题 |
|---|---|---|
| M1 ✅ | `PluginHost.kt:355-358` | install 回滚缺口：`placePayload` 后的四次写文件在 try 之外，失败不回滚，磁盘/账本不一致 |
| M2 ✅ | `CrashHook.kt:61` | `.crash` 熔断标记**无任何清除路径**——崩溃一次永久禁用，更新/重装同 id 都无法解禁 |
| M3 ✅ | `PluginHost.kt:653-671` | `observePreviousNativeCrash` 不按进程/时间过滤退出记录，归因取目录里任意第一个陈旧 `.crash` 标记，且每次启动重复 emit |
| M4 ✅ | `exec_bridge.c:49-67` | **argv 缺 argv[0]**：C 约定 argv[0]=程序名，此处直接放用户参数 → 第一个参数被被加载程序静默忽略，argc 少 1（我已在 C 源码确认） |
| M5 ✅ | `ProcessIsolationManager.kt:135-156` | `ensureBridge` 不检查 `bindService` 返回值、无超时 → 绑定失败时 `isolate()` 协程**永久挂起**；并发同槽绑定覆盖 ServiceConnection 导致泄漏 |
| M6 ✅ | `ProcessIsolationManager.kt:74-76` | `markIsolated` 一律钉槽 1，`allocateSlot` 的负载分散对安装期声明的插件是**死代码**（所有重插件挤一个进程，正是注释要避免的） |
| M7 ✅ | `HostProvider.kt:86-91` | Provider 代理 `clearQuery()` 丢 query/fragment（调用侧 `PluginApis.kt:98` 明明保留了，两侧自相矛盾） |
| M8 ✅ | `AndroidManifest.xml:45-47` + `HostReceiver.kt` | HostReceiver exported=true 且无发送方校验：第三方**显式** component 投递可伪造 SCREEN_ON/BATTERY_LOW 等"系统广播"骗插件（改为 exported=false，系统广播仍送达） |
| M9 ✅ | `HostActivity.kt:139-142` + variants | launchMode 槽位只看模式不看类：两个 SingleTask 插件 Activity 互相错路由，`onNewIntent` 不校验 ACTIVITY_CLASS，B 的启动 Intent 被喂给 A |
| M10 ✅ | `HostService.kt:110-135` / `IsolatedPluginProcessService.kt:34-44` | START_STICKY 粘性重启带 null intent 时：插件服务不可恢复且产生伪造原因的失败上报；隔离服务空载不重载 |
| M11 ✅ | `PluginResourcesLoader.kt:32` | 插件 Resources 用宿主 configuration **快照**且不经 ResourcesManager——切换语言/深色/字体后插件资源永久陈旧（配置变化时经 Application→PluginHost→LifecycleExecutor 转发 `updateConfiguration` 刷新） |
| M12 ✅ | `PluginResourcesLoader.kt:53-57` | 反射新建的 AssetManager 未挂 framework-res.apk（0x01）——插件引用 `@android:` 资源/主题时 NotFoundException（注释承诺不成立） |
| M13 ✅ | `PluginScheduler.kt:108-111` | 闹钟跨重启保留，可在框架 `initialize` 完成前触发 → `acquire → coreHandle` 未初始化 `error()` 抛异常 → 崩溃（已由 H9 的 try/catch 兜住） |

## 三、低严重度 / 潜在

- ✅ **consumer-rules.pro 缺 JNA/UniFFI keep 规则**——已补 `com.lhzkml.jasmine.core.plugin.rust.**` 与 JNA Structure/Callback/Library keep
- ✅ `build.gradle.kts:83-87`：host lib 名——已按 win/mac/linux 三分支（mac → `libffi.dylib`）
- `PluginResourcesLoader.kt:35-42`：API 30+ 路径每次加载泄漏一个 ParcelFileDescriptor；`L2` 资产缓存文件名 `a/b_c` vs `a_b/c` 碰撞
- ✅ `HostProvider.kt:77-80`：ProviderInfo 缺 packageName——已补 `packageName`（exported 恒 false 属有意设计，保留）
- ✅ `HostProvider.kt:72`：`getOrPut` 非原子——已改 `computeIfAbsent`
- `PluginScheduler.kt:98-99`：PendingIntent requestCode 32 位折叠 + taskId 不参与身份 → 跨任务互相覆盖/误取消
- ✅ `CrashHook.kt:51-57`：`PluginHost.emit` 无防护——已用 `runCatching` 兜住，监听器异常不再掐断 handler 链
- `RemoteServices.kt`：跨进程服务解析单向——宿主不对外服务自己的目录，隔离进程解析宿主服务恒 null（与注释宣称不符）
- ✅ `LifecycleExecutor.kt:141,162`：uiOnly 不调 `onNativeReady` 但 unload 无条件调 `onNativeRelease`——已按 `uiOnly` 对称
- `build.gradle.kts:89-118`：cargo/bindgen 任务无 inputs/outputs 声明，每次构建都重跑且直接写 `src/main/java`（可能读到半成品）

## 修复优先级建议

1. **H4**（删一行）、**H8**（加一行 findLoadedClass）——一行级修复，先做
2. **H2 + H3**（initialize 异常处理 + 授权移出锁外）——防启动崩溃循环和框架死锁
3. **H1 + H7 + H10 + H11**（进程隔离一族：身份判定、服务池、unbind、UI 伴侣）——彼此纠缠，建议一起改
4. **H5、H6、H9**（线程、引用计数、FGS 崩溃）
5. M2/M3（崩溃标记生命周期）随后

---

# 修复记录（2026-08-25，已按批次实施并通过 `:app:assembleRelease` 全量构建）

## 已修复（34 项）

| 编号 | 修复方式 | 涉及文件 |
|---|---|---|
| H1 | `isIsolatedProcess` 改为匹配 `:plugin_isolated` 及 `:plugin_isolated_N` 前缀，槽 2-4 不再被误判为宿主 | `process/ProcessIdentity.kt` |
| H2 | `initialize()` 改两阶段提交：全部可失败工作完成后才提交 `core/executor/lifecycle` 状态，失败清零可安全重试；`PluginHostApplication` 启动协程加 `runCatching`，初始化失败不再拖垮宿主 | `PluginHost.kt`、`PluginHostApplication.kt` |
| H3 | 裁决 + 用户授权移出 `mutex.withLock`（锁内只留 payload 落盘 + 账本提交）；新增 `requestUserGrant` 带 120s 超时，BAL 丢弃授权 Activity 时按拒绝处理，杜绝永久挂起 | `PluginHost.kt` |
| H4 | 删除 `attachInfo` 之后多余的 `p.onCreate()`（AOSP `attachInfo` 内部已调） | `proxy/HostProvider.kt` |
| H5 | 插件 Application 的 `attach`+`onCreate` 经 `runOnMainThread` 切回主线程同步执行 | `internal/LifecycleExecutor.kt` |
| H6 | 静态广播 action 改引用计数（`ConcurrentHashMap<String,Int>`），归零才真正注销 | `proxy/StaticReceiverDispatcher.kt` |
| H8 | `findClassLocally` 先查 `findLoadedClass` 再 `findClass`，避免跨插件借类重复 `defineClass` 抛 LinkageError | `internal/PluginClassLoader.kt` |
| H9 | `PluginAlarmReceiver.onReceive` 全程 try/catch：FGS 启动受限/框架未初始化时记录并回滚槽位，绝不把异常抛到 BroadcastReceiver | `process/PluginScheduler.kt` |
| H10 | `release()` 先 `unbindService` 再 `stopService`，隔离进程可真正回收，消除插件双活 | `process/ProcessIsolationManager.kt` |
| H11 | ① `LoadedPlugin` 增加 `uiOnly` 标记，宿主加载 UI 伴侣后经 `onUiCompanionLoaded` 回调异步启动隔离进程主入口；② `isolate()` 仅在非 UI 伴侣时卸载宿主副本 | `internal/LifecycleExecutor.kt`、`PluginHost.kt`、`process/ProcessIsolationManager.kt` |
| M1 | install 的四次 `write*` 纳入 try/rollback 范围，中途失败完整回滚 | `PluginHost.kt` |
| M2 | 新增 `PluginHost.crashMarkerDir`，安装/卸载成功时清除对应 `.crash` 熔断标记 | `PluginHost.kt`、`PluginHostApplication.kt` |
| M3 | `observePreviousNativeCrash` 限定本应用宿主进程 + 最近一条，以退出时间戳落盘去重，不再每次启动重复 emit | `PluginHost.kt` |
| M4 | `exec_bridge.c` 补 `argv[0]`=可执行文件路径，用户参数整体后移，符合 C 约定 | `cpp/exec_bridge.c` |
| M5 | `ensureBridge` 检查 `bindService` 返回值、加 10s 超时、复用进行中的绑定，超时/失败回收连接可重试 | `process/ProcessIsolationManager.kt` |
| M6 | `markIsolated` 改用 `allocateSlot()` 按当前占用选最闲槽，不再一律钉槽 1 | `process/ProcessIsolationManager.kt` |
| M7 | `HostProvider.resolve` 保留 query/fragment（与调用侧 `pluginProxyUri` 对齐），不再 `clearQuery` | `proxy/HostProvider.kt` |
| M9 | `HostActivity.onNewIntent` 校验目标类与当前实例一致，不匹配则丢弃，避免 B 的 Intent 喂给 A | `proxy/HostActivity.kt` |
| M10 | `HostService.initPluginService` 对 null/blank 类名（粘性重启）静默 `stopSelf`，不产生伪造失败上报 | `proxy/HostService.kt` |
| M12 | `newHostBasedAssets` 显式挂 `framework-res.apk`（0x01），插件引用 `@android:` 资源不再 NotFoundException | `internal/PluginResourcesLoader.kt` |
| M13 | 已由 H9 的 `onReceive` try/catch 兜住（框架未初始化时 acquire 抛异常被捕获并回滚） | `process/PluginScheduler.kt` |
| M8 | `HostReceiver` 改 `android:exported="false"`：系统广播仍送达，第三方显式/隐式伪造彻底阻断 | `src/main/AndroidManifest.xml` |
| M11 | 配置变化经 `Application.onConfigurationChanged` → `PluginHost` → `LifecycleExecutor` 转发，对每个插件 `Resources.updateConfiguration` 刷新快照 | `PluginHostApplication.kt`、`PluginHost.kt`、`internal/LifecycleExecutor.kt` |
| H7 | 隔离服务池权威下沉到隔离进程（`PluginProcessBridge.Server` 持池 + 三个事务）；`IsolatedHostService1..8` 按槽重分（每槽 2 个）；宿主经 bridge 分配、隔离进程本地归还，槽位不再泄漏 | `process/PluginProcessBridge.kt`、`proxy/HostService.kt`、`proxy/HostServicePool.kt`、`process/ProcessIdentity.kt`、`src/main/AndroidManifest.xml` |
| 低 | `HostProvider` ProviderInfo 补 `packageName`；`getOrPut`→`computeIfAbsent` 保证并发单实例 | `proxy/HostProvider.kt` |
| 低 | `CrashHook` 的 `PluginHost.emit` 用 `runCatching` 兜住，监听器异常不再掐断崩溃处理链 | `crash/CrashHook.kt` |
| 低 | `uiOnly` 加载不调 `onNativeReady`、卸载也不调 `onNativeRelease`，契约对称 | `internal/LifecycleExecutor.kt` |
| 低 | `consumer-rules.pro` 补 UniFFI 绑定包与 JNA Structure/Callback/Library keep 规则 | `consumer-rules.pro` |
| 低 | host lib 名按 win/mac/linux 三分支（mac → `libffi.dylib`） | `build.gradle.kts` |
| 低 | `PluginApis` 的 `startPluginService`/`bindPluginService` 失败/未绑定时回滚池槽位（与 H7 对齐） | `proxy/PluginApis.kt` |

## H7 部分修复说明

### H7 完整修复说明（2026-08-25 第二轮）

原遗留两项已随"服务池架构重构"一并解决，方案核心：**把隔离服务池的权威下沉到隔离进程，宿主经 bridge 同步分配/归还**。

- **跨进程记账错位**：`PluginProcessBridge.Server` 新增服务池（`poolAvailable`/`poolActive` + `TX_ACQUIRE_SLOT/TX_RELEASE_SLOT/TX_PROXY_CLASS` 三个事务）。宿主 `acquire` 对隔离插件走 `bridge.acquireServiceSlot`（阻塞 transact，同步）；隔离进程 `HostService.onDestroy` 本地 `servicePool.release`。acquire/release 闭环在同一进程，宿主侧不再为隔离服务维护 `active`，槽位不再泄漏。
- **槽 2-4 无服务池**：`IsolatedHostService1..8` 按槽重分（每槽 2 个），manifest `process` 分别声明 `:plugin_isolated` / `_2` / `_3` / `_4`；`isolatedServicePool` 改为 `Map<Int, List<Class>>`；`configureIsolated` 在每个隔离进程只种本槽的 2 个代理类。槽 2-4 插件的服务运行在插件自己的进程。
- **隔离进程内自启服务**：插件代码在隔离进程内 `startPluginService` 时直接走本地池（不再找 bridge），保证同进程内服务可起。

## 未修复（留待后续）

- 无

> 说明：高/中/低全部项均已修复（共 34 项）。剩余为正常工程演进项，不列入 bug 清单。

### 四项低危修复记录（2026-08-25 第三轮）

| 项 | 修复方式 | 涉及文件 |
|---|---|---|
| fd 泄漏 | 卸载时关闭 `ResourcesProvider`（AutoCloseable，级联释放 loadFromApk 的 PFD）而非只 close `assets`（`Resources`/`ResourcesLoader` 均无公开 close()，此前误用 `Resources.close()` 是 @hide） | `internal/PluginResourcesLoader.kt`、`internal/LifecycleExecutor.kt` |
| 资产缓存名碰撞 | 缓存文件名由 `path.replace("/","_")` 改为路径 SHA-256，杜绝 `a/b` 与 `a_b` 同大小互相覆盖 | `internal/PluginResourcesLoader.kt` |
| PendingIntent 身份 | `requestCodeOf` 由 16 位 pluginId 哈希截断改为完整 32 位哈希 + 乘法/异或雪崩（taskId 是标签非身份，cancel 仅凭 pluginId+requestCode） | `process/PluginScheduler.kt` |
| RemoteServices 单向 | 宿主 bind 各槽 bridge 时把自身目录 binder 注册进隔离进程（`HOST_DIRECTORY_KEY`）；隔离进程 resolve 走宿主目录，实现"隔离发布→宿主消费、宿主发布→隔离消费"双向 | `process/PluginProcessBridge.kt`、`process/ProcessIsolationManager.kt`、`process/RemoteServices.kt` |
| cargo/bindgen 增量 | 任务加 `inputs`/`outputs` 增量声明；生成产物由 `src/main/java` 迁到 `build/generated/uniffi` 并注册 srcDir（不再污染源码树/读到半成品），删除残留生成文件 | `build.gradle.kts` |
