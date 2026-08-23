import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 开发指南示例插件：纯 Jetpack Compose，无 ViewModel/容器/native。
 * 走 jasmine.plugin-pack 产出插件 APK，由 jasmine.plugin-dev 注入宿主。
 */
plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    id("jasmine.plugin-pack")
}

android {
    namespace = "jasmine.sample.guide"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
    packageIdSlot.set(2) // 资源包 id 0x82
}

dependencies {
    compileOnly(project(":core-plugin"))

    // 插件 UI 运行时经父 ClassLoader 从宿主解析，故 compileOnly
    val composeBom = platform(libs.androidx.compose.bom)
    compileOnly(composeBom)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.material.icons.core)
    compileOnly(libs.androidx.compose.ui.tooling.preview)
}
