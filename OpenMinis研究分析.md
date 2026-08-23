# OpenMinis 架构研究分析

> 记录时间：2026-08-24
> 研究对象：`https://github.com/OpenMinis/OpenMinis`（commit `9cf3a855fecd27bb5735b84cacbd56852a3ab8dd`）+ 子模块 `https://github.com/OpenMinis/proot`（commit `8cf13e99`）
> 定位：设备端 AI Agent 的生产级参考实现，核心是"App 内跑完整 Linux 沙盒 + agent 通过命令透明调用设备能力"

---

## 一、OpenMinis 是什么

设备端 AI Agent（GPLv3）：把 Claude/GPT/Gemini 装进原生 App，给它们一个"真电脑"——**设备上跑的 Alpine Linux shell** + 浏览器自动化 + skills + 持久记忆 + 系统集成。

核心卖点也是技术难点：**"A real Linux shell — a sandboxed Alpine Linux environment runs on-device"**。

---

## 二、双平台沙盒策略（关键设计决策）

iOS 和 Android 用**两套完全不同的沙盒技术**，但抽象出**统一接口**：

| | iOS | Android |
|---|---|---|
| 沙盒引擎 | **iSH**（自研 ARM64 fork） | **PRoot**（自研 fork + talloc） |
| 原理 | 用户态 ARM64 JIT 解释器（Asbestos 引擎）+ 内核级 syscall 模拟 | 用户态 ptrace 拦截系统调用 |
| 文件系统 | fakefs（SQLite 元数据层虚拟 FS） | 真实内核 + bind mount |
| 进程模型 | 内核内进程管理 | **process-per-command**（每条命令 fork 真实进程） |
| 统一抽象 | `ISHKernel` | `PRootKernel` |

通过 `PRootKernel` ↔ `ISHKernel` 的镜像设计（代码大量 "Mirrors iOS ..."），把「沙盒启动、rootfs 安装、bind mount、DNS、native offload」抽象成同一套语义，上层 agent 完全无感。

---

## 三、PRoot 集成技术细节（Android）

### 1. PRoot 二进制 = 编译成 PIE 放 jniLibs

```kotlin
val prootBinary: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
```

`build_proot.sh` 把 proot 编译成 **PIE**（`-fPIE -pie`）+ **静态链接 talloc**，命名 `libproot.so` 打进宿主的 `jniLibs/arm64-v8a/`，运行时落在 `nativeLibraryDir`，直接 `execve`。

**关键约束**：这是"**宿主自有资产 + 编译时打包**"才成立的方案——`nativeLibraryDir` 可执行但只读，只有宿主编译时打进 jniLibs 的资产能落这里。

### 2. 命令构造（process-per-command）

```
proot -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w /root
      [-b host:linux ...] --native-offload=<socket>:<handlers>
      /bin/sh -c "<command>"
```

- `-0`：伪 root；`--link2symlink`：`link()` 转 `symlink()`（规避 Android 拒绝跨目录硬链接导致 apk 装 busybox 失败）
- `-b host:linux`：bind mount，每会话目录 `/var/minis/*`
- `--native-offload=`：自研扩展

### 3. rootfs 分发：assets 打包 + 手写 tar 解析

rootfs 打 **assets 包**（`alpine-minirootfs.tar.gz`），`extractTar` 是**手写 POSIX tar 解析器**（不依赖外部库），带 `ProgressInputStream` 进度回传。

---

## 四、Native Offload 系统（最有价值的架构创新）

### 原理

guest 执行 `execve("apple-calendar", ...)` → **PRoot 扩展拦截 execve** → **abstract unix socket** 转发 argv/env/cwd 给宿主 `NativeOffloadServer` → 宿主 handler 访问 Android 框架 → 结果写 guest `/tmp` → proot 把 execve 改写为 `/bin/cat <tmpfile>`，guest 看到的就是 stdout。

```
guest: apple-calendar --today
  → proot 拦截 execve
    → abstract socket → NativeOffloadServer
      → CalendarOffloadHandler.handle()  // 宿主 Kotlin 访问框架
        → 结果写 tmpfile
  → proot 改写为 /bin/cat tmpfile
guest 看到: JSON 结果
```

### proot fork 的 C 实现（`native_offload.c`）

- 拦截 `execve`/`execveat`（extension 的 `SYSCALL_ENTER_START`）
- 读 tracee 内存（`peek_word`/`read_string`/`peek_reg`）抠出 argv/env/cwd
- abstract socket 二进制协议（little-endian）：`MAGIC('NOFF') + version + pid + argc + argv[] + envc + envp[] + cwd`
- `rewrite_as_cat`：`set_sysarg_data`/`alloc_mem`/`write_data`/`poke_reg` 改写 execve 为 `/bin/cat <tmpfile>`

**设计取舍**：exit code 无法经 `cat` 传播，guest 侧统一返回 0（与 iOS 一致）。

### 25 个 offload handlers（Android）

`Alarm/Calendar/Clipboard/Contacts/Weather/Photos/Speech/Location/Notification/Shizuku/BrowserUse/...`——设备能力以**命令形式**暴露给 agent。

### 权限模型

`OffloadPermissionManager`：**per-chat-session 的 ASK_ONCE 授权**（经 `MINIS_CHAT_SESSION_ID` env 传递 session）。

---

## 五、PTY 桥 + 执行协调器

- `pty_bridge.c`：封装 bionic `forkpty()`，给 shell 真 TTY（PS1 提示符、回显、Ctrl+C/箭头键）
- `ExecutionCoordinator`：FIFO 串行（一次一条命令）、每会话 mount、prompt 正则检测、超时 10 分钟、输出缓冲 100KB 上限

---

## 六、与我们插件化框架的对照

| 我们框架的能力 | OpenMinis 的答案 | 适用性判断 |
|---|---|---|
| 可执行资产（P2-缺口2） | **PIE + jniLibs + execve** | ❌ 仅宿主自有资产，插件用不了 |
| 可执行资产（P2-缺口2） | **dlopen 桥**（我们的实现） | ✅ 插件运行时资产唯一解 |
| 插件调用宿主能力 | **native_offload：execve 拦截 + abstract socket + /bin/cat 改写** | ✅ 可借鉴（比 Binder/ServiceKey 更轻） |
| 跨进程通信 | **abstract unix socket**（同 UID 内） | ✅ 可借鉴（比 Binder 简单） |
| 大文件分发 | **assets 打包 + 手写 tar** | 与"资产下载"互补 |
| 能力门控 | **per-session ASK_ONCE** | 比安装级授权更细粒度 |
| 进程隔离 | **process-per-command** | 天然隔离 |

---

## 七、关键结论（2026-08-24 确认）

### 可执行资产：dlopen 桥是插件场景的必需方案，保留

**场景决定了方案，不是二选一：**

- **`nativeLibraryDir` 是只读的**，只有宿主"编译时打进 jniLibs"的资产能落这里，才能直接 execve（OpenMinis 的 proot 属于这种）。
- **`filesDir` 是 noexec**，插件运行时从 APK 提取的可执行资产只能落这里，execve 必失败。
- 因此**插件场景**（我们框架的定位：插件运行时自带可执行文件）下，**dlopen 桥是绕不开的必需方案**，不是"兜底"。

### 结论

1. 我们框架的 `ExecBridge` + dlopen 桥（`exec_bridge.c`）方向正确，**保留不改**。
2. OpenMinis 的 execve 方案（PIE + jniLibs）只适用于宿主自有资产，不适用于插件。
3. OpenMinis 对框架的真正可借鉴点是 **native_offload 模式**（execve 拦截 + abstract socket 转发）和 **abstract unix socket 跨进程通信**——这两个是"插件调用宿主能力"的轻量方案，比 Binder 桥/ServiceKey 更简洁，作为后续增强方向记录在案。

---

## 八、已实施的两个方向（2026-08-24）

### 8.1 abstract unix socket 跨进程 RPC ✅
- 新增 `process/AbstractSocketChannel`：abstract socket（`LocalServerSocket` + `LocalSocket`）
  帧通信，帧格式 `[u32 大端长度][payload]`，request/response 短连接 + 每连接 worker 线程，
  绑定 EADDRINUSE 退避重试（处理 OOM 杀后 socket 未释放）。

### 8.2 native_offload 模式（命名能力分发）✅
- 新增 `process/OffloadDispatcher`：宿主注册 handler（`name → OffloadHandler`），
  调用方以命令名（argv[0]）调用，返回 `OffloadResult(exitCode, output)`。
- 进程感知分发：本地 handler 进程内直调，未注册名走 `AbstractSocketChannel` 转发到宿主。
- 协议为 JSON over length-prefixed 帧（request = `{argv, env}`，response = `{exitCode, output}`），
  比 OpenMinis 的手写 little-endian 二进制更简洁（全 Kotlin 场景无需二进制）。
- `PluginHost.registerOffload / unregisterOffload / dispatchOffload` 暴露；
  `initialize` 时宿主进程自动 `startServer()`（隔离进程只消费不服务）。
- 权限沿用现有 `checkCapability` 能力门控模型（未照搬 per-session ASK_ONCE）。

### 8.3 与 OpenMinis 的差异（刻意保留）
- OpenMinis 的 `native_offload` 是 **execve 拦截**（proot C 扩展），我们无 guest Linux，故改为
  **命名分发 API**（`dispatchOffload`），语义等价但更直接。
- OpenMinis 的 `OffloadPermissionManager` 是 per-session ASK_ONCE，我们复用能力声明模型，
  后续若需要会话级授权可再叠加。

### 8.4 验证
- Kotlin Robolectric 单测 3 个（本地分发 + argv[0] 语义 + JSON 协议往返）。
- `assembleRelease` 全量构建成功。

### 8.5 未实施的保留项
- **per-session 授权**：`OffloadPermissionManager` 的会话级 ASK_ONCE，比现有安装级授权更贴合
  agent 场景（后续可叠加到 `OffloadDispatcher` 上）。
