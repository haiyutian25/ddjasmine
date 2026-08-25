# PRoot 插件：剩余工作清单（框架侧已完成）

> 上游文档：[`PRoot插件可行性与缺口分析.md`](PRoot插件可行性与缺口分析.md)
> 记录时间：2026-08-25（第二轮：结合 `proot-termux/` 源码 + 互联网研究更新）
> 范围：框架（`core-plugin`）侧按探测结论补齐执行底座的改造**已完成**；本文档记录**剩余未完成项**——真实 loader 接入、PRoot 插件本体、端到端验证，以及**源码/互联网研究新发现的框架缺口**。

---

## 0. 框架侧已完成（本轮交付）

对照上游文档 §5「框架要补的能力」，五项全部落地：

| §5 项 | 实现 | 位置 |
|---|---|---|
| 1. proot runner（system_linker_exec + fork 子进程） | `ExecBridge.runViaLinker`：EXEC 门控、env/cwd 注入、返回真实 `Process` | `core-plugin/.../proxy/ExecBridge.kt` |
| 2. 插件原生可执行文件托管管线（通用） | 构建期 `pluginPack.nativeExecutables` → 宿主 jniLibs；运行时 `ExecBridge.nativeExecutablePath` 定位（框架不打包任何插件专属产物） | `build-logic/PluginPackPlugin.kt`、`PluginDevPlugin.kt`、`ExecBridge.kt` |
| 3. 子进程生命周期 | **新增 `ChildProcessSupervisor`**：异步启动、stdout/stderr 管道泵（防管道写满死锁）、死亡回调、可选重启策略（线性退避 + 稳定运行重置计数）；与崩溃熔断不冲突（熔断管插件进程内崩溃，监督器管独立子进程退出） | `core-plugin/.../proxy/ChildProcessSupervisor.kt` |
| 4. env/cwd 注入 | `runViaLinker` 原生支持；**dlopen 路径 `runNative` 本轮补齐**（native 层进程级注入 + 运行后还原，`-6` = workDir 切换失败；文档注明并发干扰限制） | `ExecBridge.kt`、`exec_bridge.c` |
| 5. 隔离进程复用 | 天然成立：隔离进程内有完整 `PluginHost`，supervisor 为进程内懒加载单例；`unloadPlugin`/`uninstallPlugin` 均先 `stopAll(pluginId)` 回收子进程 | `PluginHost.kt` |

验证：`:core-plugin:compileDebugKotlin` 与 `:core-plugin:externalNativeBuildDebug`（四 ABI）均 BUILD SUCCESSFUL。

**本轮修复**：`ChildProcessSupervisor` 的 stderr 泵 Job 未纳入生命周期管理（`teardown` 无法取消），已修复为独立 `stderrPumpJob` 字段。

---

## 1. 源码 + 互联网研究：新发现的框架缺口

> 依据：`proot-termux/src` 源码逐项审计 + 互联网搜索（Termux 官方 wiki、termux-packages 构建脚本、oonid/pr 实机测试、termux/proot issues、Linux 内核 Yama 文档）。

### 1.1 🔴 zygote seccomp 过滤器（独立于 W^X 的第二道拦截）

**源码依据**：proot 运行时调用 `chmod`、`chdir`、`linkat`、`renameat2`、`faccessat2`、`openat2`、`clone3` 等系统调用。
**互联网实证**（oonid/pr 在 Samsung Android 16 实测 18+ 个被拦系统调用）：Android zygote 给每个应用进程安装 BPF seccomp 过滤器（`Seccomp: 2`），对上述系统调用返回 `ENOSYS`。这**独立于 W^X**，且 `PROOT_NO_SECCOMP=1` **只能关掉 proot 自己的 seccomp 加速，关不掉 zygote 的**。

**影响**：proot 子进程继承 zygote seccomp 过滤器，运行时会在 `chmod`/`chdir` 等处收到 `ENOSYS`。Termux fork 的 proot 内置了 SIGSYS 处理器逐个模拟被禁系统调用（`faccessat2→faccessat`、`openat2→openat`、`clone3→clone` 等降级），但**必须使用 Termux fork 编译的 proot**，上游 proot 没有这些处理。

**框架要求**：
- [ ] 确认使用的 proot 二进制是 **Termux fork**（含 SIGSYS 模拟），而非上游 proot-me
- [ ] 端到端验证时观察 `ENOSYS` 报错，确认 Termux fork 的 SIGSYS 处理器在插件子进程上下文中正常工作

### 1.2 🔴 LD_LIBRARY_PATH 未自动注入

**源码依据**：Termux 打包 proot 为**动态链接**（`TERMUX_PKG_DEPENDS="libandroid-shmem, libtalloc"`），运行时需要 `libtalloc.so.2` 等共享库。
**互联网实证**：非 Termux 应用（oonid/pr、openclawd-termux）均设置 `LD_LIBRARY_PATH=$libDir:$nativeLibDir`。

**影响**：`runViaLinker` 支持 env 注入，但**不会自动设置 `LD_LIBRARY_PATH`**。如果 proot 是动态链接的，插件必须手动注入 `LD_LIBRARY_PATH` 指向 nativeLibraryDir（proot 的 .so 依赖也在那里）。

**框架要求**：
- [ ] 插件侧启动 proot 时，`ChildProcessSpec.env` 必须包含 `LD_LIBRARY_PATH=<nativeLibraryDir>`
- [ ] 或者：proot 改为**静态链接 talloc**（Termux 构建脚本支持），消除 .so 依赖
- [ ] 框架可考虑在 `runViaLinker` 中自动注入 `LD_LIBRARY_PATH`（当二进制在 nativeLibraryDir 时）

### 1.3 🟡 PROOT_LOADER_32（32 位 guest 支持）

**源码依据**（`enter.c:564-570`）：
```c
#if defined(HAS_LOADER_32BIT)
    if (IS_CLASS32(tracee->load_info->elf_header)) {
        return getenv("PROOT_LOADER_32") ?: PROOT_UNBUNDLE_LOADER "/loader32";
    }
#endif
    return getenv("PROOT_LOADER") ?: PROOT_UNBUNDLE_LOADER "/loader";
```

**影响**：如果 rootfs 包含 32 位二进制（如 armv7 兼容包），需要第二个 loader（`loader32`）和 `PROOT_LOADER_32` 环境变量。

**框架要求**：
- [ ] 如需 32 位 guest：插件声明第二个原生可执行文件 `loader32`，经 `nativeExecutablePath` 定位，注入 `PROOT_LOADER_32`
- [ ] 如仅 64 位 guest：可忽略，但应在文档中注明

### 1.4 🟡 PROOT_L2S_DIR（link2symlink 元数据目录）

**源码依据**：Termux 扩展 `link2symlink`（`extension/link2symlink/link2symlink.c`）用符号链接模拟 `link(2)`，因为 Android SELinux 拒绝 `untrusted_app` 域的 `link()` 调用。
**互联网实证**：`PROOT_L2S_DIR` 必须与 rootfs 在**同一文件系统**，否则 dpkg 解包和 groupadd 锁文件会失败。

**框架要求**：
- [ ] 插件启动 proot 时传 `--link2symlink` 参数
- [ ] 设置 `PROOT_L2S_DIR` 指向 rootfs 内部目录（如 `<rootfs>/.l2s/`），确保同一文件系统

### 1.5 🟡 -0（fake root）与 Termux 扩展参数

**源码依据**（`extension/fake_id0/`）：`-0` 启用 `fake_id0` 扩展，伪装 root 权限（uid=0），大多数 Linux 发行版需要。
**互联网实证**：典型 proot 启动参数为 `proot -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w /root`。

**框架要求**：
- [ ] 插件侧 `ChildProcessSpec.args` 必须包含 `-0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w <workdir>`
- [ ] 这是插件侧配置，框架无需改动，但应在文档中给出标准参数模板

### 1.6 🟢 Yama ptrace_scope（已确认无阻塞）

**互联网实证**：`kernel.yama.ptrace_scope` 必须为 0 或 1（仅父→子），proot 的 `PTRACE_TRACEME` 不受影响。Samsung 默认 ptrace_scope=1，proot 正常。模式 2/3 会连 TRACEME 一起禁止，但 Android 设备普遍为 0 或 1。

**结论**：无需框架改动，但端到端验证时应确认设备 ptrace_scope ≤ 1。

### 1.7 🟢 process_vm_readv/writev（已确认无阻塞）

**源码依据**（`tracee/mem.c` + `.check_process_vm`）：proot 构建时检测 `process_vm_readv/writev`，可用则用（比 `PTRACE_PEEKDATA` 快），否则回退 ptrace。
**互联网实证**：这两个系统调用受与 `PTRACE_ATTACH` 相同的 ptrace 访问检查约束——对 proot 自己 fork+TRACEME 的子进程总是允许（同 UID、父→子关系满足 Yama 模式 1）。Android 内核（3.2+）普遍支持。

**结论**：无需框架改动。

### 1.8 🟢 /proc hidepid=2（已知限制，非框架问题）

**互联网实证**：Android 7+ 将 `/proc` 以 `hidepid=2` 挂载，应用只能看到自己 UID 的进程。proot guest 绑定宿主 `/proc`，因此 guest 内进程同样只能看到自己（同 UID）的进程。依赖读取其他进程 `/proc/<pid>` 的 guest 程序会失败。

**结论**：这是 Android 系统限制，非框架可解决。部分项目（openclawd-termux）用伪造 `/proc` 条目缓解。

### 1.9 🟢 多线程 guest 死锁（已知 proot 限制）

**互联网实证**（termux/proot issue #326）：proot 事件循环单线程、一次只处理一个 `waitpid` 事件，两个线程同时进入 ptrace stop 且相互依赖时会死锁。影响 Node.js/npm 等多线程 guest。

**结论**：这是 proot 本身的架构限制，非框架可解决。

---

## 2. 剩余未完成项（更新后）

### 2.1 真实 loader 接入（缺口 1 硬约束，P1 收尾项）

框架的通用能力已就位（`nativeExecutablePath` 定位 + `runViaLinker` env 注入），但**尚未用真实 PRoot loader 走通**：

- [ ] 插件声明真实 `loader`（Termux fork 编译，含 SIGSYS 模拟），经 `pluginPack.nativeExecutables` 落 `nativeLibraryDir`
- [ ] 插件启动 proot 时经 `ChildProcessSpec.env` 注入：
  - `PROOT_LOADER=<nativeExecutablePath("loader")>`
  - `PROOT_TMP_DIR=<插件目录/tmp>`（只需可写）
  - `LD_LIBRARY_PATH=<nativeLibraryDir>`（proot 动态链接依赖）
  - `PROOT_LOADER_32=<nativeExecutablePath("loader32")>`（如需 32 位 guest）
- [ ] 验证 `access(path, X_OK)` 误导报错不再出现（SELinux 拒在真实 execve 才暴露）
- [ ] 确认使用 **Termux fork proot**（含 zygote seccomp SIGSYS 模拟），而非上游 proot-me

### 2.2 PRoot 插件本体（完全未开始）

目前只有探针插件 `sample-proot-probe`，真正的 PRoot 插件模块不存在：

- [ ] 新建插件模块（如 `plugin-proot`），`PluginEntry` 实现 + `EXEC` 能力声明 + `isolated` 标记（建议进隔离进程，proot ptrace 崩溃不拖垮宿主）
- [ ] rootfs 打包与分发：走 `PluginHost.assetDownloader()`（断点续传 + digest 校验 + 磁盘配额），rootfs 落插件目录（guest 二进制经 mmap PROT_EXEC 装载，已实证放行）
- [ ] Linux 环境配置：标准启动参数模板 `proot -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w <workdir>`
- [ ] `PROOT_L2S_DIR` 指向 rootfs 内部（同一文件系统）
- [ ] 插件侧生命周期：经 `PluginHost.childProcessSupervisor()` 启动 proot，配置 `RestartPolicy`，`onUnload` 时框架自动回收（已内置）
- [ ] 插件 UI：终端交互界面（输入/输出流接 `ManagedProcess.stdin` / `onOutput`）

### 2.3 端到端验证（从未用真实 proot 走通）

所有探针（A–E）验证的都是极小 PIE / 模拟程序：

- [ ] 真 proot（Termux fork）+ 真 loader + 真 rootfs（如 Alpine minirootfs）经框架完整路径跑通：安装 → 加载 → `runViaLinker` 起 proot → ptrace guest → shell 交互
- [ ] **zygote seccomp 验证**：观察 proot 子进程是否触发 `ENOSYS`，确认 Termux fork 的 SIGSYS 处理器正常工作
- [ ] 隔离进程场景复测：`:plugin_isolated_N` 内起 proot，验证崩溃隔离与跨进程服务
- [ ] 重启策略实测：kill proot 子进程，验证 `ChildProcessSupervisor` 退避重启与 `stableRunMs` 计数重置
- [ ] seccomp 加速在真实 proot 下的表现（探针 C 只测了"能否安装"，未测与 zygote seccomp 的实际交互；不行则 `PROOT_NO_SECCOMP` 回退）
- [ ] 确认设备 `kernel.yama.ptrace_scope` ≤ 1

### 2.4 已知限制（设计内，非缺陷）

- **dlopen 路径（`runNative`）的 env/cwd 注入是进程级**：宿主进程内生效、运行后还原；并发运行多个带不同 env/cwd 的 dlopen 程序会相互干扰。需要严格隔离时用 `runViaLinker`（独立子进程）——proot 场景本就应走此路径。
- **`/proc/PID/exe` 显示为 linker64**：system_linker_exec 的固有特征（上游文档 §4.6），对依赖 `/proc/exe` 识别的程序需注意。
- **loader 仅支持动态链接目标经 linker 路径**；静态二进制须直接落 `nativeLibraryDir`（探针 A 已证可 execve）。
- **`/proc` hidepid=2**：guest 只能看到同 UID 进程，依赖跨进程 `/proc` 读取的 guest 程序会失败。
- **多线程 guest 死锁风险**：proot 单线程事件循环的架构限制（termux/proot issue #326）。

---

## 3. 建议实施顺序

1. **2.1 真实 loader 接入**——P1 收尾，框架能力到真实产物的第一次对接；**必须使用 Termux fork proot**
2. **2.3 端到端最小验证**——真 proot + Alpine minirootfs 先跑通 shell，重点观察 zygote seccomp ENOSYS
3. **2.2 PRoot 插件本体**——按验证结果定型 rootfs 分发与 UI 方案
4. **2.3 余项**——隔离进程、重启策略、seccomp 的完整回归

---

## 4. 互联网研究来源

- [Termux proot build.sh (termux-packages)](https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh)
- [Termux execution environment (termux-packages wiki)](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)
- [oonid/pr — Important Notes (W^X / SELinux / zygote seccomp 实测)](https://github.com/oonid/pr/blob/main/docs/important-notes.md)
- [termux/proot issue #326 — npm/node ptrace 死锁](https://github.com/termux/proot/issues/326)
- [termux-packages issue #27475/#27606 — NDK r29 proot 构建失败与 PROOT_UNBUNDLE_LOADER](https://github.com/termux/termux-packages/issues/27475)
- [Linux kernel Yama LSM 文档（ptrace_scope 模式 0–3）](https://docs.kernel.org/6.5/admin-guide/LSM/Yama.html)
- [Android-Proot-Builder 编译指南（环境变量表、Android 16 clone3 问题）](https://github.com/wuxianggujun/Android-Proot-Builder/blob/main/proot-compilation-guide.md)
