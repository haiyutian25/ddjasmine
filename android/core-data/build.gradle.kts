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
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lhzkml.jasmine.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "com.lhzkml.jasmine.core.testing.HiltTestRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
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

// ---------------------------------------------------------------------------
// UniFFI Kotlin bindings: generated from the Rust cdylib instead of checked
// in. Requires a Rust toolchain (cargo) on the build machine; runs before
// every build so a fresh clone compiles without committed bindings.
// ---------------------------------------------------------------------------
val rustDir = rootProject.file("../rust")
val hostLibName = if (System.getProperty("os.name", "").lowercase().contains("win")) {
    "ffi.dll"
} else {
    "libffi.so"
}

val cargoBuildHostLib by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the host Rust cdylib that UniFFI bindings are generated from"
    workingDir = rustDir
    commandLine("cargo", "build", "--release", "-p", "ffi")
}

val generateBindings by tasks.registering(Exec::class) {
    group = "build"
    description = "Regenerate Rust UniFFI Kotlin bindings (com.lhzkml.jasmine.rust) from the host cdylib"
    dependsOn(cargoBuildHostLib)
    workingDir = rustDir
    commandLine(
        "cargo", "run", "--release", "-p", "uniffi-bindgen", "--",
        "generate", "--library", "$rustDir/target/release/$hostLibName",
        "--language", "kotlin",
        "--out-dir", layout.projectDirectory.dir("src/main/java").asFile.absolutePath,
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
    implementation(project(":core-database"))
    api(project(":core-kernel"))
    api(project(":core-agent"))

    // Rust spine: uniffi-generated bindings (com.lhzkml.jasmine.rust)
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // Arch Components
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(project(":core-kernel-ksp"))

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
