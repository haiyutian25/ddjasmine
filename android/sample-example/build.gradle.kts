import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

/**
 * 功能示例插件：完整演示 Activity/Service/Receiver/Provider/native so/热更。
 * 走 jasmine.plugin-pack 产出插件 APK，由 jasmine.plugin-dev 注入宿主。
 */
plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    id("jasmine.plugin-pack")
}

android {
    namespace = "jasmine.sample.example"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

pluginPack {
    packageIdSlot.set(3) // 资源包 id 0x83
}

// ---------------------------------------------------------------------------
// 把 cmake 编译出的 PIE hello 复制到 assets/exec/hello，随插件 APK 分发；
// 框架提取后经 ExecBridge 的 dlopen 桥运行（验证 noexec 下的可执行资产）。
// 产物落 build/generated，不污染源码树；挂在 mergeAssets 前避免与
// externalNativeBuild 形成 preBuild 循环依赖。
// ---------------------------------------------------------------------------
android {
    sourceSets {
        getByName("main") {
            assets.srcDir("build/generated/execAssets")
        }
    }
}

val copyHelloExec by tasks.registering {
    group = "build"
    description = "Copies the PIE hello binary into assets/exec for the ExecBridge"
    dependsOn("externalNativeBuildRelease")
    val projectDirPath = projectDir.absolutePath
    doLast {
        val root = File(projectDirPath, "build/intermediates/cxx")
        val hello = root.walkTopDown().firstOrNull {
            it.isFile && it.name == "hello" && it.parentFile?.name == "arm64-v8a"
        }
        if (hello != null) {
            val dest = File(projectDirPath, "build/generated/execAssets/exec/hello")
            dest.parentFile.mkdirs()
            hello.copyTo(dest, overwrite = true)
        }
    }
}

tasks.matching { it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn(copyHelloExec)
}

dependencies {
    compileOnly(project(":core-plugin"))
    compileOnly(libs.androidx.activity)
    compileOnly(libs.androidx.activity.compose)

    // 插件 UI 运行时经父 ClassLoader 从宿主解析，故 compileOnly
    val composeBom = platform(libs.androidx.compose.bom)
    compileOnly(composeBom)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.material.icons.core)
    compileOnly(libs.androidx.compose.ui.tooling.preview)
    compileOnly(libs.androidx.lifecycle.viewmodel.compose)
    compileOnly(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
