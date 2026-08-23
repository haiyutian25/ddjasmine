plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("pluginPack") {
            id = "jasmine.plugin-pack"
            implementationClass = "jasmine.buildlogic.PluginPackPlugin"
        }
        register("pluginDev") {
            id = "jasmine.plugin-dev"
            implementationClass = "jasmine.buildlogic.PluginDevPlugin"
        }
    }
}

dependencies {
    // AGP DSL types (LibraryExtension/ApplicationExtension, variants API)
    compileOnly("com.android.tools.build:gradle:9.3.1")
}
