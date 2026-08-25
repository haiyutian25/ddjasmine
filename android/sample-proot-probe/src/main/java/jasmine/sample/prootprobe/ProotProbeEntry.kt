package jasmine.sample.prootprobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
import com.lhzkml.jasmine.core.plugin.PluginMenuEntry
import com.lhzkml.jasmine.core.plugin.ServiceTable
import com.lhzkml.jasmine.core.plugin.proxy.ExecBridge
import com.lhzkml.jasmine.core.plugin.proxy.ProotProbe
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 探针 A：极小静态 PIE 未在宿主 nativeLibraryDir 找到（未打包/未提取）。 */
private const val PIE_MISSING = -6

/**
 * PRoot 可行性探针插件入口。
 *
 * 在插件自己的目录（`filesDir/plugins/<id>/`，app_data_file）里实测三项，
 * 裁决"PRoot Linux 能否做成本框架的插件"：
 *  1. execve：复制 /system/bin/sh 进插件目录并 ProcessBuilder 执行（预期被 SELinux 拒）。
 *  2. mmap PROT_EXEC：写机器码进插件目录 → mmap + 子进程执行（guest 装载路径，预期放行）。
 *  3. memfd + fexecve：匿名内存文件跑独立程序（proot/loader 的出路，插件模型生死线）。
 */
class ProotProbeEntry : PluginEntry {

    override val services: ServiceTable = emptyMap()

    override val menuEntry: PluginMenuEntry = PluginMenuEntry(
        title = "PRoot 探针",
        subtitle = "在插件目录实测 execve / mmap / memfd",
    )

    /** 插件私有目录（onLoad 注入），探针的落点。 */
    @Volatile
    private var pluginDir: File? = null

    /** 插件 ID（onLoad 注入），经通用托管管线定位插件的原生可执行文件。 */
    @Volatile
    private var pluginId: String? = null

    /** 宿主 Application，用于把报告导出到外部文件目录。 */
    @Volatile
    private var app: android.app.Application? = null

    override fun onLoad(context: PluginContext) {
        pluginDir = File(context.pluginDir)
        pluginId = context.pluginId
        app = context.application
    }

    override fun onUnload() {
        pluginDir = null
        pluginId = null
        app = null
    }

    @Composable
    override fun MainScreen() {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var lines by remember { mutableStateOf<List<String>>(emptyList()) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("PRoot 可行性探针", style = MaterialTheme.typography.headlineSmall)
            Text(
                "落点：${pluginDir?.absolutePath ?: "(未就绪)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Button(
                onClick = {
                    if (!running) {
                        running = true
                        lines = listOf("探测运行中…")
                        scope.launch {
                            lines = withContext(Dispatchers.IO) { runProbes() }
                            running = false
                        }
                    }
                },
                enabled = !running && pluginDir != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "探测中…" else "运行探测")
            }
            Spacer(Modifier.height(4.dp))
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    /** 探针：三项通用路径 + 三项地基（A/B/C），返回逐行结果。 */
    private fun runProbes(): List<String> {
        val dir = pluginDir ?: return listOf("插件目录未就绪")
        val out = mutableListOf<String>()
        out += "== PRoot 插件探针（插件目录）=="
        out += "dir: ${dir.absolutePath}"
        out += ""
        out += "[${mark(probeExecve(dir))}] execve（proot/loader 直接执行）: ${execveInfo(dir)}"
        out += "[${mark(probeMmap(dir))}] mmap PROT_EXEC（guest 装载路径）: ${mmapInfo(dir)}"
        val memfd = ProotProbe.memfdExec()
        out += "[${mark(memfd == 42)}] memfd+fexecve（proot/loader 出路·生死线）: ${interpret(memfd)}"
        out += ""
        out += "结论: " + verdict(probeMmap(dir), memfd)
        out += ""
        out += "== 地基探针 A/B/C（PRoot 专属链路，缺口 §4.5）=="
        val nativeLibDir = app?.applicationInfo?.nativeLibraryDir
        out += "nativeLibraryDir: ${nativeLibDir ?: "(未知)"}"
        // A：nativeLibraryDir execve（proot/loader 的落点，整个方案地基）。
        // 探针 PIE 归插件所有，经框架通用「原生可执行文件托管」管线落 nativeLibraryDir，
        // 运行时用 ExecBridge.nativeExecutablePath 定位（不再硬拼 libproot_probe_pie.so）。
        val piePath = app?.let { a ->
            pluginId?.let { pid ->
                ExecBridge(a).nativeExecutablePath(pid, "proot_probe_pie")?.absolutePath
            }
        }
        val a = if (piePath != null && File(piePath).exists()) ProotProbe.execFile(piePath) else PIE_MISSING
        out += "[${mark(a == 42)}] A. nativeLibraryDir execve: ${interpretA(a)}"
        // B：ptrace 子进程往返（PRoot 命脉）。
        val b = ProotProbe.ptraceProbe()
        out += "[${mark(b == 42)}] B. ptrace 子进程（TRACEME+GETREGSET+CONT）: ${interpretB(b)}"
        // C：seccomp BPF 安装（PRoot 加速项，可回退）。
        val c = ProotProbe.seccompProbe()
        out += "[${mark(c == 42)}] C. seccomp BPF 安装: ${interpretC(c)}"
        // D：system_linker_exec（框架 runner 增强方向）——对"插件目录里的动态二进制"经 linker64 装载。
        val dyn = findPluginLib("libprobe_dyn.so")
        val d = if (dyn != null) ProotProbe.linkerExec(dyn.absolutePath) else PIE_MISSING
        out += "[${mark(d == 42)}] D. system_linker_exec（插件目录动态二进制经 linker64）: ${interpretD(d)}"
        // E：ProcessBuilder+linker64——框架 ExecBridge.runViaLinker 用的正是此机制，实证其成立。
        val e = if (dyn != null) probeProcessBuilderLinker(dyn.absolutePath) else PIE_MISSING
        out += "[${mark(e == 42)}] E. ProcessBuilder+linker64（框架 runViaLinker 同机制）: ${interpretE(e)}"
        out += ""
        out += "地基结论: " + foundationVerdict(a, b, c)
        out += "增强方向: " + linkerExecVerdict(d, e)
        // 自动导出报告到宿主外部文件目录，便于在手机上直接查看/发回。
        exportReport(out)?.let { out += "报告已导出: $it" }
        return out
    }

    /** 把结果写到宿主 app 的外部文件目录（无需权限），返回绝对路径。 */
    private fun exportReport(lines: List<String>): String? = try {
        val dir = app?.getExternalFilesDir(null) ?: return null
        File(dir, "proot-probe-report.txt")
            .apply { writeText(lines.joinToString("\n")) }
            .absolutePath
    } catch (e: Throwable) {
        null
    }

    // ---- 探针一：execve（Kotlin ProcessBuilder）----
    private fun probeExecve(dir: File): Boolean {
        val probe = File(dir, "probe_sh")
        return try {
            File("/system/bin/sh").inputStream().use { i -> probe.outputStream().use { o -> i.copyTo(o) } }
            probe.setExecutable(true, false)
            val p = ProcessBuilder(probe.absolutePath, "-c", "echo OK").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(5, TimeUnit.SECONDS)
            done && p.exitValue() == 0 && text.contains("OK")
        } catch (e: Throwable) {
            false
        } finally {
            probe.delete()
        }
    }

    private fun execveInfo(dir: File): String {
        val probe = File(dir, "probe_sh")
        return try {
            File("/system/bin/sh").inputStream().use { i -> probe.outputStream().use { o -> i.copyTo(o) } }
            probe.setExecutable(true, false)
            val p = ProcessBuilder(probe.absolutePath, "-c", "echo OK").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(5, TimeUnit.SECONDS)
            "exit=${if (done) p.exitValue() else -1}, out=${text.trim().ifEmpty { "<空>" }}"
        } catch (e: Throwable) {
            "exec 抛异常: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            probe.delete()
        }
    }

    // ---- 探针二：mmap PROT_EXEC（框架原生）----
    /** arm64：mov w0,#42 ; ret */
    private val mmapCode = byteArrayOf(
        0x40, 0x05, 0x80.toByte(), 0x52,
        0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte(),
    )

    private fun probeMmap(dir: File): Boolean {
        val payload = File(dir, "probe_mmap.bin")
        return try {
            payload.writeBytes(mmapCode)
            ProotProbe.mmapExec(payload.absolutePath) == 42
        } catch (e: Throwable) {
            false
        } finally {
            payload.delete()
        }
    }

    private fun mmapInfo(dir: File): String {
        val payload = File(dir, "probe_mmap.bin")
        return try {
            payload.writeBytes(mmapCode)
            interpret(ProotProbe.mmapExec(payload.absolutePath))
        } catch (e: Throwable) {
            "异常: ${e.message}"
        } finally {
            payload.delete()
        }
    }

    // ---- 解释与结论 ----
    private fun mark(ok: Boolean) = if (ok) "OK" else "DENIED"

    private fun interpret(r: Int): String = when {
        r == 42 -> "可执行（返回 42）"
        r == -9999 -> "libexecbridge 未加载"
        r < 0 && r > -1000 -> "被拒 errno=${-r}"
        r <= -1000 && r > -2000 -> "执行时被信号 ${-(r + 1000)} 杀死"
        r <= -3000 && r > -4000 -> "execl 失败 errno=${-(r + 3000)}${errnoHint(-(r + 3000))}"
        else -> "异常码 $r"
    }

    private fun errnoHint(e: Int): String = when (e) {
        13 -> "（EACCES，多为 SELinux 拒绝）"
        1 -> "（EPERM）"
        2 -> "（ENOENT）"
        8 -> "（ENOEXEC，非可执行格式）"
        else -> ""
    }

    // ---- 地基探针 A/B/C 的解读 ----
    private fun interpretA(r: Int): String = when {
        r == 42 -> "放行（PIE 退出 42）→ proot/loader 可落 nativeLibraryDir"
        r == PIE_MISSING -> "PIE 未找到（未打包进宿主 jniLibs 或未提取）"
        r <= -3000 && r > -4000 -> "execve 失败 errno=${-(r + 3000)}${errnoHint(-(r + 3000))}"
        else -> interpret(r)
    }

    private fun interpretB(r: Int): String = when {
        r == 42 -> "全链路可用（附着+读寄存器+继续）→ PRoot 命脉 OK"
        r == -2001 -> "子进程未按预期停止/结果不符"
        r == -2002 -> "PTRACE_CONT 失败"
        r <= -4000 && r > -5000 -> "PTRACE_TRACEME 失败 errno=${-(r + 4000)}"
        else -> interpret(r)
    }

    private fun interpretC(r: Int): String = when {
        r == 42 -> "可安装 seccomp 过滤 → PRoot 加速可用"
        r <= -5000 && r > -6000 -> "安装失败 errno=${-(r + 5000)}（可 PROOT_NO_SECCOMP 回退）"
        else -> interpret(r)
    }

    private fun foundationVerdict(a: Int, b: Int, c: Int): String {
        val aOk = a == 42
        val bOk = b == 42
        return when {
            aOk && bOk ->
                "nativeLibraryDir execve 与 ptrace 均放行 → P1（proot runner）地基成立" +
                    if (c == 42) "；seccomp 可用（加速）" else "；seccomp 受阻（用 PROOT_NO_SECCOMP 回退）"
            aOk && !bOk ->
                "nativeLibraryDir 可 execve，但 ptrace 受阻 → proot 起得来却拦不了 syscall，需查 ptrace 限制"
            !aOk && bOk ->
                "ptrace 可用，但 nativeLibraryDir execve 受阻 → proot/loader 无落点，方案需重估"
            else ->
                "nativeLibraryDir execve 与 ptrace 均受阻 → PRoot 插件路线在本机受阻"
        }
    }

    // ---- 探针 D（system_linker_exec）辅助 ----

    /** 在插件的 lib/<abi>/ 里按名找框架解压出来的 native 库。 */
    private fun findPluginLib(name: String): File? {
        val dir = pluginDir ?: return null
        val libRoot = File(dir, "lib")
        if (!libRoot.isDirectory) return null
        libRoot.listFiles()?.forEach { abiDir ->
            val f = File(abiDir, name)
            if (f.isFile) return f
        }
        return null
    }

    private fun interpretD(r: Int): String = when {
        r == 42 -> "可经 linker64 装载运行 → 框架 runner 能用 system_linker_exec，proot 可留插件目录"
        r == PIE_MISSING -> "动态探针 PIE 未找到（未随插件解压到 lib/<abi>）"
        r <= -3000 && r > -4000 -> "execve linker64 失败 errno=${-(r + 3000)}${errnoHint(-(r + 3000))}"
        else -> interpret(r)
    }

    /** 探针 E：ProcessBuilder+linker64（框架 ExecBridge.runViaLinker 用的正是此机制）。 */
    private fun probeProcessBuilderLinker(binaryPath: String): Int {
        return try {
            val linker =
                if (android.os.Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"
            val p = ProcessBuilder(linker, binaryPath).redirectErrorStream(true).start()
            val done = p.waitFor(5, TimeUnit.SECONDS)
            if (done) p.exitValue() else -2000
        } catch (t: Throwable) {
            -1
        }
    }

    private fun interpretE(r: Int): String = when {
        r == 42 -> "ProcessBuilder+linker64 可用 → 框架 runViaLinker 能把插件目录 proot 起为子进程"
        r == PIE_MISSING -> "动态探针 PIE 未找到"
        r == -2000 -> "超时（子进程未退出）"
        r == -1 -> "ProcessBuilder 抛异常"
        else -> "退出码 $r（非 42）"
    }

    private fun linkerExecVerdict(d: Int, e: Int): String = when {
        d == 42 && e == 42 ->
            "system_linker_exec 成立（native 与 ProcessBuilder 双路皆绿）→ 框架 ExecBridge.runViaLinker 可用，" +
                "proot 本体可留插件（仅 loader 仍需执行底座 nativeLibraryDir）"
        d == 42 ->
            "system_linker_exec 成立（native），但 ProcessBuilder 路径未绿 → runViaLinker 实现方式需复核"
        else ->
            "system_linker_exec 不成立 → 框架执行底座仍须完全依赖 nativeLibraryDir（proot+loader 都进框架 jniLibs）"
    }

    private fun verdict(mmapOk: Boolean, memfd: Int): String = when {
        memfd == 42 && mmapOk ->
            "memfd 与 mmap 均放行 → PRoot 插件路线可行（proot/loader 走 memfd，guest 走 mmap）"
        memfd == 42 ->
            "memfd 放行但 mmap 被拒 → proot 可起，guest 装载需另验"
        mmapOk ->
            "mmap 放行但 memfd 被拒 → guest 可装载，但 proot/loader 无出路，需框架侧 runner"
        else ->
            "memfd 与 mmap 均被拒 → PRoot 插件路线在本机受阻"
    }
}
