# 插件框架 API 文档

> 模块：`core-plugin`（包名 `com.lhzkml.jasmine.core.plugin`）
> 版本：随 `core-plugin` 演进（本文档对齐第四轮修复后的最新实现）
> 面向：插件开发者 + 宿主集成者

本框架是一个 **签名优先、能力门控、支持进程隔离** 的 Android 插件运行时。插件以独立签名 APK 分发，由宿主动态安装、加载与生命周期管理；重负载/高风险插件可移入独立隔离进程。

---

## 1. 概述

| 能力 | 说明 |
|---|---|
| 动态安装/卸载 | 插件为独立签名 APK，宿主校验签名后安装 |
| 四大组件代理 | Activity / Service / Receiver / Provider 经宿主占坑代理 |
| 进程隔离 | 重插件移入 `:plugin_isolated[_N]` 独立进程，崩溃/资源隔离 |
| 能力门控 | exec/gpu/network/storage/camera 五类能力，安装时声明+授权 |
| 敏感 API 门控 | 运行时按宪章裁决，可升级为用户授权 |
| 跨进程服务 | Binder 服务经进程桥发布/解析 |
| Offload 分发 | 命名能力经 abstract socket 跨进程调用 |
| 后台调度 | AlarmManager 一次性/周期任务 |
| 热更新 | 更新清单 + 资产下载通道 |
| 崩溃熔断 | Java/native 崩溃归因、`.crash` 标记、自动禁用 |

### 核心对象

| 对象 | 角色 |
|---|---|
| `PluginHost` | 宿主侧唯一门面（安装/加载/查询/门控/隔离/跨进程） |
| `PluginHostApplication` | 宿主 `Application` 基类，负责框架初始化 |
| `PluginEntry` | 插件入口契约（插件侧实现） |
| `PluginContext` | 插件运行时上下文（资源/文件/数据库） |

---

## 2. 插件侧契约

### 2.1 `PluginEntry` —— 插件入口

```kotlin
interface PluginEntry {
    val id: String
    val version: String
    fun onLoad(context: PluginContext)
    fun onNativeReady(context: PluginContext)   // 主入口在进程内就绪后回调（可启动 native/后台）
    fun onNativeRelease()                          // 与 onNativeReady 对称的释放
    fun onUnload()
    @Composable fun MainScreen()
    val services: List<PluginServiceDescriptor>
}
```

生命周期顺序：
- 加载：`onLoad` → （非 UI 伴侣时）`onNativeReady`
- 卸载：`onUnload` → （非 UI 伴侣时）`onNativeRelease`
- **UI 伴侣**（隔离插件在宿主进程里的轻量 UI 入口）只走 `onLoad`/`onUnload`，不触发 native 回调。

### 2.2 `PluginService`

```kotlin
interface PluginService {
    fun attach(host: Service)
    fun onCreate()
    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int?  // null = 用宿主默认
    fun onBind(intent: Intent): IBinder?
    fun onDestroy()
    fun onConfigurationChanged(newConfig: Configuration)
    fun onLowMemory()
    fun onTrimMemory(level: Int)
}
```

### 2.3 `PluginContext`

```kotlin
class PluginContext(
    val application: Application,
    val pluginId: String,
    val pluginDir: File,          // 插件私有目录
    val resources: Resources,     // 插件资源（随宿主配置变化刷新）
) {
    fun filesDir(): File                       // 插件私有 files 目录
    fun databaseDir(): File                    // 插件私有 databases 目录
    fun openFileOutput(name: String, mode: Int): FileOutputStream
    fun openFileInput(name: String): FileInputStream
}
```

### 2.4 `PluginReceiver`

```kotlin
interface PluginReceiver {
    fun onReceive(context: Context, intent: Intent)
}
```

### 2.5 `PluginActivity`

```kotlin
interface PluginActivity {
    fun attach(activity: Activity)
    val intent: Intent?
    fun onCreate(savedInstanceState: Bundle?)
    fun onStart(); fun onResume(); fun onPause(); fun onStop()
    fun onDestroy()
    fun onNewIntent(intent: Intent)                              // singleTask/singleTop 复用
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
    fun onTouchEvent(event: MotionEvent): Boolean
    fun onConfigurationChanged(newConfig: Configuration)
    fun onLowMemory(); fun onTrimMemory(level: Int)
}
```

> 框架已转发 `onActivityResult` 与 `onRequestPermissionsResult`（配合 `PluginHost.requestPermission`）。

### 2.6 `BasePluginActivity`

`PluginActivity` 的缺省空实现基类，`activityResultRegistry` 为 `MutableMap<Int, (Int, Intent?) -> Unit>`，便于登记 `onActivityResult` 回调。插件 Activity 可继承它只覆写需要的方法。

---

## 3. 宿主侧门面 `PluginHost`

### 3.1 初始化与就绪

| 方法 | 说明 |
|---|---|
| `suspend initialize(application, policy, loadFilter)` | 初始化框架；`policy` 为签名策略，`loadFilter` 过滤自动加载的插件 |
| `suspend awaitReady()` | 挂起直到初始化完成 |
| `val isInitialized: Boolean` | 是否已初始化 |
| `suspend refreshLedger(): FfiAuditReport` | 重读账本（跨进程可见性，隔离进程加载前调用） |

### 3.2 安装 / 卸载 / 启停

| 方法 | 说明 |
|---|---|
| `suspend installPlugin(apk, expectedSha256 = null, forceOverwrite = false): FfiPluginRecord` | 安装单个 APK（签名/能力裁决在内部完成） |
| `suspend installWithDependencies(apk, expectedSha256 = null): FfiPluginRecord` | 安装并解析依赖 |
| `suspend installBundledPlugins(assetsDir = "plugins"): List<String>` | 安装 assets 内置插件 |
| `suspend uninstallPlugin(pluginId: String): FfiPluginRecord` | 卸载（含回收隔离进程） |
| `suspend setPluginEnabled(pluginId, enabled)` | 启用/禁用 |
| `suspend launchPlugin(pluginId)` | 启动（隔离插件路由到隔离进程） |
| `suspend unloadPlugin(pluginId)` | 卸载内存态（保留安装） |

### 3.3 查询

| 方法 | 说明 |
|---|---|
| `allPlugins(): List<FfiPluginRecord>` | 全部已安装插件 |
| `pluginRecord(pluginId): FfiPluginRecord?` | 单个插件记录 |
| `isLoaded(pluginId): Boolean` | 是否已加载 |
| `isUiCompanionLoaded(pluginId): Boolean` | 宿主里加载的是否为隔离插件的 UI 伴侣 |
| `loadedPluginIds(): List<String>` | 已加载插件 id |
| `entryOf(pluginId): PluginEntry?` | 插件入口 |
| `resourcesOf(pluginId): Resources?` | 插件资源 |
| `dependentsChain(pluginId) / dependenciesChain(pluginId): List<String>` | 依赖/被依赖链 |
| `requiredPermissionsOf(pluginId): List<String>` | 插件声明的 uses-permission |
| `audit() / repair(): FfiAuditReport` | 账本审计 / 修复索引漂移 |

### 3.4 配置

| 属性/方法 | 说明 |
|---|---|
| `var authorizationHandler: AuthorizationHandler?` | 授权处理器 |
| `var loadFailureCallback: LoadFailureCallback?` | 加载失败回调 |
| `var crashMarkerDir: File?` | 崩溃熔断标记目录 |
| `var updateManifestBaseUrl: String?` | 更新清单地址 |
| `fun setEventListener(listener: PluginEventListener?)` | 事件监听器 |
| `fun onConfigurationChanged(newConfig)` | 转发配置变化（刷新插件资源） |
| `fun notifyLowMemory() / notifyTrimMemory(level)` | 内存压力转发 |

---

## 4. 动态菜单

```kotlin
val loadedMenuEntries: StateFlow<Map<String, PluginMenuEntry>>
```

已加载插件的菜单项（`pluginId → PluginMenuEntry`），随加载/卸载自动刷新。宿主 `collect` 该 `StateFlow` 渲染动态菜单。

---

## 5. 签名校验

```kotlin
enum class SignaturePolicy { Strict, UserGrant, Insecure }
```

| 策略 | 行为 |
|---|---|
| `Strict` | 仅允许与宿主同签名（或白名单）的插件 |
| `UserGrant` | 签名不匹配时升级为用户授权 |
| `Insecure` | 不校验签名（仅调试） |

策略在 `initialize(application, policy, ...)` 传入；宿主可通过覆写 `PluginHostApplication.pluginPolicy()` 提供默认值（默认 `Strict`）。

---

## 6. 能力系统（Capabilities）

五类敏感能力，插件在 manifest 用 `jasmine.plugin.capabilities` 声明，安装时经宪章裁决（拒绝则安装失败）；运行时以声明为门控依据。

```kotlin
enum FfiCapability { EXEC, GPU, NETWORK, STORAGE, CAMERA }
```

| 方法 | 说明 |
|---|---|
| `hasCapability(capability, pluginId): Boolean` | 能力是否已声明 |
| `requireCapability(capability, pluginId)` | 强制门控：未声明抛 `SecurityException` |
| `suspend checkCapability(capability, callerPluginId, hardFail = false): Boolean` | 软门控：返回布尔，可升级用户授权 |

---

## 7. 敏感 API 与运行时权限

### 7.1 敏感 API 门控

```kotlin
suspend fun checkApi(
    rule: ApiRule,
    callerPluginId: String?,      // null = 宿主自身（显式放行）
    targetPluginId: String,
    permissionKey: String,
    hardFail: Boolean = false,
): Boolean
```

按宪章裁决；`Ask` 裁决路由到 `authorizationHandler`，授权结果由核心按会话缓存并持久化。

### 7.2 运行时权限（宿主权限池）

插件运行在宿主进程，权限 = 宿主权限集。

| 方法 | 说明 |
|---|---|
| `hasPermission(permission): Boolean` | 宿主是否已授予 |
| `suspend requestPermission(permission): Boolean` | 宿主替插件弹系统授权对话框，挂起返回结果 |

---

## 8. 进程隔离

重负载/高风险插件移入独立进程（`:plugin_isolated`、`_2`、`_3`、`_4` 四个槽），实现崩溃与资源隔离。

| 方法 | 说明 |
|---|---|
| `suspend isolatePlugin(pluginId): Boolean` | 将插件移入隔离进程 |
| `releaseIsolation(pluginId)` | 释放隔离（停止私有进程） |

- 隔离插件在宿主进程加载 **UI 伴侣**（轻量 UI 入口），主入口在隔离进程加载。
- 卸载（`uninstallPlugin`）会自动回收隔离进程/服务。
- 隔离进程的插件服务运行在插件自己的进程槽内。

---

## 9. 跨进程服务（RemoteServices）

隔离进程里的插件可发布 Binder 服务，宿主/其它进程解析。

```kotlin
class RemoteServiceKey(val name: String)

fun publishRemoteService(key: RemoteServiceKey, service: IBinder)
fun resolveRemoteService(key: RemoteServiceKey): IBinder?
```

- `publish` 在隔离进程把 Binder 注册进本进程桥目录。
- `resolve` 先查本地目录（同进程），再经进程桥（跨进程）；双向可达（隔离发布→宿主消费，宿主发布→隔离消费）。
- 与进程内 `ServiceKey`（值为任意对象）不同，远程服务的值必须是 `IBinder`。

---

## 10. Offload 命名能力分发

经 abstract socket 的跨进程命名能力调用（宿主服务、隔离进程消费）。

```kotlin
data class OffloadResult(val exitCode: Int, val output: String)
fun interface OffloadHandler {
    fun handle(argv: List<String>, env: Map<String, String>): OffloadResult
}

fun registerOffload(name: String, handler: OffloadHandler)
fun unregisterOffload(name: String)
fun dispatchOffload(name: String, argv: List<String> = emptyList(), env: Map<String, String> = emptyMap()): OffloadResult
```

socket 名按包名隔离，服务端校验对端 UID，防跨应用劫持。

---

## 11. ExecBridge（可执行能力）

声明 `EXEC` 能力的插件可运行随包的可执行文件 / 加载原生库。

```kotlin
fun execBridge(): ExecBridge
// ExecBridge:
//   fun executablePath(pluginId, name): File?   // 限定在本插件 execDir 内
//   fun runNative(pluginId, name, args): Int     // dlopen 桥
```

> 路径已规范化并限定在本插件 `execDir`，杜绝 `../` 逃逸。能力门控信任调用方自报的 `pluginId`（同进程无调用方身份识别），彻底隔离需进程级方案。

---

## 12. 后台调度（PluginScheduler）

`object PluginScheduler`（插件直接调用，非经 `PluginHost`）。

```kotlin
fun scheduleOnce(context, pluginId, requestCode, triggerAtMillis, serviceClassName, taskId = "scheduled-$requestCode")
fun schedulePeriodic(context, pluginId, requestCode, intervalMillis, serviceClassName, taskId = ...)
fun cancel(context, pluginId, requestCode)
```

- 基于 `AlarmManager`（`ELAPSED_REALTIME_WAKEUP` 基准）。
- Android 12+ 无 `SCHEDULE_EXACT_ALARM` 权限时自动降级为非精确触发。
- 触发经服务代理池启动插件服务；框架未初始化/启动失败时优雅降级，不崩宿主。

---

## 13. 更新通道

| 方法/属性 | 说明 |
|---|---|
| `var updateManifestBaseUrl: String?` | 更新清单基础地址 |
| `updateChannel(): PluginUpdateChannel?` | 更新通道 |
| `assetDownloader(): AssetDownloader` | 资产下载器 |
| `suspend applyAvailableUpdates(): List<String>` | 应用可用更新，返回已更新插件 |

---

## 14. 崩溃与熔断

| 机制 | 说明 |
|---|---|
| `CrashHook` | Java 崩溃捕获、归因到插件、写 `.crash` 标记 |
| `observePreviousNativeCrash()` | 经 `ApplicationExitInfo`（Android 11+）补齐 native 崩溃观测 |
| 熔断 | 崩溃插件写 `.crash` 标记，启动时跳过；安装/卸载成功自动清除标记 |
| `var crashMarkerDir: File?` | 标记目录（宿主在初始化前设置） |

---

## 15. 事件监听

```kotlin
fun setEventListener(listener: PluginEventListener?)
```

`PluginEventListener.onEvent(event: PluginEvent)`。事件类型含 `Installed` / `Uninstalled` / `Crash` / `IsolationReleased` 等。

---

## 16. 权限模型

- **插件运行在宿主进程**，其可用权限 = 宿主 manifest 声明的权限集。
- 插件 `uses-permission` 在安装时解析（`requiredPermissionsOf`）；宿主未声明的权限会导致对应功能失效（框架会告警）。
- 运行时危险权限经 `requestPermission` 由宿主代弹。
- 敏感能力/API 另经能力门控与宪章裁决（见 §6/§7）。

---

## 17. 集成步骤（宿主）

1. `Application` 继承 `PluginHostApplication`，覆写 `pluginPolicy()`（可选）、`pluginCrashCallback()`（可选）。
2. 框架在 `onCreate` 自动初始化；宿主侧用 `PluginHost.awaitReady()` 等待就绪。
3. 安装：`installBundledPlugins()`（内置）或 `installPlugin(apk)`（动态）。
4. 渲染动态菜单：`collect` `PluginHost.loadedMenuEntries`。
5. 按需启用隔离（`isolatePlugin`）、更新（`applyAvailableUpdates`）、调度（`PluginScheduler`）。

## 18. 集成步骤（插件）

1. 实现 `PluginEntry`（`onLoad`/`onNativeReady`/`MainScreen`/`services`）。
2. 需要 Activity/Service/Receiver 时实现对应契约接口。
3. manifest 声明所需 `uses-permission` 与 `jasmine.plugin.capabilities`。
4. 打包为独立签名 APK，交由宿主安装。

---

## 19. 快速参考

```
入口契约     : PluginEntry / PluginService / PluginReceiver / PluginActivity / BasePluginActivity
运行时上下文 : PluginContext（resources / filesDir / databaseDir / openFileInput/Output）
宿主门面     : PluginHost
应用基类     : PluginHostApplication
签名策略     : SignaturePolicy.{Strict, UserGrant, Insecure}
能力         : FfiCapability.{EXEC, GPU, NETWORK, STORAGE, CAMERA}
菜单         : PluginHost.loadedMenuEntries (StateFlow)
进程隔离     : isolatePlugin / releaseIsolation
跨进程服务   : publishRemoteService / resolveRemoteService (RemoteServiceKey)
Offload      : registerOffload / dispatchOffload (OffloadHandler/OffloadResult)
执行桥       : PluginHost.execBridge()
后台调度     : PluginScheduler.{scheduleOnce, schedulePeriodic, cancel}
更新         : updateChannel / assetDownloader / applyAvailableUpdates
事件         : PluginHost.setEventListener(PluginEventListener)
```

---

## 附：相对旧版文档的主要修订

- **`PluginEntry`** 补充 `onNativeReady` / `onNativeRelease`。
- **`PluginActivity`** 补充 `onNewIntent` / `onActivityResult` / `onRequestPermissionsResult`（旧文档误称"未做权限结果转发"）。
- **`PluginContext`** 补充 `filesDir` / `databaseDir` / `openFileInput` / `openFileOutput`。
- **`installPlugin`** 签名更正为 `(apk, expectedSha256, forceOverwrite)`；签名策略移至 `initialize`。
- **`uninstallPlugin`** 参数更正为 `pluginId: String`（旧文档为 `FfiPluginRecord`）。
- **事件监听** 更正为 `setEventListener(listener)` 方法。
- **动态菜单** 更正为 `loadedMenuEntries: StateFlow`（旧文档为 `menuEntries()` 方法）。
- **`SignaturePolicy`** 补全 `Strict / UserGrant / Insecure` 三值。
- **新增章节**：能力系统、敏感 API 与运行时权限、进程隔离、跨进程服务、Offload、ExecBridge、后台调度、更新通道、崩溃熔断。
