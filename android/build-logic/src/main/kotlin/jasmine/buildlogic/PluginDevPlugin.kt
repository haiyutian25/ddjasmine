package jasmine.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Development-mode injection for the host app: builds each declared plugin
 * module's APK and wires it into the host's `assets/plugins/` through the
 * variant generated-sources API — Run the host and the plugins ride along.
 *
 * Usage in the host app module:
 * ```kotlin
 * plugins { id("jasmine.plugin-dev") }
 * pluginDev { modules.set(listOf(":sample-plugin")) }
 * ```
 */
abstract class PluginDevExtension {
    /** Gradle paths of application-module plugins (plain assemble output). */
    @get:Input
    abstract val modules: ListProperty<String>

    /**
     * Gradle paths of library-module plugins that apply jasmine.plugin-pack;
     * their APK comes from `build/outputs/plugin/plugin-signed.apk` via the
     * `packagePlugin` task.
     */
    @get:Input
    abstract val packModules: ListProperty<String>
}

abstract class InjectPluginsTask : DefaultTask() {

    /** "modulePath=apkFileName" pairs resolved at configuration time. */
    @get:Input
    abstract val apkSources: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun inject() {
        val out = outputDir.get().dir("plugins").asFile
        out.deleteRecursively()
        out.mkdirs()
        for (spec in apkSources.get()) {
            val parts = spec.split("=", limit = 3)
            val modulePath = parts[0]
            val name = parts[1]
            val source = File(parts[2])
            check(source.exists()) { "插件 APK 不存在: $source（$modulePath）" }
            source.copyTo(File(out, "$name.apk"), overwrite = true)
            logger.info("注入插件: $name.apk <- $source")
        }
    }
}

/**
 * 通用「插件原生可执行文件托管」管线（宿主侧）：把各 packModule 经
 * jasmine.plugin-pack 暴露的原生可执行文件（outputs/plugin/native-executables/<abi>/）
 * 合并进宿主 jniLibs。安装后它们落到宿主 nativeLibraryDir——全体系唯一可
 * execve 处。产物归插件，框架只提供托管能力，不打包任何插件专属产物。
 */
abstract class InjectNativeExecutablesTask : DefaultTask() {

    /** "modulePath=nativeExecutablesDir" pairs resolved at configuration time. */
    @get:Input
    abstract val nativeExeDirs: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun inject() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        var count = 0
        for (spec in nativeExeDirs.get()) {
            val parts = spec.split("=", limit = 2)
            val modulePath = parts[0]
            val dir = File(parts[1])
            if (!dir.isDirectory) continue
            // 目录结构 <abi>/lib<namespace>.<name>.so，按 ABI 并入宿主 jniLibs。
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val abi = file.parentFile.name
                val destDir = File(out, abi).apply { mkdirs() }
                file.copyTo(File(destDir, file.name), overwrite = true)
                count++
                logger.info("并入插件原生可执行文件: $abi/${file.name} ($modulePath)")
            }
        }
        logger.lifecycle("[plugin-dev] 并入宿主 jniLibs 的插件原生可执行文件: $count 个")
    }
}

class PluginDevPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("pluginDev", PluginDevExtension::class.java)
        extension.modules.convention(emptyList())
        extension.packModules.convention(emptyList())

        project.plugins.withId("com.android.application") {
            val androidComponents =
                project.extensions.getByType(
                    com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java,
                )
            androidComponents.onVariants { variant ->
                val modules = extension.modules.get()
                val packModules = extension.packModules.get()
                if (modules.isEmpty() && packModules.isEmpty()) return@onVariants
                val capitalized = variant.name.replaceFirstChar { it.uppercase() }
                val specs = mutableListOf<String>()
                for (modulePath in modules) {
                    val name = modulePath.substringAfterLast(':')
                    specs += "${modulePath}=${variant.name}=" +
                        project.rootProject.file(
                            "$name/build/outputs/apk/${variant.name}/$name-${variant.name}.apk",
                        ).absolutePath
                }
                for (modulePath in packModules) {
                    val name = modulePath.substringAfterLast(':')
                    specs += "${modulePath}=${name}=" +
                        project.rootProject.file(
                            "$name/build/outputs/plugin/plugin-signed.apk",
                        ).absolutePath
                }
                val task = project.tasks.register<InjectPluginsTask>(
                    "inject${capitalized}Plugins",
                ) {
                    outputDir.set(
                        project.layout.buildDirectory.dir("generated/pluginAssets/${variant.name}"),
                    )
                    apkSources.set(specs)
                    // 把源 APK 文件声明为输入文件：仅用字符串 @Input 无法感知
                    // 插件 APK 内容变化，会导致注入任务被误判为 UP-TO-DATE，
                    // 宿主打包出的还是旧插件。
                    inputs.files(specs.map { File(it.split("=", limit = 3)[2]) })
                    for (modulePath in modules) {
                        dependsOn("$modulePath:assemble$capitalized")
                    }
                    for (modulePath in packModules) {
                        dependsOn("$modulePath:packagePlugin")
                    }
                }
                variant.sources.assets?.addGeneratedSourceDirectory(task) { it.outputDir }

                // 通用原生可执行文件托管：收集各 packModule 经 plugin-pack 暴露的
                // 原生可执行文件，并入宿主 jniLibs（安装后落 nativeLibraryDir，全体系
                // 唯一可 execve 处）。产物归插件，框架只提供托管能力。
                if (packModules.isNotEmpty()) {
                    val nativeSpecs = packModules.map { modulePath ->
                        val name = modulePath.substringAfterLast(':')
                        "$modulePath=" + project.rootProject.file(
                            "$name/build/outputs/plugin/native-executables",
                        ).absolutePath
                    }
                    val nativeTask = project.tasks.register<InjectNativeExecutablesTask>(
                        "inject${capitalized}PluginNativeExecutables",
                    ) {
                        outputDir.set(
                            project.layout.buildDirectory.dir("generated/pluginJniLibs/${variant.name}"),
                        )
                        nativeExeDirs.set(nativeSpecs)
                        // 把各插件 native-executables 目录声明为输入文件：仅字符串 @Input
                        // 无法感知目录内容变化，任务会被误判 UP-TO-DATE（同 InjectPluginsTask
                        // 对 APK 文件的处理）。
                        inputs.files(nativeSpecs.map { File(it.split("=", limit = 2)[1]) })
                        for (modulePath in packModules) {
                            dependsOn("$modulePath:packagePlugin")
                        }
                    }
                    variant.sources.jniLibs?.addGeneratedSourceDirectory(nativeTask) { it.outputDir }
                }
            }
        }
    }
}
