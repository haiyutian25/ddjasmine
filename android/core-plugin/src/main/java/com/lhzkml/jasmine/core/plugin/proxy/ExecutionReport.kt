package com.lhzkml.jasmine.core.plugin.proxy

/**
 * E19：执行能力综合报告。
 *
 * 整合框架侧可直接探测的执行底座事实（dlopen 桥、nativeLibraryDir、SELinux、
 * ptrace_scope、seccomp、幻影进程杀手、ABI）与插件侧记录的探针结果（A–E）。
 *
 * 框架不硬编码任何探针逻辑——探针 A–E 由插件侧经 [ProotProbe] 运行，
 * 结果经 [ExecBridge.recordProbeResult] 记录，报告统一呈现。
 *
 * @param dlopenBridgeAvailable dlopen 桥（进程内执行）是否可用。
 * @param nativeLibraryDir 宿主 nativeLibraryDir 绝对路径（框架唯一可 execve 的落点）。
 * @param nativeLibraryDirWritable nativeLibraryDir 是否可写（通常只读）。
 * @param selinuxContext 当前进程 SELinux 上下文（如 `u:r:untrusted_app:s0:...`）。
 * @param ptraceScope Yama ptrace_scope（-1 = Yama 未启用）。≥2 时 proot 必然无法工作。
 * @param seccompMode 当前进程 seccomp 模式（0=disabled, 1=strict, 2=filter；-1=读取失败）。
 * @param phantomProcessKiller 幻影进程杀手状态。
 * @param supportedAbis 设备支持的 ABI 列表。
 * @param probeResults 插件侧记录的探针结果（探针名 → 返回码；42=成功）。
 * @param generatedAtMs 报告生成时间戳（毫秒）。
 */
data class ExecutionReport(
    val dlopenBridgeAvailable: Boolean,
    val nativeLibraryDir: String,
    val nativeLibraryDirWritable: Boolean,
    val selinuxContext: String,
    val ptraceScope: Int,
    val seccompMode: Int,
    val phantomProcessKiller: ExecBridge.PhantomKillerStatus,
    val supportedAbis: List<String>,
    val probeResults: Map<String, Int>,
    val generatedAtMs: Long,
) {
    /** ptrace_scope >= 2 时 proot 的 PTRACE_TRACEME 必然失败。 */
    val prootBlockedByYama: Boolean
        get() = ptraceScope >= 2

    /** 已记录的探针是否全部成功（42）。空记录视为未探测。 */
    val allProbesPassed: Boolean
        get() = probeResults.isNotEmpty() && probeResults.values.all { it == 42 }
}
