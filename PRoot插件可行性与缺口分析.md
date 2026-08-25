# PRoot Linux 插件：可行性与缺口分析（插件框架视角）

> 目标：把 PRoot Linux 能力做成 **`core-plugin` 插件框架里的一个插件**（不是宿主 app 直接集成）
> 对照上游：`proot-termux/`（termux/proot）、`proot-openminis/`（OpenMinis/proot，含 native_offload）；参考实现 `OpenMinis/`
> 记录时间：2026-08-25（三轮真机实测 + 上游源码研究；本文档已按"插件模型"重构）

---

## 0. 结论速览

**最终目标**：让 PRoot Linux 以**插件**形式运行在本框架里。探测的意义在于**告诉框架要为插件补什么执行底座**；框架据此改造、补齐能力后，即可支持 PRoot 插件。

- **探测结论（插件上下文，走框架真实路径）**：
  - 插件目录 `execve` → ❌ EACCES；
  - `mmap PROT_EXEC` 只读映射 → ✅ 放行（guest 可装载）；
  - `memfd + fexecve` → ❌ EACCES（SELinux 禁 untrusted_app 执行 memfd/shmem）。
  - **地基探针（真机全绿 ✅）**：nativeLibraryDir `execve` → 42、ptrace 往返 → 42、seccomp 安装 → 42、**system_linker_exec → 42**（详见 §4.5.1 / §4.6）。
- **✅ 地基已实证**：`nativeLibraryDir` 可 execve、ptrace 命脉可用、seccomp 加速可用、**system_linker_exec 成立**（框架可经 linker64 执行插件目录里的二进制）→ **PRoot 插件路线完全可行**。
- **✅ 执行底座最终形态（由探针 D 定型）**：框架 runner 用 **system_linker_exec** 执行**插件目录里的 proot**（proot 本体归插件分发）；仅 **loader** 因被 guest 进程裸 execve、无法挂钩子，仍须落 `nativeLibraryDir`——但 loader 产物**归插件**，经框架**通用「插件原生可执行文件托管」管线**落位（框架不打包任何 PRoot 专属产物，见 §3/§4.6）。这比"proot+loader 都进宿主/框架 jniLibs"更贴合"PRoot 是插件"。
- **对框架的要求**：插件自身无法 exec proot（目录、memfd 都被拒），但 guest 能经 mmap 装载。因此**框架要提供一个 "proot runner" 能力**——在框架唯一可 execve 的位置（应用 `nativeLibraryDir`，即框架执行底座落点）起 proot 子进程，供插件调用（EXEC 门控）。插件负责 rootfs + 配置 + Linux 环境。
- **要点**：这**不是"把 PRoot 挪去宿主"**，而是**框架为插件补上插件自己拿不到的执行能力**。PRoot 的分发、安装、配置、rootfs、生命周期**仍是插件**；框架只是提供执行底座。

---

## 1. 插件模型对框架的要求

插件的文件被框架解压到 `filesDir/plugins/<id>/`（app_data_file）。探测表明插件自身**无法 exec** proot（execve/memfd 均被 SELinux 拒），但 guest 可经 mmap 装载。于是框架必须补上插件拿不到的那一环：

| 环节 | 插件能否自理 | 框架要提供 |
|---|---|---|
| rootfs（guest 二进制） | ✅ 放插件目录，经 mmap 装载 | 解压/存储 |
| proot/loader 的 execve | ❌ 插件目录/memfd 都被拒 | **proot runner**：在框架执行底座落点 `nativeLibraryDir` execve + fork 子进程（EXEC 门控） |
| 崩溃隔离 | — | 隔离进程 `:plugin_isolated_N` |
| 分发/安装/生命周期 | ✅ 独立签名 APK，`installPlugin` | 安装/加载/卸载 |

**结论**：框架改造的核心是新增 **proot runner** 能力（框架侧 execve + 子进程管理），其余（rootfs、配置、分发）仍归插件。PRoot 始终是插件，框架只是提供它无法自备的执行底座。

---

## 2. 设备层事实（三轮真机实测，对插件同样适用）

测试机：Xiaomi Redmi K30 Pro，Android 12 / API 31，arm64-v8a，MIUI。`/data` 挂载 `f2fs rw,…,seclabel,nosuid,nodev,…`（**无 noexec**）。

| 探针 | 结果 | 含义 |
|---|---|---|
| `execve` app_data_file（filesDir / cacheDir / 插件目录） | ❌ `error=13 EACCES` | SELinux 拦 execve（挂载无 noexec，是 `seclabel` 策略） |
| **`mmap PROT_EXEC` 只读映射 app_data_file** | ✅ **映射可执行（返回 42）** | guest 二进制能被 loader 经 mmap 装载 |
| `memfd_create + fexecve`（匿名内存文件） | ❌ `execl 失败 errno=13 EACCES` | SELinux 禁 untrusted_app 执行 memfd/shmem |

**原理**：SELinux neverallow 针对 `execute_no_trans`（execve）与 `execmod`（可写+可执行）；PRoot loader 用**只读 `mmap PROT_EXEC`** 装载 guest ELF，走另一条权限检查，被放行。这正是 Termux/proot-distro 能在 Android 10+ 跑 Linux 的根本机制。而 **memfd/shmem 文件的执行同样被 SELinux 拒绝**（防无文件恶意代码），故"把 proot 搬进匿名内存再 fexecve"的绕法在本机不可行。

> 注：这些是**设备/OS 层事实**。插件与宿主同 UID、文件同为 app_data_file，故结论对插件**逐位适用**。探测由**探针插件**（`sample-proot-probe`）在插件目录、走框架真实安装/加载路径完成。

---

## 3. 落点设计：框架提供执行底座，插件提供 Linux 环境

> **术语界定（"执行底座"）**：指**框架提供的执行能力（proot runner）+ 随附的 loader 产物**。它**没有独立的物理目录**——`core-plugin` 是库模块、构建时并入宿主，运行时只有**宿主 app 包的那一个 `nativeLibraryDir`**（全体系唯一可 execve 处）。故文中"执行底座落点 nativeLibraryDir"= **框架提供、寄存在宿主 app 的 nativeLibraryDir**；"归框架"指**能力与产物由框架负责**，并非存在一个框架私有目录。

| 对象 | 落点 | 由谁提供 / 依据 |
|---|---|---|
| proot 本体 | 插件目录 `filesDir/plugins/<id>/`（框架 runner 经 **system_linker_exec** 执行，探针 D 已证） | **插件**（分发归插件；框架只提供执行能力，见 §4.6） |
| loader | 宿主 APK 的 `nativeLibraryDir`（loader 产物**归插件**；框架提供**通用「插件原生可执行文件托管」管线**，构建期把插件声明的原生可执行文件并入宿主 jniLibs）+ 插件传入的 env（`PROOT_LOADER`）指向 | **插件产物 + 框架通用托管**（guest 裸 execve、挂不上钩子，须直接可 execve，见 §4.6） |
| `PROOT_TMP_DIR` | 插件目录 / cacheDir | 插件（只需可写，不需可执行） |
| rootfs（guest 二进制） | 插件目录 `filesDir/plugins/<id>/rootfs` | **插件**（mmap PROT_EXEC 放行，loader 经 mmap 装载） |

**分工**：探测表明插件自身无法 exec proot（目录、memfd 均被拒，见 §4），故 **execve + 起子进程这一环由框架以 "proot runner" 能力提供**（框架侧 nativeLibraryDir execve，EXEC 门控）；rootfs、配置、Linux 环境仍由插件负责。**PRoot 依然是插件**——框架只是补上插件无法自备的执行底座，而非把 PRoot 挪去宿主。

> **已查实的架构事实**（`InstallExecutor`）：框架**不**经 `PackageManager` 真装插件，只把插件 APK 解压到 `filesDir/plugins/<id>/`；插件**没有自己的 nativeLibraryDir**，其 `.so` 落在 `filesDir/plugins/<id>/lib/<abi>/`（app_data_file，直接 execve 被拒）。但**探针 D 实证 system_linker_exec 成立**（§4.6）：框架 runner 可经 linker64 执行插件目录里的动态二进制。因此 **proot 本体留在插件目录**（经 system_linker_exec 执行），仅 **loader**（guest 裸 execve、挂不上钩子）须落 nativeLibraryDir——loader 产物**归插件**，经框架**通用「插件原生可执行文件托管」管线**落位（构建期把插件声明的原生可执行文件并入宿主 APK jniLibs、运行时返回其 nativeLibraryDir 路径；框架不打包任何 PRoot 专属产物）；PRoot 的 **rootfs / 配置 / 分发 / 生命周期仍是插件**。这才是"框架提供执行底座、PRoot 是插件"的准确落地。

---

## 4. memfd 生死线：已实测 = 被拒 ❌（errno=13 确认）

探针插件 `sample-proot-probe`（走框架真实安装/加载路径）在插件目录
`/data/user/0/com.lhzkml.jasmine/files/plugins/jasmine.sample.prootprobe` 实测，原始输出：

```
== PRoot 插件探针（插件目录）==
dir: /data/user/0/com.lhzkml.jasmine/files/plugins/jasmine.sample.prootprobe
[DENIED] execve（proot/loader 直接执行）: exec 抛异常: IOException:
         Cannot run program ".../probe_sh": error=13, Permission denied
[OK]     mmap PROT_EXEC（guest 装载路径）: 可执行（返回 42）
[DENIED] memfd+fexecve（proot/loader 出路·生死线）: execl 失败 errno=13（EACCES，多为 SELinux 拒绝）
结论: mmap 放行但 memfd 被拒 → guest 可装载，但 proot/loader 无出路，需框架侧 runner
```

- **`memfd_create + fexecve` → ❌ `execl 失败 errno=13 EACCES`**：子进程对 `/proc/self/fd/<memfd>` 执行 `execl` 被拒，精确 errno=13 确认是 SELinux 拦截（非格式/路径问题）。
- **原因**：Android 12 SELinux 禁 `untrusted_app` 执行 memfd/shmem 文件（防无文件恶意代码）。
- **对框架的指引**：插件自身没有可 execve proot 的位置，故框架要以 **proot runner** 能力补上这一环（§3）——这是框架对插件的能力支撑，PRoot 仍是插件。
- **报告自动导出**：探针跑完写 `proot-probe-report.txt` 到 `Android/data/com.lhzkml.jasmine/files/`。
- **剩余未实证项**：不止 nativeLibraryDir execve 一条，详见 §4.5（源码级缺口分析：loader 提取路径、nativeLibraryDir execve、ptrace、seccomp）。

---

## 4.5 PRoot 运行时依赖缺口（探针未覆盖，源码级确认）

> 依据：已克隆到本地的 `proot-termux/` 源码。前面三条探针只裁决了"通用执行路径"，但 PRoot 真正运行时还依赖几条**专属链路**——其中一条**源码级确认必挂**，另几条**尚未实测**。这些是探针没探出来的缺口。

### 缺口 1（🔴 源码级确认必挂）：loader 的"提取→execve"路径

PRoot 默认流程（`src/execve/enter.c:482-555`）：
1. 把内嵌 loader 用 `mkstemp` 写到 `$PROOT_TMP_DIR`（`src/path/temp.c:17-44`，Android 上必然落在 app_data_file）；
2. `fchmod u+rx`（`enter.c:514`）；
3. tracee 对该**真实文件路径**做真正的内核 execve（`enter.c:709-715`）。

这与探针 1（execve app_data_file → EACCES）**完全同构**——内核 execve 的 SELinux 检查看的是底层 inode。**只要 `PROOT_TMP_DIR` 在 app_data_file，loader 提取路径必挂**。

更隐蔽：`enter.c:526` 的 `access(path, X_OK)` 只查 DAC / noexec 挂载、**查不出 SELinux**，会通过检查、到真正 execve 才 EACCES，报错还会误导成 "mounted no execution permission"。

**对策（源码给出）**：`get_loader_path()`（`enter.c:562-588`）支持 `PROOT_LOADER` 环境变量或编译期 `PROOT_UNBUNDLE_LOADER`，**跳过提取**。但注意**并不跳过 execve**——tracee 仍要 execve 那个预置路径。所以 **loader 必须放可 execve 的 `nativeLibraryDir` 并用 `PROOT_LOADER` 指过去**。这把 §3 的"loader→nativeLibraryDir"从建议升级为**硬约束**。**通用化落地**：loader 产物归插件，经框架**通用「插件原生可执行文件托管」管线**落 nativeLibraryDir（框架构建期并入宿主 jniLibs）；框架不打包任何 PRoot 专属产物，`PROOT_LOADER` 只是插件经 runner 通用 env 注入传入的普通环境变量。

### 缺口 2（🟡 地基，未实测）：nativeLibraryDir 的 execve

proot 本体和 loader 都要从框架执行底座落点 `nativeLibraryDir` execve，但这条**从未实测**（目录只读、探针写不进去）。这是整个方案的地基：把一个极小 PIE 经插件声明（由框架通用托管管线并入宿主 jniLibs）再 execve 它，通了 proot/loader 才有落点。

### 缺口 3（🟡 PRoot 命脉，未实测）：ptrace 子进程

PRoot 用全套 ptrace（`src/tracee/event.c`、`src/tracee/reg.c`）：`PTRACE_TRACEME`、`PTRACE_SYSCALL`、`GETREGS/SETREGS`、`GETREGSET/SETREGSET`（arm64 还要 `NT_ARM_SYSTEM_CALL` 改系统调用号）、`PEEKDATA/POKEDATA`。Termux 在包括 MIUI 的海量设备上跑 proot，**经验上大概率可行**，但本机未实证。

### 缺口 4（🟢 加速项，有回退）：seccomp

PRoot 给 tracee 装 seccomp BPF 加速（`src/tracee/seccomp.c`），可用 `PROOT_NO_SECCOMP` 回退。zygote 的 seccomp 是否冲突未验证，但有回退，优先级低。

### 探针覆盖对照 & 需补探针

现有三探针覆盖：`execve app_data_file`（插件目录直接 exec）、`mmap PROT_EXEC`（loader 装载 guest，`loader.c:144` ✅）、`memfd+fexecve`（一种绕法，已否）。**未覆盖**上面缺口 2/3/4，需补：

| 新探针 | 测什么 | 为什么 |
|---|---|---|
| **A. nativeLibraryDir execve** | 极小 PIE 经插件声明（框架通用托管管线并入宿主 jniLibs），execve 它 | 地基；通了 proot/loader 才有落点 |
| **B. ptrace 子进程** | fork 子进程 `PTRACE_TRACEME`+`PTRACE_SYSCALL`+`GETREGS` 往返 | PRoot 命脉，本机实证 |
| **C. seccomp（可选）** | 给子进程装 seccomp BPF | 确认加速可用，不行就 `PROOT_NO_SECCOMP` |

> 缺口 1 **不用探**（源码已确认必挂），但它把"loader 必须走 nativeLibraryDir + `PROOT_LOADER`"升级为硬约束。缺口 2/3（+可选 4）由新探针 A/B/C 实证。

### 4.5.1 P0.75 探针已实现并真机验证（执行底座经框架通用管线托管）
== PRoot 插件探针（插件目录）==dir: /data/user/0/com.lhzkml.jasmine/files/plugins/jasmine.sample.prootprobe[DENIED] execve（proot/loader 直接执行）: exec 抛异常: IOException: Cannot run program "/data/user/0/com.lhzkml.jasmine/files/plugins/jasmine.sample.prootprobe/probe_sh": error=13, Permission denied[OK] mmap PROT_EXEC（guest 装载路径）: 可执行（返回 42）[DENIED] memfd+fexecve（proot/loader 出路·生死线）: execl 失败 errno=13（EACCES，多为 SELinux 拒绝）结论: mmap 放行但 memfd 被拒 → guest 可装载，但 proot/loader 无出路，需框架侧 runner== 地基探针 A/B/C（PRoot 专属链路，缺口 §4.5）==nativeLibraryDir: /data/app/~~X_B7hiYTXSIsYwkiF5ncUQ==/com.lhzkml.jasmine-ZZnJu5rg5iIaP8_yzrH4Bw==/lib/arm64[OK] A. nativeLibraryDir execve: 放行（PIE 退出 42）→ proot/loader 可落 nativeLibraryDir[OK] B. ptrace 子进程（TRACEME+GETREGSET+CONT）: 全链路可用（附着+读寄存器+继续）→ PRoot 命脉 OK[OK] C. seccomp BPF 安装: 可安装 seccomp 过滤 → PRoot 加速可用[OK] D. system_linker_exec（插件目录动态二进制经 linker64）: 可经 linker64 装载运行 → 框架 runner 能用 system_linker_exec，proot 可留插件目录[OK] E. ProcessBuilder+linker64（框架 runViaLinker 同机制）: ProcessBuilder+linker64 可用 → 框架 runViaLinker 能把插件目录 proot 起为子进程地基结论: nativeLibraryDir execve 与 ptrace 均放行 → P1（proot runner）地基成立；seccomp 可用（加速）增强方向: system_linker_exec 成立（native 与 ProcessBuilder 双路皆绿）→ 框架 ExecBridge.runViaLinker 可用，proot 本体可留插件（仅 loader 仍需执行底座 nativeLibraryDir）

地基与探针均已落码并打包（执行底座 PIE 归**探针插件**，经框架通用托管管线运行时并入应用 APK）：

- **地基 PIE**：`sample-proot-probe/src/main/exec/proot_probe_pie.c`（NDK 编译的极小静态 PIE，`ET_DYN`、无 `PT_INTERP`、入口即 `exit(42)`），由探针插件经 `pluginPack.nativeExecutables` 声明、框架通用托管管线并入宿主 jniLibs，随安装提取到执行底座落点 `nativeLibraryDir`（`libjasmine.sample.prootprobe.proot_probe_pie.so`）。**PIE 产物归探针插件，框架只提供通用托管能力，寄存在宿主 app 的 nativeLibraryDir（唯一可 execve 处，见 §3 术语界定）。**
- **提取前提**：`app/build.gradle.kts` 设 `packaging.jniLibs.useLegacyPackaging = true`（等效 `extractNativeLibs=true`），否则 PIE 不落盘、无法 execve。AGP 禁止在 Manifest 直接写 `extractNativeLibs`。
- **native 探针**：`core-plugin/.../cpp/exec_bridge.c` 新增 `nativeExecFileProbe`（A：fork+execve 真实文件）、`nativePtraceProbe`（B：TRACEME+GETREGSET+CONT 往返）、`nativeSeccompProbe`（C：装全放行 BPF）。Kotlin 封装在 `ProotProbe.execFile/ptraceProbe/seccompProbe`。
- **探针入口**：`sample-proot-probe` 的 `ProotProbeEntry` 增"地基探针 A/B/C"段，读 `applicationInfo.nativeLibraryDir`（框架执行底座落点）定位 PIE，结果与地基结论一并导出报告。
- **判读**：A=42→nativeLibraryDir 可 execve（proot/loader 有落点）；B=42→ptrace 命脉可用；C=42→seccomp 加速可用（否则 `PROOT_NO_SECCOMP` 回退）。

**✅ 真机实测结果（K30 Pro，release 版，2026-08-25）——三项全绿**（PIE 改由框架 `core-plugin` 提供后复探，结果一致，确认探的是框架执行底座落点）：
```
nativeLibraryDir: /data/app/~~.../com.lhzkml.jasmine-.../lib/arm64
[OK] A. nativeLibraryDir execve: 放行（PIE 退出 42）→ proot/loader 可落 nativeLibraryDir
[OK] B. ptrace 子进程（TRACEME+GETREGSET+CONT）: 全链路可用 → PRoot 命脉 OK
[OK] C. seccomp BPF 安装: 可安装 seccomp 过滤 → PRoot 加速可用
地基结论: nativeLibraryDir execve 与 ptrace 均放行 → P1（proot runner）地基成立；seccomp 可用（加速）
```
至此缺口 1/2/3/4 裁决：缺口 1 走 `PROOT_LOADER`→nativeLibraryDir（A 已证可 execve）；缺口 2/3/4 实测全通。随后探针 D 又实证了 `system_linker_exec`（§4.6），进一步把 proot 本体还给插件。

---

## 4.6 system_linker_exec：Android W^X 限制与 Termux 官方绕法（探针 D 实证成立）

> 依据：Termux 官方 wiki《Termux execution environment》、termux-exec README（联网精读）+ 探针 D 真机实证。

### 背景：Android 10+ W^X 铁律（Termux 同样被拦）
官方原文：Android 10 起、`targetSdkVersion>=29` 的 app **不能 exec 自己数据目录**（`/data/data/<pkg>`）的文件——这是 **W^X**（可写/可执行二选一）SELinux 策略（commit `0dd738d8`）。**Termux 也一样被拦**，与探针 1（插件目录 execve→EACCES）完全一致——说明这不是探错，是全平台铁律。

### Termux 官方绕法：system_linker_exec
原理（termux-exec README）：自 2018 起 Android 动态链接器可被**直接 exec**——传入一个可执行文件路径，链接器会自行 mmap 装载并运行它。于是把 `execve(path/to/mybinary)` 改写为 `execve("/system/bin/linker64", "linker64", /abs/path/to/mybinary)`。**内核只看到执行了 `/system/bin/linker64`**（`system_linker_exec` 标签，放行），目标由链接器经 mmap 装载（mmap PROT_EXEC app_data_file 已证放行，见探针 2）。

官方限制：仅**动态链接**二进制（静态不行）；目标须**绝对路径**；透明化靠 `LD_PRELOAD` 钩 libc `exec()` 包装、对**裸 execve 系统调用无效**；`/proc/PID/exe` 会显示为 linker64。

### 探针 D 真机实证（✅ 成立）
把动态链接极小 PIE（`PT_INTERP=linker64`、依赖 `libc.so`）放进探针插件 jniLibs，框架解压到 `filesDir/plugins/<id>/lib/arm64-v8a/`（app_data_file），对其绝对路径执行 `execve linker64`：
```
[OK] D. system_linker_exec（插件目录动态二进制经 linker64）: 可经 linker64 装载运行（退出 42）
```
**结论**：本机 `system_linker_exec` 成立 → 框架 runner 可据此执行**插件目录里的 proot**。

### 对设计的定型（为何 loader 仍留执行底座）
- **proot 本体**：动态链接，可经 system_linker_exec 从插件目录被框架 runner 执行 → **proot 归插件分发**。
- **loader**：由 tracee 以**裸 execve** 触发（`execve/enter.c` 把 tracee 的 execve 改写为 loader 路径），且其后的 tracee 是 **guest 进程（musl/glibc）**，挂不上 bionic 的 `LD_PRELOAD` 钩子，system_linker_exec 帮不上 → **loader 仍须落可 execve 的 `nativeLibraryDir`**（探针 A 已证可 execve）。**通用化落地**：loader 产物归插件，经框架**通用「插件原生可执行文件托管」管线**落 nativeLibraryDir（框架不打包任何 PRoot 专属产物）。

---

## 5. 框架（core-plugin）要补的能力

要承载一个 PRoot 插件，框架当前缺口：

1. **proot runner（system_linker_exec + fork 子进程）**：✅ 已实现 `ExecBridge.runViaLinker`——`EXEC` 门控，经 **system_linker_exec**（execve linker64 + 插件目录二进制，探针 D 已证）起**独立子进程**并返回 `Process`（管道/waitFor/destroy），支持注入 env/cwd（`PROOT_LOADER`/`PROOT_TMP_DIR`）。**探针 E 真机实证成立（42）**——ProcessBuilder+linker64 可把插件目录二进制成子进程。此前 `ExecBridge` 仅 dlopen（进程内、同步、无句柄），不满足 proot 需独立被 ptrace 进程的要求。
2. **插件原生可执行文件托管管线（通用）**：loader 类产物须落 `nativeLibraryDir`（唯一可 execve 处），但插件运行时才安装、无法自行落位。框架需提供**通用管线**：插件声明原生可执行文件 → 构建期并入宿主 APK jniLibs → 运行时返回其 `nativeLibraryDir` 路径。框架**不打包任何插件专属产物**（通用能力，任何需要原生可执行文件落 nativeLibraryDir 的插件都能复用）。✅ 已实现——插件侧 `pluginPack.nativeExecutables` 声明、宿主侧 `PluginDevPlugin` 构建期并入宿主 jniLibs、运行时 `ExecBridge.nativeExecutablePath` 定位；探针 PIE 已改经此管线端到端验证，框架不再携带任何 PRoot 专属产物。
3. **子进程生命周期**：异步启动、stdin/stdout 管道泵、死亡回调/重建（框架现有策略是"崩溃熔断=禁用"，方向相反）。
4. **env/cwd 注入**：尤其 `PROOT_TMP_DIR`、`PROOT_LOADER`；dlopen 路径两者皆无。
5. **隔离进程复用**：可把 PRoot 插件整体放进 `:plugin_isolated_N`，proot ptrace 其子进程、崩溃不拖垮宿主（框架现成优势）。

可复用地基：`EXEC` 能力门控（语义本就指向 "Proot-style user-space Linux"）、4 槽隔离进程 + Binder 目录、`AssetDownloader`、exec 资产提取管线、abstract socket `OffloadDispatcher`。

---

## 6. 探测方案：做成插件，走框架真实路径

探测由一个**探针插件**（`sample-proot-probe`）完成：

- 作为标准插件（`jasmine.plugin-pack`）打包、经 `installPlugin` 安装、由框架加载。
- 插件在自己的 `pluginDir`（即 `filesDir/plugins/<id>/`）里跑三项探针：
  1. **execve**：复制 `/system/bin/sh` 进 pluginDir → `ProcessBuilder` exec（实测 DENIED）。
  2. **mmap PROT_EXEC**：写机器码进 pluginDir → mmap + fork 子进程执行（实测 OK）。
  3. **memfd + fexecve**：memfd 写机器码 → fork 子进程 fexecve（**decisive**，实测被拒，见 §4）。
- 结果在插件 `MainScreen` 展示 + 自动导出报告文件。
- 这同时验证了"框架能否安装/加载插件、插件能否在其目录跑原生代码"这条真实路径。

---

## 7. 已经针对缺口对框架完成优化增强的部分

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 ✅ | 设备层 noexec/mmap 实测（execve 拒、mmap 放行） | 已完成 |
| P0.5 ✅ | **memfd 探针**（探针插件，走框架真实路径）：**被拒** → 框架以 proot runner（nativeLibraryDir）支撑 | 已完成 |
| P0.75 ✅ | **地基探针 A/B/C/D**（§4.5/§4.6）：**真机全绿** —— A=42（nativeLibraryDir 可 execve）、B=42（ptrace）、C=42（seccomp）、**D=42（system_linker_exec，proot 可留插件）** → **P1 地基成立** | 已完成 |
| P1 | 框架补 **proot runner**：✅ 已实现 `ExecBridge.runViaLinker`（system_linker_exec + ProcessBuilder 子进程 + EXEC 门控），**探针 E 真机实证成立（42）→ runner 核心验证通过**；**loader**（产物归插件）经框架**通用「插件原生可执行文件托管」管线**落 nativeLibraryDir、由插件传入 env（`PROOT_LOADER`）指向、禁用默认提取（缺口 1 硬约束，待真 loader） | 进行中 |
