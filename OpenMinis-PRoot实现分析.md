# OpenMinis Android 端 PRoot Linux 完整实现分析

> 分析对象：`d:\deepseek-harness-master\dsh-android\OpenMinis`（Android 部分）
> 范围：PRoot 的**来源与 ptrace 实现机制**、**Alpine 系统镜像的来源与安装**、以及沙箱在 app 内的配置/执行/跨进程能力/应用接线的**完整实现**
> 记录时间：2026-08-25（多子代理深挖 deps/proot 源码、Alpine 镜像链路、集成接线 + 联网核对上游来源）

---

## 0. 总览与架构

Android 端用 **PRoot（ptrace 型用户态 chroot）+ Alpine aarch64 minirootfs** 在设备本地跑一个完整 Linux。PRoot 被当作**可执行文件**（非 JNI 库）由 `ProcessBuilder`/`forkpty` 启动，分三种执行形态：

| 形态 | 载体 | 用途 |
|---|---|---|
| **持久 shell** | `PersistentShell`（每会话一个 `/bin/sh` + stdin 标记协议） | agent 的 `shell_execute` |
| **一次性执行** | `ShellExecutor`（每命令一个进程） | 备用/遗留路径 |
| **交互式终端** | `TerminalSession` + `PtyBridge`（JNI forkpty 真 PTY） | 用户手敲终端 |

guest 内的 `execve` 经 PRoot 的 `native_offload` 扩展（OpenMinis 自研）通过 abstract socket 转发给宿主 Kotlin，让 Linux 命令直接调用日历/相册/TTS/浏览器等原生能力。

```
┌──────────────────────────── OpenMinis App（宿主进程）────────────────────────────┐
│  ChatViewModel(agent) ─ shell_execute ─▶ ExecutionCoordinator ─▶ PersistentShell │
│  TerminalScreen(UI) ───────────────────▶ TerminalSession ─▶ PtyBridge(forkpty)   │
│  file_read/write/edit ─ resolveSessionHostPath ─▶ 宿主文件直接 I/O（不经 proot）  │
│  NativeOffloadServer ◀── abstract socket "native-offload" ◀──┐                   │
│     └─ 20+1 个 OffloadHandler（日历/相册/TTS/浏览器/…）        │                   │
│  ┌──────────── proot 子进程（每会话/每终端一个）───────────────┼─────────────┐   │
│  │  Alpine rootfs（-r）+ /dev /proc /sys（-b）+ /var/minis/*（-b）           │   │
│  │  /bin/sh ─ execve("android-calendar"…) ─ native_offload 扩展 ────────────┘   │
│  └──────────────────────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

# 第一部分：PRoot 本身

## 1. 来源与谱系（它是谁的、从哪来）

PRoot 在本项目里是**三层继承**的产物：

```
proot-me/PRoot v5.1.0  ──▶  termux/proot  ──▶  OpenMinis/proot（本项目子模块）
（原始上游）               （Android 补丁）     （自研 native_offload）
```

### 1.1 原始上游：proot-me/PRoot
- **是什么**：用户态实现的 `chroot`、`mount --bind`、`binfmt_misc`——**无需 root、无需内核支持**，靠 `ptrace`（每个 Linux 内核都有的非特权系统调用）实现（官网 proot-me.github.io）。
- **作者/版权**：`deps/proot/src/cli/proot.h:81` 声明 "Copyright (C) 2015 STMicroelectronics, licensed under GPL v2 or later"；`COPYING` 为 GPL-2 全文。
- **版本**：本项目锁定 **v5.1.0**（`deps/proot/src/cli/proot.h:8-10` `#define VERSION "5.1.0"`；`doc/proot/changelog.txt:1` "Release v5.1.0"）。

### 1.2 中间层：termux/proot
- `deps/proot/README.md:1-6` 原样保留："This is a copy of the PRoot project with patches applied to work better under Termux."（`.travis.yml:24` Coverity 项目名仍是 `termux/proot`，证明直接复制自 termux/proot）。
- **Termux/Android 适配改动**（本项目继承）：
  - Android 终端 ioctl 降级（`TCSETS2→TCSETS`）：`src/syscall/enter.c:613-637`（`#ifdef __ANDROID__`）。
  - `memfd_create` 黑名单（Qt JIT、php opcache、apk-tools v3 的 execveat）：`src/syscall/enter.c:639-667`。
  - f2fs bug 规避：`src/path/f2fs-bug.c`、`src/path/canon.c:156-157`。
  - `/linkerconfig` 无法 stat 的兜底：`src/path/canon.c:160-168`。
  - Termux 系扩展：`kompat`（`-k` 内核版本伪装）、`fake_id0`（`-0`）、`link2symlink`、`hidden_files`、`port_switch`、`fix_symlink_size`、`ashmem_memfd`、`sysvipc`、`mountinfo`（`src/extension/`）。

### 1.3 本项目层：OpenMinis/proot
- **核心新增：`native_offload` 扩展**（上游与 termux 均无，OpenMinis 自研）：
  - 新增源码 `src/extension/native_offload/native_offload.c/.h`
  - 构建挂接 `src/GNUmakefile:87`；扩展注册声明 `src/extension/extension.h:210`
  - CLI 选项 `src/cli/proot.c:338-344`、`src/cli/proot.h:70,288-297`（`--native-offload=<socket>:<handler>,...`）
  - changelog 未更新（`doc/proot/changelog.txt` 无 "offload"），说明是 fork 后直接加的代码。

## 2. PRoot 靠什么实现"Linux"：ptrace 机制

**核心原理**：PRoot 自身是个普通进程，它 `fork` 出要运行的程序（tracee），用 `ptrace(PTRACE_SYSCALL)` 附加，使 tracee 的**每个系统调用在进入/退出内核时都停下来**交给 PRoot。PRoot 借此改写 syscall 的参数（尤其是路径）和返回值，从而在**不改内核、不要 root** 的前提下伪造出"换了根目录、挂了 bind、是 root 用户"的假象。

### 2.1 源码模块结构（`deps/proot/src/`）

| 模块 | 职责 |
|---|---|
| `cli/` | 命令行解析与主流程。`cli.c:448` `main()`：解析参数 → `initialize_bindings`(`cli.c:414`) → `launch_process`(`cli.c:488`) → `event_loop`(`cli.c:495`)。`proot.c/h` 定义 `-r -b -0 -q -k --native-offload` 等选项 |
| `tracee/` | "被跟踪进程"抽象。`tracee.c/h`：`Tracee` 结构（寄存器、文件系统命名空间、堆、扩展链表）与进程树；`event.c`：ptrace 事件循环；`reg.c`：`PTRACE_GETREGS/SETREGS` 读写寄存器；`mem.c`：`process_vm_readv/writev` 跨进程内存读写；`seccomp.c`：装 seccomp 过滤 |
| `syscall/` | **系统调用翻译核心**。`syscall.c` `translate_syscall()` 总入口（区分 enter/exit）；`enter.c`/`exit.c`：各 syscall 的进入/退出翻译大开关；`sysnum.c`+`sysnums-*.h`：各架构 syscall 号表；`heap.c`：brk 堆仿真；`socket.c`：unix socket 路径翻译；`chain.c`：PRoot 主动插入的"链式 syscall" |
| `path/` | **路径翻译子系统**。`binding.c`：绑定表（`-r`/`-b`）；`canon.c`：guest 视角规范化（realpath）；`path.c`：`translate_path`/`detranslate_path`（guest↔host 双向）；`glue.c`：绑定挂载点"胶水"占位文件；`proc.c`：`/proc` 动态符号链接仿真 |
| `execve/` | **execve 重写**。`enter.c`：解析目标 ELF、改写为执行 loader；`exit.c`：生成"加载脚本"写入 tracee 栈；`elf.c`：ELF 解析；`shebang.c`：`#!` 展开；`ldso.c`：动态链接器环境变量重建；`aoxp.c`：tracee 内存中 argv/envp 安全编辑；`auxv.c`：辅助向量 |
| `loader/` | **内置 ELF 加载器**（独立小程序、无 libc）。`loader.c` 的 `_start` 解释"加载脚本"；`assembly-*.h` 各架构裸 syscall 宏 |
| `ptrace/` | **guest 内部 ptrace 的仿真**——让 gdb/strace 能在 guest 里工作 |
| `extension/` | 扩展框架 + 各扩展（`fake_id0`、`link2symlink`、`native_offload` 等） |

### 2.2 各 Linux 语义如何伪造

- **`-r <rootfs>`（换根）**：PRoot 把 rootfs 作为根绑定。所有路径参数在 syscall 进入时被 `translate_path` 从 guest 视角翻译成 host 真实路径（查 `binding.c` 绑定表 + `canon.c` 规范化），返回时再 `detranslate_path` 翻回。于是 `/etc/passwd` 实际访问 `<rootfs>/etc/passwd`。
- **`-b host:guest`（bind mount）**：往绑定表加一条 `guest→host` 映射，同样走路径翻译；`glue.c` 在挂载点放"胶水"占位文件使目录可被 stat/进入。
- **`-0`（伪 root）**：`fake_id0` 扩展。对 `getuid/geteuid/getgid/getegid/setuid/...` 等，在 syscall **退出**时把返回值改成 0（或允许 setuid 成功），让 guest 以为自己是 root。
- **`--link2symlink`**：扩展。拦截 `link()`，不真正硬链接（Android `/data` 拒绝 app uid 跨目录硬链接），改在目标处建符号链接——对 `apk` 装包（busybox 硬链接 ar/ld/nm）功能等价。
- **execve 与 loader**：guest 的 ELF 其解释器路径（如 `/lib/ld-musl-aarch64.so.1`）在 host 上不存在，直接 execve 会失败。PRoot 在 `execve/enter.c` 把目标改写成执行**内置 loader**，`exit.c` 生成加载脚本写入 tracee 栈，loader（无 libc、裸 syscall）在 tracee 内把真正的 guest ELF 映射并跳入。**native_offload 正是在 execve 进入时拦截**（见 2.3）。
- **`/proc`/`/dev`/`/sys`**：用 `-b` 直接绑宿主真实伪文件系统；`/proc` 下涉及自身的条目由 `proc.c` 仿真。

### 2.3 native_offload 扩展（C 侧，OpenMinis 自研）

位置：`deps/proot/src/extension/native_offload/native_offload.c/.h`。
- 注册为 execve 回调（`extension.h:210` `native_offload_callback`）。
- 当 guest `execve` 的可执行文件名命中 `--native-offload=<socket>:<handler>,...` 列出的 handler 名时，扩展**不真正执行该文件**，而是：把 `argv/env/cwd` 按小端协议打包，经 abstract unix socket 发给宿主 `NativeOffloadServer`；收到 `(exit_code, tmpfile)` 响应后，把原 execve **改写成 `/bin/cat <tmpfile>`**，guest 即把宿主能力输出当 stdout 看到。
- 宿主侧配套见第二部分 §7。

## 3. 在本项目里如何编译（`deps/build_proot.sh`）

- **工具链**：NDK `aarch64-linux-android26-clang`，`ANDROID_API=26`、ABI 仅 `arm64-v8a`（`build_proot.sh:46-48`）。
- **talloc 静态链接**：只取 `talloc.c/talloc.h`，绕开 Samba waf，现场生成最小兼容 `replace.h`，编成 `libtalloc.a` 静态链入（`build_proot.sh:125-234`）——proot 二进制**无** `DT_NEEDED libtalloc.so`。
- **编译参数**：`-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072`，`CFLAGS=-O2 -fPIE`，`LDFLAGS=-pie -Wl,-z,noexecstack -ltalloc`（`build_proot.sh:252-254`）→ **PIE 可执行文件**；loader 直接链进二进制（运行时经 `/proc/self/fd` 提取，`PRootKernel.kt:83-90`）。
- **产物双份**：`assets/proot-aarch64` + `jniLibs/arm64-v8a/libproot.so`（`build_proot.sh:292-309`）。运行时用后者（`RootfsManager.kt:43`），manifest `extractNativeLibs="true"` 保证安装解压。

---

# 第二部分：Alpine 系统镜像

## 4. 镜像来源（从哪来）

### 4.1 下载脚本 `scripts/prepare_android_sandbox.sh`
- **URL**（`:19`）：
  ```
  https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz
  ```
  - 域名 `dl-cdn.alpinelinux.org` = **Alpine 官方 CDN**；路径 `v3.21/releases/aarch64/`；变体 `minirootfs`。
- **版本**（`:17-18`）：`ALPINE_VERSION="3.21"`、`ALPINE_RELEASE="3.21.3"`。
- **无完整性校验**：不下载 `.sha256`、不跑 `sha256sum`/`gpg`，仅 `curl -fSL -o`（`:35`）+ `du -h` 打印大小（`:36`）。
- **落盘**：重命名为固定名 `src/android/app/src/main/assets/alpine-minirootfs.tar.gz`（去掉版本号，与 `RootfsManager` 常量对应）；幂等缓存（已存在则跳过，`:31-33`）；被 `.gitignore:120` 排除。
- **无二次加工/裁剪**：原封不动存入 assets，所有定制推迟到设备端运行时（与 iOS 构建期加工路线不同）。

### 4.2 脚本的另一半：PRoot 备用二进制
- 从 Termux 包仓库下载 `proot_5.1.107-70_aarch64.deb`（`:22-23`，`packages.termux.dev`），`ar x` 解包（兼容 `data.tar.xz/.gz/.zst`，`:51-67`），拷为 `assets/proot-aarch64`。
- **注意**：这只是备用/快捷路线；正式路线是 `build_proot.sh` 从源码编译，且运行时实际用 jniLibs 的 `libproot.so`（`assets/proot-aarch64` 仅一个未实际使用的常量引用 `RootfsManager.kt:632`）。

## 5. 镜像是什么：Alpine minirootfs

- **官方最小根文件系统变体**：只含用 `apk` 引导系统所需的最小集合——**busybox**（提供 `sh` 和核心命令）、**apk** 包管理器、**musl libc**、基础 `/etc`。不含文档、man 页、附加工具包。
- **体量**：约 3MB 级压缩包 / 解压后 10MB 出头。
- **为什么选它**：足够小（适合塞进 APK asset、快速首启解压），后续能力靠 `apk` 按需扩展；且纯用户态、无内核依赖，正好匹配 PRoot 这种用户态 chroot（`README.md:39,172-175`、`RootfsManager.kt:18-25`）。

## 6. 安装到设备（`RootfsManager`）

### 6.1 安装流程 `installIfNeeded()`（`:68-157`）
- **安装目标**：`rootfsDir = File(context.filesDir, "alpine-rootfs")`（`:42`），即 `/data/data/<pkg>/files/alpine-rootfs`。
- 状态机 `RootfsInstallState`（Idle/Preparing/Extracting(f)/Finalizing/Installed/Failed）经 `StateFlow` 驱动 UI 进度（`:26-60`）；进度按"压缩字节消耗"计（rootfs 随包发布、不联网下载，`:20-24`）。
- AAPT 可能把 `.tar.gz` 解压成 `.tar`，两个名字都试（`:87-92`）。
- **手写 POSIX tar 解析器** `extractTar`（`:464-552`）：不依赖第三方库，处理普通文件/目录/符号链接/硬链接（硬链接退化拷贝）、GNU 长名前缀、512 字节对齐、可执行位保留。
- 装完初始化：写 `.arch`（`aarch64`）标记、预建 `/var/minis/{attachments,offloads,workspace,skills,memory,shared,mounts}` 与 `/opt/bin`（`:131-138`）、`refreshDns()`、置 `rootfs.freshInstall`（触发首次镜像自动测速）。

### 6.2 其他职责
- `installProotIfNeeded()`（`:165-179`）：校验 `libproot.so` 存在且可执行。
- `reset(keepUserData)`（`:185-208`）：可选保留 `/root` 后重装。
- `refreshDns()`（`:260-307`）：从 `ConnectivityManager` 读系统 DNS 写 `etc/resolv.conf`，无则回退 `8.8.8.8/8.8.4.4`。
- `applyDefaultMountOverlay()`（`:321-366`）：每次启动把 `assets/default_mount/` 覆盖进 rootfs（profile、URL 拦截 wrapper 随 app 更新下发）；`bin` 目录置可执行；删 PEP 668 `EXTERNALLY-MANAGED` 让 `pip` 可用；把 `minis-mcp-cli` 库锁只读。

### 6.3 完整链路
```
[构建期] prepare_android_sandbox.sh 从 Alpine 官方 CDN 下载 minirootfs
        → assets/alpine-minirootfs.tar.gz（无校验、不加工）→ 打进 APK
[首启]   RootfsManager.installIfNeeded → 手写 tar 解析器
        → files/alpine-rootfs/ → 预建目录 + resolv.conf + .arch
[每次启动] applyDefaultMountOverlay 叠加 default_mount → PRootKernel.boot
```

---

# 第三部分：沙箱在 app 内的运行

## 7. 配置层：`PRootKernel`

**不是常驻内核**，只是配置持有者 + 命令行构造器（`:17-23` 注释，对应 iOS ISHKernel）。

### 7.1 `boot()`（`:53-179`）
装 rootfs → 装 proot → `applyDefaultMountOverlay` → 重放用户镜像选择 → `refreshDns` → 设 `nativeLibDir`/loader → 注入环境变量 → 注册全局挂载 → 启动 `NativeOffloadServer` → 装 handler stub → 物化外挂挂载目录。**懒触发**（不在 MinisApp 里调，见 §11）。

### 7.2 环境变量（`:92-148`）
`PATH`、`HOME=/root`、`TZ`（POSIX、符号取反，`posixTz():487-500`）、系统 HTTP 代理六件套（`systemProxyEnv:539-561`）、`NO_COLOR`、`PYTHONDONTWRITEBYTECODE`、`GOMAXPROCS=2`、`BROWSER=/usr/local/bin/minis-open`、`ENV=/etc/profile`、`CHARSET=UTF-8`、`UV_LINK_MODE=symlink`。

### 7.3 命令行构造 `buildProotCommand`（`:586-648`）
```
<proot> -0 --link2symlink -r <rootfs>
        -b /dev -b /proc -b /sys -w /root
        [-b <host路径>:<linux路径> ...每个挂载]
        --native-offload=<socket>:<handler1,handler2,...>
        /bin/sh -c "<command>"
```
（`-0`/`--link2symlink`/`-b` 的语义见第一部分 §2.2。）还装 `top` wrapper 绕开 Android `hidepid=2` 的 procfs 限制（`:731-832`）。

## 8. 执行层（三种形态）

### 8.1 agent 命令：`ExecutionCoordinator` + `PersistentShell`
- **`ExecutionCoordinator`（object，`:26-300`）**：`PRootKernel` 全局单例 + 每 `sessionId` 一个 `PersistentShell` + 每会话一个 `Mutex`。并发保证（`:20-24`）：同会话串行、跨会话并发、`globalLock` 防重复创建、shell 死亡自愈重建。`execute()`（`:72-125`）：自动 boot → `getOrCreateShell` → 注入用户环境变量整快照 → 执行 → `TerminalSanitizer` 清洗/截断。
- **`PersistentShell`（`:25-371`）**：长驻 `/bin/sh`。命令经 stdin 写入，用唯一结束标记界定输出——`{cmd}; echo "__MINIS_DONE_{marker}_EXIT_$?__"`（`:277-278`），reader 线程解析拿 `(output, exitCode)`。超时不杀 shell；`applyEnvironment` 用 `unset`+`export` 整快照。

### 8.2 一次性执行：`ShellExecutor`
`ProcessBuilder` 起一条 `buildProotCommand(command)` 进程，stderr 合并、行回调、超时强杀返回 124。备用/遗留路径。

### 8.3 交互式终端：`TerminalSession` + `PtyBridge`
- `start()`：boot → 组 `proot … /bin/sh -l -i`（login+interactive）→ envp（`TERM=xterm-256color` 等）→ `PtyBridge.forkExec(...)` 得 PTY master fd → `readLoop` 灌 `outputBytes` SharedFlow。
- 输入：`sendRawBytes`/`sendText`（`\r\n`→`\r`）、`sendInterrupt`（0x03→SIGINT）、`setWindowSize`（SIGWINCH）。
- Compose：输出 → `TerminalEmulator.feed`（解析 ANSI）→ `TerminalNativeViewCompose` 渲染；键盘经 Ctrl 组合键翻译。
- `PtyBridge` + `cpp/pty_bridge.c`：JNI 封装 bionic `forkpty()`（API 21+），真 TTY（PS1、回显、方向键、Ctrl+C）。

## 9. native_offload 宿主侧

### 9.1 机制（`NativeOffload.kt:15-27`）
1. 宿主为每个能力在 rootfs `/usr/local/bin/` 装 **stub 可执行文件**（`PRootKernel.installHandlerStubs:705-719`）。
2. guest `execve("<handler名>", argv, envp)` → **proot native_offload 扩展拦截**（C 侧，见 §2.3）。
3. 经 abstract socket `native-offload` 发给宿主 `NativeOffloadServer`。
4. 宿主分发给注册的 `NativeOffloadHandler`，输出写进 guest `/tmp` tmpfile，回 `(exit_code, tmpfile路径)`。
5. 扩展把 execve 改写成 `/bin/cat <tmpfile>`，guest 见 stdout。

### 9.2 协议字节格式（`handleClient:148-217`）
- 请求：`magic(LE,'NOFF')+version(=1)+pid+argc+argv[]+envc+env[](K=V)+cwd`，小端。
- 响应：`magic('NOFR')+exitCode+tmpGuestPath`；输出先写宿主 `<rootfs>/tmp/.native-offload-<pid>-<seq>`。
- 边界：`argc≤256`、`envc≤4096`；无 handler 返回 127；handler 抛异常返回 1。
- socket 绑定带退避重试（`bindWithRetry:106-118`，0/50/100/200/400/800ms），应对旧进程被杀后内核短暂占名。

### 9.3 权限门控 `OffloadGate`（`offload/OffloadGate.kt`）
- 三态 **BYPASS / ASK_ONCE / NOT_ALLOWED**，存于 `OffloadPermissionManager`；每个 `android-*` CLI 都过同一道闸。
- `ASK_ONCE` 挂起等用户授权，`runBlocking` 桥接同步 `handle`。
- **按会话隔离**：优先用 `MINIS_CHAT_SESSION_ID`，非聊天回退全局 id。
- 拒绝返回 126 + `permission_denied` JSON。

### 9.4 已注册 handler（`MinisApp.kt:371-427`，20+1）
`android-alarm/calendar/clipboard/contacts/device/location/notification/open/photos/player/speak/speech/weather`、`android-a11y-cli`、`android-shizuku-cli`、`minis-model-use/config/browser-use/sessions-cli/scheduled`、DEBUG-only `minis-debug`。

## 10. 文件系统与挂载 + 文件工具

| 挂载 | Linux 路径 | 宿主路径 | 作用域 |
|---|---|---|---|
| 伪文件系统 | `/dev /proc /sys` | 同名 | 每次 |
| 全局 | `/var/minis/{memory,skills,shared,mcp-servers}` | `filesDir/minis-global/*` | 跨会话 |
| 会话 | `/var/minis/{attachments,offloads,workspace,browser}` | `filesDir/minis-sessions/<sessionId>/*` | 单会话 |
| 外挂 | `/var/minis/mounts/<name>` | SAF tree URI 解码路径 | 用户配置 |

- 外挂只读保护：PRoot `-b` 无只读选项 → shell wrapper + 文件工具门禁双保险。
- **文件工具直接 I/O**：`file_read/write/edit/read_image` 不走 shell，用 `resolveSessionHostPath(sessionId,…)` 映射到本会话宿主目录后直接读写（避免全局挂载表"最后写入者获胜"串会话）；写前过只读门禁。

## 11. 应用启动接线（`MinisApp.onCreate`，`:153` 起）

1. 进程守卫：`:acra` 进程 return；崩溃安全模式跳过子系统。
2. 沙箱单例（不解压）：`RootfsManager.getInstance` + `ExecutionCoordinator.init` + 注入 `envVarRepository`（`:317-319`）。
3. `networkMonitor.start`（DNS 变化刷 resolv.conf）。
4. `PRootKernel.registerGlobalBindMounts`（boot 前注册全局挂载，`:335`）。
5. 外挂目录接线：`MountedFoldersStore` → `PRootKernel.mountedFoldersStore`，`onChange` → `applyMountedFoldersSnapshot` + `stopCurrentCommand`（`:343-365`）。
6. 注册 20+1 个 offload handler（`:371-427`）。
7. `NativeOffloadServer.start(rootfsDir)`（提前启动，`:429`）。
8. **`PRootKernel.boot` 懒触发**：首次 `ExecutionCoordinator.execute`（`ExecutionCoordinator.kt:87`）或 `TerminalSession.start`（`TerminalSession.kt:115`）。
9. 系统广播：`ACTION_TIMEZONE_CHANGED` / `PROXY_CHANGE_ACTION` → `broadcastTimezoneChange/broadcastProxyChange`（`:569-601`）。

## 12. 系统事件热更新（时区/代理/DNS）
- **时区**：`broadcastTimezoneChange` → 更新 `customEnvironment["TZ"]`（新 shell 继承）→ 存活 `PersistentShell` `applyEnvironment` → `TerminalSession.broadcastTimezone`（**向运行中 PTY stdin 直接写 `export TZ='…'\r`**）。
- **代理**：`broadcastProxyChange` 同构，六键整块下发（空值也发，用于关闭时清旧值）。
- **DNS**：`networkMonitor` 触发 `refreshDns` 重写 `etc/resolv.conf`。

## 13. 辅助组件

| 组件 | 职责 |
|---|---|
| `ShellExecutor.kt` | 一次性执行器（每命令一进程） |
| `ShellTimeoutPolicy.kt` | 按命令前缀分档超时（1/3/10/20/30min）；注释 "Not wired yet" |
| `TerminalSanitizer.kt` | 输出清洗：CR 回卷、剥 ANSI/OSC、去控制字符、压空行、截断 |
| `MountedFolderCoordinator.kt` | 外挂桥：`-b` 规格 + 写工具只读门禁 |
| `PtyBridge.kt` + `pty_bridge.c` | JNI forkpty 交互终端 |

---

## 14. 完整数据流

```
[构建] build_proot.sh(NDK 编译 OpenMinis/proot + 静态 talloc) → jniLibs/libproot.so
       prepare_android_sandbox.sh(Alpine CDN 下载 minirootfs) → assets/*.tar.gz

[首启] RootfsManager 手写 tar 解压 → files/alpine-rootfs → 预建目录/resolv.conf

LLM tool_call(shell_execute)
  → ChatViewModel.executeTool → executeShellCommand [bash检测/延迟/脱敏]
    → ExecutionCoordinator.execute(sessionId, cmd, timeout, lineCallback)
      → (首次) PRootKernel.boot → PersistentShell(proot /bin/sh) 标记协议取 (output, exitCode)
    → EnvVarRedactor 脱敏 → tool_result 回模型

文件工具: file_read/write/edit → resolveSessionHostPath(sessionId,…) → 宿主文件直接 I/O

交互终端: TerminalSession.start → PtyBridge.forkExec(proot /bin/sh -l -i)
  输出 PTY fd → outputBytes → TerminalEmulator → Compose；输入 sendRawBytes → PTY master

guest execve("android-calendar"…) → proot native_offload 扩展拦截
  → abstract socket → NativeOffloadServer → OffloadGate 权限闸 → Handler
  → 输出写 guest /tmp → execve 改写 /bin/cat <tmpfile> → guest 见 stdout

系统事件: TIMEZONE/PROXY 广播 → 更新 customEnvironment + PersistentShell.applyEnvironment + 向 PTY 写 export
```

---

## 15. 与 iOS 端的对应关系

| Android（PRoot） | iOS（iSH） | 说明 |
|---|---|---|
| `PRootKernel` | `ISHKernel` | 配置/命令行（Android 无常驻内核） |
| `PersistentShell` | `ISHKernel.executeCommandAndWait` | 持久 shell + 标记协议 |
| `ShellExecutor` | `ISHShellExecutor` | 一次性执行 |
| `PtyBridge` | iSH 前端 | 交互式终端 |
| `NativeOffloadServer` | `native_offload_add_handler/exec` | 宿主能力转发 |
| `RootfsManager` | `RootfsManager` | rootfs 安装/解压（iOS 构建期加工，Android 运行时加工） |
| PRoot（ptrace 真实 ARM 执行） | iSH（x86 用户态模拟） | Android 无需 CPU 模拟 |

---

## 16. 对 dsh-android 插件框架的借鉴点

我们的 `core-plugin` 已有 `OffloadDispatcher`/`ExecBridge`/抽象 socket 雏形，与 native_offload 同源。可借鉴：

1. **PRoot 选型**：无 root 跑 Linux 的成熟方案；`-0`/`--link2symlink`/`-b /dev /proc /sys` 是跑 Alpine 的成熟参数配方。
2. **stub + execve 拦截**：让"沙箱内命令"无感调用宿主能力，比显式 `dispatchOffload` 更自然。
3. **持久 shell + `__MINIS_DONE_{marker}_EXIT_$?__` 标记协议**：比每命令起进程更能保持环境/包状态。
4. **只读挂载用 shell wrapper 兜底**（PRoot `-b` 无只读选项）。
5. **abstract socket 绑定退避重试**：应对旧进程被杀后内核占名（我们 `AbstractSocketChannel` 已有类似处理，可对照）。
6. **权限三态（BYPASS/ASK_ONCE/NOT_ALLOWED）+ 按会话隔离**：比我们当前 `requireCapability` 更细。
7. **手写 POSIX tar 解析器**：无第三方依赖解压资产，适合插件场景。
8. **系统事件热更新**：向运行中 shell/PTY 直接写 `export` 行，无需重启进程。
9. **许可注意**：PRoot GPLv2、iSH GPLv3——若我们引入 PRoot，整个 app 会被传染为 GPL，需评估与现有许可的兼容性。
