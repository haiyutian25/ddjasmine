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
// excludeGroups 无需手写：PluginPackPlugin 自动读取宿主 app 的
// releaseRuntimeClasspath，宿主已提供的依赖（Ktor/OkHttp/Okio/Compose）
// 自动排除出插件 DEX，运行时 parent-first 从宿主解析。
pluginPack {
    packageIdSlot.set(4)
}

dependencies {
    // 框架内置的插件开发 API 门面：core-plugin 已用 api 传递宿主主题 / Compose /
    // Ktor 等全部共享 API，compileOnly 一个依赖即可 import，运行时 parent-first
    // 从宿主解析。用 compileOnly 而非 implementation：不让 project 依赖进入 runtime
    // classpath，避免 PluginPackPlugin resolve 时的 variant 歧义。
    compileOnly(project(":core-plugin"))

    // 宿主没有的官方 MCP SDK，打进插件 DEX；其传递的 Ktor/OkHttp/Okio 等
    // 宿主已提供，由 PluginPackPlugin 自动排除，无需任何 excludeGroups。
    implementation(libs.mcp.sdk.client)

    ksp(project(":core-plugin-ksp"))
}
