import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * PRoot 可行性探针插件：作为标准插件经 jasmine.plugin-pack 打包、由框架安装加载，
 * 在插件自己的目录（filesDir/plugins/<id>/）里跑 execve / mmap / memfd 三项探针，
 * 走框架真实路径裁决"PRoot Linux 能否做成本框架的插件"。
 */
plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    id("jasmine.plugin-pack")
}

android {
    namespace = "jasmine.sample.prootprobe"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Packaging: plugin package-id 0x85 (slot 5).
pluginPack {
    packageIdSlot.set(5)
    // 通用原生可执行文件托管：探针 PIE 归插件，经框架管线并入宿主 jniLibs
    //（安装后落 nativeLibraryDir，全体系唯一可 execve 处），运行时经
    // ExecBridge.nativeExecutablePath 定位。
    nativeExecutables.put("arm64-v8a", "src/main/exec/proot_probe_pie.so")
}

dependencies {
    compileOnly(project(":core-plugin"))
    compileOnly(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    compileOnly(composeBom)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.material3)

    ksp(project(":core-plugin-ksp"))
}
