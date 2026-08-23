# 插件框架 API 文档

> 本文档记录 Jasmine 插件框架对外公开的 API 契约，供插件开发者与宿主开发者查阅，避免反复翻源码。
> 决策逻辑全部在 Rust（`rust/plugin-core`），Kotlin 侧是执行壳；本框架命名与 ComboLite 不同，不与其 API 对齐。

---

## 一、模块组成

| 模块 | 角色 | 语言 |
|---|---|---|
| `rust/plugin-core` | 决策核心：ledger（注册表）/ topology（依赖拓扑）/ charter（权限章程）/ dispatch（分发匹配） | Rust |
| `android/core-plugin` | 执行壳：门面、安装、加载、代理组件、资源、崩溃、更新、授权 | Kotlin |
| `android/core-plugin-ksp` | `@GatedApi` 注解处理器（生成 `<接口>Gated` 包装） | Kotlin |
| `android/build-logic` | `jasmine.plugin-pack`（AAR→APK）、`jasmine.plugin-dev`（注入宿主） | Gradle/Kotlin |
| `android/sample-plugin` | 示例插件 | Kotlin |

---

## 二、插件开发者视角 API（写插件必读）

### 2.1 入口类：`PluginEntry`

插件通过 manifest 的 `jasmine.plugin.entryClass` 元数据声明入口类，该类实现 `PluginEntry`。

```kotlin
interface PluginEntry {
    val services: ServiceTable                 // 依赖注入：ServiceKey → 实现，替代容器
        get() = emptyMap()
    val menuEntry: PluginMenuEntry?            // 激活后动态加入宿主设置列表的菜单入口；null 则无
        get() = null
    fun onLoad(context: PluginContext)         // 加载后调用（异常会失败加载并回滚）
    fun onUnload()                             // 卸载前调用（异常会上报，不吞）
    @Composable fun MainScreen()               // 点击菜单入口后宿主渲染的插件主界面
}
```

### 2.2 菜单入口：`PluginMenuEntry`

```kotlin
class PluginMenuEntry(
    val title: String,        // 设置列表里显示的标题
    val subtitle: String? = null,
    val iconResId: Int? = null,  // 指向插件自己的（分区后）资源 id
)
```

### 2.3 上下文：`PluginContext`

```kotlin
class PluginContext(
    val application: Application,
    val pluginId: String,
    val pluginDir: String,     // 插件私有目录
    val resources: Resources,  // 插件自己的 Resources（显式注入，宿主无全局合并）
)
```

### 2.4 依赖注入：`ServiceKey` / `ServiceTable`

本框架**不带容器**，用类型化 key 定位服务（替代 Koin）：

```kotlin
class ServiceKey<T : Any>(val name: String)
typealias ServiceTable = Map<ServiceKey<*>, Any>

// 插件发布：
override val services = mapOf(Greeter.Key to Greeter { "hi" })
// 宿主/其他插件解析：
PluginHost.resolveService<Greeter>(Greeter.Key)
```

### 2.5 组件契约（生命周期转发）

> 注入方法为 `attach`（非 `onAttach`）。

**Activity：`PluginActivity`**（可继承 `BasePluginActivity`）
```kotlin
interface PluginActivity {
    fun attach(proxy: ComponentActivity)      // 第一步：绑定宿主代理（在 onCreate 之前）
    fun onCreate(savedInstanceState: Bundle?)
    fun onStart(); fun onResume(); fun onPause(); fun onStop()
    fun onDestroy(); fun onRestart()
    fun onSaveInstanceState(outState: Bundle)
    fun onRestoreInstanceState(savedInstanceState: Bundle)
    fun onConfigurationChanged(newConfig: Configuration)
    fun onWindowFocusChanged(hasFocus: Boolean)
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean
    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean
    fun onTouchEvent(event: MotionEvent?): Boolean
}
```
> 注：无 `onRequestPermissionsResult`（本框架未做权限结果转发）。

**Service：`PluginService`**（可继承 `BasePluginService`）
```kotlin
interface PluginService {
    fun attach(proxy: Service)                // 第一步：绑定宿主代理
    fun onCreate()
    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    fun onBind(intent: Intent?): IBinder?
    fun onUnbind(intent: Intent?): Boolean
    fun onRebind(intent: Intent?)
    fun onDestroy()
    fun onConfigurationChanged(newConfig: Configuration)
    fun onLowMemory(); fun onTrimMemory(level: Int)
}
```

**Receiver：`PluginReceiver`**
```kotlin
interface PluginReceiver {
    fun onReceive(context: Context, intent: Intent)
}
```

### 2.6 权限注解：`@GatedApi`

```kotlin
@Target(FUNCTION) @Retention(SOURCE)
annotation class GatedApi(
    val rule: ApiRule = ApiRule.Host,   // 访问规则
    val hardFail: Boolean = false,      // true=直接拒绝；false=走授权流程
    val targetParam: String = "",       // SelfOrHost 规则下目标插件 id 的参数名
)

enum class ApiRule { Host, SelfOrHost, AnyPlugin }
```

KSP 会为标注了 `@GatedApi` 的接口生成 `<接口>Gated` 包装类，其 suspend 方法先过 `PluginHost.checkApi` 再委托。

### 2.7 插件 manifest 约定

```xml
<meta-data android:name="jasmine.plugin.entryClass"
           android:value="jasmine.sample.hello.HelloEntry" />
<meta-data android:name="jasmine.plugin.description"
           android:value="..." />
```

---

## 三、宿主开发者视角 API（`PluginHost`）

### 3.1 初始化

```kotlin
// 直接继承 PluginHostApplication（推荐）
class App : PluginHostApplication() {
    override fun pluginPolicy() = SignaturePolicy.Strict      // 签名策略
    override fun pluginCrashCallback(): PluginCrashCallback?  // 崩溃策略钩子
    override fun onPluginFrameworkReady() = { ... }           // 框架就绪后的回调（suspend）
}
// 或手动：
PluginHost.initialize(application, policy)
```

### 3.2 生命周期管理（均为 suspend）

| 方法 | 说明 |
|---|---|
| `installPlugin(apk, expectedSha256?, forceOverwrite)` | 安装/更新（先裁决，失败回滚），返回 `FfiPluginRecord` |
| `uninstallPlugin(pluginId)` | 卸载（卸载运行态 → 删文件 → 提交注册表） |
| `launchPlugin(pluginId)` | 启动；已加载则走链式重启计划 |
| `unloadPlugin(pluginId)` | 运行期卸载（保留注册表） |
| `setPluginEnabled(pluginId, enabled)` | 启用/禁用（下次启动生效） |
| `installBundledPlugins(assetsDir="plugins")` | 安装宿主 assets 内置插件 |
| `applyAvailableUpdates()` | 检查并热更所有已装插件（占位地址，见 3.4） |

### 3.3 查询

| 方法 | 说明 |
|---|---|
| `allPlugins(): List<FfiPluginRecord>` | 全部已安装插件 |
| `pluginRecord(pluginId)` | 单个记录 |
| `isLoaded(pluginId)` / `loadedPluginIds()` | 运行态 |
| `entryOf(pluginId)` | 已加载插件入口实例 |
| `resourcesOf(pluginId)` | 插件 Resources |
| `resolveService<T>(key)` | 解析跨插件服务 |
| `dependentsChain(id)` / `dependenciesChain(id)` | 依赖链（不含自身） |
| `audit()` / `repair()` | 三方对账 / 漂移修复 |
| `checkApi(rule, callerPluginId?, targetPluginId, permissionKey, hardFail)` | 敏感 API 门禁（suspend） |

### 3.4 响应式状态与配置

| 成员 | 说明 |
|---|---|
| `loadedMenuEntries: StateFlow<Map<pluginId, PluginMenuEntry>>` | 已加载插件的动态菜单入口 |
| `updateManifestBaseUrl: String?` | 更新通道 base URL（`updates/<id>.json`） |
| `updateChannel(): PluginUpdateChannel?` | 更新通道实例 |
| `authorizationHandler: AuthorizationHandler?` | 授权 UI 钩子 |
| `loadFailureCallback: LoadFailureCallback?` | 加载/卸载失败回调 |

---

## 四、打包与注入（build-logic）

### 4.1 `jasmine.plugin-pack`（库模块插件 → APK）

```kotlin
plugins { id("jasmine.plugin-pack") }
pluginPack { packageIdSlot.set(1) }   // 资源包 id = 0x80 + slot；宿主保留 0x7f
// 产物：build/outputs/plugin/plugin-signed.apk
```

### 4.2 `jasmine.plugin-dev`（宿主开发期注入）

```kotlin
plugins { id("jasmine.plugin-dev") }
pluginDev {
    modules.set(listOf(":some-app-plugin"))     // application 模块产出的插件
    packModules.set(listOf(":sample-plugin"))   // 走 pack 管线的库模块插件
}
```

---

## 五、崩溃与授权

- `CrashHook.install(callback)`：安装崩溃钩子，归因分类在 Rust（cause 链查类索引 + 分类优先级）。
- `PluginCrashCallback.onPluginCrash(crash: PluginCrash)`：宿主崩溃策略钩子。
- `AuthorizationHandler.onAuthorization(prompt: AuthorizationPrompt): Boolean`：授权 UI 钩子；默认实现 `DefaultPluginAuthorizationHandler` + `PluginAuthorizationActivity`。

---

## 六、与 ComboLite 的关键命名差异（便于记忆）

| 概念 | ComboLite | 本框架 |
|---|---|---|
| 入口接口 | `IPluginEntryClass` | `PluginEntry` |
| 主界面方法 | `Content()` | `MainScreen()` |
| 组件注入 | `onAttach(proxyActivity)` | `attach(proxy)` |
| Activity 接口 | `IPluginActivity` | `PluginActivity` |
| Service 接口 | `IPluginService` | `PluginService` |
| Receiver 接口 | `IPluginReceiver` | `PluginReceiver` |
| 权限注解 | `@RequiresPermission(level, hardFail)` | `@GatedApi(rule, hardFail, targetParam)` |
| 依赖注入 | Koin `pluginModule` | `ServiceKey` / `ServiceTable` |
| 元数据键 | `plugin.entryClass` | `jasmine.plugin.entryClass` |
| 菜单入口 | 无（硬编码在 home 插件） | `PluginMenuEntry`（动态） |
