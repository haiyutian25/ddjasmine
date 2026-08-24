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

/**
 * MCP 插件：完整演示 Model Context Protocol 客户端能力。
 *
 * 通过官方 `io.modelcontextprotocol:kotlin-sdk-client`（Maven Central）实现协议层，
 * 依赖由 `jasmine.plugin-pack` 的 dependencyJars 机制打进插件 DEX（框架已支持插件
 * 自带远程 SDK）。UI 使用宿主主推的 Jetpack Compose 声明式方案，运行时从宿主解析。
 */
plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    id("jasmine.plugin-pack")
}

android {
    namespace = "jasmine.sample.mcp"
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

// Packaging: plugin package-id 0x84 (slot 4, host keeps 0x7f).
pluginPack {
    packageIdSlot.set(4)
    // 宿主 core-agent 已改用 Ktor（OkHttp engine），Ktor 及其底层
    // OkHttp/Okio 都在宿主 APK。这里把 MCP SDK 传递进来的 Ktor/OkHttp/Okio
    // 从插件 DEX 排除，运行时从宿主解析，避免插件重复打包 Ktor。
    excludeGroups.add("io.ktor")
    excludeGroups.add("com.squareup.okhttp3")
    excludeGroups.add("com.squareup.okio")
}

dependencies {
    compileOnly(project(":core-plugin"))
    compileOnly(libs.androidx.activity)

    // MCP 官方 SDK 打进插件 DEX；Ktor 由宿主 core-agent 提供，compileOnly
    // 声明从宿主解析（excludeGroups 进一步阻止其打进插件 DEX）。
    implementation(libs.mcp.sdk.client)
    compileOnly(libs.ktor.client.core)
    compileOnly(libs.ktor.client.okhttp)

    // 插件 UI 在运行时经父 ClassLoader 从宿主解析，故 compileOnly
    val composeBom = platform(libs.androidx.compose.bom)
    compileOnly(composeBom)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.material.icons.core)

    ksp(project(":core-plugin-ksp"))
}
