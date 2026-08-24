package jasmine.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.FileOutputStream
import javax.tools.ToolProvider
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Packaging plugin for library-module plugins: turns a `com.android.library`
 * module's release AAR into a signed, installable plugin APK.
 *
 * Pipeline (mirrors the reference five-step chain): extract → aapt2
 * compile+link with a partitioned `--package-id` (host keeps 0x7f, plugins
 * take 0x80+N — resource ids can never collide at runtime) → d8 → zip →
 * apksigner. SDK tools are located from the module's own SDK; defaults sign
 * with the standard debug keystore so host-trusted installs just work in
 * development.
 */
abstract class PluginPackExtension {
    /** Package-id suffix: the plugin gets 0x80 + slot (1..127). */
    @get:Input
    abstract val packageIdSlot: Property<Int>

    /** 打包时排除的依赖 group（宿主已提供，不打进插件 DEX 以免冗余）。
     *  匹配规则：group 相等或以其为前缀（如 "androidx" 排除 androidx.core 等）。 */
    @get:Input
    abstract val excludeGroups: ListProperty<String>

    @get:Input
    abstract val keystorePath: Property<String>

    @get:Input
    abstract val keystorePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @get:Input
    abstract val keyPassword: Property<String>

    /** 宿主 app 模块路径（如 ":app"）。构建时自动读取其 releaseRuntimeClasspath，
     *  把宿主已提供的依赖 group 自动排除出插件 DEX，插件无需手写 excludeGroups。 */
    @get:Input
    abstract val hostProject: Property<String>

    init {
        packageIdSlot.convention(1)
        excludeGroups.convention(listOf("org.jetbrains.kotlin", "androidx"))
        hostProject.convention(":app")
        keystorePath.convention(
            File(System.getProperty("user.home"), ".android/debug.keystore").absolutePath,
        )
        keystorePassword.convention("android")
        keyAlias.convention("androiddebugkey")
        keyPassword.convention("android")
    }
}

abstract class PluginPackagingTask : DefaultTask() {

    @get:InputFile
    abstract val aarFile: RegularFileProperty

    @get:Input
    abstract val packageIdSlot: Property<Int>

    /** 插件自带远程 SDK：`implementation`（含传递）依赖的 jar 文件集合。 */
    @get:Classpath
    abstract val dependencyJars: ConfigurableFileCollection

    @get:Internal
    abstract val sdkDirectory: Property<File>

    @get:Input
    abstract val keystorePath: Property<String>

    @get:Input
    abstract val keystorePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @get:Input
    abstract val keyPassword: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun buildTools(): File {
        val root = File(sdkDirectory.get(), "build-tools")
        return root.listFiles()?.maxByOrNull { it.name }
            ?: error("未找到 build-tools: $root")
    }

    private fun tool(name: String): String {
        val exe = if (isWindows()) "$name.exe" else name
        val bat = if (isWindows()) "$name.bat" else name
        val dir = buildTools()
        return listOf(File(dir, exe), File(dir, bat), File(dir, "lib/$exe"))
            .firstOrNull { it.exists() }?.absolutePath
            ?: error("SDK 工具缺失: $name（${dir.absolutePath}）")
    }

    private fun androidJar(): File {
        val platforms = File(sdkDirectory.get(), "platforms")
        val newest = platforms.listFiles()?.maxByOrNull { it.name }
            ?: error("未找到 platforms: $platforms")
        return File(newest, "android.jar")
    }

    private fun exec(command: List<String>, workDir: File) {
        logger.info("执行: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            error("命令失败($code): ${command.first()}\n$output")
        }
        if (output.isNotBlank()) logger.info(output)
    }

    @TaskAction
    fun packagePlugin() {
        val workDir = File(outputDir.get().asFile, "work").apply {
            deleteRecursively()
            mkdirs()
        }
        val extractDir = File(workDir, "extracted").apply { mkdirs() }

        // 1. extract the AAR
        ZipFile(aarFile.get().asFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val out = File(extractDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        val manifest = File(extractDir, "AndroidManifest.xml")
        check(manifest.exists()) { "AAR 缺少 AndroidManifest.xml" }

        // 2. aapt2 compile + link with the partitioned package id
        val slot = packageIdSlot.get()
        check(slot in 1..127) {
            "packageIdSlot 必须在 1..127（生成 package-id 0x81..0xFF，避免与宿主 0x7f 及彼此冲突），当前 $slot"
        }
        val packageId = "0x%02x".format(0x80 + slot)
        val flatDir = File(workDir, "flat").apply { mkdirs() }
        val resDir = File(extractDir, "res")
        if (resDir.isDirectory && resDir.listFiles()?.isNotEmpty() == true) {
            exec(
                listOf(tool("aapt2"), "compile", "--dir", resDir.absolutePath, "-o", flatDir.absolutePath),
                workDir,
            )
        }
        val genJava = File(workDir, "gen").apply { mkdirs() }
        val unsignedApk = File(workDir, "unsigned.apk")
        val link = mutableListOf(
            tool("aapt2"), "link",
            "-o", unsignedApk.absolutePath,
            "-I", androidJar().absolutePath,
            "--manifest", manifest.absolutePath,
            "--java", genJava.absolutePath,
            "--package-id", packageId,
            "--auto-add-overlay",
            "--no-version-vectors",
            "--no-static-lib-packages",
        )
        val flats = flatDir.walkTopDown().filter { it.isFile && it.extension == "flat" }.toList()
        if (flats.isNotEmpty()) {
            val responseFile = File(workDir, "aapt2-flat-files")
            responseFile.writeText(flats.joinToString(System.lineSeparator()) { it.absolutePath })
            link += "-R"
            link += "@${responseFile.absolutePath}"
        }
        exec(link, workDir)

        // 3. compile the aapt2-generated R.java (final package-id ids) so the
        //    plugin DEX carries R / R$layout / R$id, exactly like an installed
        //    app. Library-module R classes are non-final and never ship in
        //    classes.jar, so this is the only source of real ids.
        val rClassesDir = File(workDir, "rclasses").apply { mkdirs() }
        val rJavaFiles = genJava.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        if (rJavaFiles.isNotEmpty()) {
            val javac = ToolProvider.getSystemJavaCompiler()
            check(javac != null) { "JDK javac 不可用（需在 JDK 下运行，而非 JRE）" }
            val javacArgs = mutableListOf("-d", rClassesDir.absolutePath)
            rJavaFiles.forEach { javacArgs += it.absolutePath }
            val exit = javac.run(null, null, null, *javacArgs.toTypedArray())
            check(exit == 0) { "编译插件 R.java 失败" }
        }

        // 4. d8: classes.jar (+ libs/*.jar + R classes) → classes.dex
        val dexDir = File(workDir, "dex").apply { mkdirs() }
        val jars = buildList {
            val classesJar = File(extractDir, "classes.jar")
            if (classesJar.exists()) add(classesJar)
            File(extractDir, "libs").listFiles()?.filter { it.extension == "jar" }?.let { addAll(it) }
        }
        check(jars.isNotEmpty()) { "AAR 缺少 classes.jar" }
        val d8Inputs = buildList {
            jars.forEach { add(it.absolutePath) }
            // 插件自带远程 SDK：把 implementation 依赖的 jar 一并 d8 进 DEX，
            // 使插件可依赖宿主未提供的第三方库（如 Ktor、MCP Kotlin SDK）。
            dependencyJars.files.forEach { add(it.absolutePath) }
            rClassesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { add(it.absolutePath) }
        }
        exec(
            listOf(tool("d8"), "--output", dexDir.absolutePath) + d8Inputs,
            workDir,
        )

        // 5. zip: unsigned.apk + classes.dex + assets/ + lib/<abi>/
        val unsignedOut = File(workDir, "packaged.apk")
        unsignedApk.copyTo(unsignedOut, overwrite = true)
        appendToZip(unsignedOut, File(dexDir, "classes.dex"), "classes.dex")
        File(extractDir, "assets").takeIf { it.isDirectory }?.let { assets ->
            assets.walkTopDown().filter { it.isFile }.forEach { file ->
                appendToZip(unsignedOut, file, "assets/${file.relativeTo(assets).invariantSeparatorsPath}")
            }
        }
        File(extractDir, "jni").takeIf { it.isDirectory }?.let { jni ->
            jni.walkTopDown().filter { it.isFile }.forEach { file ->
                appendToZip(unsignedOut, file, "lib/${file.relativeTo(jni).invariantSeparatorsPath}")
            }
        }

        // 6. sign
        val signedApk = File(outputDir.get().asFile, "plugin-signed.apk")
        exec(
            listOf(
                tool("apksigner"), "sign",
                "--ks", keystorePath.get(),
                "--ks-pass", "pass:${keystorePassword.get()}",
                "--ks-key-alias", keyAlias.get(),
                "--key-pass", "pass:${keyPassword.get()}",
                "--out", signedApk.absolutePath,
                unsignedOut.absolutePath,
            ),
            workDir,
        )
        logger.lifecycle("插件包已生成: ${signedApk.absolutePath} (package-id $packageId)")
    }

    private fun appendToZip(zipFile: File, entry: File, entryName: String) {
        if (!entry.exists()) return
        val existing = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { e ->
                existing += ZipEntry(e.name) to zip.getInputStream(e).readBytes()
            }
        }
        ZipOutputStream(FileOutputStream(zipFile)).use { out ->
            for ((ze, bytes) in existing) {
                out.putNextEntry(ze)
                out.write(bytes)
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry(entryName))
            out.write(entry.readBytes())
            out.closeEntry()
        }
    }
}

class PluginPackPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("pluginPack", PluginPackExtension::class.java)
        project.plugins.withId("com.android.library") {
            val sdkComponents = project.extensions.getByType(
                com.android.build.api.variant.LibraryAndroidComponentsExtension::class.java,
            ).sdkComponents
            project.tasks.register<PluginPackagingTask>("packagePlugin") {
                aarFile.set(
                    project.layout.buildDirectory.file(
                        "outputs/aar/${project.name}-release.aar",
                    ),
                )
                packageIdSlot.set(extension.packageIdSlot)
                // 插件自带远程 SDK：把 `implementation`（含传递）依赖的 jar 一并
                // d8 进 DEX，使插件可依赖宿主未提供的第三方库。`compileOnly` 不进
                // runtime classpath，天然排除（仍从宿主 parent-first 解析）。
                dependencyJars.from(
                    project.provider {
                        val runtimeClasspath =
                            project.configurations.getByName("releaseRuntimeClasspath")
                        val excluded = extension.excludeGroups.get()
                        val hostGroups = hostProvidedGroups(project, extension.hostProject.get())
                        // 只取外部模块的 jar：componentFilter 在 variant 匹配前过滤掉
                        // project 依赖（core-plugin 等），避免 Android library project 依赖
                        // 的 variant 歧义；project 依赖的类由宿主提供、运行时 parent-first
                        // 解析，不进插件 DEX。
                        runtimeClasspath.incoming.artifactView {
                            componentFilter { it is ModuleComponentIdentifier }
                        }.artifacts.artifacts
                            .filter { art ->
                                val group =
                                    (art.id.componentIdentifier as? ModuleComponentIdentifier)
                                        ?.group ?: return@filter false
                                val manuallyExcluded =
                                    excluded.any { group == it || group.startsWith("$it.") }
                                val hostProvided =
                                    hostGroups.any { group == it || group.startsWith("$it.") }
                                !manuallyExcluded && !hostProvided
                            }
                            .map { it.file }
                    },
                )
                sdkDirectory.set(sdkComponents.sdkDirectory.map { it.asFile }.get())
                keystorePath.set(extension.keystorePath)
                keystorePassword.set(extension.keystorePassword)
                keyAlias.set(extension.keyAlias)
                keyPassword.set(extension.keyPassword)
                outputDir.set(project.layout.buildDirectory.dir("outputs/plugin"))
                dependsOn("bundleReleaseAar")
            }
        }
    }
}

/**
 * 读取宿主 app 模块的 releaseRuntimeClasspath，返回其全部依赖 group，
 * 用于自动排除"宿主已提供"的依赖。插件只需写 implementation，框架据此
 * 决定哪些打进 DEX、哪些从宿主 parent-first 解析。
 */
private fun hostProvidedGroups(project: Project, hostProjectPath: String): Set<String> =
    runCatching {
        val host = project.rootProject.project(hostProjectPath)
        // 用 resolutionResult（仅依赖图解析，不解析产物文件）收集宿主全部传递
        // 依赖的 group，避免 Android library project 依赖因 artifactType 属性
        // 缺失而 variant 歧义；只取外部模块，跳过 project 依赖（core-*/feature-*）。
        val groups = host.configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? ModuleComponentIdentifier)?.group }
            .toSet()
        project.logger.lifecycle(
            "[plugin-pack] 宿主 [$hostProjectPath] 已提供依赖 group (${groups.size}): " +
                groups.sorted().joinToString(", "),
        )
        groups
    }.onFailure { e ->
        val chain = generateSequence(e as Throwable?) { it.cause }
            .map { it.message ?: it::class.simpleName.orEmpty() }
            .joinToString("  <--  ")
        project.logger.lifecycle("[plugin-pack] 读取宿主依赖失败: $chain")
    }.getOrDefault(emptySet())
