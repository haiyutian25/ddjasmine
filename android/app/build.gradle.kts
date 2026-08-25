/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    id("jasmine.plugin-dev")
}

// Development/发布态插件分发：sample-plugin 经 pack 管线产出的插件 APK
// 由 plugin-dev 生成式注入 assets/plugins（构建产物，不落源码树）。
pluginDev {
    packModules.set(listOf(":sample-plugin", ":sample-guide", ":sample-example", ":sample-mcp", ":sample-proot-probe"))
}

android {
    namespace = "com.lhzkml.jasmine"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lhzkml.jasmine"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "com.lhzkml.jasmine.core.testing.HiltTestRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // 占位签名：发布前替换为正式证书；插件同样以 debug 证书打包，
            // 保证 Strict 策略下宿主信任校验通过。
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 探针 A（nativeLibraryDir execve）地基：让 jniLibs 真实提取到磁盘
        // （等效 extractNativeLibs=true），极小静态 PIE 才能被 execve。
        // proot runner 后续同样依赖此落点。
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            // 宿主 Manifest 由 generatePluginPermissions 生成到 build/（模板 =
            // src/main/AndroidManifest.xml + 注入的插件权限）。注意 srcFile 是
            // 替换语义而非追加：直接指向权限片段会丢 label/图标/入口 Activity。
            manifest.srcFile("build/generated/pluginPermissions/AndroidManifest.xml")
        }
    }
}

// Enable room auto-migrations
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// 插件分发交给 jasmine.plugin-dev（见上方 pluginDev 块）；代理组件由
// :core-plugin 库 manifest 合并，无需在此注册。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 插件权限自动合并：插件在自己的 manifest 声明 uses-permission，构建时
// 由此 task 扫描所有插件模块的 manifest 并合并进宿主（无需宿主/框架预声明
// 权限池）。插件自主声明、构建期自动生效。
// ---------------------------------------------------------------------------
val pluginModules = listOf(":sample-plugin", ":sample-guide", ":sample-example", ":sample-mcp", ":sample-proot-probe")

val generatePluginPermissions by tasks.registering {
    group = "build"
    description = "Generates the host manifest: src/main template + plugin uses-permission injected"
    val templatePath = project.file("src/main/AndroidManifest.xml").absolutePath
    val manifestPaths = pluginModules.map {
        project.rootProject.file("${it.removePrefix(":")}/src/main/AndroidManifest.xml").absolutePath
    }
    val outPath = project.file("build/generated/pluginPermissions/AndroidManifest.xml").absolutePath
    inputs.files(manifestPaths + templatePath)
    outputs.file(outPath)
    doLast {
        val permissions = linkedSetOf<String>()
        for (path in manifestPaths) {
            val manifestFile = File(path)
            if (!manifestFile.exists()) continue
            Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifestFile.readText()).forEach { m ->
                permissions += m.groupValues[1]
            }
        }
        val template = File(templatePath).readText()
        // 宿主模板已声明的不再重复注入
        Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(template).forEach {
            permissions -= it.groupValues[1]
        }
        val injection = buildString {
            appendLine("    <!-- 自动生成：从插件 manifest 收集，勿手改 -->")
            permissions.sorted().forEach { appendLine("    <uses-permission android:name=\"$it\" />") }
        }
        val xml = template.replaceFirst("</manifest>", "$injection</manifest>")
        val out = File(outPath)
        out.parentFile?.mkdirs()
        out.writeText(xml)
        logger.lifecycle("[permissions] 合并插件权限 (${permissions.size}): ${permissions.sorted()}")
    }
}

tasks.named("preBuild") {
    dependsOn(generatePluginPermissions)
}

// ---------------------------------------------------------------------------
// Rust native library pipeline: cross-compile the UniFFI cdylib for all four
// Android ABIs and sync into jniLibs before packaging. FFI 面一改，宿主 .so
// 自动同步，不再手动拷贝。无 Rust 工具链的环境可用 -PskipRustNative=true
// 跳过（沿用已签入的 .so）。
// ---------------------------------------------------------------------------
val rustDir = rootProject.file("../rust")
val rustAndroidAbis = mapOf(
    "aarch64-linux-android" to "arm64-v8a",
    "armv7-linux-androideabi" to "armeabi-v7a",
    "i686-linux-android" to "x86",
    "x86_64-linux-android" to "x86_64",
)

val skipRustNative = providers.gradleProperty("skipRustNative")
    .map { it.toBoolean() }
    .getOrElse(false)

val buildRustAndroid by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles the Rust UniFFI cdylib for all Android ABIs"
    workingDir = rustDir
    commandLine(
        listOf("cargo", "build", "--release", "-p", "ffi") +
            rustAndroidAbis.keys.flatMap { listOf("--target", it) },
    )
}

// One Copy task per ABI (a Copy task has a single destination).
val syncRustJniLibsByAbi = rustAndroidAbis.map { (rustTarget, androidDir) ->
    tasks.register<Copy>("syncRustJniLibs${androidDir.replace("-", "")}") {
        group = "build"
        description = "Copies fresh libffi.so into src/main/jniLibs/$androidDir"
        dependsOn(buildRustAndroid)
        from(rustDir.resolve("target/$rustTarget/release/libffi.so"))
        into(layout.projectDirectory.dir("src/main/jniLibs/$androidDir").asFile)
    }
}

val syncRustJniLibs by tasks.registering {
    group = "build"
    description = "Syncs the freshly built Rust cdylib into src/main/jniLibs (all ABIs)"
    dependsOn(syncRustJniLibsByAbi)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(syncRustJniLibs)
}

// Skip switch for CI / toolchain-less environments.
if (skipRustNative) {
    buildRustAndroid.configure { onlyIf { false } }
    syncRustJniLibsByAbi.forEach { it.configure { onlyIf { false } } }
    syncRustJniLibs.configure { onlyIf { false } }
}

// Migrate from kotlinOptions to compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-plugin"))
    implementation(project(":core-ui"))
    implementation(project(":feature-plugin"))
    implementation(project(":feature-plugin-navigation"))
    implementation(project(":core-data"))
    implementation(project(":core-agent"))
    implementation(project(":feature-session"))
    implementation(project(":feature-session-navigation"))


    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Plugin framework API weave: activates the moment an interface is @GatedApi
    ksp(project(":core-plugin-ksp"))
    kspAndroidTest(libs.hilt.compiler)
    kspTest(libs.hilt.compiler)

    // Arch Components

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Instrumented tests
    androidTestImplementation(composeBom)
    androidTestImplementation(project(":core-testing"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
