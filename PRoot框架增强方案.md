# PRoot 框架增强方案（最优·最完整·纯框架侧）

> **目标对齐**（引自 [`PRoot插件可行性与缺口分析.md`](PRoot插件可行性与缺口分析.md) §0）：
> **让 PRoot Linux 以插件形式运行在本框架里。** 框架据此改造、补齐能力后，即可支持 PRoot 插件。
>
> **本文档范围**：**仅框架（`core-plugin` / `build-logic`）侧增强**。不包含插件侧实现（PRoot 插件本体、rootfs 分发、终端 UI 等属后续插件工作）。
>
> **设计原则**：
> 1. 框架不打包任何 PRoot 专属产物——所有增强都是**通用能力**。
> 2. 穷尽框架能为插件提供的执行底座能力，让未来的 PRoot 插件（及任何类似工作负载）只需插件侧配置即可无缝接入。
> 3. 一切以真机实证为准（探针 A–E 已全绿）。
> 4. 每项增强均标注互联网研究来源与业界最佳实践。
>
> 记录时间：2026-08-25（方案）；2026-08-26 更新实施状态
> 依据：两份既有文档 + `proot-termux/` 源码审计 + 三路互联网深度研究（2025-2026，覆盖 oonid/pr、openclawd-termux、proot-distro、Winlator、termux/proot issues、Android 内核文档等 40+ 来源）
>
> **实施状态（2026-08-26）**：✅ **23 项增强（E1–E23）已全部实现并通过编译**（`:core-plugin:compileDebugKotlin` + `externalNativeBuildDebug`）。落地位置见 §3 总表与 §5 批次状态。

---

## 1. 框架现状盘点

### 1.1 已具备的执行底座原语

| 能力 | 实现 | 位置 |
|---|---|---|
| system_linker_exec 子进程 | `ExecBridge.runViaLinker`：EXEC 门控、env/cwd 注入、返回 `Process` | `proxy/ExecBridge.kt` |
| dlopen 桥（进程内） | `ExecBridge.runNative`：env/cwd 进程级注入 + 还原 | `proxy/ExecBridge.kt` + `cpp/exec_bridge.c` |
| 直接 ProcessBuilder（遗留） | `ExecBridge.run`：仅 mount 允许时可用 | `proxy/ExecBridge.kt` |
| 原生可执行文件托管管线 | 构建期 `nativeExecutables` → 宿主 jniLibs；运行时 `nativeExecutablePath` 定位 | `build-logic/` + `ExecBridge.kt` |
| 子进程生命周期监督 | `ChildProcessSupervisor`：异步启动、stdout/stderr 双泵、死亡回调、重启策略 | `proxy/ChildProcessSupervisor.kt` |
| 插件卸载回收 | `PluginHost.unloadPlugin/uninstallPlugin` → `stopAll(pluginId)` | `PluginHost.kt` |
| 隔离进程 | `:plugin_isolated_N` 4 槽 + 完整 PluginHost | `process/ProcessIsolationManager.kt` |
| 资产下载 | `AssetDownloader`：断点续传 + digest 校验 + 磁盘配额 | `update/AssetDownloader.kt` |
| 跨进程 offload | `OffloadDispatcher`：abstract socket + 命令路由 | `process/OffloadDispatcher.kt` |
| 真机探针 A–E | execve / mmap / memfd / ptrace / seccomp / linker_exec / ProcessBuilder | `cpp/exec_bridge.c` + `proxy/ProotProbe.kt` |

### 1.2 已确认的设备层事实（探针全绿）

- `nativeLibraryDir` execve → ✅（探针 A）
- ptrace TRACEME+GETREGSET+CONT → ✅（探针 B）
- seccomp BPF 安装 → ✅（探针 C）
- system_linker_exec → ✅（探针 D）
- ProcessBuilder+linker64 → ✅（探针 E）
- mmap PROT_EXEC app_data_file → ✅（探针 2）
- execve app_data_file → ❌ EACCES（探针 1）
- memfd+fexecve → ❌ EACCES（探针 3）

---

## 2. 缺口全集与最优策略

> 整合：两份文档 + 源码审计 + **三路互联网深度研究**的全部缺口。
> 层级：T1 = PRoot 跑通的必要条件；T2 = 健壮性；T3 = 可观测性；T4 = 高级能力。
> 归属：🔧 框架改动；🟢 已具备无需改。

---

### T1：PRoot 跑通的必要框架能力

#### E1. LD_LIBRARY_PATH 自动注入 + SONAME 兼容 🔧

- **缺口**：`runViaLinker` 不设置 `LD_LIBRARY_PATH`。动态链接二进制经 linker64 执行时，链接器搜索序为 DT_RUNPATH → LD_LIBRARY_PATH → app nativeLibraryDir → 系统目录，**不搜索二进制自身目录**。
- **互联网研究**：
  - linker 无 `--library-path` 参数，`LD_LIBRARY_PATH` 是 system_linker_exec 场景**唯一**可用的运行期库路径机制（NCC Group）。
  - openclawd-termux 把 `libtalloc.so` **复制为 `libtalloc.so.2`** 以匹配 SONAME（Android 包管理器只认 `lib*.so` 命名）。
  - CLU-BOX PR #5 实证：移除/错误设置 `LD_LIBRARY_PATH` 会直接导致 linker64 mmap 段错误。
  - **最佳实践**：宿主层设置 `LD_LIBRARY_PATH`，guest 环境用 `env -i` 完全重置，防止 `LD_LIBRARY_PATH` 泄漏进 guest 污染 glibc 程序。
- **增强**：
  1. `runViaLinker` 在插件未显式提供 `LD_LIBRARY_PATH` 时，自动注入 `nativeLibraryDir + 插件 lib/<abi> 目录`。
  2. 新增 `ExecBridge.prepareSonameLibs(pluginId, mappings: Map<String, String>)`：把插件声明的 `.so` 文件复制/符号链接为 SONAME 匹配名（如 `libtalloc.so` → `libtalloc.so.2`），落插件 `lib/<abi>/` 目录。
  3. 新增 `ExecBridge.guestEnvReset(): Map<String, String>`：返回 `env -i` 等效的最小环境（`HOME`/`PATH`/`TERM`/`LANG`），供插件构造 guest 命令前缀。
- **实现**：`ExecBridge.kt`（~50 行）。
- **优先级**：T1

#### E2. nativeLibraryDir 访问器 🔧

- **缺口**：插件构造 `PROOT_LOADER`/`LD_LIBRARY_PATH` 需要知道宿主 `nativeLibraryDir` 绝对路径。
- **增强**：`ExecBridge` 新增 `nativeLibraryDir(): File`。
- **实现**：一行方法。
- **优先级**：T1

#### E3. pluginTmpDir 便利方法 🔧

- **缺口**：PRoot 需要 `PROOT_TMP_DIR`（只需可写）。不设且默认 `/tmp` 不存在时会报 "can't create glue rootfs / can't create temporary directory"（Termony issue #51）。
- **增强**：`ExecBridge` 新增 `pluginTmpDir(pluginId): File`，返回并确保 `pluginDir/tmp` 存在。
- **实现**：复用 `InstallExecutor.pluginDir`，追加 `/tmp`，`mkdirs()`。
- **优先级**：T1

#### E4. PTY 支持（伪终端）🔧

- **缺口**：当前 `runViaLinker` 返回的 `Process` 使用 pipe。PRoot 运行交互式 shell 需要 **PTY**——pipe 不提供终端语义（无行编辑、无信号生成、无 `isatty()` 为真），guest 内的程序（bash、vim、top 等）会降级或失败。
- **互联网研究**：Termux 使用 `JNI forkPty`（`termux-app/terminal/jni/`）；oonid/pr 使用 `openpty` + `fork`；所有非 Termux proot 应用都需要 PTY 才能提供交互式终端。
- **增强**：
  - `exec_bridge.c` 新增 `nativeSpawnWithPty`：`openpty()` → `fork()` → 子进程 `setsid()` + `ioctl(TIOCSCTTY)` + `execve(linker64, ...)` → 父进程返回 master fd。
  - `ExecBridge.kt` 新增 `runViaLinkerWithPty(pluginId, relPath, args, workDir, env, rows, cols): PtyProcess`。
  - `PtyProcess` 类：封装 master fd 的 `InputStream`/`OutputStream`、`resize(rows, cols)`（`ioctl(TIOCSWINSZ)`）、`waitFor()`、`destroy()`。
  - `ChildProcessSupervisor` 新增 `startWithPty(spec): ManagedPtyProcess`，复用现有泵/重启/回收逻辑。
- **实现**：`exec_bridge.c`（~120 行 C）+ `ExecBridge.kt`（~60 行）+ `PtyProcess.kt`（新文件 ~100 行）+ `ChildProcessSupervisor.kt`（~30 行）。
- **优先级**：T1

#### E5. 信号转发 🔧

- **缺口**：`Process.destroy()` 发送 SIGKILL。PRoot 需要 **SIGTERM**（优雅退出）→ 等待 → SIGKILL（强制）的升级序列。
- **增强**：
  - `ManagedProcess` 新增 `signal(sig: Int)` 方法，经 `kill(pid, sig)` 发送。
  - `ManagedProcess` 新增 `destroyGracefully(timeoutMs: Long)`：SIGTERM → 等待 → SIGKILL。
  - `ChildProcessSupervisor.stopAll` 改用优雅关闭序列。
- **实现**：`ChildProcessSupervisor.kt`（~40 行）。`Process.pid()` 在 API 26+ 可用（minSdk 26 满足）。
- **优先级**：T1

#### E6. 进程组管理 🔧

- **缺口**：PRoot fork 出 tracee 子进程。`Process.destroy()` 只杀 proot 本身，tracee 成为孤儿。
- **增强**：
  - `nativeSpawnWithPty`（E4）中子进程调用 `setsid()` 创建新会话/进程组。
  - `ManagedProcess.destroy()` 改为 `killpg(pgid, SIGKILL)` 杀整个进程组。
  - `destroyGracefully()` 改为 `killpg(pgid, SIGTERM)` → 等待 → `killpg(pgid, SIGKILL)`。
- **实现**：与 E4 联动。
- **优先级**：T1

#### E7. 幻影进程杀手检测与缓解 🔧（新发现）

- **缺口**：Android 12+ 的**幻影进程杀手**（Phantom Process Killer）监控应用 fork 出的子进程，**超过 32 个就杀**，表现为 `Process completed (signal 9)`。PRoot 的 tracee 树 + guest 内进程很容易触发。
- **互联网研究**：
  - termux-app#2366：大量用户报告 proot 子进程被杀。
  - 缓解：`settings put global settings_enable_monitor_phantom_procs false` + `device_config put activity_manager max_phantom_processes 2147483647`（需 adb）。
  - 12L/13+ 有开发者选项开关，但 OEM 皮肤（MIUI 等）常阉割。
- **增强**：
  - `ExecBridge` 新增 `phantomProcessKillerStatus(): PhantomKillerStatus`：
    - 读 `Settings.Global.settings_enable_monitor_phantom_procs`（需 `READ_SETTINGS` 权限或 `settings` 命令）
    - 读 `/proc/sys/kernel/phantom_process_killer` 或 `device_config` 输出
    - 返回 `ENABLED / DISABLED / UNKNOWN`
  - `ExecutionReport`（E15）包含此项。
  - 框架在检测到启用时，通过日志/回调**警告插件**。
- **实现**：`ExecBridge.kt`（~30 行）。
- **优先级**：T1（不检测则 PRoot 子进程可能被系统静默杀死，极难诊断）

#### E8. Guest 环境隔离（env -i）🔧（新发现）

- **缺口**：宿主进程的环境变量（`LD_PRELOAD`、`BOOTCLASSPATH`、`LD_LIBRARY_PATH`、`ANDROID_ROOT` 等）会泄漏进 guest，导致 glibc 程序崩溃（Node.js `os.networkInterfaces()` 在 Bionic 环境泄漏变量下崩溃）。
- **互联网研究**：
  - openclawd-termux：guest 环境用 `/usr/bin/env -i` 完全重置，只保留 `HOME`/`PATH`/`TERM`/`LANG`。
  - proot-distro `command_login`：`/usr/bin/env -i "HOME=/root" "LANG=C.UTF-8" "PATH=..." "TERM=xterm-256color" /bin/bash --login`。
  - CLU-BOX PR #5：`LD_LIBRARY_PATH` 留在 guest 环境里会引发问题。
- **增强**：
  - `ExecBridge` 新增 `guestEnvPreset(home: String, term: String = "xterm-256color"): Map<String, String>`：返回最小干净环境。
  - `ChildProcessSpec` 新增 `isolateGuestEnv: Boolean`（默认 false）：为 true 时，`runViaLinker` 的 env 只包含 `guestEnvPreset` + 插件显式追加的变量，**不继承宿主环境**。
- **实现**：`ExecBridge.kt`（~20 行）+ `ChildProcessSupervisor.kt`（~10 行）。
- **优先级**：T1

---

### T2：健壮性增强

#### E9. 子进程状态查询 API 🔧

- **缺口**：`ManagedProcess` 只暴露 `isAlive` 和 `stdin`。
- **增强**：`ManagedProcess` 新增：
  - `pid(): Int`
  - `uptimeMs(): Long`
  - `restartCount(): Int`
  - `lastExitCode(): Int?`
  - `state(): ChildState`（枚举：`STARTING / RUNNING / RESTARTING / STOPPING / STOPPED`）
- **实现**：`ChildProcessSupervisor.kt`（~30 行）。
- **优先级**：T2

#### E10. 输出环形缓冲区 🔧

- **缺口**：`onOutput` 即发即弃，崩溃后无法回溯。
- **增强**：`ManagedProcess` 内置环形缓冲区（默认 256 行），新增 `recentOutput(lines: Int): List<String>`。
- **实现**：`ChildProcessSupervisor.kt`（~25 行）。
- **优先级**：T2

#### E11. 并发子进程限制 🔧

- **缺口**：无限制——恶意/错误插件可耗尽系统资源。
- **增强**：`maxChildrenPerPlugin`（默认 4），超限抛异常。
- **实现**：`ChildProcessSupervisor.kt`（~10 行）。
- **优先级**：T2

#### E12. 优雅关闭序列（supervisor 级）🔧

- **缺口**：`stopAll` 直接 `destroyForcibly()`（SIGKILL）。
- **增强**：`stopAll(pluginId, graceful: Boolean = true, timeoutMs: Long = 5000)`：SIGTERM → 等待 → killpg(SIGKILL)。
- **实现**：`ChildProcessSupervisor.kt`（~30 行）。
- **优先级**：T2

#### E13. 环境变量预设系统 🔧

- **缺口**：每个插件都要手动构造完整 env map。
- **增强**：`ExecBridge.envPreset(pluginId): Map<String, String>`，返回：
  - `LD_LIBRARY_PATH` = nativeLibraryDir + 插件 lib/\<abi\>
  - `HOME` = 插件目录
  - `TMPDIR` = pluginTmpDir
  - `PATH` = 插件 exec 目录 + `/system/bin`
- **实现**：`ExecBridge.kt`（~25 行）。
- **优先级**：T2

#### E14. 挂起检测（多线程死锁缓解）🔧（新发现）

- **缺口**：proot 单线程事件循环在多线程 guest（Node.js/npm）下会死锁（termux/proot#326），表现为子进程"活着但不响应"。
- **互联网研究**：
  - PR #337（批量 waitpid + CLONE 优先排序）是已知最佳修复但**未合并**（2026-05 关闭）。
  - 无 fork 改成多线程事件循环。
  - 各项目实际做法：重试（npm 挂起后重跑往往成功）。
  - **框架层可做的**：监控子进程 `/proc/PID/status` 是否长时间处于 `t`（tracing stop）状态 + 超时触发重启。
- **增强**：
  - `ChildProcessSpec` 新增可选 `hangDetection`：
    - `checkIntervalMs`：检查间隔（默认 10_000）
    - `hangThresholdMs`：判定挂起的阈值（默认 60_000）
    - `onHangDetected`：回调（插件可决定重启/通知用户）
  - 实现：定期读 `/proc/<pid>/status` 的 `State:` 行，若连续 N 次为 `t (tracing stop)` 且无输出，判定挂起。
- **实现**：`ChildProcessSupervisor.kt`（~50 行）。
- **优先级**：T2

#### E15. /proc 伪文件管理基础设施 🔧（新发现）

- **缺口**：Android 8+ SELinux/seccomp 屏蔽 `/proc/stat`、`/proc/loadavg`、`/proc/uptime`、`/proc/version`、`/proc/vmstat`、`/proc/net/*` 等文件的读取。guest 内的 `top`/`htop`/`apt` 等依赖这些文件。
- **互联网研究**：
  - **proot-distro 方案（事实标准）**：安装时在 rootfs 内生成静态假文件（`.loadavg`、`.stat`、`.uptime`、`.version`、`.vmstat`），登录时用 `-b` 绑定到 `/proc/*`。v5.1.0 新增伪造 `/proc/sys/kernel/overflowuid`/`overflowgid`。
  - **openclawd-termux 扩展集**：额外伪造 `cap_last_cap`、`max_user_watches`、`fips_enabled`，并把空目录绑定到 `/sys/fs/selinux`。
  - `/proc/net/*` 的 `-b` 绑定可能不生效（termux-packages discussion #27568），应视为可降级场景。
- **增强**：
  - `ExecBridge` 新增 `procFakeDir(pluginId): File`：返回并确保 `pluginDir/proc_fakes/` 存在。
  - `ExecBridge` 新增 `generateProcFakes(pluginId, entries: Map<String, String>)`：在 `proc_fakes/` 下生成假文件（如 `stat`、`loadavg`、`uptime`、`version`、`vmstat`），内容为插件提供的静态文本。
  - `ExecBridge` 新增 `procFakeBindings(pluginId): List<Pair<String, String>>`：返回 `-b` 参数对列表（如 `["<proc_fakes>/stat", "/proc/stat"]`），插件直接拼入启动参数。
  - 框架**不硬编码**任何 /proc 条目内容（通用能力），由插件决定伪造哪些。
- **实现**：`ExecBridge.kt`（~40 行）。
- **优先级**：T2

---

### T3：可观测性与诊断

#### E16. Native spawn（精确控制）🔧

- **缺口**：`ProcessBuilder` 无法控制 `setsid`、`rlimits`、精确 `env`（只能 putAll 不能清空继承环境）、`cwd` 的原子性。
- **增强**：`exec_bridge.c` 新增 `nativeSpawn`：
  - `fork()` → 子进程：`setsid()` → `chdir(workDir)` → `clearenv()` → 逐项 `setenv` → `execve(linker64, argv)` → `_exit(errno)`
  - 父进程：返回 PID + 三个管道 fd
  - 支持 `rlimits`（`setrlimit`）
- **Kotlin 封装**：`ExecBridge.spawnNative(pluginId, relPath, args, workDir, env, rlimits): NativeChildProcess`
- **实现**：`exec_bridge.c`（~150 行 C）+ `ExecBridge.kt`（~50 行）+ `NativeChildProcess.kt`（新文件 ~80 行）。
- **优先级**：T3

#### E17. 崩溃诊断信息收集 🔧

- **缺口**：子进程被信号杀死时，框架只报告退出码。
- **增强**：
  - `ManagedProcess.onExit` 回调扩展：`(exitCode: Int, termSignal: Int?, willRestart: Boolean)`
  - `termSignal` 非 null 表示被信号杀死（如 SIGSYS=31 表示 seccomp 拦截，SIGKILL=9 可能是幻影进程杀手）
- **实现**：`ChildProcessSupervisor.kt`（~15 行）。
- **优先级**：T3

#### E18. SELinux 上下文探测 🔧

- **缺口**：框架无法在运行时确认当前进程的 SELinux 上下文。
- **增强**：`ExecBridge` 新增 `selinuxContext(): String`，读取 `/proc/self/attr/current`。
- **实现**：`ExecBridge.kt`（~10 行）。
- **优先级**：T3

#### E19. 执行能力综合报告 🔧

- **缺口**：探针 A–E 分散，无统一报告。
- **增强**：`ExecBridge` 新增 `executionCapabilityReport(): ExecutionReport`，整合：
  - dlopen 桥可用性
  - nativeLibraryDir 路径与可写性
  - 探针 A–E 结果（缓存）
  - SELinux 上下文（E18）
  - ptrace_scope（读 `/proc/sys/kernel/yama/ptrace_scope`，文件不存在 = Yama 未启用，视为 0；≥2 则 proot 必然无法工作）
  - seccomp 状态（读 `/proc/self/status` 的 `Seccomp:` 行）
  - **幻影进程杀手状态**（E7）
  - 设备 ABI 列表
- **实现**：`ExecBridge.kt`（~50 行）+ `ExecutionReport.kt`（新数据类 ~40 行）。
- **优先级**：T3

#### E20. Yama ptrace_scope 检测与早期失败 🔧（新发现）

- **缺口**：若设备 `ptrace_scope >= 2`，proot 的 `PTRACE_TRACEME` 必然失败，但当前框架无检测。
- **互联网研究**：
  - 没有确认存在 `ptrace_scope >= 2` 的量产 Android 设备（GrapheneOS 开发者澄清不限制 ptrace）。
  - 但 TRACEME 失败的设备（如 docomo f-04k）几乎都是 SELinux 或厂商 seccomp 屏蔽。
  - **检测即可**：读 sysctl，≥2 直接失败报错。
- **增强**：纳入 `ExecutionReport`（E19）。`ExecBridge` 新增 `ptraceScope(): Int`（-1 = 文件不存在/Yama 未启用）。
- **实现**：`ExecBridge.kt`（~10 行）。
- **优先级**：T3

---

### T4：高级能力

#### E21. 资源限制（rlimits）🔧

- **缺口**：子进程无资源限制。
- **增强**：`nativeSpawn`（E16）支持 `rlimits` 参数（`RLIMIT_AS`/`RLIMIT_CPU`/`RLIMIT_NOFILE`/`RLIMIT_NPROC`）。
- **实现**：与 E16 联动。
- **优先级**：T4

#### E22. 多子进程编排 🔧

- **缺口**：无依赖/编排能力。
- **增强**：`startGroup(specs, mode)`：`SEQUENTIAL` / `PARALLEL`。
- **实现**：`ChildProcessSupervisor.kt`（~50 行）。
- **优先级**：T4

#### E23. 健康检查 / 存活探针 🔧

- **缺口**：子进程可能"活着但不响应"。
- **增强**：`ChildProcessSpec` 新增可选 `healthCheck`（`intervalMs` + `probe` + 连续失败触发重启）。
- **实现**：`ChildProcessSupervisor.kt`（~40 行）。
- **优先级**：T4

---

## 3. 增强总表

| 编号 | 增强 | 层级 | 改动文件 | 新增代码量（估） | 状态 |
|---|---|---|---|---|---|
| E1 | LD_LIBRARY_PATH 自动注入 + SONAME 兼容 + guest 环境隔离 | T1 | `ExecBridge.kt` | ~50 行 | ✅ 已实现 |
| E2 | nativeLibraryDir 访问器 | T1 | `ExecBridge.kt` | ~5 行 | ✅ 已实现 |
| E3 | pluginTmpDir 便利方法 | T1 | `ExecBridge.kt` | ~10 行 | ✅ 已实现 |
| E4 | PTY 支持 | T1 | `exec_bridge.c` + `ExecBridge.kt` + `PtyProcess.kt`（新） | ~280 行 | ✅ 已实现 |
| E5 | 信号转发 | T1 | `ChildProcessSupervisor.kt` | ~40 行 | ✅ 已实现 |
| E6 | 进程组管理 | T1 | `exec_bridge.c` + `ChildProcessSupervisor.kt` | ~30 行 | ✅ 已实现 |
| E7 | 幻影进程杀手检测 | T1 | `ExecBridge.kt` | ~30 行 | ✅ 已实现 |
| E8 | Guest 环境隔离（env -i） | T1 | `ExecBridge.kt` + `ChildProcessSupervisor.kt` | ~30 行 | ✅ 已实现 |
| E9 | 子进程状态查询 | T2 | `ChildProcessSupervisor.kt` | ~30 行 | ✅ 已实现 |
| E10 | 输出环形缓冲区 | T2 | `ChildProcessSupervisor.kt` | ~25 行 | ✅ 已实现 |
| E11 | 并发子进程限制 | T2 | `ChildProcessSupervisor.kt` | ~10 行 | ✅ 已实现 |
| E12 | 优雅关闭序列 | T2 | `ChildProcessSupervisor.kt` | ~30 行 | ✅ 已实现 |
| E13 | 环境变量预设 | T2 | `ExecBridge.kt` | ~25 行 | ✅ 已实现 |
| E14 | 挂起检测（多线程死锁缓解） | T2 | `ChildProcessSupervisor.kt` | ~50 行 | ✅ 已实现 |
| E15 | /proc 伪文件管理 | T2 | `ExecBridge.kt` | ~40 行 | ✅ 已实现 |
| E16 | Native spawn | T3 | `exec_bridge.c` + `ExecBridge.kt` + `NativeChildProcess.kt`（新） | ~280 行 | ✅ 已实现 |
| E17 | 崩溃诊断 | T3 | `ChildProcessSupervisor.kt` | ~15 行 | ✅ 已实现 |
| E18 | SELinux 上下文探测 | T3 | `ExecBridge.kt` | ~10 行 | ✅ 已实现 |
| E19 | 执行能力综合报告 | T3 | `ExecBridge.kt` + `ExecutionReport.kt`（新） | ~90 行 | ✅ 已实现 |
| E20 | Yama ptrace_scope 检测 | T3 | `ExecBridge.kt` | ~10 行 | ✅ 已实现 |
| E21 | 资源限制 | T4 | `exec_bridge.c` + `ExecBridge.kt` | ~30 行 | ✅ 已实现 |
| E22 | 多子进程编排 | T4 | `ChildProcessSupervisor.kt` | ~50 行 | ✅ 已实现 |
| E23 | 健康检查 | T4 | `ChildProcessSupervisor.kt` | ~40 行 | ✅ 已实现 |

**总计**：~1300 行新增代码（C + Kotlin），3 个新文件（`PtyProcess.kt`、`NativeChildProcess.kt`、`ExecutionReport.kt`）。

> **✅ 实施完成（2026-08-26）**：23 项增强已全部落地并通过编译验证。落地文件：
> - `proxy/ExecBridge.kt`（E1/E2/E3/E7/E8/E13/E15/E16/E18/E19/E20/E21 + PTY/native spawn 封装）
> - `proxy/ChildProcessSupervisor.kt`（E5/E6/E9/E10/E11/E12/E14/E17/E22/E23 + PTY 监督）
> - `proxy/PtyProcess.kt`（新，E4）、`proxy/NativeChildProcess.kt`（新，E16）、`proxy/ExecutionReport.kt`（新，E19）
> - `cpp/exec_bridge.c`（E4/E5/E6/E16/E21 的 native 层）

---

## 4. 增强后的执行底座架构

```
插件调用层
  │
  ├─ ExecBridge.envPreset()              ← E13 环境预设
  ├─ ExecBridge.guestEnvPreset()         ← E8  guest 环境隔离
  ├─ ExecBridge.nativeLibraryDir()       ← E2  执行底座落点
  ├─ ExecBridge.pluginTmpDir()           ← E3  临时目录
  ├─ ExecBridge.selinuxContext()         ← E18 SELinux 上下文
  ├─ ExecBridge.ptraceScope()            ← E20 Yama 检测
  ├─ ExecBridge.phantomProcessKillerStatus() ← E7 幻影进程
  ├─ ExecBridge.executionReport()        ← E19 综合报告
  ├─ ExecBridge.prepareSonameLibs()      ← E1  SONAME 兼容
  ├─ ExecBridge.procFakeDir()            ← E15 /proc 伪文件
  ├─ ExecBridge.generateProcFakes()      ← E15
  ├─ ExecBridge.procFakeBindings()       ← E15
  │
  ├─ ExecBridge.runViaLinker()           ← E1  LD_LIBRARY_PATH 自动注入
  ├─ ExecBridge.runViaLinkerWithPty()    ← E4  PTY 子进程
  ├─ ExecBridge.spawnNative()            ← E16 native spawn
  │
  └─ ChildProcessSupervisor
       ├─ start(spec)                    ← 现有 + E11 并发限制 + E8 环境隔离
       ├─ startWithPty(spec)             ← E4  PTY 监督
       ├─ startGroup(specs, mode)        ← E22 多子进程编排
       ├─ stop / stopAll                 ← E5 信号 + E6 进程组 + E12 优雅关闭
       ├─ ManagedProcess
       │    ├─ signal(sig)               ← E5
       │    ├─ destroyGracefully()       ← E5 + E6
       │    ├─ pid/uptime/state          ← E9
       │    ├─ recentOutput()            ← E10
       │    ├─ hangDetection             ← E14
       │    └─ healthCheck               ← E23
       └─ onChildExit                    ← E17 崩溃诊断（信号号）
```

---

## 5. 实施顺序

| 批次 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| **批次 1**（基础补全） | E1 + E2 + E3 + E7 + E8 + E13 + E18 + E20 | 无 | ✅ 完成 |
| **批次 2**（子进程控制） | E5 + E6 + E9 + E10 + E11 + E12 + E17 | 无 | ✅ 完成 |
| **批次 3**（PTY） | E4 | E5/E6 | ✅ 完成 |
| **批次 4**（/proc 伪文件 + 挂起检测） | E14 + E15 | 无 | ✅ 完成 |
| **批次 5**（native spawn） | E16 + E21 | E5/E6 | ✅ 完成 |
| **批次 6**（综合报告 + 高级） | E19 + E22 + E23 | 批次 1-5 | ✅ 完成 |

每批次完成后编译验证（`:core-plugin:compileDebugKotlin` + `:core-plugin:externalNativeBuildDebug`）。

---

## 6. 框架级验证计划

| 步骤 | 内容 | 判据 |
|---|---|---|
| F1 | E1 验证：动态链接测试二进制经 `runViaLinker` 运行，不手动设 `LD_LIBRARY_PATH` | 链接器自动找到依赖 |
| F2 | E4 验证：PTY 子进程运行 `sh`，`isatty()` 为真，行编辑可用 | 交互式终端语义正确 |
| F3 | E5/E6 验证：启动子进程 → SIGTERM → 确认优雅退出；启动带子进程的程序 → `killpg` → 确认无孤儿 | 信号+进程组正确 |
| F4 | E7 验证：读幻影进程杀手状态 | 返回正确状态 |
| F5 | E8 验证：`isolateGuestEnv=true` 时子进程环境不含宿主变量 | `env` 输出干净 |
| F6 | E14 验证：启动一个会进入 tracing stop 的进程，等待挂起检测触发 | 回调正确触发 |
| F7 | E15 验证：生成 /proc 伪文件并绑定 | 文件存在且内容正确 |
| F8 | E16 验证：native spawn 的 `setsid`/`clearenv`/`rlimits` 生效 | `/proc/<pid>/status` 确认 |
| F9 | E19 验证：综合报告输出所有探针结果 | 报告完整 |
| F10 | 回归：现有探针 A–E 不受影响 | 全绿 |

---

## 7. 互联网研究来源（2025-2026，40+ 条）

### seccomp / SIGSYS
- [oonid/pr — proot Improvements（18+ SIGSYS 处理器、push_specific_regs 修复、x0 clobber、POKEDATA workaround）](https://github.com/oonid/pr/blob/main/docs/proot-improvement.md)
- [oonid/pr — Important Notes（被拦截系统调用表、W^X、targetSdk 35）](https://github.com/oonid/pr/blob/main/docs/important-notes.md)
- [termux/proot — src/tracee/seccomp.c（~30 个处理器）](https://github.com/termux/proot/blob/master/src/tracee/seccomp.c)
- [coderredlab/proroot — issue #4（clone3 TRAP 先杀线程 + 二进制补丁修复）](https://github.com/coderredlab/proroot/issues/4)
- [termux-etc-redirect PR #2（SECCOMP_RET_USER_NOTIF 优先级低于 TRAP，已实证否决）](https://github.com/rios0rios0/termux-etc-redirect/pull/2/files)
- [bun issue #30766（close_range 被 SIGSYS 杀死 + 运行时探测伪代码）](https://github.com/oven-sh/bun/issues/30766)
- [XDA — Android 15 set_robust_list SIGSYS](https://xdaforums.com/t/running-native-glibc-debian-binaries-on-android-15-without-proot.4788725/)
- [termux-packages issue #30082（landlock_create_ruleset 被拦）](https://github.com/termux/termux-packages/issues/30082)
- [Linux 内核文档 — Seccomp BPF 返回值优先级](https://www.kernel.org/doc/html/latest/userspace-api/seccomp_filter.html)

### LD_LIBRARY_PATH / SONAME / 环境隔离
- [NCC Group — Cross-Execute Your Linux Binaries（linker 无 --library-path）](https://www.nccgroup.com/es/research-blog/cross-execute-your-linux-binaries-don-t-cross-compile-them/)
- [openclawd-termux — PRoot 环境与 Bionic Bypass（libtalloc.so→libtalloc.so.2、LD_LIBRARY_PATH、env -i）](https://deepwiki.com/mithun50/openclawd-termux/2.2-proot-environment-and-bionic-bypass)
- [CLU-BOX PR #5（LD_LIBRARY_PATH 缺失导致 linker64 段错误）](https://github.com/Flynn013/CLU-BOX/pull/5/files)
- [Android-Proot-Builder（静态 talloc -TallocLink static）](https://github.com/wuxianggujun/Android-Proot-Builder/blob/main/proot-compilation-guide.md)

### PROOT_LOADER / link2symlink / 启动参数
- [oonid/pr — Phase 7（PROOT_LOADER in nativeLibraryDir、seccomp 静态分析）](https://github.com/oonid/pr/blob/main/docs/phase7-targetSdk35.md)
- [termux-packages issue #27606（PROOT_UNBUNDLE_LOADER 与 loader 膨胀）](https://github.com/termux/termux-packages/issues/27606)
- [proot-distro 架构解析（command_login 参数逐项分析）](https://blog.csdn.net/gitblog_00341/article/details/155058690)
- [openclawd-termux — Terminal Service（~49 个参数、宿主层环境分层）](https://deepwiki.com/mithun50/openclawd-termux/5.2-terminal-service-and-proot-configuration)
- [Winlator（PROOT_LOADER_32 条件设置）](https://blog.csdn.net/gitblog_00688/article/details/150800036)
- [claude-android-proot-f2fs（PROOT_L2S_DIR 必须在 rootfs 内）](https://github.com/ART449/claude-android-proot-f2fs)
- [termux/proot-distro issue #592（.l2s 目录创建日志）](https://github.com/termux/proot-distro/issues/592)

### /proc 伪文件 / hidepid
- [proot-distro v5.1.0 release（overflowuid 伪造）](https://newreleases.io/project/github/termux/proot-distro/release/v5.1.0)
- [Termux Wiki FAQ（hidepid=2、/proc 屏蔽清单）](https://wiki.termux.com/wiki/FAQ)
- [termux-packages discussion #27568（/proc/net/* 绑定不生效）](https://www.github.com/termux/termux-packages/discussions/27568)

### 多线程死锁 / ptrace
- [termux/proot issue #326（npm 多线程死锁）](https://github.com/termux/proot/issues/326)
- [termux/proot PR #337（批量 waitpid + CLONE 优先排序，已关闭未合并）](https://github.com/termux/proot/pull/337)
- [oonid/pr — Phase 8（vfork/CLONE_VM 修复、link2symlink readlink 泄漏）](https://github.com/oonid/pr/blob/main/docs/phase8-rust-support.md)
- [Linux kernel Yama LSM（ptrace_scope 0–3）](https://docs.kernel.org/6.5/admin-guide/LSM/Yama.html)
- [nix-on-droid#130（GrapheneOS ptrace 澄清）](https://github.com/t184256/nix-on-droid/issues/130)
- [pdocker-android（Android 15/kernel 6.6 写内存阻断，弃用 proot）](https://github.com/ryo100794/pdocker-android/blob/main/docs/design/RUNTIME_STRATEGY.md)

### 幻影进程 / 16KB 对齐
- [termux-app#2366（Android 12 幻影进程杀手）](https://github.com/termux/termux-app/issues/2366)
- [Andronix — Android 12 幻影进程修复](https://docs.andronix.app/android-12/andronix-on-android-12-and-beyond)
- [termux-app#4185（16KB 页大小支持与 Play Store 时间线）](https://github.com/termux/termux-app/issues/4185)
- [16KB page-size enforcement（Play Console 2025-11）](https://digitalradium.com/why-your-android-apps-must-prepare-for-the-16kb-memory-page-shift/)

### 其他
- [Termux execution environment (termux-packages wiki)](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)
- [green-green-avk/proot（相对 loader 路径、--mute-setxid、--bind-memfd）](https://github.com/green-green-avk/proot/)
- [OpceanAI/Doki-proot（seccomp 过滤、microVM 检测）](https://github.com/OpceanAI/Doki-proot/wiki/Architecture)
- [proot(1) man page](https://manpages.debian.org/testing/proot/proot.1.en.html)
- [termux/proot-distro（v5.8.0）](https://github.com/termux/proot-distro)
- [Habr — linker64 + termux-exec（Sept 2025）](https://habr.com/ru/articles/943188/)
