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
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lhzkml.jasmine.core.plugin"
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

    // dlopen-based exec shim (ExecBridge): lets plugins run PIE executables
    // extracted to the noexec filesDir by loading them as shared objects.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.offline", "true")
            // Point Robolectric at the locally-cached android-all jar (offline
            // CI/dev boxes without maven central access).
            it.systemProperty(
                "robolectric.dependency.dir",
                System.getProperty("user.home") +
                    "/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7",
            )
        }
    }

    sourceSets {
        // UniFFI 绑定生成到 build/generated/uniffi（见 generateBindings 任务）。
        getByName("main").java.srcDir("build/generated/uniffi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// UniFFI Kotlin bindings: generated from the Rust cdylib instead of checked
// in. Same pipeline as :core-data, but the package is overridden
// (uniffi-kotlin-override.toml) so both modules can coexist.
// ---------------------------------------------------------------------------
val rustDir = rootProject.file("../rust")
val hostOs = System.getProperty("os.name", "").lowercase()
val hostLibName = when {
    hostOs.contains("win") -> "ffi.dll"
    hostOs.contains("mac") || hostOs.contains("darwin") -> "libffi.dylib"
    else -> "libffi.so"
}
val hostLib = File(rustDir, "target/release/$hostLibName")
val uniffiOutDir = layout.buildDirectory.dir("generated/uniffi")
// Rust 源 + manifest 变化时才重编译/重生成，避免每次构建无条件跑 cargo+bindgen。
val ffiInputs = fileTree(rustDir) { include("src/**/*.rs", "**/Cargo.toml") }

val cargoBuildHostLib by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the host Rust cdylib that UniFFI bindings are generated from"
    workingDir = rustDir
    inputs.files(ffiInputs)
    outputs.file(hostLib)
    commandLine("cargo", "build", "--release", "-p", "ffi")
}

val generateBindings by tasks.registering(Exec::class) {
    group = "build"
    description = "Regenerate plugin-runtime UniFFI Kotlin bindings from the host cdylib"
    dependsOn(cargoBuildHostLib)
    workingDir = rustDir
    // 输入=cdylib+覆盖配置，输出=生成目录：cdylib 未变则跳过（增量）。
    inputs.file(hostLib)
    inputs.file(layout.projectDirectory.file("uniffi-kotlin-override.toml"))
    outputs.dir(uniffiOutDir)
    commandLine(
        "cargo", "run", "--release", "-p", "uniffi-bindgen", "--",
        "generate", "--library", "$rustDir/target/release/$hostLibName",
        "--language", "kotlin",
        "--config", layout.projectDirectory.file("uniffi-kotlin-override.toml").asFile.absolutePath,
        // 写入 build/ 而非 src/main/java：源码树不被生成物污染、不会读到半成品。
        "--out-dir", uniffiOutDir.get().asFile.absolutePath,
    )
}

val generateUniffiBindings by tasks.registering {
    group = "build"
    description = "Ensures the UniFFI Kotlin bindings are regenerated before compilation"
    dependsOn(generateBindings)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateUniffiBindings)
}

dependencies {
    // Rust decision core: uniffi-generated bindings (com.lhzkml.jasmine.core.plugin.rust)
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 授权账本持久化复用宿主的 Room（core-database 的 api 暴露 Room），不再用 JSON 文件自存。
    implementation(project(":core-database"))

    // 插件 UI 契约：@Composable 注解（api 使插件 compileOnly 时可见）
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)

    // Runtime DEX class-index scan (build-time scan is the primary source);
    // dexlib2's API surface exposes guava types, so guava must be explicit.
    implementation(libs.smali.dexlib2)
    implementation("com.google.guava:guava:33.4.0-jre")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
}
