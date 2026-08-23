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
            }
        }
    }
}
