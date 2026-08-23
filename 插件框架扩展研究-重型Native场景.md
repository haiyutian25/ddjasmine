# 插件化框架扩展研究：重型 Native 场景（Proot / MNN）满足度评估与优化方案

> 日期：2026-08-24
> 范围：评估 Jasmine 插件化框架对「重型 native + 大文件资产 + 需隔离」场景的满足程度，
> 并给出框架级优化方案与实施路线。
> 触发场景：① Proot（可随时安装/卸载的 Linux 环境）；② MNN（阿里移动端推理引擎）。
> 结论先行：**当前框架不满足 Proot，部分满足 MNN**；须从「单进程 + APK 粒度」演进到
> 「多进程 + APK/资产分离 + 能力声明」模型。

---

## 1. 当前框架能力盘点（基线）

| 能力 | 实现位置 | 现状 |
|---|---|---|
| DEX 动态加载 | `PluginClassLoader` | 多 DEX、跨插件类定位、宿主回退 |
| 资源分区 | `PluginResourcesLoader` + `--package-id` | 宿主 0x7f / 插件 0x80+N |
| **native 库加载** | `InstallExecutor.extractNativeLibraries` | 只提取 `.so` → `lib/<abi>/`，best-ABI-first |
| 四大组件代理 | Activity / Service池 / Receiver / Provider | 全在宿主进程内 |
| 安装裁决 | Rust `charter.adjudicate_install` | digest + 降级禁令 + 签名连续性 + 宿主信任 |
| 敏感 API 门控 | Rust `charter.check_api_access` | `Host / SelfOrHost / AnyPlugin` 三级 |
| 更新通道 | `PluginUpdateChannel` | 整包 APK 下载 + SHA256 校验 + 热更 |
| 跨插件服务 | `ServiceKey` / `ServiceTable` | 同进程内存表 |
| 崩溃隔离 | `classifyCrash` + 崩溃引导禁用 | 仅进程内 |
| 生命周期 | `PluginEntry.onLoad / onUnload` | 无 native 资源钩子 |

**结论**：框架已具备「单进程内、以 APK 为粒度」的完整插件化能力，但对重型 native 场景
（大文件、可执行二进制、独立进程、内存敏感资源）目前是空白。

---

## 2. 场景需求 → 框架要求

### 2.1 Proot（随时装卸的 Linux 环境）

Proot 是用户态 `ptrace` 拦截器，通过 `PTRACE_TRACEME` 追踪自己 fork 出的子进程，
拦截 `execve`/`open`/`chdir` 等系统调用，模拟 `chroot` + `bind mount`，**无需 root**。
Termux 的 `proot-distro` 已证明这条路线在 Android 上可行。

三个关键难点：

1. **可执行二进制的执行受限（最致命）**：Android 10+ 的 W^X 强化后，app 私有目录
   （`filesDir`）是 noexec 挂载，普通 app 无法 `execve` 私有目录里的 ELF。Proot 本身
   是可执行二进制，还要 fork/exec rootfs 里的 `/bin/sh`。Termux 靠 `termux-exec` 拦截
   `execve` 转成 dlopen 方式才跑起来。
2. **rootfs 体积巨大**：最小 Linux rootfs 几百 MB，塞 APK 不现实。
3. **ptrace 与进程模型**：Proot 需要 trace 自己的子进程，rootfs 里的进程必须运行在
   **独立进程**（否则崩溃拖垮宿主，且 ptrace 隔离性差）。

### 2.2 MNN（手机端模型推理底层）

MNN 是阿里开源移动端推理引擎，核心是 `libMNN.so`（约 8–15MB/ABI，含 CPU/OpenCL/Vulkan
后端）+ `.mnn` 模型文件。API 是 `Interpreter → Session → Tensor` 三级。

**这个方向可行性远高于 Proot**：本质就是 `.so + 数据文件`，契合现有 native 提取路径，
主要卡在三点：模型文件大不能进 APK、大型 so 的加载/卸载与内存、能力声明（GPU/网络）。

---

## 3. 六个框架缺口与优化方案

### 缺口 1：大文件资产按需下载（分发层）

**现状**：`PluginUpdateChannel` 只支持整包 APK 下载 + SHA256 校验，无断点续传、无分片、
无资产分离。

**优化**：引入「插件 = APK（代码/组件/元数据） + Assets（大文件，可选，按需下载）」两级模型。

- 新增 `AssetManifest { name, sha256, size, url, optional }`
- 下载支持断点续传 + 分片校验 + 磁盘配额
- 复用现有 `expectedSha256` 裁决机制

**复杂度**：中。**优先级 P0**。

### 缺口 2：可执行资产 exec（安装层）

**现状**：`InstallExecutor.extractNativeLibraries` 只提取 `.so` → `lib/<abi>/`，走
`System.loadLibrary`。没有任何「可执行 ELF」的提取与执行路径。

**优化**：
- 新增「可执行资产」类型，提取到 `nativeLibraryDir`
- 关键约束：Android 10+ `filesDir` 是 noexec，直接 `execve` 会失败。需走 `dlopen` 桥接
  （把可执行文件当 `.so` 加载后调 `main()`，`termux-exec` 同款思路），或用 `app_process`

**复杂度**：大。**优先级 P2**（Proot 的最大拦路虎）。

### 缺口 3：插件独立进程 / 隔离（进程层）

**现状**：所有代理组件（Activity/Service池/Receiver/Provider）都跑在宿主进程，manifest
无 `android:process`。`ServiceKey` 服务表是同进程内存 Map。

**优化**：
- 代理组件增加 `android:process=":plugin_<slot>"`
- 引入跨进程通信（Binder/AIDL，或复用 `HostProvider.call` 通道）
- Rust 核心维护「插件 → 进程」映射，卸载时杀进程

**复杂度**：大。**优先级 P2**。

### 缺口 4：native 生命周期钩子（生命周期层）

**现状**：`PluginEntry` 只有 `onLoad` / `onUnload`，无 native 资源钩子；`LifecycleExecutor`
无 so 引用计数、无内存敏感卸载策略。

**优化**：
- 新增 `onNativeReady` / `onNativeRelease` 钩子
- so 引用计数 + 卸载时显式 `System.gc`
- 内存敏感场景（MNN）支持「空闲自动卸载」策略

**复杂度**：小。**优先级 P1**。

### 缺口 5：能力声明与裁决面扩展（裁决层）

**现状**：`FfiAccessRule` 只有 `HOST / SELF_OR_HOST / ANY_PLUGIN` 三级；
`FfiInstallRequest` 无能力声明字段。

**优化**：
- 新增 `FfiCapability`（`EXEC / GPU / NETWORK / STORAGE / CAMERA ...`）
- 插件 manifest 声明能力 → 安装时 `adjudicate_install` 裁决 → 运行时 `check_api_access` 门控
- 直接复用现有 `Verdict.Allow/Deny/RequireUserGrant` 三段式

**复杂度**：小（FFI 面已成型）。**优先级 P0**。

### 缺口 6：跨进程服务发布（服务层）

**现状**：`ServiceKey` / `ServiceTable` 是进程内内存 Map，`resolveService` 直接查表。

**优化**：
- `ServiceKey` 服务在独立进程时，自动包一层 Binder 代理
- 跨进程服务表由 Rust 核心登记（`registerInstance` 已有雏形，扩展进程维度即可）

**复杂度**：中。**优先级 P2**（依赖缺口 3）。

---

## 4. 满足度对照表

| 场景要求 | 框架现状 | 满足？ | 对应缺口 |
|---|---|---|---|
| 插件动态安装/卸载 | ✅ 事务安装 + 回滚 | 满足 | — |
| 完整性/签名校验 | ✅ SHA256 + 签名裁决 | 满足 | — |
| 跨插件服务 | ✅ `ServiceKey`（进程内） | 部分 | 缺口 6 |
| 崩溃隔离 | ✅ 崩溃分类 + 禁用 | 部分（仅进程内） | 缺口 3 |
| 敏感 API 门控 | ✅ 三级规则 | 部分（无能力维度） | 缺口 5 |
| **大文件按需下发** | ❌ 只整包 APK | 不满足 | 缺口 1 |
| **可执行二进制** | ❌ 只 `.so` | 不满足 | 缺口 2 |
| **进程/内存隔离** | ❌ 单进程 | 不满足 | 缺口 3 |
| **native 生命周期** | ❌ 无钩子 | 不满足 | 缺口 4 |

---

## 5. 实施路线（按依赖关系）

```
P0  缺口 1（资产下载） + 缺口 5（能力声明）
        ↓
P1  缺口 4（native 生命周期钩子） → MNN 可落地
        ↓
P2  缺口 3（进程隔离） + 缺口 6（跨进程服务） → 隔离性完备
        ↓
P2  缺口 2（可执行资产） → Proot 才可落地
```

**一句话总结**：框架当前**不满足** Proot，**部分满足** MNN；要做这两类场景，框架必须从
「单进程 + APK 粒度」演进到「多进程 + APK/资产分离 + 能力声明」的模型。P0 两项（资产下载
+ 能力声明）是共用地基，做完后 MNN 可立即接入，Proot 还差进程隔离和可执行资产两个硬骨头。

---

## 6. 实施状态（2026-08-24）

六个缺口已**全部落地**，Rust 全量测试 148 通过、`assembleRelease` 构建成功。

### 6.1 缺口 5（能力声明）✅
- Rust `plugin-core`：`Capability` 枚举（`Exec/Gpu/Network/Storage/Camera`）、
  `InstallRequest.capabilities`、`PluginRecord.capabilities`（持久化）、
  `Charter::adjudicate_capabilities`（逐能力授权 + 缓存）。
- FFI：`FfiCapability` 枚举 + `adjudicateCapabilities` 方法 + 两个 record 字段。
- Kotlin：`InstallExecutor` 解析 `jasmine.plugin.capabilities` meta-data；
  `PluginHost.installPlugin` 安装时逐能力裁决并记录授权；新增 `PluginHost.checkCapability`
  运行时门控（复用 `capability:<name>` 权限键）。

### 6.2 缺口 1（资产下载）✅
- 新增 `update/AssetDownloader`：`AssetManifest`（name/sha256/size/url/optional）、
  HTTP Range 断点续传、SHA-256 校验、磁盘配额 + 最旧资产淘汰。
- `PluginHost.assetDownloader()` 暴露。

### 6.3 缺口 4（native 生命周期）✅
- `PluginEntry` 新增 `onNativeReady(context)` / `onNativeRelease()` 钩子（默认空实现）。
- `LifecycleExecutor` 在 load 时 `onLoad→onNativeReady`，unload 时 `onNativeRelease→onUnload`。

### 6.4 缺口 2（可执行资产）✅
- `InstallExecutor` 提取 `assets/exec/` 到 `exec/` 目录并 `+x`。
- 新增 `proxy/ExecBridge`：`executablePath` / `run`（`ProcessBuilder` 直跑）/ `runNative`
  （dlopen 桥，`EXEC` 能力门控）。
- **native shim `libexecbridge.so`**（`core-plugin/src/main/cpp/exec_bridge.c` + CMake）：
  `dlopen` 加载 PIE 可执行资产 → `dlsym("main")` → 新线程运行，规避 Android 10+ noexec。
- 验证产物：`sample-example` 的 `hello.c`（PIE）编译后经 `copyHelloExec` 复制到
  `assets/exec/hello`，随插件分发。

### 6.5 缺口 3（进程隔离）✅
- 新增 `process/ProcessIdentity`：`/proc/self/cmdline` 进程名探测。
- 新增 `process/IsolatedPluginProcessService`：`:plugin_isolated` 进程宿主，`awaitReady` 后
  真正加载插件。
- 新增 `process/ProcessIsolationManager`：plugin→process 映射持久化（JSON）、
  isolate/release/mark。
- `PluginHostApplication` 按进程分流：宿主加载非隔离插件，隔离进程不自动加载；
  `PluginHost.initialize` 新增 `loadFilter`；`launchPlugin` 感知隔离转交；
  `InstallExecutor` 解析 `jasmine.plugin.isolated` meta-data。

### 6.6 缺口 6（跨进程服务）✅
- `PluginProcessBridge` 的 Server 单例化（多次 bind 共享同一 name→Binder 目录）。
- 新增 `process/RemoteServices` + `RemoteServiceKey`：隔离进程 `publish`（进程内直注册），
  宿主 `resolve`（本地优先、跨进程走 bound bridge）。
- `PluginHost.publishRemoteService` / `resolveRemoteService` 暴露。

### 6.7 验证与边界
- **验证**：Rust 148 测试通过；`assembleRelease` 全量构建成功；`libexecbridge.so` 编译 4 ABI；
  `hello` PIE 打进插件 `assets/exec/hello`。
- **真机验证路径**：
  1. 给插件加 `jasmine.plugin.isolated=true` meta-data → 观察 logcat 插件在 `:plugin_isolated`
     进程加载；
  2. 调用 `PluginHost.execBridge().runNative(pluginId, "hello", listOf("a","b"))` → 返回 42；
  3. 隔离进程发布 Binder 服务 → 宿主 `resolveRemoteService` 解析。
- **边界**：`ExecBridge.runNative` 要求资产为 `-fPIE -pie` 且导出 `main`；`dlopen` 的
  PIE 支持依赖 bionic linker（API 21+ 基本支持）。跨进程服务 value 必须是 `IBinder`。
